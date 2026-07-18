package com.junited31.leftovers.data

import java.util.UUID

@ConsistentCopyVisibility
data class PantryItemId private constructor(val value: String) {
    companion object {
        fun parse(value: String): PantryItemId? {
            val canonical = try {
                UUID.fromString(value).toString()
            } catch (_: IllegalArgumentException) {
                return null
            }
            return canonical.takeIf { it.equals(value, ignoreCase = true) }?.let(::PantryItemId)
        }
    }
}

enum class PantryUnit(val value: String) {
    GRAM("g"),
    MILLILITER("ml"),
    COUNT("count"),
    ;

    companion object {
        fun parse(value: String): PantryUnit? = entries.firstOrNull { it.value == value }
    }
}

data class PantryBinding(
    val pantryItemId: PantryItemId,
    val sourceVersion: Int,
    val unit: PantryUnit,
    val proposedMilliUnits: Long,
)

data class PantryBindings(val values: List<PantryBinding>)

data class RecipeSteps(val values: List<String>)

data class RecipeSnapshotRecord(
    val id: String,
    val title: String,
    val pantryBindings: PantryBindings,
    val steps: RecipeSteps,
    val createdAtEpochMillis: Long,
)

data class ActualPantryUse(
    val pantryItemId: PantryItemId,
    val sourceVersion: Int,
    val unit: PantryUnit,
    val actualMilliUnits: Long,
)

data class ActualPantryUses(val values: List<ActualPantryUse>)

data class PantryRemaining(
    val pantryItemId: PantryItemId,
    val unit: PantryUnit,
    val quantityMilliUnits: Long,
    val version: Int,
)

data class PantryRemainingSnapshots(val values: List<PantryRemaining>)

data class CompleteCookSessionCommand(
    val cookSessionId: String,
    val mealLogId: String,
    val completedAtEpochMillis: Long,
    val actualUses: ActualPantryUses,
)

sealed interface CompletionResult {
    data class Success(val mealLogId: String) : CompletionResult
    data class DuplicateInventoryId(val pantryItemId: PantryItemId) : CompletionResult
    data class UnknownInventory(val pantryItemId: PantryItemId) : CompletionResult
    data class InventoryNotBound(val pantryItemId: PantryItemId) : CompletionResult
    data class MissingActualUse(val pantryItemId: PantryItemId) : CompletionResult
    data class UnitMismatch(val pantryItemId: PantryItemId) : CompletionResult
    data class StaleInventory(val pantryItemId: PantryItemId) : CompletionResult
    data class InvalidActualUse(val pantryItemId: PantryItemId) : CompletionResult
    data object UnknownCookSession : CompletionResult
    data object UnknownRecipeSnapshot : CompletionResult
}
