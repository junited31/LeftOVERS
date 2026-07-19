package com.junited31.leftovers.recipes

import com.junited31.leftovers.data.PantryBinding
import com.junited31.leftovers.data.PantryBindings
import com.junited31.leftovers.data.PantryItemEntity
import com.junited31.leftovers.data.PantryItemId
import com.junited31.leftovers.data.RecipePreferenceMetadata
import com.junited31.leftovers.data.RecipeSnapshotDao
import com.junited31.leftovers.data.RecipeSnapshotEntity
import com.junited31.leftovers.data.RecipeSteps

class RecipeSnapshotSaver(private val dao: RecipeSnapshotDao) {
    suspend fun save(
        result: RecommendationResult,
        selectedId: String,
        pantryById: Map<PantryItemId, PantryItemEntity>,
        nowEpochMillis: Long,
    ): Boolean {
        if (result !is RecommendationResult.Valid || result.ranked.size != 3) return false
        val selected = result.ranked.singleOrNull { it.id == selectedId } ?: return false
        val candidate = selected.candidate
        return dao.insert(
            RecipeSnapshotEntity(
                id = selected.id,
                title = candidate.title,
                pantryBindings = PantryBindings(
                    candidate.trackedUses.map {
                        PantryBinding(
                            pantryItemId = it.pantryItemId,
                            sourceVersion = it.sourceVersion,
                            unit = it.unit,
                            proposedMilliUnits = it.proposedMilliUnits,
                        )
                    },
                ),
                steps = RecipeSteps(
                    values = candidate.steps.toList(),
                    metadata = RecipePreferenceMetadata(
                        cuisine = candidate.cuisine,
                        primaryTechnique = candidate.primaryTechnique,
                        ingredientNames = RecipeFingerprint.candidateIngredientNames(candidate, pantryById).toSet(),
                    ),
                ),
                createdAtEpochMillis = nowEpochMillis,
            ),
        ) != -1L
    }
}
