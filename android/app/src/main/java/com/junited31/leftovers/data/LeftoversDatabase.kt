package com.junited31.leftovers.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        PantryItemEntity::class,
        RecipeSnapshotEntity::class,
        CookSessionEntity::class,
        MealLogEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(JsonConverters::class)
abstract class LeftoversDatabase : RoomDatabase() {
    abstract fun pantryDao(): PantryDao
    abstract fun recipeSnapshotDao(): RecipeSnapshotDao
    abstract fun cookSessionDao(): CookSessionDao
    abstract fun mealLogDao(): MealLogDao
    abstract fun inventoryCompletionDao(): InventoryCompletionDao

    companion object {
        const val NAME = "leftovers.db"

        @Volatile
        private var instance: LeftoversDatabase? = null

        fun get(context: Context): LeftoversDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                LeftoversDatabase::class.java,
                NAME,
            ).build().also { instance = it }
        }
    }
}
