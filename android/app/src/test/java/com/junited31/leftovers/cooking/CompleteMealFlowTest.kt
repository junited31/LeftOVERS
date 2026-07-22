package com.junited31.leftovers.cooking

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.junited31.leftovers.data.ActualPantryUse
import com.junited31.leftovers.data.ActualPantryUses
import com.junited31.leftovers.data.CompleteCookSessionCommand
import com.junited31.leftovers.data.CompletionResult
import com.junited31.leftovers.data.CookSessionEntity
import com.junited31.leftovers.data.InventoryCompletionDao
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
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
@OptIn(ExperimentalCoroutinesApi::class)
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

        val success = result as CompletionResult.Success
        assertEquals(0L, database.pantryDao().get(riceId)?.quantityMilliUnits)
        val log = requireNotNull(database.mealLogDao().get("meal"))
        assertEquals(log.recipeSnapshot.feedback.finalPhotoPath, success.retainedPhotoPath)
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

    @Test
    fun cancellationDuringRoomWorkWaitsForCommittedPhotoState() = runTest {
        givenSession(version = 1, quantity = 10_000)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val photo = photos.createManagedPhoto().also { it.file.writeBytes(byteArrayOf(7, 8, 9)) }
        val store = MealCompletionStore(
            BarrierCompletionDao(database.inventoryCompletionDao(), beforeCommit = entered, release = release),
            photos,
        )

        val completion = async { store.complete(command(), photo) }
        entered.await()
        completion.cancel()
        release.complete(Unit)
        completion.cancelAndJoin()

        val log = database.mealLogDao().get("meal")
        assertEquals(1, database.mealLogDao().count())
        assertTrue(File(requireNotNull(log?.recipeSnapshot?.feedback?.finalPhotoPath)).isFile)
        assertEquals(1, photos.retainedFinalPhotos().size)
    }

    @Test
    fun cancellationImmediatelyAfterRoomCommitPreservesReferencedPhoto() = runTest {
        givenSession(version = 1, quantity = 10_000)
        val committed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val photo = photos.createManagedPhoto().also { it.file.writeBytes(byteArrayOf(10, 11, 12)) }
        val store = MealCompletionStore(
            BarrierCompletionDao(database.inventoryCompletionDao(), afterCommit = committed, release = release),
            photos,
        )

        val completion = async { store.complete(command(), photo) }
        committed.await()
        completion.cancel()
        release.complete(Unit)
        completion.cancelAndJoin()

        val log = requireNotNull(database.mealLogDao().get("meal"))
        assertEquals(1, database.mealLogDao().count())
        assertTrue(File(requireNotNull(log.recipeSnapshot.feedback.finalPhotoPath)).isFile)
        assertEquals(1, photos.retainedFinalPhotos().size)
    }

    @Test
    fun successResultExposesNullableRetainedPath() {
        assertTrue(
            CompletionResult.Success::class.java.declaredFields.any { it.name == "retainedPhotoPath" },
        )
    }

    @Test
    fun cancellationBeforeOwnershipEntryLeavesNoLogOrPhoto() = runTest {
        givenSession(version = 1, quantity = 10_000)
        val photo = photos.createManagedPhoto().also { it.file.writeBytes(byteArrayOf(13, 14, 15)) }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val completion = launch(dispatcher) {
            MealCompletionStore(database.inventoryCompletionDao(), photos).complete(command(), photo)
        }

        completion.cancel()
        runCurrent()

        assertEquals(0, database.mealLogDao().count())
        assertTrue(photos.retainedFinalPhotos().isEmpty())
    }

    @Test
    fun roomFailureDeletesUnreferencedRetainedPhoto() = runTest {
        val photo = photos.createManagedPhoto().also { it.file.writeBytes(byteArrayOf(16, 17, 18)) }

        runCatching {
            MealCompletionStore(FailingCompletionDao(), photos).complete(command(), photo)
        }.onSuccess { throw AssertionError("Room failure must escape") }

        assertEquals(0, database.mealLogDao().count())
        assertTrue(photos.ownedCacheFiles().isEmpty())
        assertTrue(photos.retainedFinalPhotos().isEmpty())
    }

    @Test
    fun failedCompensationLeavesOneManagedOrphanForRetry() = runTest {
        val deniedPhotos = PhotoLifecycle(context) { false }
        val photo = deniedPhotos.createManagedPhoto().also { it.file.writeBytes(byteArrayOf(19, 20, 21)) }

        runCatching {
            MealCompletionStore(FailingCompletionDao(), deniedPhotos).complete(command(), photo)
        }.onSuccess { throw AssertionError("Room failure must escape") }

        assertEquals(0, database.mealLogDao().count())
        assertTrue(deniedPhotos.ownedCacheFiles().isEmpty())
        assertEquals(1, deniedPhotos.retainedFinalPhotos().size)
        assertEquals(1, photos.reconcileRetained(emptyList()))
        assertTrue(photos.retainedFinalPhotos().isEmpty())
    }

    @Test
    fun successWithNoPhotoKeepsNullableFallback() = runTest {
        givenSession(version = 1, quantity = 10_000)

        val result = MealCompletionStore(database.inventoryCompletionDao(), photos).complete(command(), null)

        val success = result as CompletionResult.Success
        assertEquals(null, success.retainedPhotoPath)
        assertEquals(null, database.mealLogDao().get("meal")?.recipeSnapshot?.feedback?.finalPhotoPath)
        assertTrue(photos.retainedFinalPhotos().isEmpty())
    }

    @Test
    fun successDoesNotReturnAfterReferencedFileDisappears() = runTest { supervisorScope {
        givenSession(version = 1, quantity = 10_000)
        val committed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val photo = photos.createManagedPhoto().also { it.file.writeBytes(byteArrayOf(22, 23, 24)) }
        val store = MealCompletionStore(
            BarrierCompletionDao(database.inventoryCompletionDao(), afterCommit = committed, release = release),
            photos,
        )

        val completion = async { store.complete(command(), photo) }
        committed.await()
        val referenced = File(requireNotNull(database.mealLogDao().get("meal")
            ?.recipeSnapshot?.feedback?.finalPhotoPath))
        assertTrue(referenced.delete())
        release.complete(Unit)

        assertTrue(runCatching { completion.await() }.isFailure)
        assertEquals(1, database.mealLogDao().count())
        assertFalse(referenced.exists())
    } }

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

private class BarrierCompletionDao(
    private val delegate: InventoryCompletionDao,
    private val beforeCommit: CompletableDeferred<Unit>? = null,
    private val afterCommit: CompletableDeferred<Unit>? = null,
    private val release: CompletableDeferred<Unit>,
) : InventoryCompletionDao() {
    override suspend fun complete(command: CompleteCookSessionCommand): CompletionResult {
        beforeCommit?.complete(Unit)
        beforeCommit?.let { release.await() }
        val result = delegate.complete(command)
        afterCommit?.complete(Unit)
        afterCommit?.let { release.await() }
        return result
    }

    override suspend fun session(id: String): CookSessionEntity? = null
    override suspend fun recipe(id: String): RecipeSnapshotEntity? = null
    override suspend fun pantryRows(ids: List<PantryItemId>): List<PantryItemEntity> = emptyList()
    override suspend fun updatePantry(rows: List<PantryItemEntity>) = Unit
    override suspend fun updateSession(session: CookSessionEntity) = Unit
    override suspend fun insertMealLog(mealLog: com.junited31.leftovers.data.MealLogEntity) = Unit
}

private class FailingCompletionDao : InventoryCompletionDao() {
    override suspend fun complete(command: CompleteCookSessionCommand): CompletionResult =
        throw IllegalStateException("synthetic Room failure")

    override suspend fun session(id: String): CookSessionEntity? = null
    override suspend fun recipe(id: String): RecipeSnapshotEntity? = null
    override suspend fun pantryRows(ids: List<PantryItemId>): List<PantryItemEntity> = emptyList()
    override suspend fun updatePantry(rows: List<PantryItemEntity>) = Unit
    override suspend fun updateSession(session: CookSessionEntity) = Unit
    override suspend fun insertMealLog(mealLog: com.junited31.leftovers.data.MealLogEntity) = Unit
}
