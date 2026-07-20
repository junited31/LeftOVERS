package com.junited31.leftovers.e2e

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsDisplayed
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
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.junited31.leftovers.BuildConfig
import com.junited31.leftovers.DebugApplication
import com.junited31.leftovers.MainActivity
import com.junited31.leftovers.data.LeftoversDatabase
import com.junited31.leftovers.data.LeftoversPreferenceKeys
import com.junited31.leftovers.data.PantryItemEntity
import com.junited31.leftovers.data.PantryItemId
import com.junited31.leftovers.data.PantryUnit
import com.junited31.leftovers.data.leftoversDataStore
import com.junited31.leftovers.photo.PhotoLifecycle
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URI
import java.time.LocalDate
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RealCloudE2eTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val application = context as DebugApplication
    private val database = LeftoversDatabase.get(context)
    private val photos = PhotoLifecycle(context)
    private val auth = FirebaseAuth.getInstance()
    private var scenario: ActivityScenario<MainActivity>? = null
    private var fixture: Uri? = null
    private var networkDisabled = false

    @Before
    fun setUp() = runBlocking {
        assertTrue(BuildConfig.LEFTOVERS_API_BASE_URL.startsWith("https://"))
        application.recipeApiOverride = null
        application.photoPickerFixtureOverride = null
        auth.signOut()
        database.clearAllTables()
        context.leftoversDataStore.edit { it.clear() }
        photos.ownedCacheFiles().forEach(File::delete)
        photos.retainedFinalPhotos().forEach(File::delete)
        database.pantryDao().insertAll(pantry())
        context.leftoversDataStore.edit {
            it[LeftoversPreferenceKeys.EQUIPMENT_IDS] = setOf(
                "gas_burner",
                "basic_cookware",
                "microwave",
            )
        }
        Unit
    }

    @After
    fun tearDown() {
        scenario?.close()
        application.recipeApiOverride = null
        application.photoPickerFixtureOverride = null
        fixture?.let { context.contentResolver.delete(it, null, null) }
        restoreNetwork()
        auth.currentUser?.let { user ->
            runCatching { Tasks.await(user.delete(), 15, TimeUnit.SECONDS) }
        }
        auth.signOut()
        runBlocking { database.clearAllTables() }
        photos.ownedCacheFiles().forEach(File::delete)
        photos.retainedFinalPhotos().forEach(File::delete)
    }

    @Test
    fun realFirebaseCloudRunRecipesAdviceCompletionAndOfflineHistory() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithTag("nav-recipes").performClick()
        compose.onNodeWithTag("generate-recipes").performClick()
        compose.waitUntil(180_000) {
            compose.onAllNodesWithTag("recipe-card").fetchSemanticsNodes().size == 3
        }
        val recipeCount = compose.onAllNodesWithTag("recipe-card").fetchSemanticsNodes().size
        assertEquals(3, recipeCount)
        assertTrue(auth.currentUser?.isAnonymous == true)

        compose.onNodeWithTag("save-recipe-0").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            runBlocking { database.cookSessionDao().active() != null }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("cooking-screen").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("cooking-screen").assertIsDisplayed()
        val active = checkNotNull(runBlocking { database.cookSessionDao().active() })
        val snapshot = checkNotNull(runBlocking { database.recipeSnapshotDao().get(active.recipeSnapshotId) })

        fixture = foodPhotoFixture()
        application.photoPickerFixtureOverride = fixture
        compose.onNodeWithTag("attach-gallery").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithTag("request-advice").assertIsDisplayed() }.isSuccess
        }
        compose.onNodeWithTag("request-advice").performScrollTo().performClick()
        compose.waitUntil(180_000) {
            compose.onAllNodesWithTag("advice-card").fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag("advice-card").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("안전 안내").performScrollTo().assertIsDisplayed()

        for (expectedIndex in 1 until snapshot.steps.values.size) {
            compose.onNodeWithTag("next-step").performScrollTo().performClick()
            compose.waitUntil(10_000) {
                runBlocking { database.cookSessionDao().active()?.currentStepIndex == expectedIndex }
            }
        }
        compose.onNodeWithTag("start-completion").performScrollTo().performClick()
        compose.onNodeWithTag("rating-5").performScrollTo().performClick()
        compose.onNodeWithTag("complete-meal").performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { database.mealLogDao().count() } == 1 }
        compose.onNodeWithTag("completion-success").assertIsDisplayed()

        val log = checkNotNull(runBlocking { database.mealLogDao().latest() }.single())
        disableNetwork()
        compose.waitUntil(10_000) { !isOnline() }
        scenario?.recreate()
        compose.onNodeWithTag("nav-history").performClick()
        compose.onNodeWithTag("history-screen").assertIsDisplayed()
        compose.onNodeWithText(log.recipeSnapshot.title).assertIsDisplayed()
        compose.waitForIdle()

        capture("task-11-cloud.png")
        writeEvidence(
            "task-11-cloud.json",
            JSONObject()
                .put("deviceModel", Build.MODEL)
                .put("apiHost", URI(BuildConfig.LEFTOVERS_API_BASE_URL).host)
                .put("firebaseAnonymousAuth", true)
                .put("recipeCount", 3)
                .put("photoInputPixels", "256x256")
                .put("photoAdviceSchemaRendered", true)
                .put("safetyGuidanceRendered", true)
                .put("mealLogCount", 1)
                .put("offlineBeforeHistoryRead", true)
                .put("historyTitleRendered", true)
                .put("temporaryPhotoCacheCount", photos.ownedCacheFiles().size)
                .toString(2),
        )
    }

    private fun disableNetwork() {
        shell("svc wifi disable")
        shell("svc data disable")
        networkDisabled = true
    }

    private fun restoreNetwork() {
        if (!networkDisabled) return
        shell("svc wifi enable")
        shell("svc data enable")
        networkDisabled = false
    }

    private fun isOnline(): Boolean = context.getSystemService(ConnectivityManager::class.java)
        .activeNetwork != null

    private fun foodPhotoFixture() = checkNotNull(
        context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "leftovers-task-11-food.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            },
        ),
    ).also { uri ->
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(Color.rgb(236, 220, 186))
        paint.color = Color.rgb(50, 112, 61)
        canvas.drawCircle(128f, 128f, 103f, paint)
        paint.color = Color.rgb(238, 197, 91)
        canvas.drawCircle(104f, 119f, 54f, paint)
        paint.color = Color.rgb(164, 73, 45)
        canvas.drawCircle(157f, 146f, 42f, paint)
        context.contentResolver.openOutputStream(uri).use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, checkNotNull(output))
        }
        bitmap.recycle()
    }

    private fun capture(name: String) {
        shell("screencap -p /sdcard/Download/$name")
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
        item("00000000-0000-4000-8000-000000001101", "cooked rice", 900_000, PantryUnit.GRAM, 1),
        item("00000000-0000-4000-8000-000000001102", "eggs", 8_000, PantryUnit.COUNT, 2),
        item("00000000-0000-4000-8000-000000001103", "spinach", 300_000, PantryUnit.GRAM, 3),
        item("00000000-0000-4000-8000-000000001104", "tofu", 500_000, PantryUnit.GRAM, 4),
        item("00000000-0000-4000-8000-000000001105", "kimchi", 350_000, PantryUnit.GRAM, 5),
    )

    private fun item(id: String, name: String, amount: Long, unit: PantryUnit, version: Int) = PantryItemEntity(
        id = requireNotNull(PantryItemId.parse(id)),
        name = name,
        quantityMilliUnits = amount,
        unit = unit,
        expiryEpochDay = LocalDate.now().plusDays(version.toLong()).toEpochDay(),
        version = version,
    )
}
