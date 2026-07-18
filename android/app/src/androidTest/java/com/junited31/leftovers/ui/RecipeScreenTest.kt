package com.junited31.leftovers.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.util.Xml
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.junited31.leftovers.DebugApplication
import com.junited31.leftovers.MainActivity
import com.junited31.leftovers.data.LeftoversDatabase
import com.junited31.leftovers.data.LeftoversPreferenceKeys
import com.junited31.leftovers.data.PantryItemEntity
import com.junited31.leftovers.data.PantryItemId
import com.junited31.leftovers.data.PantryUnit
import com.junited31.leftovers.data.leftoversDataStore
import com.junited31.leftovers.network.LeftoversApi
import com.junited31.leftovers.network.TokenProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class RecipeScreenTest {
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
        context.leftoversDataStore.edit { it.clear() }
        database.pantryDao().insertAll(pantry())
        context.leftoversDataStore.edit {
            it[LeftoversPreferenceKeys.EQUIPMENT_IDS] = setOf("gas_burner", "basic_cookware")
        }
        server = MockWebServer()
        server.start()
        application.recipeApiOverride = LeftoversApi(
            server.url("/").toString(),
            TokenProvider { "device-test-token" },
        )
    }

    @After
    fun tearDown() {
        scenario?.close()
        application.recipeApiOverride = null
        server.shutdown()
    }

    @Test
    fun production_recipe_screen_displays_exactly_three_complete_ranked_cards_and_saves_snapshot() {
        // Given
        server.enqueue(MockResponse().setResponseCode(200).setBody(validResponse()))
        launchRecipes()

        // When
        compose.onNodeWithTag("generate-recipes").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("recipe-card").fetchSemanticsNodes().size == 3 }

        // Then
        compose.onAllNodesWithTag("recipe-card").assertCountEquals(3)
        compose.onNodeWithText("Egg fried rice").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Uses:\nRice 300 g\nEggs 2 count").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Equipment: Gas burner")[0].performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Why this ranks")[0].performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Missing: Salt 1 g").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Rice omelette").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Why this ranks")[1].performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Coverage 67%")[1].performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Expiry 67%")[1].performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Preference 50%")[1].performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Novelty 100%")[1].performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Recipes").assertExists()
        captureRecipeEvidence()
        compose.onNodeWithTag("save-recipe-0").performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking { database.recipeSnapshotDao().count() } == 1 }
        assertEquals("Crispy spinach rice", runBlocking { database.recipeSnapshotDao().get(
            database.recipeSnapshotDao().getAllIds().single(),
        )?.title })
    }

    @Test
    fun typed_diversity_failure_shows_no_partial_cards_and_preserves_snapshot_count() {
        // Given
        server.enqueue(
            MockResponse().setResponseCode(422)
                .setBody("""{"error":{"code":"model_validation_failed","message":"invalid"}}"""),
        )
        launchRecipes()
        val before = runBlocking { database.recipeSnapshotDao().count() }

        // When
        compose.onNodeWithTag("generate-recipes").performClick()
        compose.onNodeWithText("Could not produce three valid, diverse recipes").assertIsDisplayed()

        // Then
        compose.onAllNodesWithTag("recipe-card").assertCountEquals(0)
        assertEquals(before, runBlocking { database.recipeSnapshotDao().count() })
        assertEquals(1, server.requestCount)
    }

    private fun launchRecipes() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithTag("nav-recipes").performClick()
        compose.onNodeWithTag("generate-recipes").assertIsDisplayed()
    }

    private fun captureRecipeEvidence() {
        val directory = requireNotNull(context.getExternalFilesDir(null))
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val appScreenshot = File(directory, "task-6-recipes.png")
        FileOutputStream(appScreenshot).use { output ->
            automation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        shell(automation.executeShellCommand(
            "cp ${appScreenshot.absolutePath} /sdcard/Download/task-6-recipes.png",
        ))
        val appXml = File(directory, "task-6-recipes.xml")
        FileOutputStream(appXml).use { output ->
            val serializer = Xml.newSerializer()
            serializer.setOutput(output, "UTF-8")
            serializer.startDocument("UTF-8", true)
            serializer.startTag(null, "hierarchy")
            writeNode(serializer, requireNotNull(automation.rootInActiveWindow))
            serializer.endTag(null, "hierarchy")
            serializer.endDocument()
        }
        shell(automation.executeShellCommand(
            "cp ${appXml.absolutePath} /sdcard/Download/task-6-recipes.xml",
        ))
    }

    private fun shell(descriptor: ParcelFileDescriptor) =
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }

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

    private fun pantry() = listOf(
        item("00000000-0000-0000-0000-000000000401", "Rice", 900_000, PantryUnit.GRAM, 1),
        item("00000000-0000-0000-0000-000000000402", "Spinach", 300_000, PantryUnit.GRAM, 2),
        item("00000000-0000-0000-0000-000000000403", "Eggs", 6_000, PantryUnit.COUNT, 3),
    )

    private fun item(id: String, name: String, amount: Long, unit: PantryUnit, version: Int) =
        PantryItemEntity(
            requireNotNull(PantryItemId.parse(id)),
            name,
            amount,
            unit,
            LocalDate.now().plusDays(version.toLong()).toEpochDay(),
            version,
        )

    private fun validResponse() = """
        {"recipes":[
          {"title":"Egg fried rice","cuisine":"Korean","primaryTechnique":"stir fry","requiredEquipment":["gas burner"],"trackedUses":[{"pantryItemId":"00000000-0000-0000-0000-000000000401","version":1,"unit":"g","proposedMilliUnits":300000},{"pantryItemId":"00000000-0000-0000-0000-000000000403","version":3,"unit":"count","proposedMilliUnits":2000}],"missingIngredients":[],"steps":["Cook rice","Add eggs"]},
          {"title":"Rice omelette","cuisine":"Japanese","primaryTechnique":"pan fry","requiredEquipment":["gas burner"],"trackedUses":[{"pantryItemId":"00000000-0000-0000-0000-000000000401","version":1,"unit":"g","proposedMilliUnits":200000},{"pantryItemId":"00000000-0000-0000-0000-000000000403","version":3,"unit":"count","proposedMilliUnits":2000}],"missingIngredients":[{"name":"Salt","amountMilliUnits":1000,"unit":"g"}],"steps":["Beat eggs","Fold rice"]},
          {"title":"Crispy spinach rice","cuisine":"Korean","primaryTechnique":"bake","requiredEquipment":["basic cookware"],"trackedUses":[{"pantryItemId":"00000000-0000-0000-0000-000000000401","version":1,"unit":"g","proposedMilliUnits":250000},{"pantryItemId":"00000000-0000-0000-0000-000000000402","version":2,"unit":"g","proposedMilliUnits":100000}],"missingIngredients":[],"steps":["Mix","Bake"]}
        ]}
    """.trimIndent()
}
