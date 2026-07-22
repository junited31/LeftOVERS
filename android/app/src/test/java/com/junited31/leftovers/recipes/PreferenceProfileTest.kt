package com.junited31.leftovers.recipes

import com.junited31.leftovers.data.ActualPantryUses
import com.junited31.leftovers.data.JsonConverters
import com.junited31.leftovers.data.MealFeedback
import com.junited31.leftovers.data.MealLogEntity
import com.junited31.leftovers.data.MealLogDao
import com.junited31.leftovers.data.MeasurementAdjustment
import com.junited31.leftovers.data.PantryBindings
import com.junited31.leftovers.data.PantryRemainingSnapshots
import com.junited31.leftovers.data.PantryUnit
import com.junited31.leftovers.data.PantryItemEntity
import com.junited31.leftovers.data.PantryItemId
import com.junited31.leftovers.data.RecipePreferenceMetadata
import com.junited31.leftovers.data.RecipeSnapshotRecord
import com.junited31.leftovers.data.RecipeSteps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class PreferenceProfileTest {
    @Test
    fun mealLogJsonMapsToRecommendationHistory() {
        val log = log(
            id = "meal",
            completedAt = 1_000,
            cuisine = " Korean ",
            technique = " Stir   Fry ",
            ingredients = setOf(" Rice ", "EGG"),
            rating = 5,
            recommendAgain = false,
        )
        val converters = JsonConverters()

        val roundTripped = log.copy(
            recipeSnapshot = converters.jsonToRecipeSnapshot(
                converters.recipeSnapshotToJson(log.recipeSnapshot),
            ),
        ).toRecommendationHistory()

        assertEquals("fingerprint-meal", roundTripped?.fingerprint)
        assertEquals("korean", roundTripped?.cuisine)
        assertEquals("stir fry", roundTripped?.primaryTechnique)
        assertEquals(setOf("rice", "egg"), roundTripped?.ingredientNames)
        assertEquals(5, roundTripped?.rating)
        assertFalse(requireNotNull(roundTripped).recommendAgain)
        assertEquals(Instant.ofEpochMilli(1_000), roundTripped.completedAt)
    }

    @Test
    fun measurementHintsUseNewestAdjustmentPerNormalizedNameAndUnitAndCapTwentyUniqueKeys() {
        val newestDuplicate = adjustment("  Ｒｉｃｅ  ", 333_000, PantryUnit.GRAM, "new", 3_000)
        val olderDuplicate = adjustment("rice", 111_000, PantryUnit.GRAM, "old", 1_000)
        val unique = (1..21).map { index ->
            adjustment("Ingredient $index", index * 1_000L, PantryUnit.GRAM, "note $index", 2_000L + index)
        }
        val profile = PreferenceProfile.from(
            listOf(
                log("new", 3_000, adjustments = listOf(newestDuplicate)),
                log("bulk", 2_000, adjustments = unique),
                log("old", 1_000, adjustments = listOf(olderDuplicate)),
            ),
        )

        assertEquals(20, profile.measurementHints.size)
        assertEquals(333_000, profile.measurementHints.first { it.ingredientName == "rice" }.preferredAmountMilliUnits)
        assertEquals("new", profile.measurementHints.first { it.ingredientName == "rice" }.note)
        assertFalse(profile.measurementHints.any { it.ingredientName == "ingredient 1" })
        assertTrue(profile.measurementHints.any { it.ingredientName == "ingredient 21" })
    }

    @Test
    fun latestTwentyRealMealLogsDrivePositiveNegativeSignalsAndExactCooldownBoundary() {
        val now = Instant.parse("2026-07-19T00:00:00Z")
        val positive = log("positive", now.minusSeconds(1).toEpochMilli(), rating = 5, recommendAgain = true)
        val negative = log("negative", now.minusSeconds(2).toEpochMilli(), rating = 1, recommendAgain = false)
        val filler = (1..20).map { index ->
            log("filler-$index", now.minusSeconds((index + 10).toLong()).toEpochMilli(), cuisine = "Other $index")
        }

        val profile = PreferenceProfile.from(listOf(positive, negative) + filler)

        assertEquals(20, profile.history.size)
        assertTrue(profile.history.any { it.fingerprint == "fingerprint-positive" })
        assertTrue(profile.history.any { it.fingerprint == "fingerprint-negative" })
        assertFalse(profile.history.any { it.fingerprint == "fingerprint-filler-20" })
    }

    @Test
    fun recipeScreenRepositorySeamLoadsLatestMealLogsInsteadOfEmptyHistory() = runTest {
        val logs = (1..21).map { index -> log("meal-$index", index.toLong()) }
        val dao = object : MealLogDao {
            override suspend fun get(id: String) = logs.firstOrNull { it.id == id }
            override suspend fun count() = logs.size
            override suspend fun latest() = logs
        }

        val profile = loadPreferenceProfile(dao)

        assertEquals(20, profile.history.size)
        assertEquals("fingerprint-meal-21", profile.history.first().fingerprint)
        assertEquals("fingerprint-meal-2", profile.history.last().fingerprint)
    }

    @Test
    fun mappedMealLogsProduceExactPositiveNegativePreferenceAndThirtyDayCooldown() {
        val now = Instant.parse("2026-07-19T00:00:00Z")
        val pantry = pantry()
        val candidates = candidates(pantry.single().id)
        val candidateFingerprint = RecipeFingerprint.of(candidates.first(), pantry.associateBy { it.id })
        val positive = PreferenceProfile.from(
            listOf(log("positive", now.minusSeconds(1).toEpochMilli(), rating = 5, recommendAgain = true)),
        )
        val negative = PreferenceProfile.from(
            listOf(log("negative", now.minusSeconds(1).toEpochMilli(), rating = 1, recommendAgain = false)),
        )

        val positiveScore = valid(rank(candidates, pantry, positive, now)).ranked
            .single { it.candidate.title == "Rice bowl" }.preference
        val negativeScore = valid(rank(candidates, pantry, negative, now)).ranked
            .single { it.candidate.title == "Rice bowl" }.preference

        assertEquals(1.0, positiveScore, 0.0)
        assertEquals(0.0, negativeScore, 0.0)

        val beforeBoundary = PreferenceProfile.from(
            listOf(
                log(
                    "cooldown",
                    now.minusSeconds(30 * 24 * 60 * 60).plusMillis(1).toEpochMilli(),
                    rating = 1,
                    recommendAgain = false,
                    fingerprint = candidateFingerprint,
                ),
            ),
        )
        val atBoundary = PreferenceProfile.from(
            listOf(
                log(
                    "cooldown",
                    now.minusSeconds(30 * 24 * 60 * 60).toEpochMilli(),
                    rating = 1,
                    recommendAgain = false,
                    fingerprint = candidateFingerprint,
                ),
            ),
        )
        assertEquals(
            RecommendationInvalidReason.ACTIVE_COOLDOWN,
            (rank(candidates, pantry, beforeBoundary, now) as RecommendationResult.Invalid).reason,
        )
        assertTrue(rank(candidates, pantry, atBoundary, now) is RecommendationResult.Valid)
    }

    private fun log(
        id: String,
        completedAt: Long,
        cuisine: String = "Korean",
        technique: String = "stir fry",
        ingredients: Set<String> = setOf("Rice", "Egg"),
        rating: Int = 3,
        recommendAgain: Boolean = true,
        adjustments: List<MeasurementAdjustment> = emptyList(),
        fingerprint: String = "fingerprint-$id",
    ) = MealLogEntity(
        id = id,
        cookSessionId = "session-$id",
        recipeSnapshot = RecipeSnapshotRecord(
            id = fingerprint,
            title = "Recipe $id",
            pantryBindings = PantryBindings(emptyList()),
            steps = RecipeSteps(
                values = listOf("Cook"),
                metadata = RecipePreferenceMetadata(cuisine, technique, ingredients),
                recipeKind = com.junited31.leftovers.data.RecipeKind.MEAL,
            ),
            createdAtEpochMillis = 1,
            feedback = MealFeedback(rating, "local note", recommendAgain, adjustments, null),
        ),
        actualUses = ActualPantryUses(emptyList()),
        remainingPantry = PantryRemainingSnapshots(emptyList()),
        completedAtEpochMillis = completedAt,
    )

    private fun adjustment(name: String, amount: Long, unit: PantryUnit, note: String, completedAt: Long) =
        MeasurementAdjustment(name, amount, unit, note, completedAt)

    private fun rank(
        candidates: List<RecommendationCandidate>,
        pantry: List<PantryItemEntity>,
        profile: PreferenceProfile,
        now: Instant,
    ) = RecommendationRanker.rank(
        candidates,
        pantry,
        setOf("basic cookware"),
        profile.history,
        now,
        LocalDate.parse("2026-07-19"),
    )

    private fun valid(result: RecommendationResult) = result as RecommendationResult.Valid

    private fun pantry(): List<PantryItemEntity> {
        val id = requireNotNull(PantryItemId.parse("00000000-0000-4000-8000-000000000089"))
        return listOf(PantryItemEntity(id, "Rice", 10_000, PantryUnit.GRAM, null, 1))
    }

    private fun candidates(id: PantryItemId) = listOf(
        candidate("Rice bowl", "Korean", "stir fry", id),
        candidate("Rice soup", "Japanese", "boil", id),
        candidate("Rice bake", "Korean", "bake", id),
    )

    private fun candidate(title: String, cuisine: String, technique: String, id: PantryItemId) =
        RecommendationCandidate(
            title,
            cuisine,
            technique,
            listOf("basic cookware"),
            listOf(ProposedPantryUse(id, 1, PantryUnit.GRAM, 1_000)),
            emptyList(),
            listOf("Cook"),
            com.junited31.leftovers.data.RecipeKind.MEAL,
        )
}
