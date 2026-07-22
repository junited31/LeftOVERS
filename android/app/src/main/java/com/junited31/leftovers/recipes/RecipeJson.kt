package com.junited31.leftovers.recipes

import com.junited31.leftovers.data.PantryItemEntity
import com.junited31.leftovers.data.PantryItemId
import com.junited31.leftovers.data.PantryUnit
import com.junited31.leftovers.data.RecipeKind
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
        measurementHints: List<MeasurementHint> = emptyList(),
        locale: String,
        recipeKind: RecipeKind,
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
        .put(
            "measurementHints",
            JSONArray().apply { measurementHints.forEach { put(measurementHint(it)) } },
        )
        .put("locale", locale)
        .put("recipeKind", recipeKind.value)
        .toString()

    fun response(body: String, requestedKind: RecipeKind): RecipeDecodeResult = try {
        val root = JSONObject(body)
        require(!root.has("locale"))
        val recipes = root.getJSONArray("recipes")
        RecipeDecodeResult.Success(List(recipes.length()) {
            candidate(recipes.getJSONObject(it), requestedKind)
        })
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

    private fun measurementHint(hint: MeasurementHint) = JSONObject()
        .put("ingredientName", hint.ingredientName)
        .put("preferredAmountMilliUnits", hint.preferredAmountMilliUnits)
        .put("unit", hint.unit.value)
        .apply { if (hint.note.isNotBlank()) put("note", hint.note) }

    private fun candidate(json: JSONObject, requestedKind: RecipeKind): RecommendationCandidate {
        require(!json.has("locale"))
        val recipeKind = requireNotNull(RecipeKind.parse(json.getString("recipeKind")))
        require(recipeKind == requestedKind)
        return RecommendationCandidate(
        title = json.getString("title"),
        cuisine = json.getString("cuisine"),
        primaryTechnique = json.getString("primaryTechnique"),
        requiredEquipment = strings(json.getJSONArray("requiredEquipment")),
        trackedUses = objects(json.getJSONArray("trackedUses")) { trackedUse(it) },
        missingIngredients = objects(json.getJSONArray("missingIngredients")) { missing(it) },
        steps = strings(json.getJSONArray("steps")),
        recipeKind = recipeKind,
    )
    }

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
