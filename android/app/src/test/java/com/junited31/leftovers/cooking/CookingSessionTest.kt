package com.junited31.leftovers.cooking

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.junited31.leftovers.R
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
        assertEquals(7, CookingStep.from("약 7분 볶기").durationMinutes)
        assertNull(CookingStep.from("간을 확인하기").durationMinutes)
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
            CookingAdviceJson.response(validAdvice().replace("surface_browned", "")),
        )
    }

    @Test
    fun safety_or_doneness_claims_are_invalid_before_ui_rendering() {
        // Given: English and Korean dynamic fields claim the photo proves safety or doneness.
        val unsafeAdvice = listOf(
            """
                {"status":"continue","observations":["The image proves the chicken is fully cooked and safe"],"nextActions":["Serve immediately"],"confidence":1.0,"safetyNote":"The photo confirms it is safe to eat."}
            """.trimIndent(),
            """
                {"status":"continue","observations":["사진을 보면 닭고기는 완전히 익었고 안전합니다"],"nextActions":["바로 드세요"],"confidence":1.0,"safetyNote":"사진으로 안전함을 확인했습니다."}
            """.trimIndent(),
            """
                {"status":"continue","observations":["표면이 노릇해 보여요"],"nextActions":["닭고기는 완전히 익었고 안전하니 드세요"],"confidence":1.0,"safetyNote":"사진으로 안전함을 확인했습니다."}
            """.trimIndent(),
        )

        // When / Then: each response is rejected by the decoder seam.
        unsafeAdvice.forEach { payload ->
            assertEquals(CookingAdviceDecodeResult.Invalid, CookingAdviceJson.response(payload))
        }
    }

    @Test
    fun external_verification_stays_valid_and_server_note_cannot_control_safety_copy() {
        // Given: approved visual and verification codes carry hostile server notes.
        val validAdvice = listOf(
            """
                {"status":"adjust","observations":["surface_browned"],"nextActions":["check_center_temperature"],"confidence":0.72,"safetyNote":"The photo confirms it is safe to eat."}
            """.trimIndent(),
            """
                {"status":"adjust","observations":["visible_moisture"],"nextActions":["turn_and_check_center_temperature"],"confidence":0.72,"safetyNote":"사진으로 안전함을 확인했습니다."}
            """.trimIndent(),
        )

        // When / Then: useful advice survives, but rendered safety guidance is app-owned.
        validAdvice.forEachIndexed { index, payload ->
            val decoded = CookingAdviceJson.response(payload)
            assertTrue(decoded is CookingAdviceDecodeResult.Success)
            val advice = (decoded as CookingAdviceDecodeResult.Success).advice
            assertEquals(R.string.safety_guidance, advice.safetyNote)
            if (index == 0) assertEquals(listOf(R.string.observation_surface_browned), advice.observations)
        }
    }

    @Test
    fun unapproved_model_prose_is_invalid_before_ui_rendering() {
        // Given: plausible visual prose avoids explicit safety and doneness terms.
        val payload = """
            {"status":"adjust","observations":["The surface is lightly browned"],"nextActions":["Check the center temperature"],"confidence":0.72,"safetyNote":"Use time and temperature."}
        """.trimIndent()

        // When / Then: arbitrary model prose cannot become UI copy.
        assertEquals(CookingAdviceDecodeResult.Invalid, CookingAdviceJson.response(payload))
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
        {"status":"adjust","observations":["surface_browned"],"nextActions":["check_center_temperature"],"confidence":0.72,"safetyNote":"사진만으로 익음이나 식품 안전을 확인할 수 없어요. 시간과 온도를 확인하세요."}
    """.trimIndent()
}
