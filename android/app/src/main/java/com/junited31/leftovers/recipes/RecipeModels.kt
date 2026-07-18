package com.junited31.leftovers.recipes

import com.junited31.leftovers.data.PantryItemId
import com.junited31.leftovers.data.PantryUnit
import java.time.Instant

data class ProposedPantryUse(
    val pantryItemId: PantryItemId,
    val sourceVersion: Int,
    val unit: PantryUnit,
    val proposedMilliUnits: Long,
)

data class MissingRecipeIngredient(
    val name: String,
    val amountMilliUnits: Long,
    val unit: PantryUnit,
)

data class RecommendationCandidate(
    val title: String,
    val cuisine: String,
    val primaryTechnique: String,
    val requiredEquipment: List<String>,
    val trackedUses: List<ProposedPantryUse>,
    val missingIngredients: List<MissingRecipeIngredient>,
    val steps: List<String>,
)

data class RecommendationHistory(
    val fingerprint: String,
    val cuisine: String,
    val primaryTechnique: String,
    val ingredientNames: Set<String>,
    val rating: Int,
    val recommendAgain: Boolean,
    val completedAt: Instant,
)

data class RankedRecommendation(
    val id: String,
    val candidate: RecommendationCandidate,
    val score: Double,
    val coverage: Double,
    val expiry: Double,
    val preference: Double,
    val novelty: Double,
)

enum class RecommendationInvalidReason {
    CANDIDATE_COUNT,
    EQUIPMENT_MISMATCH,
    ACTIVE_COOLDOWN,
    DUPLICATE_TITLE,
    DUPLICATE_FINGERPRINT,
    CUISINE_DIVERSITY,
    TECHNIQUE_DIVERSITY,
    UNKNOWN_PANTRY,
    DUPLICATE_PANTRY,
    VERSION_MISMATCH,
    UNIT_MISMATCH,
    INVALID_AMOUNT,
}

sealed interface RecommendationResult {
    data class Valid(val ranked: List<RankedRecommendation>) : RecommendationResult
    data class Invalid(val reason: RecommendationInvalidReason) : RecommendationResult
}
