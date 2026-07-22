package com.junited31.leftovers.recipes

import com.junited31.leftovers.data.PantryItemEntity
import com.junited31.leftovers.data.PantryItemId
import com.junited31.leftovers.data.PantryUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class RecommendationRankerTest {
    private val riceId = pantryId("10000000-0000-4000-8000-000000000001")
    private val eggId = pantryId("10000000-0000-4000-8000-000000000002")
    private val spinachId = pantryId("10000000-0000-4000-8000-000000000003")
    private val now = Instant.parse("2026-07-18T00:00:00Z")
    private val today = LocalDate.parse("2026-07-18")

    @Test
    fun rejects_candidate_when_required_equipment_is_missing() {
        val candidates = validCandidates().mapIndexed { index, candidate ->
            if (index == 0) candidate.copy(requiredEquipment = listOf("oven")) else candidate
        }

        val result = rank(candidates, equipment = setOf("gas burner", "basic cookware"))

        assertEquals(RecommendationInvalidReason.EQUIPMENT_MISMATCH, invalid(result))
    }

    @Test
    fun rejects_two_candidates_instead_of_three() {
        assertEquals(
            RecommendationInvalidReason.CANDIDATE_COUNT,
            invalid(rank(validCandidates().take(2))),
        )
    }

    @Test
    fun rejects_duplicate_normalized_title_and_exact_fingerprint() {
        val titleDuplicate = validCandidates().toMutableList().also {
            it[1] = it[1].copy(title = "  EGG   FRIED RICE ")
        }
        val fingerprintDuplicate = validCandidates().toMutableList().also {
            it[1] = it[0].copy(title = "Different title")
        }

        assertEquals(RecommendationInvalidReason.DUPLICATE_TITLE, invalid(rank(titleDuplicate)))
        assertEquals(
            RecommendationInvalidReason.DUPLICATE_FINGERPRINT,
            invalid(rank(fingerprintDuplicate)),
        )
    }

    @Test
    fun rejects_one_cuisine_or_one_primary_technique() {
        val cuisines = validCandidates().map { it.copy(cuisine = "Korean") }
        val techniques = validCandidates().map { it.copy(primaryTechnique = "pan fry") }

        assertEquals(RecommendationInvalidReason.CUISINE_DIVERSITY, invalid(rank(cuisines)))
        assertEquals(RecommendationInvalidReason.TECHNIQUE_DIVERSITY, invalid(rank(techniques)))
    }

    @Test
    fun rejects_unknown_duplicate_stale_wrong_unit_and_excessive_pantry_use() {
        val unknown = useMutation { copy(pantryItemId = pantryId("10000000-0000-4000-8000-000000000099")) }
        val duplicate = validCandidates().toMutableList().also { candidates ->
            candidates[0] = candidates[0].copy(trackedUses = listOf(candidates[0].trackedUses[0], candidates[0].trackedUses[0]))
        }
        val stale = useMutation { copy(sourceVersion = 99) }
        val unit = useMutation { copy(unit = PantryUnit.MILLILITER) }
        val amount = useMutation { copy(proposedMilliUnits = 900_001) }

        assertEquals(RecommendationInvalidReason.UNKNOWN_PANTRY, invalid(rank(unknown)))
        assertEquals(RecommendationInvalidReason.DUPLICATE_PANTRY, invalid(rank(duplicate)))
        assertEquals(RecommendationInvalidReason.VERSION_MISMATCH, invalid(rank(stale)))
        assertEquals(RecommendationInvalidReason.UNIT_MISMATCH, invalid(rank(unit)))
        assertEquals(RecommendationInvalidReason.INVALID_AMOUNT, invalid(rank(amount)))
    }

    @Test
    fun shared_normalization_fixtures_match_backend_values_and_fingerprint() {
        // Given: the same fixture file consumed by the backend test.
        val fixtures = sharedNormalizationFixtures()
        val cases = fixtures.getJSONArray("normalization")

        // When: Android normalizes every cross-runtime edge value.
        val normalized = List(cases.length()) { index ->
            RecipeNormalizer.normalize(cases.getJSONObject(index).getString("input"))
        }
        val fingerprint = fixtures.getJSONObject("fingerprint")

        // Then: values and composed SHA-256 exactly match the shared expectations.
        assertEquals(
            List(cases.length()) { index -> cases.getJSONObject(index).getString("expected") },
            normalized,
        )
        assertEquals(
            fingerprint.getString("expectedSha256"),
            RecipeFingerprint.sha256(
                fingerprint.getString("cuisine"),
                fingerprint.getString("primaryTechnique"),
                List(fingerprint.getJSONArray("ingredientNames").length()) { index ->
                    fingerprint.getJSONArray("ingredientNames").getString(index)
                },
            ),
        )
    }

    @Test
    fun no_history_yields_neutral_preference_and_full_novelty() {
        val first = valid(rank(validCandidates())).ranked.first()

        assertEquals(0.5, first.preference, 0.000_001)
        assertEquals(1.0, first.novelty, 0.000_001)
    }

    @Test
    fun tag_score_uses_exact_signal_formula_and_candidate_mean() {
        val logs = listOf(
            history(cuisine = "Korean", technique = "stir fry", rating = 5, again = true, daysAgo = 1),
            history(cuisine = "Korean", technique = "steam", rating = 1, again = false, daysAgo = 2),
        )

        val ranked = valid(rank(validCandidates(), history = logs)).ranked
        val friedRice = ranked.single { it.candidate.title == "Egg fried rice" }

        assertEquals(0.75, friedRice.preference, 0.000_001)
    }

    @Test
    fun novelty_uses_ingredient_sets_not_fingerprint_hash_strings() {
        val log = history(
            cuisine = "Other",
            technique = "Other",
            rating = 3,
            again = true,
            daysAgo = 1,
            ingredients = setOf("rice", "egg", "kimchi"),
        )

        val friedRice = valid(rank(validCandidates(), history = listOf(log))).ranked
            .single { it.candidate.title == "Egg fried rice" }

        assertEquals(1.0 / 3.0, friedRice.novelty, 0.000_001)
    }

    @Test
    fun profile_uses_only_newest_twenty_logs_and_novelty_only_newest_five() {
        val preferenceLogs = (1..21).map { day ->
            history(
                cuisine = "Korean",
                technique = "stir fry",
                rating = if (day == 21) 1 else 5,
                again = day != 21,
                daysAgo = day.toLong(),
            )
        }
        val noveltyLogs = (1..6).map { day ->
            history(
                cuisine = "Other $day",
                technique = "Other $day",
                rating = 3,
                again = true,
                daysAgo = day.toLong(),
                ingredients = if (day == 6) setOf("rice", "egg") else setOf("unseen-$day"),
            )
        }

        val preference = valid(rank(validCandidates(), history = preferenceLogs)).ranked
            .single { it.candidate.title == "Egg fried rice" }.preference
        val novelty = valid(rank(validCandidates(), history = noveltyLogs)).ranked
            .single { it.candidate.title == "Egg fried rice" }.novelty

        assertEquals(1.0, preference, 0.000_001)
        assertEquals(1.0, novelty, 0.000_001)
    }

    @Test
    fun cooldown_expires_at_exact_thirty_day_utc_boundary() {
        val fingerprint = RecipeFingerprint.of(validCandidates()[0], pantry().associateBy { it.id })
        val beforeBoundary = history(
            cuisine = "Korean",
            technique = "stir fry",
            rating = 1,
            again = false,
            daysAgo = 30,
            fingerprint = fingerprint,
            completedAt = now.minusSeconds(30 * 24 * 60 * 60).plusMillis(1),
        )
        val atBoundary = beforeBoundary.copy(completedAt = now.minusSeconds(30 * 24 * 60 * 60))

        assertEquals(RecommendationInvalidReason.ACTIVE_COOLDOWN, invalid(rank(validCandidates(), history = listOf(beforeBoundary))))
        assertTrue(rank(validCandidates(), history = listOf(atBoundary)) is RecommendationResult.Valid)
    }

    @Test
    fun expiry_weights_are_one_half_and_one_tenth_and_urgency_is_captured_over_total() {
        val rows = pantry().map { row ->
            when (row.id) {
                riceId -> row.copy(expiryEpochDay = today.plusDays(3).toEpochDay())
                eggId -> row.copy(expiryEpochDay = today.plusDays(7).toEpochDay())
                else -> row.copy(expiryEpochDay = null)
            }
        }

        val friedRice = valid(rank(validCandidates(), pantry = rows)).ranked
            .single { it.candidate.title == "Egg fried rice" }

        assertEquals(1.0, RecommendationRanker.expiryWeight(today.plusDays(3).toEpochDay(), today), 0.0)
        assertEquals(0.5, RecommendationRanker.expiryWeight(today.plusDays(7).toEpochDay(), today), 0.0)
        assertEquals(0.1, RecommendationRanker.expiryWeight(null, today), 0.0)
        assertEquals(1.5 / 1.6, friedRice.expiry, 0.000_001)
    }

    @Test
    fun expiry_urgency_includes_every_pantry_item_even_common_staples() {
        // Given: urgent salt plus two later pantry rows.
        val rows = pantry().map { row ->
            when (row.id) {
                eggId -> row.copy(name = "Salt", expiryEpochDay = today.plusDays(1).toEpochDay())
                else -> row.copy(expiryEpochDay = null)
            }
        }

        // When: a candidate captures the urgent staple and one later row.
        val friedRice = valid(rank(validCandidates(), pantry = rows)).ranked
            .single { it.candidate.title == "Egg fried rice" }

        // Then: expiry is captured over all pantry rows, not the coverage-only domain.
        assertEquals(1.1 / 1.2, friedRice.expiry, 0.000_001)
    }

    @Test
    fun coverage_excludes_normalized_korean_common_staples() {
        // Given: the egg row is actually Korean-labelled salt.
        val rows = pantry().map { row -> if (row.id == eggId) row.copy(name = "  소금  ") else row }

        // When: a candidate uses rice plus that staple.
        val friedRice = valid(rank(validCandidates(), pantry = rows)).ranked
            .single { it.candidate.title == "Egg fried rice" }

        // Then: one of two non-staple rows is covered.
        assertEquals(0.5, friedRice.coverage, 0.000_001)
    }

    @Test
    fun stable_ties_use_coverage_then_expiry_then_normalized_title() {
        val tied = validCandidates().map { candidate ->
            candidate.copy(
                trackedUses = listOf(use(riceId, 1, PantryUnit.GRAM, 100_000)),
                missingIngredients = emptyList(),
            )
        }

        val ordered = valid(rank(tied)).ranked.map { it.candidate.title }

        assertEquals(listOf("Crispy spinach rice", "Egg fried rice", "Rice omelette"), ordered)
    }

    @Test
    fun identical_fixture_has_byte_stable_ordered_ids_across_three_runs() {
        val outputs = List(3) {
            valid(rank(validCandidates())).ranked.joinToString(",") { it.id }
        }

        assertEquals(1, outputs.distinct().size)
        assertEquals(
            listOf("Egg fried rice", "Rice omelette", "Crispy spinach rice"),
            valid(rank(validCandidates())).ranked.map { it.candidate.title },
        )
    }

    private fun rank(
        candidates: List<RecommendationCandidate>,
        pantry: List<PantryItemEntity> = pantry(),
        equipment: Set<String> = setOf("gas burner", "basic cookware"),
        history: List<RecommendationHistory> = emptyList(),
    ) = RecommendationRanker.rank(candidates, pantry, equipment, history, now, today)

    private fun valid(result: RecommendationResult) = result as RecommendationResult.Valid

    private fun invalid(result: RecommendationResult) =
        (result as RecommendationResult.Invalid).reason

    private fun useMutation(
        transform: ProposedPantryUse.() -> ProposedPantryUse,
    ): List<RecommendationCandidate> = validCandidates().toMutableList().also { candidates ->
        candidates[0] = candidates[0].copy(
            trackedUses = listOf(candidates[0].trackedUses[0].transform()),
        )
    }

    private fun validCandidates() = listOf(
        candidate(
            title = "Egg fried rice",
            cuisine = "Korean",
            technique = "stir fry",
            tracked = listOf(use(riceId, 1, PantryUnit.GRAM, 300_000), use(eggId, 2, PantryUnit.COUNT, 2_000)),
        ),
        candidate(
            title = "Rice omelette",
            cuisine = "Japanese",
            technique = "pan fry",
            tracked = listOf(use(riceId, 1, PantryUnit.GRAM, 200_000), use(eggId, 2, PantryUnit.COUNT, 2_000)),
            missing = listOf(MissingRecipeIngredient("salt", 1_000, PantryUnit.GRAM)),
        ),
        candidate(
            title = "Crispy spinach rice",
            cuisine = "Korean",
            technique = "bake",
            tracked = listOf(use(riceId, 1, PantryUnit.GRAM, 250_000), use(spinachId, 3, PantryUnit.GRAM, 100_000)),
        ),
    )

    private fun candidate(
        title: String,
        cuisine: String,
        technique: String,
        tracked: List<ProposedPantryUse>,
        missing: List<MissingRecipeIngredient> = emptyList(),
    ) = RecommendationCandidate(
        title = title,
        cuisine = cuisine,
        primaryTechnique = technique,
        requiredEquipment = listOf("gas burner"),
        trackedUses = tracked,
        missingIngredients = missing,
        steps = listOf("Prepare", "Cook"),
        recipeKind = com.junited31.leftovers.data.RecipeKind.MEAL,
    )

    private fun use(id: PantryItemId, version: Int, unit: PantryUnit, amount: Long) =
        ProposedPantryUse(id, version, unit, amount)

    private fun pantry() = listOf(
        PantryItemEntity(riceId, "Rice", 900_000, PantryUnit.GRAM, today.plusDays(3).toEpochDay(), 1),
        PantryItemEntity(eggId, "Egg", 6_000, PantryUnit.COUNT, today.plusDays(7).toEpochDay(), 2),
        PantryItemEntity(spinachId, "Spinach", 300_000, PantryUnit.GRAM, null, 3),
    )

    private fun history(
        cuisine: String,
        technique: String,
        rating: Int,
        again: Boolean,
        daysAgo: Long,
        ingredients: Set<String> = setOf("rice", "egg"),
        fingerprint: String = "history-$daysAgo",
        completedAt: Instant = now.minusSeconds(daysAgo * 24 * 60 * 60),
    ) = RecommendationHistory(
        fingerprint = fingerprint,
        cuisine = cuisine,
        primaryTechnique = technique,
        ingredientNames = ingredients,
        rating = rating,
        recommendAgain = again,
        completedAt = completedAt,
    )

    private fun pantryId(value: String) = requireNotNull(PantryItemId.parse(value))

    private fun sharedNormalizationFixtures(): JSONObject {
        val relative = ".omo/evidence/leftovers/task-6-normalization-fixtures.json"
        val roots = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        val fixture = roots.map { File(it, relative) }.first(File::isFile)
        return JSONObject(fixture.readText())
    }
}
