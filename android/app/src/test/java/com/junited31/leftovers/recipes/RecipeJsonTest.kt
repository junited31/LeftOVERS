package com.junited31.leftovers.recipes

import com.junited31.leftovers.data.PantryItemEntity
import com.junited31.leftovers.data.PantryItemId
import com.junited31.leftovers.data.PantryUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class RecipeJsonTest {
    private val pantryId = requireNotNull(
        PantryItemId.parse("10000000-0000-4000-8000-000000000001"),
    )

    @Test
    fun request_contains_strict_pantry_equipment_and_only_newest_twenty_history_rows() {
        // Given
        val pantry = listOf(PantryItemEntity(pantryId, "Ｒｉｃｅ", 500_000, PantryUnit.GRAM, 20_000, 7))
        val history = (1..21).map { day ->
            RecommendationHistory(
                fingerprint = day.toString().padStart(64, 'a'),
                cuisine = "Cuisine $day",
                primaryTechnique = "Technique $day",
                ingredientNames = setOf("Ingredient $day"),
                rating = 4,
                recommendAgain = true,
                completedAt = Instant.parse("2026-07-${day.toString().padStart(2, '0')}T00:00:00Z"),
            )
        }

        // When
        val json = JSONObject(RecipeJson.request(pantry, setOf("gas burner"), history))

        // Then
        assertEquals(pantryId.value, json.getJSONArray("pantry").getJSONObject(0).getString("pantryItemId"))
        assertEquals(7, json.getJSONArray("pantry").getJSONObject(0).getInt("version"))
        assertEquals(500_000, json.getJSONArray("pantry").getJSONObject(0).getLong("quantityMilliUnits"))
        assertEquals("gas burner", json.getJSONArray("equipment").getString(0))
        assertEquals(20, json.getJSONArray("history").length())
        assertEquals("Cuisine 21", json.getJSONArray("history").getJSONObject(0).getString("cuisine"))
        assertEquals("Cuisine 2", json.getJSONArray("history").getJSONObject(19).getString("cuisine"))
    }

    @Test
    fun response_decodes_real_candidate_fields_and_rejects_malformed_json() {
        // Given
        val body = """{"recipes":[{"title":"Rice bowl","cuisine":"Korean","primaryTechnique":"mix","requiredEquipment":["basic cookware"],"trackedUses":[{"pantryItemId":"${pantryId.value}","version":7,"unit":"g","proposedMilliUnits":100000}],"missingIngredients":[{"name":"Salt","amountMilliUnits":1000,"unit":"g"}],"steps":["Mix","Serve"]}]}"""

        // When
        val decoded = RecipeJson.response(body)
        val malformed = RecipeJson.response("{not-json")

        // Then
        val candidate = (decoded as RecipeDecodeResult.Success).candidates.single()
        assertEquals("Rice bowl", candidate.title)
        assertEquals(100_000, candidate.trackedUses.single().proposedMilliUnits)
        assertEquals("Salt", candidate.missingIngredients.single().name)
        assertEquals(listOf("Mix", "Serve"), candidate.steps)
        assertTrue(malformed is RecipeDecodeResult.Invalid)
    }

    @Test
    fun requestCharacterizationKeepsHistoryEmptyWhenCallerHasNoLogs() {
        val pantry = listOf(PantryItemEntity(pantryId, "Rice", 500_000, PantryUnit.GRAM, null, 1))

        val request = JSONObject(RecipeJson.request(pantry, setOf("gas burner"), emptyList()))

        assertEquals(0, request.getJSONArray("history").length())
        assertEquals("gas burner", request.getJSONArray("equipment").getString(0))
    }

    @Test
    fun nextRequestSerializesMeasurementHintsWithBackendFieldNames() {
        val pantry = listOf(PantryItemEntity(pantryId, "Rice", 500_000, PantryUnit.GRAM, null, 1))
        val hints = listOf(MeasurementHint("rice", 125_000, PantryUnit.GRAM, "half cup"))

        val request = JSONObject(RecipeJson.request(pantry, setOf("gas burner"), emptyList(), hints))

        val hint = request.getJSONArray("measurementHints").getJSONObject(0)
        assertEquals("rice", hint.getString("ingredientName"))
        assertEquals(125_000L, hint.getLong("preferredAmountMilliUnits"))
        assertEquals("g", hint.getString("unit"))
        assertEquals("half cup", hint.getString("note"))
    }

    @Test
    fun requestOmitsBlankMeasurementHintNotes() {
        val pantry = listOf(PantryItemEntity(pantryId, "Rice", 500_000, PantryUnit.GRAM, null, 1))
        val hints = listOf(
            MeasurementHint("rice", 125_000, PantryUnit.GRAM, ""),
            MeasurementHint("water", 250_000, PantryUnit.MILLILITER, "   \t"),
        )

        val request = JSONObject(RecipeJson.request(pantry, emptySet(), emptyList(), hints))

        assertFalse(request.getJSONArray("measurementHints").getJSONObject(0).has("note"))
        assertFalse(request.getJSONArray("measurementHints").getJSONObject(1).has("note"))
    }
}
