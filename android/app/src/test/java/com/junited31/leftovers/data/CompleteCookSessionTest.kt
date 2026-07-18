package com.junited31.leftovers.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CompleteCookSessionTest {
    private lateinit var database: LeftoversDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            LeftoversDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun rollsBackEveryRowWhenActualUseExceedsQuantity() = runTest {
        // Given
        givenSession()

        // When
        val result = database.inventoryCompletionDao().complete(
            command(
                ActualPantryUse("flour", 1, PantryUnit.GRAM, 10_001),
                ActualPantryUse("milk", 1, PantryUnit.MILLILITER, 1_000),
            ),
        )

        // Then
        assertEquals(CompletionResult.InvalidActualUse("flour"), result)
        assertUnchangedInventoryAndNoLog()
    }

    @Test
    fun rollsBackEveryRowWhenUnitDiffersFromBinding() = runTest {
        // Given
        givenSession()

        // When
        val result = database.inventoryCompletionDao().complete(
            command(
                ActualPantryUse("flour", 1, PantryUnit.COUNT, 1_000),
                ActualPantryUse("milk", 1, PantryUnit.MILLILITER, 1_000),
            ),
        )

        // Then
        assertEquals(CompletionResult.UnitMismatch("flour"), result)
        assertUnchangedInventoryAndNoLog()
    }

    @Test
    fun rollsBackEveryRowWhenInventoryVersionIsStale() = runTest {
        // Given
        givenSession(flourVersion = 2)

        // When
        val result = database.inventoryCompletionDao().complete(
            command(
                ActualPantryUse("flour", 1, PantryUnit.GRAM, 1_000),
                ActualPantryUse("milk", 1, PantryUnit.MILLILITER, 1_000),
            ),
        )

        // Then
        assertEquals(CompletionResult.StaleInventory("flour"), result)
        assertUnchangedInventoryAndNoLog(flourVersion = 2)
    }

    @Test
    fun rejectsDuplicateIdsBeforeMutatingAnyRow() = runTest {
        // Given
        givenSession()

        // When
        val result = database.inventoryCompletionDao().complete(
            command(
                ActualPantryUse("flour", 1, PantryUnit.GRAM, 1_000),
                ActualPantryUse("flour", 1, PantryUnit.GRAM, 2_000),
            ),
        )

        // Then
        assertEquals(CompletionResult.DuplicateInventoryId("flour"), result)
        assertUnchangedInventoryAndNoLog()
    }

    @Test
    fun rejectsUnknownIdsBeforeMutatingAnyRow() = runTest {
        // Given
        givenSession()

        // When
        val result = database.inventoryCompletionDao().complete(
            command(
                ActualPantryUse("flour", 1, PantryUnit.GRAM, 1_000),
                ActualPantryUse("missing", 1, PantryUnit.MILLILITER, 1_000),
            ),
        )

        // Then
        assertEquals(CompletionResult.UnknownInventory("missing"), result)
        assertUnchangedInventoryAndNoLog()
    }

    private suspend fun givenSession(flourVersion: Int = 1) {
        database.pantryDao().insertAll(
            listOf(
                PantryItemEntity("flour", "Flour", 10_000, PantryUnit.GRAM, null, flourVersion),
                PantryItemEntity("milk", "Milk", 20_000, PantryUnit.MILLILITER, null, 1),
            ),
        )
        database.recipeSnapshotDao().insert(
            RecipeSnapshotEntity(
                id = "recipe",
                title = "Pancakes",
                pantryBindings = PantryBindings(
                    listOf(
                        PantryBinding("flour", 1, PantryUnit.GRAM, 5_000),
                        PantryBinding("milk", 1, PantryUnit.MILLILITER, 2_000),
                    ),
                ),
                steps = RecipeSteps(listOf("Mix", "Cook")),
                createdAtEpochMillis = 1_000,
            ),
        )
        database.cookSessionDao().insert(
            CookSessionEntity("session", "recipe", 2_000, 0, null),
        )
    }

    private fun command(vararg uses: ActualPantryUse) = CompleteCookSessionCommand(
        cookSessionId = "session",
        mealLogId = "meal",
        completedAtEpochMillis = 3_000,
        actualUses = ActualPantryUses(uses.toList()),
    )

    private suspend fun assertUnchangedInventoryAndNoLog(flourVersion: Int = 1) {
        assertEquals(
            listOf(
                PantryItemEntity("flour", "Flour", 10_000, PantryUnit.GRAM, null, flourVersion),
                PantryItemEntity("milk", "Milk", 20_000, PantryUnit.MILLILITER, null, 1),
            ),
            database.pantryDao().getAll().sortedBy { it.id },
        )
        assertEquals(0, database.mealLogDao().count())
    }
}
