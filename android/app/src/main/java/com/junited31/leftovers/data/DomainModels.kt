package com.junited31.leftovers.data

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
    val pantryItemId: String,
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
    val pantryItemId: String,
    val sourceVersion: Int,
    val unit: PantryUnit,
    val actualMilliUnits: Long,
)

data class ActualPantryUses(val values: List<ActualPantryUse>)

data class PantryRemaining(
    val pantryItemId: String,
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
    data class DuplicateInventoryId(val pantryItemId: String) : CompletionResult
    data class UnknownInventory(val pantryItemId: String) : CompletionResult
    data class InventoryNotBound(val pantryItemId: String) : CompletionResult
    data class MissingActualUse(val pantryItemId: String) : CompletionResult
    data class UnitMismatch(val pantryItemId: String) : CompletionResult
    data class StaleInventory(val pantryItemId: String) : CompletionResult
    data class InvalidActualUse(val pantryItemId: String) : CompletionResult
    data object UnknownCookSession : CompletionResult
    data object UnknownRecipeSnapshot : CompletionResult
}
