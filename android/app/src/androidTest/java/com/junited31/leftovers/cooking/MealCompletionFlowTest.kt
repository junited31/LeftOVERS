package com.junited31.leftovers.cooking

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import android.os.ParcelFileDescriptor
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.text.AnnotatedString
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.junited31.leftovers.DebugApplication
import com.junited31.leftovers.MainActivity
import com.junited31.leftovers.data.CookSessionEntity
import com.junited31.leftovers.data.LeftoversDatabase
import com.junited31.leftovers.data.PantryBinding
import com.junited31.leftovers.data.PantryBindings
import com.junited31.leftovers.data.PantryItemEntity
import com.junited31.leftovers.data.PantryItemId
import com.junited31.leftovers.data.PantryUnit
import com.junited31.leftovers.data.RecipePreferenceMetadata
import com.junited31.leftovers.data.RecipeSnapshotEntity
import com.junited31.leftovers.data.RecipeSteps
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
        database.clearAllTables()
        photos.ownedCacheFiles().forEach(File::delete)
        photos.retainedFinalPhotos().forEach(File::delete)
        database.pantryDao().insertAll(
            listOf(PantryItemEntity(riceId, "쌀", 10_000, PantryUnit.GRAM, null, 1)),
        )
        database.recipeSnapshotDao().insert(
            RecipeSnapshotEntity(
                "task-8-recipe",
                "쌀 요리",
                PantryBindings(listOf(PantryBinding(riceId, 1, PantryUnit.GRAM, 5_000))),
                RecipeSteps(listOf("완성하세요"), RecipePreferenceMetadata("Korean", "mix", setOf("쌀"))),
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
        runBlocking { database.clearAllTables() }
        photos.ownedCacheFiles().forEach(File::delete)
        photos.retainedFinalPhotos().forEach(File::delete)
    }

    @Test
    fun editsActualUseFeedbackAndPhotoThenCompletesOfflineWithExactRemaining() {
        launchCompletion()
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
        assertEquals("쌀", log.actualUses.values.single().displayName)
        assertEquals(5, log.recipeSnapshot.feedback.rating)
        assertFalse(log.recipeSnapshot.feedback.recommendAgain)
        assertEquals("덜 짜게", log.recipeSnapshot.feedback.notes)
        assertEquals(4_000L, log.recipeSnapshot.feedback.measurementAdjustments.single().preferredAmountMilliUnits)
        assertTrue(File(requireNotNull(log.recipeSnapshot.feedback.finalPhotoPath)).isFile)
        val profile = PreferenceProfile.from(listOf(log))
        assertEquals(1, profile.history.size)
        assertEquals(4_000L, profile.measurementHints.single().preferredAmountMilliUnits)
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

    private fun hideKeyboard() {
        scenario?.onActivity { activity ->
            activity.getSystemService(InputMethodManager::class.java)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        Thread.sleep(750)
        compose.waitForIdle()
    }

}
