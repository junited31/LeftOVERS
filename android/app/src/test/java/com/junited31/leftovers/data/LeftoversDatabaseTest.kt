package com.junited31.leftovers.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LeftoversDatabaseTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "leftovers-persistence-test.db"
    private val riceId = pantryId("00000000-0000-0000-0000-000000000003")

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun persistsExactInventoryAndImmutableMealSnapshotAfterReopen() = runTest {
        // Given
        context.deleteDatabase(databaseName)
        var database = openDatabase()
        database.pantryDao().insertAll(
            listOf(PantryItemEntity(riceId, "Rice", 3_500, PantryUnit.GRAM, 21_000, 4)),
        )
        database.recipeSnapshotDao().insert(
            RecipeSnapshotEntity(
                "recipe",
                "Rice bowl",
                PantryBindings(listOf(PantryBinding(riceId, 4, PantryUnit.GRAM, 1_500))),
                RecipeSteps(listOf("Cook rice")),
                100,
            ),
        )
        database.cookSessionDao().insert(CookSessionEntity("session", "recipe", 200, 0, null))

        // When
        val result = database.inventoryCompletionDao().complete(
            CompleteCookSessionCommand(
                "session",
                "meal",
                300,
                ActualPantryUses(listOf(ActualPantryUse(riceId, 4, PantryUnit.GRAM, 1_250))),
            ),
        )
        database.close()
        database = openDatabase()

        // Then
        assertEquals(CompletionResult.Success("meal"), result)
        assertEquals(2_250L, database.pantryDao().get(riceId)?.quantityMilliUnits)
        val meal = database.mealLogDao().get("meal")
        assertEquals("Rice bowl", meal?.recipeSnapshot?.title)
        assertEquals(1_250L, meal?.actualUses?.values?.single()?.actualMilliUnits)
        assertEquals(2_250L, meal?.remainingPantry?.values?.single()?.quantityMilliUnits)
        assertEquals(1, database.mealLogDao().count())
        database.close()
    }

    @Test
    fun parsesOnlyCanonicalUnitsAndAtMostThreeDecimalPlaces() {
        // Given / When / Then
        assertEquals(PantryUnit.GRAM, PantryUnit.parse("g"))
        assertEquals(1_234L, QuantityParser.parseMilliUnits("1.234"))
        assertEquals(-1L, QuantityParser.parseMilliUnits("-0.001"))
        listOf("1.2345", "1.", ".5", "1e3", "9223372036854775.808").forEach {
            assertTrue(QuantityParser.parseMilliUnits(it) == null)
        }
        listOf("G", "grams", " ml", "").forEach {
            assertTrue(PantryUnit.parse(it) == null)
        }
        assertEquals(
            "00000000-0000-0000-0000-0000000000aa",
            PantryItemId.parse("00000000-0000-0000-0000-0000000000AA")?.value,
        )
    }

    private fun openDatabase() = Room.databaseBuilder(
        context,
        LeftoversDatabase::class.java,
        databaseName,
    ).allowMainThreadQueries().build()

    private fun pantryId(value: String) = requireNotNull(PantryItemId.parse(value))
}
