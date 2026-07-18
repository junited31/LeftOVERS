package com.junited31.leftovers.recipes

import com.junited31.leftovers.data.PantryItemEntity
import com.junited31.leftovers.data.PantryItemId
import com.junited31.leftovers.data.PantryUnit
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDate

sealed interface RecipeDecodeResult {
    data class Success(val candidates: List<RecommendationCandidate>) : RecipeDecodeResult
    data object Invalid : RecipeDecodeResult
}

object RecipeJson {
    fun request(
        pantry: List<PantryItemEntity>,
        equipment: Set<String>,
        history: List<RecommendationHistory>,
    ): String = JSONObject()
        .put("pantry", JSONArray().apply { pantry.forEach { put(pantryRow(it)) } })
        .put(
            "equipment",
            JSONArray(equipment.map { it.replace('_', ' ') }.sorted()),
        )
        .put(
            "history",
            JSONArray().apply {
                history.sortedByDescending { it.completedAt }.take(20).forEach { put(historyRow(it)) }
            },
        )
        .toString()

    fun response(body: String): RecipeDecodeResult = try {
        val recipes = JSONObject(body).getJSONArray("recipes")
        RecipeDecodeResult.Success(List(recipes.length()) { candidate(recipes.getJSONObject(it)) })
    } catch (_: JSONException) {
        RecipeDecodeResult.Invalid
    } catch (_: IllegalArgumentException) {
        RecipeDecodeResult.Invalid
    }

    private fun pantryRow(item: PantryItemEntity) = JSONObject()
        .put("pantryItemId", item.id.value)
        .put("version", item.version)
        .put("name", item.name)
        .put("unit", item.unit.value)
        .put("quantityMilliUnits", item.quantityMilliUnits)
        .put(
            "expiryDate",
            item.expiryEpochDay?.let { LocalDate.ofEpochDay(it).toString() } ?: JSONObject.NULL,
        )

    private fun historyRow(log: RecommendationHistory) = JSONObject()
        .put("fingerprint", log.fingerprint)
        .put("cuisine", log.cuisine)
        .put("primaryTechnique", log.primaryTechnique)
        .put("ingredientNames", JSONArray(log.ingredientNames.sorted()))
        .put("rating", log.rating)
        .put("recommendAgain", log.recommendAgain)
        .put("completedAt", log.completedAt.toString())

    private fun candidate(json: JSONObject) = RecommendationCandidate(
        title = json.getString("title"),
        cuisine = json.getString("cuisine"),
        primaryTechnique = json.getString("primaryTechnique"),
        requiredEquipment = strings(json.getJSONArray("requiredEquipment")),
        trackedUses = objects(json.getJSONArray("trackedUses")) { trackedUse(it) },
        missingIngredients = objects(json.getJSONArray("missingIngredients")) { missing(it) },
        steps = strings(json.getJSONArray("steps")),
    )

    private fun trackedUse(json: JSONObject) = ProposedPantryUse(
        pantryItemId = requireNotNull(PantryItemId.parse(json.getString("pantryItemId"))),
        sourceVersion = json.getInt("version"),
        unit = requireNotNull(PantryUnit.parse(json.getString("unit"))),
        proposedMilliUnits = json.getLong("proposedMilliUnits"),
    )

    private fun missing(json: JSONObject) = MissingRecipeIngredient(
        name = json.getString("name"),
        amountMilliUnits = json.getLong("amountMilliUnits"),
        unit = requireNotNull(PantryUnit.parse(json.getString("unit"))),
    )

    private fun strings(array: JSONArray) = List(array.length()) { array.getString(it) }

    private fun <T> objects(array: JSONArray, transform: (JSONObject) -> T) =
        List(array.length()) { transform(array.getJSONObject(it)) }
}
