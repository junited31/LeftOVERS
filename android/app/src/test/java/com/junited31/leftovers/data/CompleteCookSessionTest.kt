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
    private val flourId = pantryId("00000000-0000-0000-0000-000000000001")
    private val milkId = pantryId("00000000-0000-0000-0000-000000000002")
    private val missingId = pantryId("00000000-0000-0000-0000-000000000099")

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
                ActualPantryUse(flourId, 1, PantryUnit.GRAM, 10_001),
                ActualPantryUse(milkId, 1, PantryUnit.MILLILITER, 1_000),
            ),
        )

        // Then
        assertEquals(CompletionResult.InvalidActualUse(flourId), result)
        assertUnchangedInventoryAndNoLog()
    }

    @Test
    fun rollsBackEveryRowWhenUnitDiffersFromBinding() = runTest {
        // Given
        givenSession()

        // When
        val result = database.inventoryCompletionDao().complete(
            command(
                ActualPantryUse(flourId, 1, PantryUnit.COUNT, 1_000),
                ActualPantryUse(milkId, 1, PantryUnit.MILLILITER, 1_000),
            ),
        )

        // Then
        assertEquals(CompletionResult.UnitMismatch(flourId), result)
        assertUnchangedInventoryAndNoLog()
    }

    @Test
    fun rollsBackEveryRowWhenInventoryVersionIsStale() = runTest {
        // Given
        givenSession(flourVersion = 2)

        // When
        val result = database.inventoryCompletionDao().complete(
            command(
                ActualPantryUse(flourId, 1, PantryUnit.GRAM, 1_000),
                ActualPantryUse(milkId, 1, PantryUnit.MILLILITER, 1_000),
            ),
        )

        // Then
        assertEquals(CompletionResult.StaleInventory(flourId), result)
        assertUnchangedInventoryAndNoLog(flourVersion = 2)
    }

    @Test
    fun rejectsDuplicateIdsBeforeMutatingAnyRow() = runTest {
        // Given
        givenSession()

        // When
        val result = database.inventoryCompletionDao().complete(
            command(
                ActualPantryUse(flourId, 1, PantryUnit.GRAM, 1_000),
                ActualPantryUse(flourId, 1, PantryUnit.GRAM, 2_000),
            ),
        )

        // Then
        assertEquals(CompletionResult.DuplicateInventoryId(flourId), result)
        assertUnchangedInventoryAndNoLog()
    }

    @Test
    fun rejectsUnknownIdsBeforeMutatingAnyRow() = runTest {
        // Given
        givenSession()

        // When
        val result = database.inventoryCompletionDao().complete(
            command(
                ActualPantryUse(flourId, 1, PantryUnit.GRAM, 1_000),
                ActualPantryUse(missingId, 1, PantryUnit.MILLILITER, 1_000),
            ),
        )

        // Then
        assertEquals(CompletionResult.UnknownInventory(missingId), result)
        assertUnchangedInventoryAndNoLog()
    }

    @Test
    fun rejectsMalformedPantryIdentityBeforeAnyMutation() = runTest {
        // Given
        givenSession()
        val original = database.pantryDao().getAll()

        // When
        val malformed = listOf("not-a-uuid", "1-1-1-1-1", "").map(PantryItemId::parse)

        // Then
        assertEquals(
            Triple(listOf(null, null, null), original, 0),
            Triple(
                malformed,
                database.pantryDao().getAll(),
                database.mealLogDao().count(),
            ),
        )
    }

    @Test
    fun successfulCompletionRetainsFullyConsumedRowsAndWritesOneImmutableLog() = runTest {
        // Characterization: T8 builds on the existing transaction semantics.
        givenSession()

        val result = database.inventoryCompletionDao().complete(
            command(
                ActualPantryUse(flourId, 1, PantryUnit.GRAM, 10_000),
                ActualPantryUse(milkId, 1, PantryUnit.MILLILITER, 0),
            ),
        )

        assertEquals(CompletionResult.Success("meal"), result)
        assertEquals(0L, database.pantryDao().get(flourId)?.quantityMilliUnits)
        assertEquals(20_000L, database.pantryDao().get(milkId)?.quantityMilliUnits)
        assertEquals(1, database.mealLogDao().count())
        assertEquals(0L, database.mealLogDao().get("meal")?.remainingPantry?.values?.first()?.quantityMilliUnits)
    }

    private suspend fun givenSession(flourVersion: Int = 1) {
        database.pantryDao().insertAll(
            listOf(
                PantryItemEntity(flourId, "Flour", 10_000, PantryUnit.GRAM, null, flourVersion),
                PantryItemEntity(milkId, "Milk", 20_000, PantryUnit.MILLILITER, null, 1),
            ),
        )
        database.recipeSnapshotDao().insert(
            RecipeSnapshotEntity(
                id = "recipe",
                title = "Pancakes",
                pantryBindings = PantryBindings(
                    listOf(
                        PantryBinding(flourId, 1, PantryUnit.GRAM, 5_000),
                        PantryBinding(milkId, 1, PantryUnit.MILLILITER, 2_000),
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
                PantryItemEntity(flourId, "Flour", 10_000, PantryUnit.GRAM, null, flourVersion),
                PantryItemEntity(milkId, "Milk", 20_000, PantryUnit.MILLILITER, null, 1),
            ),
            database.pantryDao().getAll().sortedBy { it.id.value },
        )
        assertEquals(0, database.mealLogDao().count())
    }

    private fun pantryId(value: String) = requireNotNull(PantryItemId.parse(value))
}
