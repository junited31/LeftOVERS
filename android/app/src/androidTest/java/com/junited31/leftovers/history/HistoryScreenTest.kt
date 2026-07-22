package com.junited31.leftovers.history

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.util.Xml
import android.view.accessibility.AccessibilityNodeInfo
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.core.os.LocaleListCompat
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.junited31.leftovers.MainActivity
import com.junited31.leftovers.data.ActualPantryUse
import com.junited31.leftovers.data.ActualPantryUses
import com.junited31.leftovers.data.JsonConverters
import com.junited31.leftovers.data.LeftoversDatabase
import com.junited31.leftovers.data.LeftoversPreferenceKeys
import com.junited31.leftovers.data.MealFeedback
import com.junited31.leftovers.data.MealLogEntity
import com.junited31.leftovers.data.MeasurementAdjustment
import com.junited31.leftovers.data.PantryBinding
import com.junited31.leftovers.data.PantryBindings
import com.junited31.leftovers.data.PantryItemId
import com.junited31.leftovers.data.PantryRemaining
import com.junited31.leftovers.data.PantryRemainingSnapshots
import com.junited31.leftovers.data.PantryUnit
import com.junited31.leftovers.data.RecipePreferenceMetadata
import com.junited31.leftovers.data.RecipeSnapshotRecord
import com.junited31.leftovers.data.RecipeSteps
import com.junited31.leftovers.data.leftoversDataStore
import com.junited31.leftovers.photo.PhotoLifecycle
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class HistoryScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = LeftoversDatabase.get(context)
    private val converters = JsonConverters()
    private val photos = PhotoLifecycle(context)
    private val pantryId = requireNotNull(PantryItemId.parse("00000000-0000-4000-8000-000000000099"))
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() = runBlocking {
        compose.runOnUiThread {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
        }
        database.clearAllTables()
        context.leftoversDataStore.edit { it[LeftoversPreferenceKeys.ONBOARDING_COMPLETE] = true }
        photos.retainedFinalPhotos().forEach(File::delete)
    }

    @After
    fun tearDown() = runBlocking {
        database.clearAllTables()
        photos.retainedFinalPhotos().forEach(File::delete)
        if (scenario == null) scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.runOnUiThread {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
        }
        compose.waitUntil(5_000) {
            AppCompatDelegate.getApplicationLocales().toLanguageTags() == "en"
        }
        scenario?.close()
        Unit
    }

    @Test
    fun emptyHistoryShowsAUsefulState() {
        launchHistory()

        compose.onNodeWithTag("history-empty").assertIsDisplayed()
        compose.onNodeWithText("No completed meals yet.").assertIsDisplayed()
    }

    @Test
    fun newestMealDetailSurvivesActivityRestartAndShowsEverySavedField() {
        val photoPath = retainedPhoto("newer")
        insertLog(log("older", 1_000, null))
        insertLog(log("newer", 2_000, photoPath))
        launchHistory()

        compose.onNodeWithTag("history-row-newer").assertIsDisplayed().performClick()
        compose.onNodeWithTag("history-detail").assertIsDisplayed()
        compose.onNodeWithText("김치볶음밥 newer").assertIsDisplayed()

        scenario?.recreate()
        compose.onNodeWithTag("history-detail").assertIsDisplayed()

        compose.onNodeWithTag("history-photo").assertIsDisplayed()
        scrollTo("Rating 5 / 5")
        compose.onNodeWithText("다음에는 덜 짜게").assertIsDisplayed()
        compose.onNodeWithText("Not recommended again").assertIsDisplayed()
        scrollTo("Actual use")
        scrollTo("김치 · 5 g")
        scrollTo("Remaining quantity")
        scrollTo("김치 · 7 g")
        scrollTo("김치 · 4 g · 한 숟갈 적게")
        capture("task-9-history-bottom.png")
        scrollTo("김치볶음밥 newer")
        capture("task-9-history.png")
    }

    @Test
    fun historyDetailShowsLocalizedPersistedRecipeKind() {
        val base = log("kind", 2_500, null)
        insertLog(base.copy(
            recipeSnapshot = base.recipeSnapshot.copy(
                steps = stepsWithKind(base.recipeSnapshot.steps.values, base.recipeSnapshot.steps.metadata, "DRINK"),
            ),
        ))
        launchHistory()

        compose.onNodeWithTag("history-row-kind").performClick()
        compose.onNodeWithTag("history-recipe-kind").assertIsDisplayed()
        compose.onNodeWithText("Drink").assertIsDisplayed()

        compose.runOnUiThread {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ko"))
        }
        compose.waitUntil(5_000) {
            AppCompatDelegate.getApplicationLocales().toLanguageTags() == "ko"
        }
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithText("음료").assertIsDisplayed() }.isSuccess
        }
    }

    @Test
    fun deletedPhotoShowsFallbackWithoutChangingStoredSnapshot() {
        val path = retainedPhoto("missing")
        insertLog(log("missing", 3_000, path))
        val before = storedSnapshot("missing")
        assertTrue(File(path).delete())
        launchHistory()

        compose.onNodeWithTag("history-row-missing").performClick()
        compose.onNodeWithTag("history-photo-missing").assertIsDisplayed()
        compose.onNodeWithText("The final photo file can’t be found.").assertIsDisplayed()

        assertEquals(before, storedSnapshot("missing"))
        assertEquals(path, runBlocking { database.mealLogDao().get("missing") }
            ?.recipeSnapshot?.feedback?.finalPhotoPath)
        capture("task-9-missing-photo.xml", xml = true)
        capture("failure-missing.xml", xml = true)
    }

    @Test
    fun adjustmentHistoryLocalizesOnlyExactCanonicalNames() {
        val base = log("names", 3_500, null)
        insertLog(base.copy(
            recipeSnapshot = base.recipeSnapshot.copy(
                feedback = base.recipeSnapshot.feedback.copy(
                    measurementAdjustments = listOf(
                        MeasurementAdjustment("onion", 1_000, PantryUnit.COUNT, "exact", 3_500),
                        MeasurementAdjustment("Onion ", 1_000, PantryUnit.COUNT, "near", 3_500),
                    ),
                ),
            ),
        ))
        launchHistory()
        compose.runOnUiThread {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ko"))
        }
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithText("완료한 요리").assertIsDisplayed() }.isSuccess
        }

        compose.onNodeWithTag("history-row-names").performClick()
        scrollTo("양파 · 1 개 · exact")
        scrollTo("Onion  · 1 개 · near")
    }

    @Test
    fun debugResetClearsMealLogsAndRetainedPhotos() {
        val path = retainedPhoto("reset")
        insertLog(log("reset", 4_000, path))
        launchHistory()

        shell("am broadcast -a com.junited31.leftovers.DEBUG_RESET")
        compose.waitUntil(5_000) { runBlocking { database.mealLogDao().count() } == 0 }

        assertEquals(0, runBlocking { database.mealLogDao().count() })
        assertFalse(File(path).exists())
    }

    private fun launchHistory() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(5_000) {
            AppCompatDelegate.getApplicationLocales().toLanguageTags() == "en"
        }
        scenario?.recreate()
        compose.waitForIdle()
        compose.onNodeWithTag("nav-history").performClick()
        compose.onNodeWithTag("history-screen").assertIsDisplayed()
    }

    private fun scrollTo(text: String) {
        compose.onNodeWithTag("history-detail").performScrollToNode(hasText(text))
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    private fun retainedPhoto(id: String): String {
        val managed = photos.createManagedPhoto()
        context.contentResolver.openOutputStream(photos.fileProviderUri(managed)).use { output ->
            Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888).run {
                eraseColor(Color.rgb(210, 145, 72))
                compress(Bitmap.CompressFormat.JPEG, 90, checkNotNull(output))
                recycle()
            }
        }
        return photos.retainFinal(managed, id)
    }

    private fun insertLog(log: MealLogEntity) {
        database.openHelper.writableDatabase.execSQL(
            """
                INSERT INTO meal_logs
                    (id, cookSessionId, recipeSnapshot, actualUses, remainingPantry, completedAtEpochMillis)
                VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                log.id,
                log.cookSessionId,
                converters.recipeSnapshotToJson(log.recipeSnapshot),
                JSONArray(converters.actualUsesToJson(log.actualUses)).apply {
                    getJSONObject(0).put("displayName", "김치")
                }.toString(),
                converters.remainingToJson(log.remainingPantry),
                log.completedAtEpochMillis,
            ),
        )
    }

    private fun storedSnapshot(id: String): String = database.openHelper.readableDatabase.query(
        "SELECT recipeSnapshot FROM meal_logs WHERE id = ?",
        arrayOf(id),
    ).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getString(0)
    }

    private fun stepsWithKind(
        values: List<String>,
        metadata: RecipePreferenceMetadata?,
        kind: String,
    ): RecipeSteps = try {
        val kindClass = Class.forName("com.junited31.leftovers.data.RecipeKind")
        val recipeKind = requireNotNull(kindClass.enumConstants).single { (it as Enum<*>).name == kind }
        RecipeSteps::class.java.constructors.single { it.parameterCount == 3 }
            .newInstance(values, metadata, recipeKind) as RecipeSteps
    } catch (error: Throwable) {
        throw AssertionError("RecipeSteps must require RecipeKind", error)
    }

    private fun log(id: String, completedAt: Long, photoPath: String?) = MealLogEntity(
        id = id,
        cookSessionId = "session-$id",
        recipeSnapshot = RecipeSnapshotRecord(
            id = "recipe-$id",
            title = "김치볶음밥 $id",
            pantryBindings = PantryBindings(listOf(PantryBinding(pantryId, 1, PantryUnit.GRAM, 5_000))),
            steps = RecipeSteps(
                listOf("재료를 볶아요", "밥을 넣어요"),
                RecipePreferenceMetadata("Korean", "stir-fry", setOf("김치", "밥")),
                com.junited31.leftovers.data.RecipeKind.MEAL,
            ),
            createdAtEpochMillis = 500,
            feedback = MealFeedback(
                rating = 5,
                notes = "다음에는 덜 짜게",
                recommendAgain = false,
                measurementAdjustments = listOf(
                    MeasurementAdjustment("김치", 4_000, PantryUnit.GRAM, "한 숟갈 적게", completedAt),
                ),
                finalPhotoPath = photoPath,
            ),
        ),
        actualUses = ActualPantryUses(listOf(ActualPantryUse(pantryId, 1, PantryUnit.GRAM, 5_000))),
        remainingPantry = PantryRemainingSnapshots(listOf(PantryRemaining(pantryId, PantryUnit.GRAM, 7_000, 2))),
        completedAtEpochMillis = completedAt,
    )

    private fun capture(name: String, xml: Boolean = false) {
        compose.waitForIdle()
        if (xml) {
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
            shell("cp ${file.absolutePath} /sdcard/Download/leftovers-expansion-task-6-$name")
        } else {
            shell("screencap -p /sdcard/Download/leftovers-expansion-task-6-$name")
        }
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

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }
}
