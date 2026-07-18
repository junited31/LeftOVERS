package com.junited31.leftovers.recipes

import com.junited31.leftovers.data.PantryItemEntity
import com.junited31.leftovers.data.PantryItemId
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

object RecipeNormalizer {
    private val whitespace = Regex("\\s+")

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .trim()
        .replace(whitespace, " ")
        .lowercase(Locale.ROOT)
}

object RecipeFingerprint {
    fun sha256(cuisine: String, technique: String, ingredientNames: Collection<String>): String {
        val canonical = buildList {
            add(RecipeNormalizer.normalize(cuisine))
            add(RecipeNormalizer.normalize(technique))
            addAll(ingredientNames.map(RecipeNormalizer::normalize).sorted())
        }.joinToString("|")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    }

    fun of(
        candidate: RecommendationCandidate,
        pantryById: Map<PantryItemId, PantryItemEntity>,
    ): String = sha256(
        candidate.cuisine,
        candidate.primaryTechnique,
        candidateIngredientNames(candidate, pantryById),
    )

    internal fun candidateIngredientNames(
        candidate: RecommendationCandidate,
        pantryById: Map<PantryItemId, PantryItemEntity>,
    ): List<String> = candidate.trackedUses.map { pantryById.getValue(it.pantryItemId).name } +
        candidate.missingIngredients.map { it.name }
}

object RecommendationRanker {
    private val cooldown = Duration.ofDays(30)

    // ponytail: fixed heuristic until pantry rows gain an explicit user-managed staple flag.
    private val commonStaples = setOf("salt", "water", "oil", "cooking oil", "pepper")

    fun rank(
        candidates: List<RecommendationCandidate>,
        pantry: List<PantryItemEntity>,
        equipment: Set<String>,
        history: List<RecommendationHistory>,
        now: Instant,
        today: LocalDate,
    ): RecommendationResult {
        if (candidates.size != 3) return invalid(RecommendationInvalidReason.CANDIDATE_COUNT)
        val normalizedEquipment = equipment.mapTo(mutableSetOf(), ::normalizeEquipment)
        if (candidates.any { candidate ->
                candidate.requiredEquipment.any {
                    normalizeEquipment(it) !in normalizedEquipment
                }
            }
        ) return invalid(RecommendationInvalidReason.EQUIPMENT_MISMATCH)

        val pantryById = pantry.associateBy { it.id }
        candidates.forEach { candidate ->
            val seen = mutableSetOf<PantryItemId>()
            candidate.trackedUses.forEach { use ->
                if (!seen.add(use.pantryItemId)) {
                    return invalid(RecommendationInvalidReason.DUPLICATE_PANTRY)
                }
                val row = pantryById[use.pantryItemId]
                    ?: return invalid(RecommendationInvalidReason.UNKNOWN_PANTRY)
                if (use.sourceVersion != row.version) {
                    return invalid(RecommendationInvalidReason.VERSION_MISMATCH)
                }
                if (use.unit != row.unit) return invalid(RecommendationInvalidReason.UNIT_MISMATCH)
                if (use.proposedMilliUnits !in 1..row.quantityMilliUnits) {
                    return invalid(RecommendationInvalidReason.INVALID_AMOUNT)
                }
            }
        }

        val fingerprints = candidates.map { RecipeFingerprint.of(it, pantryById) }
        if (history.any { log ->
                !log.recommendAgain &&
                    log.fingerprint in fingerprints &&
                    log.completedAt.plus(cooldown).isAfter(now)
            }
        ) return invalid(RecommendationInvalidReason.ACTIVE_COOLDOWN)

        val titles = candidates.map { RecipeNormalizer.normalize(it.title) }
        if (titles.any(String::isEmpty) || titles.distinct().size != 3) {
            return invalid(RecommendationInvalidReason.DUPLICATE_TITLE)
        }
        if (fingerprints.distinct().size != 3) {
            return invalid(RecommendationInvalidReason.DUPLICATE_FINGERPRINT)
        }
        if (candidates.map { RecipeNormalizer.normalize(it.cuisine) }.distinct().size < 2) {
            return invalid(RecommendationInvalidReason.CUISINE_DIVERSITY)
        }
        if (candidates.map { RecipeNormalizer.normalize(it.primaryTechnique) }.distinct().size < 2) {
            return invalid(RecommendationInvalidReason.TECHNIQUE_DIVERSITY)
        }

        val available = pantry.filter { RecipeNormalizer.normalize(it.name) !in commonStaples }
        val expiryTotal = available.sumOf { expiryWeight(it.expiryEpochDay, today) }
        val preferenceHistory = history.sortedByDescending { it.completedAt }.take(20)
        val noveltyHistory = history.sortedByDescending { it.completedAt }.take(5)
        val ranked = candidates.mapIndexed { index, candidate ->
            val usedIds = candidate.trackedUses.mapTo(mutableSetOf()) { it.pantryItemId }
            val coverage = ratio(available.count { it.id in usedIds }, available.size)
            val capturedExpiry = available.filter { it.id in usedIds }
                .sumOf { expiryWeight(it.expiryEpochDay, today) }
            val expiry = if (expiryTotal == 0.0) 0.0 else capturedExpiry / expiryTotal
            val preference = candidatePreference(candidate, preferenceHistory)
            val novelty = candidateNovelty(candidate, pantryById, noveltyHistory)
            RankedRecommendation(
                id = fingerprints[index],
                candidate = candidate,
                score = 0.45 * coverage + 0.25 * expiry + 0.20 * preference + 0.10 * novelty,
                coverage = coverage,
                expiry = expiry,
                preference = preference,
                novelty = novelty,
            )
        }.sortedWith(
            compareByDescending<RankedRecommendation> { it.score }
                .thenByDescending { it.coverage }
                .thenByDescending { it.expiry }
                .thenBy { RecipeNormalizer.normalize(it.candidate.title) },
        )
        return RecommendationResult.Valid(ranked)
    }

    fun expiryWeight(expiryEpochDay: Long?, today: LocalDate): Double {
        val days = expiryEpochDay?.let { ChronoUnit.DAYS.between(today, LocalDate.ofEpochDay(it)) }
        return when {
            days != null && days <= 3 -> 1.0
            days != null && days <= 7 -> 0.5
            else -> 0.1
        }
    }

    private fun candidatePreference(
        candidate: RecommendationCandidate,
        history: List<RecommendationHistory>,
    ): Double = (
        tagScore(candidate.cuisine, history) { it.cuisine } +
            tagScore(candidate.primaryTechnique, history) { it.primaryTechnique }
        ) / 2.0

    private fun tagScore(
        tag: String,
        history: List<RecommendationHistory>,
        selector: (RecommendationHistory) -> String,
    ): Double {
        val normalizedTag = RecipeNormalizer.normalize(tag)
        val matches = history.filter { RecipeNormalizer.normalize(selector(it)) == normalizedTag }
        if (matches.isEmpty()) return 0.5
        val signal = matches.sumOf { log ->
            ((log.rating - 3) / 2.0 + if (log.recommendAgain) 0.5 else -1.0)
                .coerceIn(-1.0, 1.0)
        }
        return (signal + matches.size) / (2.0 * matches.size)
    }

    private fun candidateNovelty(
        candidate: RecommendationCandidate,
        pantryById: Map<PantryItemId, PantryItemEntity>,
        history: List<RecommendationHistory>,
    ): Double {
        if (history.isEmpty()) return 1.0
        val ingredients = RecipeFingerprint.candidateIngredientNames(candidate, pantryById)
            .mapTo(mutableSetOf(), RecipeNormalizer::normalize)
        return 1.0 - history.maxOf { log ->
            val previous = log.ingredientNames.mapTo(mutableSetOf(), RecipeNormalizer::normalize)
            val union = ingredients union previous
            if (union.isEmpty()) 1.0 else (ingredients intersect previous).size.toDouble() / union.size
        }
    }

    private fun ratio(numerator: Int, denominator: Int): Double =
        if (denominator == 0) 0.0 else numerator.toDouble() / denominator

    private fun invalid(reason: RecommendationInvalidReason) = RecommendationResult.Invalid(reason)

    private fun normalizeEquipment(value: String) = RecipeNormalizer.normalize(value.replace('_', ' '))
}
