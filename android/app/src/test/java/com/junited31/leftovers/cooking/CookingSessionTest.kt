package com.junited31.leftovers.cooking

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.junited31.leftovers.data.LeftoversDatabase
import com.junited31.leftovers.data.PantryBindings
import com.junited31.leftovers.data.RecipeSnapshotEntity
import com.junited31.leftovers.data.RecipeSteps
import com.junited31.leftovers.network.ApiResult
import com.junited31.leftovers.network.LeftoversApi
import com.junited31.leftovers.network.TokenProvider
import com.junited31.leftovers.photo.PhotoLifecycle
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class CookingSessionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "cooking-session-test.db"
    private var database: LeftoversDatabase? = null

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(databaseName)
        PhotoLifecycle(context).ownedCacheFiles().forEach { it.delete() }
    }

    @Test
    fun step_change_invalidates_in_progress_photo_preparation() {
        // Given: a photo preparation belongs to the current cooking step.
        val epoch = PhotoPreparationEpoch()
        val previousStepPhoto = epoch.begin()
        assertTrue(epoch.owns(previousStepPhoto))

        // When: the user moves to another step before preparation completes.
        epoch.invalidate()

        // Then: the stale result is rejected and a new-step preparation can be accepted.
        assertFalse(epoch.owns(previousStepPhoto))
        assertTrue(epoch.owns(epoch.begin()))
    }

    @Test
    fun session_resumes_at_persisted_step_after_database_recreation() = runTest {
        // Given: a saved immutable recipe snapshot starts an active session.
        var db = openDatabase()
        insertRecipe(db)
        val store = CookingSessionStore(db.recipeSnapshotDao(), db.cookSessionDao())
        assertEquals("session-7", store.start("recipe-7", "session-7", 100)?.session?.id)
        assertEquals(1, store.next("session-7")?.session?.currentStepIndex)

        // When: Room is closed and recreated as it would be after process death.
        db.close()
        database = null
        db = openDatabase()
        val resumed = CookingSessionStore(db.recipeSnapshotDao(), db.cookSessionDao()).resume()

        // Then: offline steps and the exact current index survive.
        assertEquals("세 번째 단계", resumed?.recipe?.steps?.values?.last())
        assertEquals(1, resumed?.session?.currentStepIndex)
        assertEquals(0, CookingSessionStore(db.recipeSnapshotDao(), db.cookSessionDao()).previous("session-7")?.session?.currentStepIndex)
    }

    @Test
    fun duration_is_display_only_and_advice_never_advances_step() = runTest {
        // Given: the active second step contains a human-readable duration.
        val db = openDatabase()
        insertRecipe(db)
        val store = CookingSessionStore(db.recipeSnapshotDao(), db.cookSessionDao())
        store.start("recipe-7", "session-7", 100)
        store.next("session-7")
        val before = requireNotNull(store.resume())

        // When: valid photo advice is decoded.
        val decoded = CookingAdviceJson.response(validAdvice())

        // Then: duration is parsed only for display and Room remains at step two.
        assertEquals("약 7분", CookingStep.from("약 7분 볶기").durationLabel)
        assertNull(CookingStep.from("간을 확인하기").durationLabel)
        assertTrue(decoded is CookingAdviceDecodeResult.Success)
        assertEquals(before.session.currentStepIndex, store.resume()?.session?.currentStepIndex)
    }

    @Test
    fun missing_or_blank_safety_and_malformed_advice_are_typed_invalid() {
        // Given / When / Then: no unsafe partial advice can reach the UI.
        assertEquals(CookingAdviceDecodeResult.Invalid, CookingAdviceJson.response("{}"))
        assertEquals(
            CookingAdviceDecodeResult.Invalid,
            CookingAdviceJson.response(
                validAdvice().replace(
                    "사진만으로 익음이나 식품 안전을 확인할 수 없어요. 시간과 온도를 확인하세요.",
                    "   ",
                ),
            ),
        )
        assertEquals(
            CookingAdviceDecodeResult.Invalid,
            CookingAdviceJson.response(validAdvice().replace("표면이 노릇해요", "")),
        )
    }

    @Test
    fun offline_failure_uploads_once_preserves_session_and_requires_explicit_retry() = runTest {
        // Given: a persisted step-two session and a server that drops the request.
        val db = openDatabase()
        insertRecipe(db)
        val store = CookingSessionStore(db.recipeSnapshotDao(), db.cookSessionDao())
        store.start("recipe-7", "session-7", 100)
        store.next("session-7")
        val before = requireNotNull(store.resume()).session
        val lifecycle = PhotoLifecycle(context)
        val photo = lifecycle.createManagedPhoto().also { it.file.writeBytes(ByteArray(128)) }
        val server = MockWebServer().apply {
            enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            start()
        }

        // When: one production client call fails offline.
        val result = LeftoversApi(server.url("/").toString(), TokenProvider { "token" })
            .newPhotoAdviceCall(CookingAdviceJson.request("두 번째 단계"), photo)
            .execute()

        // Then: no automatic retry/re-upload or Room mutation occurs.
        assertEquals(ApiResult.NetworkFailure, result)
        assertEquals(1, server.requestCount)
        assertEquals(before, store.resume()?.session)
        assertFalse(photo.file.exists())
        assertTrue(lifecycle.ownedCacheFiles().isEmpty())
        server.shutdown()
    }

    @Test
    fun cancelling_delayed_advice_cleans_cache_without_retry_or_step_advance() = runTest {
        // Given: a delayed production upload at step two.
        val db = openDatabase()
        insertRecipe(db)
        val store = CookingSessionStore(db.recipeSnapshotDao(), db.cookSessionDao())
        store.start("recipe-7", "session-7", 100)
        store.next("session-7")
        val lifecycle = PhotoLifecycle(context)
        val photo = lifecycle.createManagedPhoto().also { it.file.writeBytes(ByteArray(128)) }
        val server = MockWebServer().apply {
            enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            start()
        }
        val call = LeftoversApi(server.url("/").toString(), TokenProvider { "token" })
            .newPhotoAdviceCall(CookingAdviceJson.request("두 번째 단계"), photo)
        val finished = CountDownLatch(1)
        var result: ApiResult? = null
        Thread {
            result = call.execute()
            finished.countDown()
        }.start()
        assertTrue(server.takeRequest(2, TimeUnit.SECONDS) != null)

        // When: the in-flight request is explicitly cancelled.
        call.cancel()

        // Then: the upload is removed, one request was made, and the step stays two.
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertEquals(ApiResult.Cancelled, result)
        assertEquals(1, server.requestCount)
        assertEquals(1, store.resume()?.session?.currentStepIndex)
        assertTrue(lifecycle.ownedCacheFiles().isEmpty())
        server.shutdown()
    }

    private fun openDatabase() = Room.databaseBuilder(context, LeftoversDatabase::class.java, databaseName)
        .allowMainThreadQueries()
        .build()
        .also { database = it }

    private suspend fun insertRecipe(db: LeftoversDatabase) {
        db.recipeSnapshotDao().insert(
            RecipeSnapshotEntity(
                id = "recipe-7",
                title = "볶음밥",
                pantryBindings = PantryBindings(emptyList()),
                steps = RecipeSteps(listOf("첫 번째 단계", "약 7분 볶기", "세 번째 단계")),
                createdAtEpochMillis = 10,
            ),
        )
    }

    private fun validAdvice() = """
        {"status":"adjust","observations":["표면이 노릇해요"],"nextActions":["중심 온도를 확인하세요"],"confidence":0.72,"safetyNote":"사진만으로 익음이나 식품 안전을 확인할 수 없어요. 시간과 온도를 확인하세요."}
    """.trimIndent()
}
