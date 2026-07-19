package com.junited31.leftovers.cooking

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Xml
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.junited31.leftovers.DebugApplication
import com.junited31.leftovers.MainActivity
import com.junited31.leftovers.data.LeftoversDatabase
import com.junited31.leftovers.data.PantryBindings
import com.junited31.leftovers.data.RecipeSnapshotEntity
import com.junited31.leftovers.data.RecipeSteps
import com.junited31.leftovers.network.LeftoversApi
import com.junited31.leftovers.network.TokenProvider
import com.junited31.leftovers.photo.PhotoContracts
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class CookingPhotoFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val application = context as DebugApplication
    private val database = LeftoversDatabase.get(context)
    private lateinit var server: MockWebServer
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() = runBlocking {
        database.clearAllTables()
        database.recipeSnapshotDao().insert(
            RecipeSnapshotEntity(
                "task-7-recipe",
                "채소 볶음",
                PantryBindings(emptyList()),
                RecipeSteps(listOf("재료를 준비하세요", "약 7분 볶으세요", "불을 끄고 담으세요")),
                10,
            ),
        )
        CookingSessionStore(database.recipeSnapshotDao(), database.cookSessionDao())
            .start("task-7-recipe", "task-7-session", 20)
        CookingSessionStore(database.recipeSnapshotDao(), database.cookSessionDao())
            .next("task-7-session")
        server = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(200).setBody(validAdvice()))
            start()
        }
        application.recipeApiOverride = LeftoversApi(server.url("/").toString(), TokenProvider { "device-token" })
    }

    @After
    fun tearDown() {
        scenario?.close()
        application.recipeApiOverride = null
        server.shutdown()
        runBlocking { database.clearAllTables() }
    }

    @Test
    fun offline_cooking_steps_and_retry_survive_an_unavailable_photo_client() {
        // Given: cooking data is already persisted, but no authenticated photo client is available.
        val fixture = mediaStoreFixture()
        application.recipeApiOverride = null

        // When: the user opens the cooking destination while offline.
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithTag("nav-cooking").performClick()
        application.photoPickerFixtureOverride = fixture
        compose.onNodeWithTag("attach-gallery").performClick()
        compose.onNodeWithTag("request-advice").performClick()

        // Then: local step guidance remains available without constructing the network client.
        compose.onNodeWithText("2 / 3 단계").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("retry-advice").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("사진 다시 선택해 재시도").assertIsDisplayed()
        assertLastRenderedLine(
            "네트워크 연결을 확인하고\n다시 시도해 주세요.",
            "다시 시도해 주세요.",
        )
        assertEquals(1, runBlocking { database.cookSessionDao().get("task-7-session") }?.currentStepIndex)
        assertEquals(0, server.requestCount)
        context.contentResolver.delete(fixture, null, null)
    }

    @Test
    fun media_store_picker_photo_shows_safe_korean_advice_and_stays_on_step_two() {
        // Given: production UI resumes the persisted second step.
        val fixture = mediaStoreFixture()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithTag("nav-cooking").performClick()
        compose.onNodeWithText("2 / 3 단계").assertIsDisplayed()

        // When: the real native picker result is parsed and the production advice action is used.
        val picked = PhotoContracts.pick.parseResult(Activity.RESULT_OK, Intent().setData(fixture))
        application.photoPickerFixtureOverride = picked
        compose.onNodeWithTag("attach-gallery").performClick()
        compose.onNodeWithTag("request-advice").performClick()

        // Then: structured Korean advice and the mandatory safety note render without advancing.
        compose.waitUntil(10_000) {
            server.requestCount == 1 &&
                compose.onAllNodesWithTag("advice-card").fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithText("관찰 결과").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("표면이 노릇해졌어요", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("다음 행동").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("신뢰도 72%").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("안전 안내").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("사진만으로 익음과 안전을 확인할 수 없어요.\n시간과 온도를 확인하세요.")
            .performScrollTo().assertIsDisplayed()
        assertLastRenderedLine(
            "사진만으로 익음과 안전을 확인할 수 없어요.\n시간과 온도를 확인하세요.",
            "시간과 온도를 확인하세요.",
        )
        compose.onNodeWithTag("cooking-screen").performTouchInput { swipeUp() }
        captureEvidence()
        compose.onNodeWithText("2 / 3 단계").performScrollTo().assertIsDisplayed()
        assertEquals(1, runBlocking { database.cookSessionDao().get("task-7-session") }?.currentStepIndex)
        assertEquals(1, server.requestCount)
        compose.onNodeWithTag("next-step").performScrollTo().performClick()
        compose.onNodeWithText("3 / 3 단계").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag("advice-card").fetchSemanticsNodes().size)
        context.contentResolver.delete(fixture, null, null)
    }

    private fun mediaStoreFixture() = checkNotNull(
        context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "leftovers-task-7-cooking.png")
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
        copyToDownloads(uri, "task-7-cooking-fixture.png")
        bitmap.recycle()
    }

    private fun copyToDownloads(source: android.net.Uri, name: String) {
        val output = checkNotNull(
            context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "image/png")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                },
            ),
        )
        context.contentResolver.openInputStream(source).use { input ->
            context.contentResolver.openOutputStream(output).use { target ->
                checkNotNull(input).copyTo(checkNotNull(target))
            }
        }
    }

    private fun captureEvidence() {
        val directory = requireNotNull(context.getExternalFilesDir(null))
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val screenshot = File(directory, "task-7-cooking.png")
        FileOutputStream(screenshot).use {
            automation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        shell(automation.executeShellCommand("cp ${screenshot.absolutePath} /sdcard/Download/task-7-cooking.png"))
        val hierarchy = File(directory, "task-7-cooking.xml")
        FileOutputStream(hierarchy).use { output ->
            val serializer = Xml.newSerializer()
            serializer.setOutput(output, "UTF-8")
            serializer.startDocument("UTF-8", true)
            serializer.startTag(null, "hierarchy")
            writeNode(serializer, requireNotNull(automation.rootInActiveWindow))
            serializer.endTag(null, "hierarchy")
            serializer.endDocument()
        }
        shell(automation.executeShellCommand("cp ${hierarchy.absolutePath} /sdcard/Download/task-7-cooking.xml"))
    }

    private fun shell(descriptor: ParcelFileDescriptor) =
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }

    private fun assertLastRenderedLine(text: String, expected: String) {
        val results = mutableListOf<TextLayoutResult>()
        val action = compose.onNodeWithText(text).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action
        assertEquals(true, action?.invoke(results))
        val layout = results.single()
        val line = layout.lineCount - 1
        assertEquals(
            expected,
            layout.layoutInput.text.text.substring(
                layout.getLineStart(line),
                layout.getLineEnd(line),
            ).trim(),
        )
    }

    private fun writeNode(serializer: org.xmlpull.v1.XmlSerializer, node: AccessibilityNodeInfo) {
        val bounds = Rect().also(node::getBoundsInScreen)
        serializer.startTag(null, "node")
        serializer.attribute(null, "text", node.text?.toString().orEmpty())
        serializer.attribute(null, "content-desc", node.contentDescription?.toString().orEmpty())
        serializer.attribute(null, "class", node.className?.toString().orEmpty())
        serializer.attribute(null, "clickable", node.isClickable.toString())
        serializer.attribute(null, "enabled", node.isEnabled.toString())
        serializer.attribute(null, "heading", node.isHeading.toString())
        serializer.attribute(null, "bounds", bounds.toShortString())
        repeat(node.childCount) { index -> node.getChild(index)?.let { writeNode(serializer, it) } }
        serializer.endTag(null, "node")
    }

    private fun validAdvice() = """
        {"status":"adjust","observations":["surface_browned"],"nextActions":["turn_and_check_center_temperature"],"confidence":0.72,"safetyNote":"사진만으로 익음과 안전을 확인할 수 없어요. 시간과 온도를 확인하세요."}
    """.trimIndent()
}
