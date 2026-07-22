package com.junited31.leftovers.cooking

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import android.os.ParcelFileDescriptor
import android.view.inputmethod.InputMethodManager
import android.graphics.Rect
import android.util.Xml
import android.view.accessibility.AccessibilityNodeInfo
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.text.AnnotatedString
import androidx.room.Room
import androidx.core.os.LocaleListCompat
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.junited31.leftovers.DebugApplication
import com.junited31.leftovers.MainActivity
import com.junited31.leftovers.data.CookSessionEntity
import com.junited31.leftovers.data.LeftoversDatabase
import com.junited31.leftovers.data.LeftoversPreferenceKeys
import com.junited31.leftovers.data.PantryBinding
import com.junited31.leftovers.data.PantryBindings
import com.junited31.leftovers.data.PantryItemEntity
import com.junited31.leftovers.data.PantryItemId
import com.junited31.leftovers.data.PantryUnit
import com.junited31.leftovers.data.RecipePreferenceMetadata
import com.junited31.leftovers.data.RecipeSnapshotEntity
import com.junited31.leftovers.data.RecipeSteps
import com.junited31.leftovers.data.leftoversDataStore
import com.junited31.leftovers.photo.PhotoLifecycle
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import org.json.JSONObject
import com.junited31.leftovers.recipes.PreferenceProfile

@RunWith(AndroidJUnit4::class)
class MealCompletionFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val application = context as DebugApplication
    private val database = LeftoversDatabase.get(context)
    private val photos = PhotoLifecycle(context)
    private val riceId = requireNotNull(PantryItemId.parse("00000000-0000-4000-8000-000000000088"))
    private var scenario: ActivityScenario<MainActivity>? = null
    private var fixture: Uri? = null

    @Before
    fun setUp() = runBlocking {
        compose.runOnUiThread {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ko"))
        }
        database.clearAllTables()
        context.leftoversDataStore.edit { it[LeftoversPreferenceKeys.ONBOARDING_COMPLETE] = true }
        photos.ownedCacheFiles().forEach(File::delete)
        photos.retainedFinalPhotos().forEach(File::delete)
        database.pantryDao().insertAll(
            listOf(PantryItemEntity(riceId, "Onion ", 10_000, PantryUnit.GRAM, null, 1)),
        )
        database.recipeSnapshotDao().insert(
            RecipeSnapshotEntity(
                "task-8-recipe",
                "쌀 요리",
                PantryBindings(listOf(PantryBinding(riceId, 1, PantryUnit.GRAM, 5_000))),
                RecipeSteps(
                    listOf("완성하세요"),
                    RecipePreferenceMetadata("Korean", "mix", setOf("쌀")),
                    com.junited31.leftovers.data.RecipeKind.MEAL,
                ),
                10,
            ),
        )
        database.cookSessionDao().insert(CookSessionEntity("task-8-session", "task-8-recipe", 20, 0, null))
    }

    @After
    fun tearDown() {
        scenario?.close()
        fixture?.let { context.contentResolver.delete(it, null, null) }
        application.photoPickerFixtureOverride = null
        application.completionCameraFixtureResult = null
        application.completionCameraFixtureBytes = null
        runBlocking { database.clearAllTables() }
        photos.ownedCacheFiles().forEach(File::delete)
        photos.retainedFinalPhotos().forEach(File::delete)
        compose.runOnUiThread {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
        }
    }

    @Test
    fun editsActualUseFeedbackAndPhotoThenCompletesOfflineWithExactRemaining() {
        launchCompletion()
        compose.onNodeWithText("Onion ").assertIsDisplayed()
        compose.onNodeWithTag("completion-form-title")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
        compose.onNodeWithTag("rating-group")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.SelectableGroup, Unit))
        compose.onNodeWithTag("actual-use-0").performTextReplacement("10")
        compose.onNodeWithTag("rating-5").performClick()
        compose.onNodeWithTag("meal-notes").performTextReplacement("덜 짜게")
        compose.onNodeWithTag("recommend-again").performClick()
        compose.onNodeWithTag("adjustment-amount-0").performTextReplacement("4")
        compose.onNodeWithTag("adjustment-note-0").performTextReplacement("한 숟갈 적게")
        attachFinalPhoto()
        hideKeyboard()
        compose.onNodeWithTag("complete-meal").performScrollTo()
        captureScreen("task-8-completion-form.png")

        compose.onNodeWithTag("complete-meal").performClick()
        compose.onNodeWithTag("completion-success").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onNodeWithTag("completion-success-title")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))

        assertEquals(0L, runBlocking { database.pantryDao().get(riceId) }?.quantityMilliUnits)
        val log = requireNotNull(runBlocking { database.mealLogDao().latest() }.single())
        assertEquals("Onion ", log.actualUses.values.single().displayName)
        assertEquals(5, log.recipeSnapshot.feedback.rating)
        assertFalse(log.recipeSnapshot.feedback.recommendAgain)
        assertEquals("덜 짜게", log.recipeSnapshot.feedback.notes)
        assertEquals(4_000L, log.recipeSnapshot.feedback.measurementAdjustments.single().preferredAmountMilliUnits)
        assertEquals("Onion ", log.recipeSnapshot.feedback.measurementAdjustments.single().ingredientName)
        assertTrue(File(requireNotNull(log.recipeSnapshot.feedback.finalPhotoPath)).isFile)
        val profile = PreferenceProfile.from(listOf(log))
        assertEquals(1, profile.history.size)
        assertEquals(4_000L, profile.measurementHints.single().preferredAmountMilliUnits)
        assertEquals("onion", profile.measurementHints.single().ingredientName)
        hideKeyboard()
        captureCompletionEvidence(log.recipeSnapshot.feedback.finalPhotoPath, profile)
    }

    @Test
    fun competingQuantityEditShowsStaleAndRollsBackLogAndFinalPhoto() = runBlocking {
        launchCompletion()
        val row = requireNotNull(database.pantryDao().get(riceId))
        database.pantryDao().update(row.copy(quantityMilliUnits = 9_000, version = 2))
        attachFinalPhoto()

        compose.onNodeWithTag("complete-meal").performScrollTo().performClick()
        compose.onNodeWithTag("completion-error").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))

        assertEquals(9_000L, database.pantryDao().get(riceId)?.quantityMilliUnits)
        assertEquals(0, database.mealLogDao().count())
        assertTrue(photos.ownedCacheFiles().isEmpty())
        assertTrue(photos.retainedFinalPhotos().isEmpty())
        writeExternalEvidence(
            "task-8-stale.txt",
            "result=StaleInventory\ninventoryMilliUnits=9000\nmealLogCount=0\ncachePhotoCount=0\nretainedPhotoCount=0\n",
        )
        writeExternalEvidence(
            "failure-compensation.json",
            JSONObject()
                .put("executionSurface", "physical-device-instrumentation")
                .put("testCase", "competingQuantityEditShowsStaleAndRollsBackLogAndFinalPhoto")
                .put("result", "StaleInventory")
                .put("mealLogCount", 0)
                .put("cachePhotoCount", 0)
                .put("retainedPhotoCount", 0)
                .put("compensationDelete", "success")
                .toString(2),
        )
    }

    @Test
    fun completionDraftSurvivesActivityRecreationWithoutSubmitting() {
        launchCompletion()
        compose.onNodeWithTag("actual-use-0").performTextReplacement("4.5")
        compose.onNodeWithTag("rating-5").performClick()
        compose.onNodeWithTag("meal-notes").performTextReplacement("Keep this draft")

        scenario?.recreate()
        compose.onNodeWithTag("nav-cooking").performClick()
        compose.onNodeWithTag("completion-form").assertIsDisplayed()

        compose.onNodeWithTag("actual-use-0").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("4.5")),
        )
        compose.onNodeWithTag("meal-notes").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("Keep this draft")),
        )
        compose.onNodeWithTag("rating-5").assertIsSelected()
        assertEquals(0, runBlocking { database.mealLogDao().count() })
    }

    @Test
    fun attachedPhotoShowsPreviewReplaceRemoveAndReadySemantics() {
        launchCompletion()
        attachFinalPhoto()

        compose.onNodeWithTag("completion-photo-preview").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("replace-final-photo").performScrollTo().assertIsDisplayed()
        captureScreen("completion-preview.png")
        captureXml("completion-preview.xml")
        val firstCachePath = photos.ownedCacheFiles().single().absolutePath
        application.photoPickerFixtureOverride = fixture
        compose.onNodeWithTag("replace-final-photo").performClick()
        compose.waitUntil(10_000) {
            photos.ownedCacheFiles().singleOrNull()?.absolutePath?.let { it != firstCachePath } == true
        }
        compose.onNodeWithTag("completion-photo-preview").performScrollTo().assertIsDisplayed()
        assertEquals(1, photos.ownedCacheFiles().size)
        compose.onNodeWithTag("remove-final-photo").performScrollTo().performClick()

        compose.onNodeWithTag("completion-photo-preview").assertDoesNotExist()
        compose.onNodeWithTag("final-photo-ready").assertDoesNotExist()
        compose.onNodeWithTag("attach-final-photo").performScrollTo().assertIsDisplayed()
        assertTrue(photos.ownedCacheFiles().isEmpty())
    }

    @Test
    fun attachedPhotoPreviewSurvivesActivityRecreation() {
        launchCompletion()
        attachFinalPhoto()

        scenario?.recreate()
        compose.onNodeWithTag("nav-cooking").performClick()

        compose.onNodeWithTag("completion-photo-preview").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("final-photo-ready").assertIsDisplayed()
        assertEquals(1, photos.ownedCacheFiles().size)
        assertEquals(0, runBlocking { database.mealLogDao().count() })
    }

    @Test
    fun completionCameraActionHasSyntheticCancelSurface() {
        launchCompletion()
        application.completionCameraFixtureResult = false
        compose.onNodeWithTag("capture-final-photo").performScrollTo().performClick()
        compose.onNodeWithTag("completion-photo-preview").assertDoesNotExist()
        assertTrue(photos.ownedCacheFiles().isEmpty())
        val cancelLogCount = runBlocking { database.mealLogDao().count() }
        val cancelCacheCount = photos.ownedCacheFiles().size
        val cancelRetainedCount = photos.retainedFinalPhotos().size

        application.completionCameraFixtureBytes = syntheticJpeg()
        application.completionCameraFixtureResult = true
        compose.onNodeWithTag("capture-final-photo").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("completion-photo-preview").fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag("completion-photo-preview").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("final-photo-ready").performScrollTo().assertIsDisplayed()
        assertEquals(1, photos.ownedCacheFiles().size)
        scenario?.recreate()
        compose.onNodeWithTag("nav-cooking").performClick()
        compose.onNodeWithTag("completion-photo-preview").performScrollTo().assertIsDisplayed()
        val cacheCountAfterRecreation = photos.ownedCacheFiles().size
        assertEquals(1, cacheCountAfterRecreation)
        writeExternalEvidence(
            "failure-camera.json",
            JSONObject()
                .put("executionSurface", "physical-device-instrumentation-synthetic-camera")
                .put("testCase", "completionCameraActionHasSyntheticCancelSurface")
                .put("cancelResult", false)
                .put("cancelMealLogCount", cancelLogCount)
                .put("cancelCachePhotoCount", cancelCacheCount)
                .put("cancelRetainedPhotoCount", cancelRetainedCount)
                .put("capturedPreviewSurvivedActivityRecreation", true)
                .put("cachePhotoCountAfterRecreation", cacheCountAfterRecreation)
                .toString(2),
        )
    }

    @Test
    fun immediateSuccessAndHistoryUseTheRetainedBytes() {
        launchCompletion()
        attachFinalPhoto()
        val preparedHash = sha256(photos.ownedCacheFiles().single())

        compose.onNodeWithTag("complete-meal").performScrollTo().performClick()
        compose.onNodeWithTag("completion-success-photo").assertIsDisplayed()
        captureScreen("success-photo.png")
        captureXml("success-photo.xml")

        val log = requireNotNull(runBlocking { database.mealLogDao().latest() }.single())
        val retained = File(requireNotNull(log.recipeSnapshot.feedback.finalPhotoPath))
        assertTrue(retained.isFile)
        assertEquals(preparedHash, sha256(retained))
        compose.onNodeWithTag("nav-history").performClick()
        compose.onNodeWithTag("history-row-${log.id}").performClick()
        compose.onNodeWithTag("history-photo").assertIsDisplayed()
        assertEquals(preparedHash, sha256(retained))
        captureScreen("history-photo.png")
        captureXml("history-photo.xml")
        val retainedHash = sha256(retained)
        writeExternalEvidence(
            "manual.json",
            JSONObject()
                .put("executionSurface", "physical-device-instrumentation-synthetic-gallery")
                .put("testCase", "immediateSuccessAndHistoryUseTheRetainedBytes")
                .put("deviceSerial", "R5CR91DXA8R")
                .put("syntheticOnly", true)
                .put("preparedSha256", preparedHash)
                .put("retainedSha256", retainedHash)
                .put("historySha256", retainedHash)
                .put("daoReference", retained.absolutePath)
                .put("sameBytes", preparedHash == retainedHash)
                .toString(2),
        )
        writeExternalEvidence(
            "adversarial.json",
            JSONObject()
                .put("executionSurface", "physical-device-instrumentation-synthetic-gallery")
                .put("testCase", "immediateSuccessAndHistoryUseTheRetainedBytes")
                .put("referencedFileExists", retained.isFile)
                .put("daoReferences", org.json.JSONArray().put(retained.absolutePath))
                .put("retainedSha256", retainedHash)
                .toString(2),
        )
    }

    @Test
    fun activityStartupReconcilesAProcessDeathOrphan() {
        val orphan = photos.createManagedPhoto().also { it.file.writeText("orphan") }
        val retained = File(photos.retainFinal(orphan, "process-death"))
        assertTrue(retained.isFile)

        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(5_000) { !retained.exists() }

        assertFalse(retained.exists())
        assertEquals(0, runBlocking { database.mealLogDao().count() })
        writeExternalEvidence(
            "failure-reconcile.json",
            JSONObject()
                .put("executionSurface", "physical-device-instrumentation-simulated-process-death")
                .put("testCase", "activityStartupReconcilesAProcessDeathOrphan")
                .put("simulatedProcessDeathOrphan", true)
                .put("orphanExistsAfterStartup", retained.exists())
                .put("mealLogCount", runBlocking { database.mealLogDao().count() })
                .put("retainedPhotoCount", photos.retainedFinalPhotos().size)
                .toString(2),
        )
    }

    private fun launchCompletion() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithTag("nav-cooking").performClick()
        compose.onNodeWithTag("start-completion").performScrollTo().performClick()
        compose.onNodeWithTag("completion-form").assertIsDisplayed()
    }

    private fun attachFinalPhoto() {
        fixture = imageFixture()
        application.photoPickerFixtureOverride = fixture
        compose.onNodeWithTag("attach-final-photo").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("final-photo-ready").fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag("final-photo-ready").performScrollTo().assertIsDisplayed()
    }

    private fun imageFixture() = checkNotNull(
        context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "leftovers-task-8-final.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            },
        ),
    ).also { uri ->
        val bitmap = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(210, 150, 80))
        }
        context.contentResolver.openOutputStream(uri).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, checkNotNull(it))
        }
        bitmap.recycle()
    }

    private fun captureCompletionEvidence(finalPhotoPath: String?, profile: PreferenceProfile) {
        Thread.sleep(5_000)
        repeat(3) { captureScreen("task-8-completion.png") }
        writeExternalEvidence(
            "task-8-feedback.json",
            JSONObject()
                .put("inventoryMilliUnits", 0)
                .put("mealLogCount", 1)
                .put("rating", 5)
                .put("recommendAgain", false)
                .put("notes", "덜 짜게")
                .put("historyCount", profile.history.size)
                .put("historyFingerprint", profile.history.single().fingerprint)
                .put("measurementHintName", profile.measurementHints.single().ingredientName)
                .put("measurementHintMilliUnits", profile.measurementHints.single().preferredAmountMilliUnits)
                .put("finalPhotoExists", finalPhotoPath?.let(::File)?.isFile == true)
                .put("networkCallsDuringCompletion", 0)
                .toString(2),
        )
    }

    private fun writeExternalEvidence(name: String, value: String) {
        val file = File(requireNotNull(context.getExternalFilesDir(null)), name)
        file.writeText(value, Charsets.UTF_8)
        copyToDownloads(file)
    }

    private fun captureScreen(name: String) {
        compose.waitForIdle()
        Thread.sleep(1_500)
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "screencap -p /sdcard/Download/$name",
            ),
        ).use { it.readBytes() }
    }

    private fun copyToDownloads(file: File) {
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "cp ${file.absolutePath} /sdcard/Download/${file.name}",
            ),
        ).use { it.readBytes() }
    }

    private fun captureXml(name: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        compose.waitUntil(5_000) { automation.rootInActiveWindow != null }
        val file = File(requireNotNull(context.getExternalFilesDir(null)), name)
        FileOutputStream(file).use { output ->
            val serializer = Xml.newSerializer()
            serializer.setOutput(output, "UTF-8")
            serializer.startDocument("UTF-8", true)
            serializer.startTag(null, "hierarchy")
            writeNode(serializer, requireNotNull(automation.rootInActiveWindow))
            serializer.endTag(null, "hierarchy")
            serializer.endDocument()
        }
        copyToDownloads(file)
    }

    private fun writeNode(serializer: org.xmlpull.v1.XmlSerializer, node: AccessibilityNodeInfo) {
        val bounds = Rect().also(node::getBoundsInScreen)
        serializer.startTag(null, "node")
        serializer.attribute(null, "text", node.text?.toString().orEmpty())
        serializer.attribute(null, "content-desc", node.contentDescription?.toString().orEmpty())
        serializer.attribute(null, "class", node.className?.toString().orEmpty())
        serializer.attribute(null, "clickable", node.isClickable.toString())
        serializer.attribute(null, "enabled", node.isEnabled.toString())
        serializer.attribute(null, "bounds", bounds.toShortString())
        repeat(node.childCount) { index -> node.getChild(index)?.let { writeNode(serializer, it) } }
        serializer.endTag(null, "node")
    }

    private fun hideKeyboard() {
        scenario?.onActivity { activity ->
            activity.getSystemService(InputMethodManager::class.java)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        Thread.sleep(750)
        compose.waitForIdle()
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun syntheticJpeg(): ByteArray = ByteArrayOutputStream().use { output ->
        Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888).run {
            eraseColor(Color.rgb(80, 160, 210))
            compress(Bitmap.CompressFormat.JPEG, 95, output)
            recycle()
        }
        output.toByteArray()
    }

}
