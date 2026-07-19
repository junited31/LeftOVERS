package com.junited31.leftovers.data

import androidx.room.TypeConverter
import org.json.JSONArray
import org.json.JSONObject

class JsonConverters {
    @TypeConverter
    fun unitToString(unit: PantryUnit): String = unit.value

    @TypeConverter
    fun stringToUnit(value: String): PantryUnit =
        requireNotNull(PantryUnit.parse(value)) { "Unknown pantry unit: $value" }

    @TypeConverter
    fun pantryItemIdToString(id: PantryItemId): String = id.value

    @TypeConverter
    fun stringToPantryItemId(value: String): PantryItemId =
        requireNotNull(PantryItemId.parse(value)) { "Invalid pantry UUID: $value" }

    @TypeConverter
    fun pantryBindingsToJson(bindings: PantryBindings): String = bindingArray(bindings).toString()

    @TypeConverter
    fun jsonToPantryBindings(value: String): PantryBindings = bindings(JSONArray(value))

    @TypeConverter
    fun recipeStepsToJson(steps: RecipeSteps): String = steps.metadata?.let { metadata ->
        JSONObject()
            .put("values", JSONArray(steps.values))
            .put("metadata", metadataJson(metadata))
            .toString()
    } ?: JSONArray(steps.values).toString()

    @TypeConverter
    fun jsonToRecipeSteps(value: String): RecipeSteps = steps(value)

    @TypeConverter
    fun recipeSnapshotToJson(snapshot: RecipeSnapshotRecord): String = JSONObject()
        .put("id", snapshot.id)
        .put("title", snapshot.title)
        .put("pantryBindings", bindingArray(snapshot.pantryBindings))
        .put("steps", JSONArray(snapshot.steps.values))
        .put("metadata", snapshot.steps.metadata?.let(::metadataJson) ?: JSONObject.NULL)
        .put("createdAtEpochMillis", snapshot.createdAtEpochMillis)
        .put("feedback", feedbackJson(snapshot.feedback))
        .toString()

    @TypeConverter
    fun jsonToRecipeSnapshot(value: String): RecipeSnapshotRecord = JSONObject(value).let { json ->
        RecipeSnapshotRecord(
            id = json.getString("id"),
            title = json.getString("title"),
            pantryBindings = bindings(json.getJSONArray("pantryBindings")),
            steps = RecipeSteps(
                values = steps(json.getJSONArray("steps")).values,
                metadata = json.optJSONObject("metadata")?.let(::metadata),
            ),
            createdAtEpochMillis = json.getLong("createdAtEpochMillis"),
            feedback = json.optJSONObject("feedback")?.let(::feedback) ?: MealFeedback(),
        )
    }

    @TypeConverter
    fun actualUsesToJson(uses: ActualPantryUses): String = JSONArray().apply {
        uses.values.forEach { use ->
            put(
                JSONObject()
                    .put("pantryItemId", use.pantryItemId.value)
                    .put("sourceVersion", use.sourceVersion)
                    .put("unit", use.unit.value)
                    .put("actualMilliUnits", use.actualMilliUnits),
            )
        }
    }.toString()

    @TypeConverter
    fun jsonToActualUses(value: String): ActualPantryUses = JSONArray(value).let { array ->
        ActualPantryUses(
            List(array.length()) { index ->
                array.getJSONObject(index).let { json ->
                    ActualPantryUse(
                        pantryItemId = requiredPantryItemId(json.getString("pantryItemId")),
                        sourceVersion = json.getInt("sourceVersion"),
                        unit = requiredUnit(json.getString("unit")),
                        actualMilliUnits = json.getLong("actualMilliUnits"),
                    )
                }
            },
        )
    }

    @TypeConverter
    fun remainingToJson(remaining: PantryRemainingSnapshots): String = JSONArray().apply {
        remaining.values.forEach { row ->
            put(
                JSONObject()
                    .put("pantryItemId", row.pantryItemId.value)
                    .put("unit", row.unit.value)
                    .put("quantityMilliUnits", row.quantityMilliUnits)
                    .put("version", row.version),
            )
        }
    }.toString()

    @TypeConverter
    fun jsonToRemaining(value: String): PantryRemainingSnapshots = JSONArray(value).let { array ->
        PantryRemainingSnapshots(
            List(array.length()) { index ->
                array.getJSONObject(index).let { json ->
                    PantryRemaining(
                        pantryItemId = requiredPantryItemId(json.getString("pantryItemId")),
                        unit = requiredUnit(json.getString("unit")),
                        quantityMilliUnits = json.getLong("quantityMilliUnits"),
                        version = json.getInt("version"),
                    )
                }
            },
        )
    }

    private fun bindingArray(bindings: PantryBindings) = JSONArray().apply {
        bindings.values.forEach { binding ->
            put(
                JSONObject()
                    .put("pantryItemId", binding.pantryItemId.value)
                    .put("sourceVersion", binding.sourceVersion)
                    .put("unit", binding.unit.value)
                    .put("proposedMilliUnits", binding.proposedMilliUnits),
            )
        }
    }

    private fun bindings(array: JSONArray) = PantryBindings(
        List(array.length()) { index ->
            array.getJSONObject(index).let { json ->
                PantryBinding(
                    pantryItemId = requiredPantryItemId(json.getString("pantryItemId")),
                    sourceVersion = json.getInt("sourceVersion"),
                    unit = requiredUnit(json.getString("unit")),
                    proposedMilliUnits = json.getLong("proposedMilliUnits"),
                )
            }
        },
    )

    private fun steps(array: JSONArray) = RecipeSteps(
        List(array.length()) { index -> array.getString(index) },
    )

    private fun steps(value: String): RecipeSteps = if (value.trimStart().startsWith("[")) {
        steps(JSONArray(value))
    } else {
        JSONObject(value).let { json ->
            RecipeSteps(
                values = steps(json.getJSONArray("values")).values,
                metadata = json.optJSONObject("metadata")?.let(::metadata),
            )
        }
    }

    private fun metadataJson(metadata: RecipePreferenceMetadata) = JSONObject()
        .put("cuisine", metadata.cuisine)
        .put("primaryTechnique", metadata.primaryTechnique)
        .put("ingredientNames", JSONArray(metadata.ingredientNames.sorted()))

    private fun metadata(json: JSONObject) = RecipePreferenceMetadata(
        cuisine = json.getString("cuisine"),
        primaryTechnique = json.getString("primaryTechnique"),
        ingredientNames = List(json.getJSONArray("ingredientNames").length()) { index ->
            json.getJSONArray("ingredientNames").getString(index)
        }.toSet(),
    )

    private fun feedbackJson(feedback: MealFeedback) = JSONObject()
        .put("rating", feedback.rating)
        .put("notes", feedback.notes)
        .put("recommendAgain", feedback.recommendAgain)
        .put("finalPhotoPath", feedback.finalPhotoPath ?: JSONObject.NULL)
        .put("measurementAdjustments", JSONArray().apply {
            feedback.measurementAdjustments.forEach { adjustment ->
                put(
                    JSONObject()
                        .put("ingredientName", adjustment.ingredientName)
                        .put("preferredAmountMilliUnits", adjustment.preferredAmountMilliUnits)
                        .put("unit", adjustment.unit.value)
                        .put("note", adjustment.note)
                        .put("completedAtEpochMillis", adjustment.completedAtEpochMillis),
                )
            }
        })

    private fun feedback(json: JSONObject) = MealFeedback(
        rating = json.getInt("rating"),
        notes = json.getString("notes"),
        recommendAgain = json.getBoolean("recommendAgain"),
        measurementAdjustments = json.getJSONArray("measurementAdjustments").let { array ->
            List(array.length()) { index ->
                array.getJSONObject(index).let { adjustment ->
                    MeasurementAdjustment(
                        ingredientName = adjustment.getString("ingredientName"),
                        preferredAmountMilliUnits = adjustment.getLong("preferredAmountMilliUnits"),
                        unit = requiredUnit(adjustment.getString("unit")),
                        note = adjustment.getString("note"),
                        completedAtEpochMillis = adjustment.getLong("completedAtEpochMillis"),
                    )
                }
            }
        },
        finalPhotoPath = if (json.isNull("finalPhotoPath")) null else json.getString("finalPhotoPath"),
    )

    private fun requiredUnit(value: String) =
        requireNotNull(PantryUnit.parse(value)) { "Unknown pantry unit: $value" }

    private fun requiredPantryItemId(value: String) =
        requireNotNull(PantryItemId.parse(value)) { "Invalid pantry UUID: $value" }
}
