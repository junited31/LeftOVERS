package com.junited31.leftovers.cooking

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.junited31.leftovers.data.ActualPantryUse
import com.junited31.leftovers.data.ActualPantryUses
import com.junited31.leftovers.data.CompleteCookSessionCommand
import com.junited31.leftovers.data.CompletionResult
import com.junited31.leftovers.data.CookSessionEntity
import com.junited31.leftovers.data.LeftoversDatabase
import com.junited31.leftovers.data.MealFeedback
import com.junited31.leftovers.data.MeasurementAdjustment
import com.junited31.leftovers.data.PantryBinding
import com.junited31.leftovers.data.PantryBindings
import com.junited31.leftovers.data.PantryItemEntity
import com.junited31.leftovers.data.PantryItemId
import com.junited31.leftovers.data.PantryUnit
import com.junited31.leftovers.data.RecipePreferenceMetadata
import com.junited31.leftovers.data.RecipeSnapshotEntity
import com.junited31.leftovers.data.RecipeSteps
import com.junited31.leftovers.photo.PhotoLifecycle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class CompleteMealFlowTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: LeftoversDatabase
    private lateinit var photos: PhotoLifecycle
    private val riceId = requireNotNull(PantryItemId.parse("00000000-0000-4000-8000-000000000081"))

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, LeftoversDatabase::class.java)
            .allowMainThreadQueries().build()
        photos = PhotoLifecycle(context)
        photos.retainedFinalPhotos().forEach(File::delete)
    }

    @After
    fun tearDown() {
        database.close()
        photos.ownedCacheFiles().forEach(File::delete)
        photos.retainedFinalPhotos().forEach(File::delete)
    }

    @Test
    fun editedActualUseCanLeaveZeroAndPersistsFeedbackAndFinalPhotoOnlyOnSuccess() = runTest {
        givenSession(version = 1, quantity = 10_000)
        val photo = photos.createManagedPhoto().also { it.file.writeBytes(byteArrayOf(1, 2, 3)) }

        val result = MealCompletionStore(database.inventoryCompletionDao(), photos).complete(command(), photo)

        assertEquals(CompletionResult.Success("meal"), result)
        assertEquals(0L, database.pantryDao().get(riceId)?.quantityMilliUnits)
        val log = requireNotNull(database.mealLogDao().get("meal"))
        assertNotNull(log)
        assertEquals(5, log.recipeSnapshot.feedback.rating)
        assertFalse(log.recipeSnapshot.feedback.recommendAgain)
        assertEquals("덜 짜게", log.recipeSnapshot.feedback.notes)
        assertEquals("rice", log.recipeSnapshot.feedback.measurementAdjustments.single().ingredientName)
        assertTrue(File(requireNotNull(log.recipeSnapshot.feedback.finalPhotoPath)).isFile)
        assertTrue(photos.ownedCacheFiles().isEmpty())
    }

    @Test
    fun staleUnitAndOverUseFailuresRollBackInventoryLogAndRetainedPhoto() = runTest {
        listOf(
            Triple(2, PantryUnit.GRAM, 10_000L),
            Triple(1, PantryUnit.MILLILITER, 10_000L),
            Triple(1, PantryUnit.GRAM, 10_001L),
        ).forEachIndexed { index, (version, unit, amount) ->
            database.clearAllTables()
            givenSession(version = version, quantity = 10_000)
            val photo = photos.createManagedPhoto().also { it.file.writeBytes(byteArrayOf(4, 5, 6)) }
            val result = MealCompletionStore(database.inventoryCompletionDao(), photos).complete(
                command(unit = unit, amount = amount, mealId = "failure-$index"),
                photo,
            )

            assertTrue(result !is CompletionResult.Success)
            assertEquals(10_000L, database.pantryDao().get(riceId)?.quantityMilliUnits)
            assertEquals(0, database.mealLogDao().count())
            assertTrue(photos.ownedCacheFiles().isEmpty())
            assertTrue(photos.retainedFinalPhotos().isEmpty())
        }
    }

    @Test
    fun malformedRatingNotesAndMeasurementAdjustmentAreRejectedBeforeMutation() = runTest {
        val invalidFeedback = listOf(
            MealFeedback(rating = 0),
            MealFeedback(rating = 6),
            MealFeedback(notes = "x".repeat(1_001)),
            MealFeedback(
                measurementAdjustments = listOf(
                    MeasurementAdjustment("rice", 0, PantryUnit.GRAM, "", 3_000),
                ),
            ),
            MealFeedback(
                measurementAdjustments = listOf(
                    MeasurementAdjustment("rice", 1_000, PantryUnit.GRAM, "x".repeat(201), 3_000),
                ),
            ),
        )
        invalidFeedback.forEachIndexed { index, feedback ->
            database.clearAllTables()
            givenSession(version = 1, quantity = 10_000)

            val result = database.inventoryCompletionDao().complete(command(mealId = "bad-$index").copy(feedback = feedback))

            assertEquals(CompletionResult.InvalidFeedback, result)
            assertEquals(10_000L, database.pantryDao().get(riceId)?.quantityMilliUnits)
            assertEquals(0, database.mealLogDao().count())
        }
    }

    private suspend fun givenSession(version: Int, quantity: Long) {
        database.pantryDao().insertAll(
            listOf(PantryItemEntity(riceId, "Rice", quantity, PantryUnit.GRAM, null, version)),
        )
        database.recipeSnapshotDao().insert(
            RecipeSnapshotEntity(
                "recipe",
                "Rice bowl",
                PantryBindings(listOf(PantryBinding(riceId, 1, PantryUnit.GRAM, 5_000))),
                RecipeSteps(
                    listOf("Cook"),
                    RecipePreferenceMetadata("Korean", "mix", setOf("Rice")),
                    com.junited31.leftovers.data.RecipeKind.MEAL,
                ),
                1,
            ),
        )
        database.cookSessionDao().insert(CookSessionEntity("session", "recipe", 2, 0, null))
    }

    private fun command(
        unit: PantryUnit = PantryUnit.GRAM,
        amount: Long = 10_000,
        mealId: String = "meal",
    ) = CompleteCookSessionCommand(
        "session",
        mealId,
        3_000,
        ActualPantryUses(listOf(ActualPantryUse(riceId, 1, unit, amount))),
        MealFeedback(
            rating = 5,
            notes = "덜 짜게",
            recommendAgain = false,
            measurementAdjustments = listOf(
                MeasurementAdjustment("rice", 4_000, PantryUnit.GRAM, "half cup", 3_000),
            ),
            finalPhotoPath = null,
        ),
    )
}
