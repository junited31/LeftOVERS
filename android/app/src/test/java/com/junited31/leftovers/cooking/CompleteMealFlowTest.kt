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
        val deniedPhotos = PhotoLifecycle(context, deleteRetainedFile = { false })
        val photo = deniedPhotos.createManagedPhoto().also { it.file.writeBytes(byteArrayOf(19, 20, 21)) }

        runCatching {
            MealCompletionStore(FailingCompletionDao(), deniedPhotos).complete(command(), photo)
        }.onSuccess { throw AssertionError("Room failure must escape") }

        assertEquals(0, database.mealLogDao().count())
        assertTrue(deniedPhotos.ownedCacheFiles().isEmpty())
        assertEquals(1, deniedPhotos.retainedFinalPhotos().size)
        assertEquals(1, photos.reconcileRetained { emptyList() })
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
    fun deletedCacheSourceCreatesNoLogOrRetainedPhoto() = runTest {
        givenSession(version = 1, quantity = 10_000)
        val photo = photos.createManagedPhoto().also { it.file.writeBytes(byteArrayOf(40, 41, 42)) }
        assertTrue(photo.file.delete())

        val failure = runCatching {
            MealCompletionStore(database.inventoryCompletionDao(), photos).complete(command(), photo)
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(0, database.mealLogDao().count())
        assertTrue(photos.ownedCacheFiles().isEmpty())
        assertTrue(photos.retainedFinalPhotos().isEmpty())
    }

    @Test
    fun duplicateMealLogIdKeepsTheSingleCommittedLogAndPhoto() = runTest {
        givenSession(version = 1, quantity = 10_000)
        val firstPhoto = photos.createManagedPhoto().also { it.file.writeBytes(byteArrayOf(43, 44, 45)) }
        val first = MealCompletionStore(database.inventoryCompletionDao(), photos).complete(command(), firstPhoto)
        val retained = File(requireNotNull((first as CompletionResult.Success).retainedPhotoPath))
        val secondPhoto = photos.createManagedPhoto().also { it.file.writeBytes(byteArrayOf(46, 47, 48)) }

        val failure = runCatching {
            MealCompletionStore(database.inventoryCompletionDao(), photos).complete(command(), secondPhoto)
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(1, database.mealLogDao().count())
        assertTrue(retained.isFile)
        assertEquals(listOf(retained), photos.retainedFinalPhotos())
        assertTrue(photos.ownedCacheFiles().isEmpty())
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

    @Test
    fun startupReconciliationCannotDeleteRetainedPhotoBeforeRoomCommit() = runTest { supervisorScope {
        givenSession(version = 1, quantity = 10_000)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val photo = photos.createManagedPhoto().also { it.file.writeBytes(byteArrayOf(25, 26, 27)) }
        val store = MealCompletionStore(
            BarrierCompletionDao(database.inventoryCompletionDao(), beforeCommit = entered, release = release),
            photos,
        )

        val completion = async { store.complete(command(), photo) }
        entered.await()
        assertEquals(1, photos.retainedFinalPhotos().size)
        val referenceLoadStarted = CompletableDeferred<Unit>()
        val reconciliation = async {
            PhotoLifecycle(context).reconcileRetained {
                referenceLoadStarted.complete(Unit)
                database.mealLogDao().latest().mapNotNull {
                    it.recipeSnapshot.feedback.finalPhotoPath
                }
            }
        }
        runCurrent()
        val referenceLoadedBeforeCommit = referenceLoadStarted.isCompleted
        release.complete(Unit)
        val result = completion.await() as CompletionResult.Success
        reconciliation.await()

        val log = requireNotNull(database.mealLogDao().get("meal"))
        val retained = File(requireNotNull(log.recipeSnapshot.feedback.finalPhotoPath))
        assertFalse(referenceLoadedBeforeCommit)
        assertEquals(retained.absolutePath, result.retainedPhotoPath)
        assertTrue(retained.isFile)
    } }

    @Test
    fun throwingCompensationNeverReplacesTypedFailure() = runTest {
        val throwingPhotos = PhotoLifecycle(
            context,
            deleteRetainedFile = { throw IllegalStateException("delete failed") },
        )
        val photo = throwingPhotos.createManagedPhoto().also { it.file.writeBytes(byteArrayOf(28, 29, 30)) }

        val result = MealCompletionStore(
            ResultCompletionDao(CompletionResult.InvalidFeedback),
            throwingPhotos,
        ).complete(command(), photo)

        assertEquals(CompletionResult.InvalidFeedback, result)
        assertEquals(1, throwingPhotos.retainedFinalPhotos().size)
    }

    @Test
    fun throwingCompensationNeverReplacesRoomException() = runTest {
        val throwingPhotos = PhotoLifecycle(
            context,
            deleteRetainedFile = { throw IllegalStateException("delete failed") },
        )
        val photo = throwingPhotos.createManagedPhoto().also { it.file.writeBytes(byteArrayOf(31, 32, 33)) }

        val failure = runCatching {
            MealCompletionStore(FailingCompletionDao(), throwingPhotos).complete(command(), photo)
        }.exceptionOrNull()

        assertEquals("synthetic Room failure", failure?.message)
        assertEquals(1, throwingPhotos.retainedFinalPhotos().size)
    }

    @Test
    fun throwingCanonicalizationNeverReplacesTypedFailureOrRoomException() = runTest {
        var canonicalizationFails = false
        val canonicalFailure = { file: File ->
            if (canonicalizationFails) throw java.io.IOException("canonical failed")
            file.canonicalFile
        }
        val typedPhotos = PhotoLifecycle(context, File::delete, canonicalFailure)
        val typedPhoto = typedPhotos.createManagedPhoto().also { it.file.writeBytes(byteArrayOf(34, 35, 36)) }
        val typed = MealCompletionStore(
            ResultCompletionDao(CompletionResult.InvalidFeedback) { canonicalizationFails = true },
            typedPhotos,
        ).complete(command(), typedPhoto)
        assertEquals(CompletionResult.InvalidFeedback, typed)

        canonicalizationFails = false
        val roomPhotos = PhotoLifecycle(context, File::delete, canonicalFailure)
        val roomPhoto = roomPhotos.createManagedPhoto().also { it.file.writeBytes(byteArrayOf(37, 38, 39)) }
        val failure = runCatching {
            MealCompletionStore(
                FailingCompletionDao { canonicalizationFails = true },
                roomPhotos,
            ).complete(command(mealId = "canonical-room"), roomPhoto)
        }.exceptionOrNull()
        assertEquals("synthetic Room failure", failure?.message)
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

private class FailingCompletionDao(
    private val beforeThrow: () -> Unit = {},
) : InventoryCompletionDao() {
    override suspend fun complete(command: CompleteCookSessionCommand): CompletionResult {
        beforeThrow()
        throw IllegalStateException("synthetic Room failure")
    }

    override suspend fun session(id: String): CookSessionEntity? = null
    override suspend fun recipe(id: String): RecipeSnapshotEntity? = null
    override suspend fun pantryRows(ids: List<PantryItemId>): List<PantryItemEntity> = emptyList()
    override suspend fun updatePantry(rows: List<PantryItemEntity>) = Unit
    override suspend fun updateSession(session: CookSessionEntity) = Unit
    override suspend fun insertMealLog(mealLog: com.junited31.leftovers.data.MealLogEntity) = Unit
}

private class ResultCompletionDao(
    private val result: CompletionResult,
    private val beforeReturn: () -> Unit = {},
) : InventoryCompletionDao() {
    override suspend fun complete(command: CompleteCookSessionCommand): CompletionResult {
        beforeReturn()
        return result
    }
    override suspend fun session(id: String): CookSessionEntity? = null
    override suspend fun recipe(id: String): RecipeSnapshotEntity? = null
    override suspend fun pantryRows(ids: List<PantryItemId>): List<PantryItemEntity> = emptyList()
    override suspend fun updatePantry(rows: List<PantryItemEntity>) = Unit
    override suspend fun updateSession(session: CookSessionEntity) = Unit
    override suspend fun insertMealLog(mealLog: com.junited31.leftovers.data.MealLogEntity) = Unit
}
