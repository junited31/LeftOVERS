package com.junited31.leftovers

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.junited31.leftovers.data.LeftoversDatabase
import com.junited31.leftovers.data.LeftoversPreferenceKeys
import com.junited31.leftovers.data.PantryBindings
import com.junited31.leftovers.data.PantryItemEntity
import com.junited31.leftovers.data.PantryItemId
import com.junited31.leftovers.data.PantryUnit
import com.junited31.leftovers.data.RecipeSnapshotEntity
import com.junited31.leftovers.data.RecipeSteps
import com.junited31.leftovers.data.leftoversDataStore
import com.junited31.leftovers.cooking.CookingSessionStore
import com.junited31.leftovers.network.LeftoversApi
import com.junited31.leftovers.network.TokenProvider
import com.junited31.leftovers.photo.PhotoLifecycle
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class LeftoversFlowE2eTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val application = context as DebugApplication
    private val database = LeftoversDatabase.get(context)
    private val photos = PhotoLifecycle(context)
    private lateinit var server: MockWebServer
    private var serverRunning = false
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() = runBlocking {
        database.clearAllTables()
        context.leftoversDataStore.edit { it.clear() }
        photos.ownedCacheFiles().forEach(File::delete)
        photos.retainedFinalPhotos().forEach(File::delete)
        database.pantryDao().insertAll(pantry())
        context.leftoversDataStore.edit {
            it[LeftoversPreferenceKeys.EQUIPMENT_IDS] = setOf("gas_burner", "basic_cookware")
            it[LeftoversPreferenceKeys.ONBOARDING_COMPLETE] = true
        }
        server = MockWebServer().apply { start() }
        serverRunning = true
        application.recipeApiOverride = LeftoversApi(
            server.url("/").toString(),
            TokenProvider { "e2e-device-token" },
        )
        shell("wm size 1080x1600")
        Unit
    }

    @After
    fun tearDown() {
        scenario?.close()
        application.recipeApiOverride = null
        if (serverRunning) server.shutdown()
        shell("wm size reset")
        runBlocking { database.clearAllTables() }
        photos.ownedCacheFiles().forEach(File::delete)
        photos.retainedFinalPhotos().forEach(File::delete)
    }

    @Test
    fun pantryRecipeCookFeedbackAndOfflineHistoryWorkOnShortViewport() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(validRecipes()))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        assertTrue(shell("wm size").contains("Override size: 1080x1600"))
        compose.onNodeWithText("Rice").performScrollTo().assertIsDisplayed()

        compose.onNodeWithTag("nav-recipes").performClick()
        compose.onNodeWithText("냉장고 재료로 만드는 세 가지 요리").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit),
        )
        compose.onNodeWithTag("generate-recipes").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("recipe-card").fetchSemanticsNodes().size == 3 }
        compose.onNodeWithTag("save-recipe-0").performScrollTo().performClick()

        compose.onNodeWithTag("next-step").performScrollTo().performClick()
        compose.onNodeWithTag("start-completion").performScrollTo().performClick()
        compose.onNodeWithTag("rating-5").performScrollTo().performClick()
        compose.onNodeWithTag("complete-meal").performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { database.mealLogDao().count() } == 1 }
        compose.onNodeWithTag("completion-success").assertIsDisplayed()
        val saved = runBlocking { database.mealLogDao().latest().single() }
        assertEquals(5, saved.recipeSnapshot.feedback.rating)
        val title = saved.recipeSnapshot.title

        server.shutdown()
        serverRunning = false
        scenario?.recreate()
        compose.onNodeWithTag("nav-history").performClick()
        compose.onNodeWithTag("history-screen").assertIsDisplayed()
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        capture("task-10-e2e.png")
    }

    @Test
    fun typedBackendErrorsNeverRetryOrMutateLocalState() {
        val recipeErrors = listOf(
            401 to "인증 정보를 확인할 수 없어요.",
            413 to "레시피 요청이 너무 커요.",
            422 to "서로 다른 세 가지 유효한 레시피를 만들지 못했어요.",
            429 to "오늘의 레시피 생성 횟수를 모두 사용했어요.",
            502 to "레시피 서비스에 연결할 수 없어요.",
        )
        val databaseBefore = databaseHash()
        val photosBefore = photoHash()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithTag("nav-recipes").performClick()
        val recipeEvidence = JSONArray()

        recipeErrors.forEachIndexed { index, (status, copy) ->
            server.enqueue(MockResponse().setResponseCode(status).setHeader("Retry-After", "37"))
            compose.onNodeWithTag("generate-recipes").performClick()
            compose.waitUntil(10_000) { server.requestCount == index + 1 }
            assertAssertiveError("recipe-error", copy)
            compose.onNodeWithTag("generate-recipes").assertIsEnabled()
            Thread.sleep(250)
            assertEquals(index + 1, server.requestCount)
            recipeEvidence.put(
                JSONObject()
                    .put("status", status)
                    .put("copy", copy)
                    .put("requestDelta", 1)
                    .put("automaticRetry", false),
            )
        }

        val databaseAfter = databaseHash()
        val photosAfter = photoHash()
        assertEquals(databaseBefore, databaseAfter)
        assertEquals(photosBefore, photosAfter)

        scenario?.close()
        scenario = null
        runBlocking {
            database.recipeSnapshotDao().insert(
                RecipeSnapshotEntity(
                    "task-10-advice-recipe",
                    "오류 경로 확인 요리",
                    PantryBindings(emptyList()),
                    RecipeSteps(
                        values = listOf("재료 상태를 확인하세요"),
                        metadata = null,
                        recipeKind = com.junited31.leftovers.data.RecipeKind.MEAL,
                    ),
                    10,
                ),
            )
            CookingSessionStore(database.recipeSnapshotDao(), database.cookSessionDao())
                .start("task-10-advice-recipe", "task-10-advice-session", 20)
        }
        val adviceDatabaseBefore = databaseHash()
        val advicePhotosBefore = photoHash()
        val fixture = mediaStoreFixture()
        val adviceErrors = listOf(
            Triple(401, "인증 정보를 확인할 수 없어요.", true),
            Triple(413, "사진을 처리할 수 없어요.", false),
            Triple(422, "조언 형식을 확인할 수 없어요.", true),
            Triple(429, "오늘의 사진 조언 횟수를 모두 사용했어요.", false),
            Triple(502, "네트워크 연결을 확인하고\n다시 시도해 주세요.", true),
        )
        val adviceEvidence = JSONArray()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithTag("nav-cooking").performClick()

        try {
            adviceErrors.forEachIndexed { index, (status, copy, manualRetry) ->
                server.enqueue(MockResponse().setResponseCode(status).setHeader("Retry-After", "37"))
                application.photoPickerFixtureOverride = fixture
                compose.onNodeWithTag("attach-gallery").performScrollTo().performClick()
                compose.waitUntil(10_000) {
                    runCatching { compose.onNodeWithTag("request-advice").assertIsEnabled() }.isSuccess
                }
                compose.onNodeWithTag("request-advice").performScrollTo().performClick()
                val expectedRequestCount = recipeErrors.size + index + 1
                compose.waitUntil(10_000) { server.requestCount >= expectedRequestCount }
                assertAssertiveError("advice-error", copy)
                assertEquals(
                    if (manualRetry) 1 else 0,
                    compose.onAllNodesWithTag("retry-advice").fetchSemanticsNodes().size,
                )
                Thread.sleep(250)
                assertEquals(expectedRequestCount, server.requestCount)
                adviceEvidence.put(
                    JSONObject()
                        .put("status", status)
                        .put("copy", copy)
                        .put("requestDelta", 1)
                        .put("automaticRetry", false)
                        .put("manualRetryOffered", manualRetry),
                )
            }
        } finally {
            context.contentResolver.delete(fixture, null, null)
        }

        val adviceDatabaseAfter = databaseHash()
        val advicePhotosAfter = photoHash()
        assertEquals(adviceDatabaseBefore, adviceDatabaseAfter)
        assertEquals(advicePhotosBefore, advicePhotosAfter)
        writeEvidence(
            "task-10-errors.json",
            JSONObject()
                .put("recipeEndpoint", "/v1/recipes/generate")
                .put("recipeResults", recipeEvidence)
                .put("recipeDatabaseHashBefore", databaseBefore)
                .put("recipeDatabaseHashAfter", databaseAfter)
                .put("recipePhotoCacheHashBefore", photosBefore)
                .put("recipePhotoCacheHashAfter", photosAfter)
                .put("adviceEndpoint", "/v1/cooking/advice")
                .put("adviceResults", adviceEvidence)
                .put("adviceDatabaseHashBefore", adviceDatabaseBefore)
                .put("adviceDatabaseHashAfter", adviceDatabaseAfter)
                .put("advicePhotoCacheHashBefore", advicePhotosBefore)
                .put("advicePhotoCacheHashAfter", advicePhotosAfter)
                .put("localStateMutated", false)
                .toString(2),
        )
    }

    private fun assertAssertiveError(tag: String, exactText: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().any { node ->
                runCatching {
                    node.config[SemanticsProperties.Text].single().text == exactText &&
                        node.config[SemanticsProperties.LiveRegion] == LiveRegionMode.Assertive
                }.getOrDefault(false)
            }
        }
        compose.onNodeWithTag(tag, useUnmergedTree = true)
            .performScrollTo()
            .assertTextEquals(exactText)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
    }

    private fun mediaStoreFixture() = checkNotNull(
        context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "leftovers-task-10-advice.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            },
        ),
    ).also { uri ->
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(196, 112, 48))
        }
        context.contentResolver.openOutputStream(uri).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, checkNotNull(it))
        }
        bitmap.recycle()
    }

    private fun databaseHash(): String = runBlocking {
        sha256(
            buildString {
                append(database.pantryDao().getAll())
                append(database.recipeSnapshotDao().getAllIds())
                append(database.cookSessionDao().active())
                append(database.mealLogDao().latest())
            }.toByteArray(),
        )
    }

    private fun photoHash(): String = sha256(
        (photos.ownedCacheFiles() + photos.retainedFinalPhotos())
            .sortedBy(File::getName)
            .flatMap { it.name.toByteArray().asIterable() + it.readBytes().asIterable() }
            .toByteArray(),
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun capture(name: String) {
        val file = File(requireNotNull(context.getExternalFilesDir(null)), name)
        FileOutputStream(file).use { output ->
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                .compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        shell("cp ${file.absolutePath} /sdcard/Download/$name")
    }

    private fun writeEvidence(name: String, value: String) {
        val file = File(requireNotNull(context.getExternalFilesDir(null)), name).apply {
            writeText(value, Charsets.UTF_8)
        }
        shell("cp ${file.absolutePath} /sdcard/Download/$name")
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }

    private fun pantry() = listOf(
        item("00000000-0000-0000-0000-000000000401", "Rice", 900_000, PantryUnit.GRAM, 1),
        item("00000000-0000-0000-0000-000000000402", "Spinach", 300_000, PantryUnit.GRAM, 2),
        item("00000000-0000-0000-0000-000000000403", "Eggs", 6_000, PantryUnit.COUNT, 3),
    )

    private fun item(id: String, name: String, amount: Long, unit: PantryUnit, version: Int) = PantryItemEntity(
        requireNotNull(PantryItemId.parse(id)),
        name,
        amount,
        unit,
        LocalDate.now().plusDays(version.toLong()).toEpochDay(),
        version,
    )

    private fun validRecipes() = """
        {"recipes":[
          {"title":"Egg fried rice","cuisine":"Korean","primaryTechnique":"stir fry","requiredEquipment":["gas burner"],"trackedUses":[{"pantryItemId":"00000000-0000-0000-0000-000000000401","version":1,"unit":"g","proposedMilliUnits":300000},{"pantryItemId":"00000000-0000-0000-0000-000000000403","version":3,"unit":"count","proposedMilliUnits":2000}],"missingIngredients":[],"steps":["Cook rice","Add eggs"]},
          {"title":"Rice omelette","cuisine":"Japanese","primaryTechnique":"pan fry","requiredEquipment":["gas burner"],"trackedUses":[{"pantryItemId":"00000000-0000-0000-0000-000000000401","version":1,"unit":"g","proposedMilliUnits":200000},{"pantryItemId":"00000000-0000-0000-0000-000000000403","version":3,"unit":"count","proposedMilliUnits":2000}],"missingIngredients":[],"steps":["Beat eggs","Fold rice"]},
          {"title":"Crispy spinach rice","cuisine":"Korean","primaryTechnique":"bake","requiredEquipment":["basic cookware"],"trackedUses":[{"pantryItemId":"00000000-0000-0000-0000-000000000401","version":1,"unit":"g","proposedMilliUnits":250000},{"pantryItemId":"00000000-0000-0000-0000-000000000402","version":2,"unit":"g","proposedMilliUnits":100000}],"missingIngredients":[],"steps":["Mix","Bake"]}
        ]}
    """.trimIndent()
}
