package com.junited31.leftovers.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InventoryCompletionInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "inventory-completion-instrumented.db"

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun completionPersistsExactQuantitiesAndOneImmutableLogAcrossReopen() = runBlocking {
        // Given
        context.deleteDatabase(databaseName)
        var database = openDatabase()
        database.pantryDao().insertAll(
            listOf(
                PantryItemEntity("rice", "Rice", 10_000, PantryUnit.GRAM, null, 1),
                PantryItemEntity("stock", "Stock", 8_000, PantryUnit.MILLILITER, null, 3),
            ),
        )
        database.recipeSnapshotDao().insert(
            RecipeSnapshotEntity(
                "recipe",
                "Device recipe",
                PantryBindings(
                    listOf(
                        PantryBinding("rice", 1, PantryUnit.GRAM, 2_500),
                        PantryBinding("stock", 3, PantryUnit.MILLILITER, 8_000),
                    ),
                ),
                RecipeSteps(listOf("Cook")),
                10,
            ),
        )
        database.cookSessionDao().insert(CookSessionEntity("session", "recipe", 20, 0, null))

        // When
        val result = database.inventoryCompletionDao().complete(
            CompleteCookSessionCommand(
                "session",
                "meal",
                30,
                ActualPantryUses(
                    listOf(
                        ActualPantryUse("rice", 1, PantryUnit.GRAM, 2_500),
                        ActualPantryUse("stock", 3, PantryUnit.MILLILITER, 8_000),
                    ),
                ),
            ),
        )
        database.close()
        database = openDatabase()

        // Then
        assertEquals(CompletionResult.Success("meal"), result)
        assertEquals(7_500L, database.pantryDao().get("rice")?.quantityMilliUnits)
        assertEquals(0L, database.pantryDao().get("stock")?.quantityMilliUnits)
        assertEquals(2, database.pantryDao().getAll().size)
        val meal = database.mealLogDao().get("meal")
        assertEquals(1, database.mealLogDao().count())
        assertEquals("Device recipe", meal?.recipeSnapshot?.title)
        assertEquals(listOf(7_500L, 0L), meal?.remainingPantry?.values?.map { it.quantityMilliUnits })
        database.close()
    }

    @Test
    fun exactlyOneConcurrentCompletionWinsAgainstOneRowVersion() = runBlocking {
        repeat(3) { iteration ->
            // Given
            val database = Room.inMemoryDatabaseBuilder(context, LeftoversDatabase::class.java).build()
            val suffix = iteration.toString()
            database.pantryDao().insertAll(
                listOf(PantryItemEntity("item$suffix", "Item", 10_000, PantryUnit.COUNT, null, 1)),
            )
            database.recipeSnapshotDao().insert(
                RecipeSnapshotEntity(
                    "recipe$suffix",
                    "Concurrent recipe",
                    PantryBindings(listOf(PantryBinding("item$suffix", 1, PantryUnit.COUNT, 1_000))),
                    RecipeSteps(listOf("Cook")),
                    10,
                ),
            )
            database.cookSessionDao().insert(CookSessionEntity("session$suffix", "recipe$suffix", 20, 0, null))

            // When
            val results = withContext(Dispatchers.IO) {
                listOf("a", "b").map { contender ->
                    async {
                        database.inventoryCompletionDao().complete(
                            CompleteCookSessionCommand(
                                "session$suffix",
                                "meal-$suffix-$contender",
                                30,
                                ActualPantryUses(
                                    listOf(ActualPantryUse("item$suffix", 1, PantryUnit.COUNT, 1_000)),
                                ),
                            ),
                        )
                    }
                }.awaitAll()
            }

            // Then
            assertEquals(1, results.count { it is CompletionResult.Success })
            assertEquals(1, results.count { it == CompletionResult.StaleInventory("item$suffix") })
            assertEquals(9_000L, database.pantryDao().get("item$suffix")?.quantityMilliUnits)
            assertEquals(1, database.mealLogDao().count())
            database.close()
        }
    }

    private fun openDatabase() = Room.databaseBuilder(
        context,
        LeftoversDatabase::class.java,
        databaseName,
    ).build()

}
