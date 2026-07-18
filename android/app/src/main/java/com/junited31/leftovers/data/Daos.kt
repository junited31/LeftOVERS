package com.junited31.leftovers.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PantryDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(items: List<PantryItemEntity>)

    @Query("SELECT * FROM pantry_items WHERE id = :id")
    suspend fun get(id: PantryItemId): PantryItemEntity?

    @Query("SELECT * FROM pantry_items ORDER BY id")
    suspend fun getAll(): List<PantryItemEntity>

    @Query("SELECT * FROM pantry_items ORDER BY name COLLATE NOCASE, id")
    fun observeAll(): Flow<List<PantryItemEntity>>

    @Update
    suspend fun update(item: PantryItemEntity): Int

    @Query("DELETE FROM pantry_items WHERE id = :id")
    suspend fun delete(id: PantryItemId): Int
}

@Dao
interface RecipeSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(snapshot: RecipeSnapshotEntity): Long

    @Query("SELECT * FROM recipe_snapshots WHERE id = :id")
    suspend fun get(id: String): RecipeSnapshotEntity?

    @Query("SELECT COUNT(*) FROM recipe_snapshots")
    suspend fun count(): Int

    @Query("SELECT id FROM recipe_snapshots ORDER BY createdAtEpochMillis, id")
    suspend fun getAllIds(): List<String>
}

@Dao
interface CookSessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: CookSessionEntity)

    @Query("SELECT * FROM cook_sessions WHERE id = :id")
    suspend fun get(id: String): CookSessionEntity?
}

@Dao
interface MealLogDao {
    @Query("SELECT * FROM meal_logs WHERE id = :id")
    suspend fun get(id: String): MealLogEntity?

    @Query("SELECT COUNT(*) FROM meal_logs")
    suspend fun count(): Int
}

@Dao
abstract class InventoryCompletionDao {
    @Query("SELECT * FROM cook_sessions WHERE id = :id")
    protected abstract suspend fun session(id: String): CookSessionEntity?

    @Query("SELECT * FROM recipe_snapshots WHERE id = :id")
    protected abstract suspend fun recipe(id: String): RecipeSnapshotEntity?

    @Query("SELECT * FROM pantry_items WHERE id IN (:ids)")
    protected abstract suspend fun pantryRows(ids: List<PantryItemId>): List<PantryItemEntity>

    @Update
    protected abstract suspend fun updatePantry(rows: List<PantryItemEntity>)

    @Update
    protected abstract suspend fun updateSession(session: CookSessionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertMealLog(mealLog: MealLogEntity)

    @Transaction
    open suspend fun complete(command: CompleteCookSessionCommand): CompletionResult {
        val uses = command.actualUses.values
        val duplicateId = uses.groupingBy { it.pantryItemId }
            .eachCount()
            .entries
            .firstOrNull { it.value > 1 }
            ?.key
        if (duplicateId != null) return CompletionResult.DuplicateInventoryId(duplicateId)

        val cookSession = session(command.cookSessionId) ?: return CompletionResult.UnknownCookSession
        val recipeSnapshot = recipe(cookSession.recipeSnapshotId)
            ?: return CompletionResult.UnknownRecipeSnapshot
        val bindings = recipeSnapshot.pantryBindings.values.associateBy { it.pantryItemId }
        val rows = pantryRows(uses.map { it.pantryItemId }).associateBy { it.id }

        uses.firstOrNull { it.pantryItemId !in rows }?.let {
            return CompletionResult.UnknownInventory(it.pantryItemId)
        }
        uses.firstOrNull { it.pantryItemId !in bindings }?.let {
            return CompletionResult.InventoryNotBound(it.pantryItemId)
        }
        bindings.keys.firstOrNull { id -> uses.none { it.pantryItemId == id } }?.let {
            return CompletionResult.MissingActualUse(it)
        }

        uses.forEach { use ->
            val binding = checkNotNull(bindings[use.pantryItemId])
            val row = checkNotNull(rows[use.pantryItemId])
            if (use.unit != binding.unit || use.unit != row.unit) {
                return CompletionResult.UnitMismatch(use.pantryItemId)
            }
            if (use.sourceVersion != binding.sourceVersion || use.sourceVersion != row.version) {
                return CompletionResult.StaleInventory(use.pantryItemId)
            }
            if (use.actualMilliUnits !in 0..row.quantityMilliUnits) {
                return CompletionResult.InvalidActualUse(use.pantryItemId)
            }
        }

        val updatedRows = uses.map { use ->
            val row = checkNotNull(rows[use.pantryItemId])
            row.copy(
                quantityMilliUnits = row.quantityMilliUnits - use.actualMilliUnits,
                version = row.version + 1,
            )
        }
        updatePantry(updatedRows)
        insertMealLog(
            MealLogEntity(
                id = command.mealLogId,
                cookSessionId = cookSession.id,
                recipeSnapshot = RecipeSnapshotRecord(
                    id = recipeSnapshot.id,
                    title = recipeSnapshot.title,
                    pantryBindings = recipeSnapshot.pantryBindings,
                    steps = recipeSnapshot.steps,
                    createdAtEpochMillis = recipeSnapshot.createdAtEpochMillis,
                ),
                actualUses = command.actualUses,
                remainingPantry = PantryRemainingSnapshots(
                    updatedRows.map {
                        PantryRemaining(it.id, it.unit, it.quantityMilliUnits, it.version)
                    },
                ),
                completedAtEpochMillis = command.completedAtEpochMillis,
            ),
        )
        updateSession(cookSession.copy(completedAtEpochMillis = command.completedAtEpochMillis))
        return CompletionResult.Success(command.mealLogId)
    }
}
