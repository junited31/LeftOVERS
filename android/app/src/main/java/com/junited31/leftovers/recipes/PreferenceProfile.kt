package com.junited31.leftovers.recipes

import com.junited31.leftovers.data.MealLogEntity
import com.junited31.leftovers.data.PantryUnit
import java.time.Instant

data class MeasurementHint(
    val ingredientName: String,
    val preferredAmountMilliUnits: Long,
    val unit: PantryUnit,
    val note: String,
)

data class PreferenceProfile(
    val history: List<RecommendationHistory>,
    val measurementHints: List<MeasurementHint>,
) {
    companion object {
        fun from(logs: List<MealLogEntity>): PreferenceProfile {
            val orderedLogs = logs.sortedWith(
                compareByDescending<MealLogEntity> { it.completedAtEpochMillis }.thenByDescending { it.id },
            )
            val history = orderedLogs.mapNotNull(MealLogEntity::toRecommendationHistory).take(20)
            val seen = mutableSetOf<Pair<String, PantryUnit>>()
            val hints = orderedLogs
                .flatMap { log -> log.recipeSnapshot.feedback.measurementAdjustments }
                .sortedByDescending { it.completedAtEpochMillis }
                .mapNotNull { adjustment ->
                    val name = RecipeNormalizer.normalize(adjustment.ingredientName)
                    if (!seen.add(name to adjustment.unit)) return@mapNotNull null
                    MeasurementHint(
                        ingredientName = name,
                        preferredAmountMilliUnits = adjustment.preferredAmountMilliUnits,
                        unit = adjustment.unit,
                        note = adjustment.note,
                    )
                }
                .take(20)
            return PreferenceProfile(history, hints)
        }
    }
}

fun MealLogEntity.toRecommendationHistory(): RecommendationHistory? {
    val metadata = recipeSnapshot.steps.metadata ?: return null
    return RecommendationHistory(
        fingerprint = recipeSnapshot.id,
        cuisine = RecipeNormalizer.normalize(metadata.cuisine),
        primaryTechnique = RecipeNormalizer.normalize(metadata.primaryTechnique),
        ingredientNames = metadata.ingredientNames.mapTo(mutableSetOf(), RecipeNormalizer::normalize),
        rating = recipeSnapshot.feedback.rating,
        recommendAgain = recipeSnapshot.feedback.recommendAgain,
        completedAt = Instant.ofEpochMilli(completedAtEpochMillis),
    )
}
