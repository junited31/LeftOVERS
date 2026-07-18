package com.junited31.leftovers.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "pantry_items")
data class PantryItemEntity(
    @PrimaryKey val id: PantryItemId,
    val name: String,
    val quantityMilliUnits: Long,
    val unit: PantryUnit,
    val expiryEpochDay: Long?,
    val version: Int,
)

@Entity(tableName = "recipe_snapshots")
data class RecipeSnapshotEntity(
    @PrimaryKey val id: String,
    val title: String,
    val pantryBindings: PantryBindings,
    val steps: RecipeSteps,
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "cook_sessions",
    foreignKeys = [
        ForeignKey(
            entity = RecipeSnapshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["recipeSnapshotId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("recipeSnapshotId")],
)
data class CookSessionEntity(
    @PrimaryKey val id: String,
    val recipeSnapshotId: String,
    val startedAtEpochMillis: Long,
    val currentStepIndex: Int,
    val completedAtEpochMillis: Long?,
)

@Entity(
    tableName = "meal_logs",
    indices = [Index(value = ["cookSessionId"], unique = true)],
)
data class MealLogEntity(
    @PrimaryKey val id: String,
    val cookSessionId: String,
    val recipeSnapshot: RecipeSnapshotRecord,
    val actualUses: ActualPantryUses,
    val remainingPantry: PantryRemainingSnapshots,
    val completedAtEpochMillis: Long,
)
