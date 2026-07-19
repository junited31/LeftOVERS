package com.junited31.leftovers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.edit
import com.junited31.leftovers.data.LeftoversDatabase
import com.junited31.leftovers.data.LeftoversPreferenceKeys
import com.junited31.leftovers.data.PantryItemEntity
import com.junited31.leftovers.data.PantryItemId
import com.junited31.leftovers.data.PantryUnit
import com.junited31.leftovers.data.PantryBindings
import com.junited31.leftovers.data.RecipeSnapshotEntity
import com.junited31.leftovers.data.RecipeSteps
import com.junited31.leftovers.data.CookSessionEntity
import com.junited31.leftovers.data.leftoversDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.time.LocalDate

class DebugSeedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = runBlocking(Dispatchers.IO) {
        val database = LeftoversDatabase.get(context)
        database.clearAllTables()
        context.leftoversDataStore.edit { preferences -> preferences.clear() }
        if (intent.action == DEBUG_SEED) {
            database.pantryDao().insertAll(seedPantry())
            database.recipeSnapshotDao().insert(
                RecipeSnapshotEntity(
                    id = "debug-cooking-recipe",
                    title = "채소 달걀 볶음밥",
                    pantryBindings = PantryBindings(emptyList()),
                    steps = RecipeSteps(
                        listOf("재료를 손질하세요", "약 7분 동안 고르게 볶으세요", "불을 끄고 접시에 담으세요"),
                    ),
                    createdAtEpochMillis = System.currentTimeMillis(),
                ),
            )
            database.cookSessionDao().insert(
                CookSessionEntity(
                    id = "debug-cooking-session",
                    recipeSnapshotId = "debug-cooking-recipe",
                    startedAtEpochMillis = System.currentTimeMillis(),
                    currentStepIndex = 1,
                    completedAtEpochMillis = null,
                ),
            )
            context.leftoversDataStore.edit { preferences ->
                preferences[LeftoversPreferenceKeys.EQUIPMENT_IDS] = seedEquipment
                preferences[LeftoversPreferenceKeys.ONBOARDING_COMPLETE] = true
            }
        }
    }

    private fun seedPantry() = listOf(
        PantryItemEntity(
            id = pantryId("00000000-0000-0000-0000-000000000401"),
            name = "Rice",
            quantityMilliUnits = 2_000_000,
            unit = PantryUnit.GRAM,
            expiryEpochDay = null,
            version = 1,
        ),
        PantryItemEntity(
            id = pantryId("00000000-0000-0000-0000-000000000402"),
            name = "Spinach",
            quantityMilliUnits = 300_000,
            unit = PantryUnit.GRAM,
            expiryEpochDay = LocalDate.now().plusDays(2).toEpochDay(),
            version = 1,
        ),
        PantryItemEntity(
            id = pantryId("00000000-0000-0000-0000-000000000403"),
            name = "Eggs",
            quantityMilliUnits = 6_000,
            unit = PantryUnit.COUNT,
            expiryEpochDay = LocalDate.now().plusDays(7).toEpochDay(),
            version = 1,
        ),
    )

    private fun pantryId(value: String) = requireNotNull(PantryItemId.parse(value))

    private companion object {
        const val DEBUG_SEED = "com.junited31.leftovers.DEBUG_SEED"
        val seedEquipment = setOf(
            "induction",
            "microwave",
            "air_fryer",
            "rice_cooker",
            "basic_cookware",
        )
    }
}
