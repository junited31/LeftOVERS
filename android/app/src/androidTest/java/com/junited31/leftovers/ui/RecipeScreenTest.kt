package com.junited31.leftovers.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.util.Xml
import android.view.accessibility.AccessibilityNodeInfo
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.datastore.preferences.core.edit
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.junited31.leftovers.DebugApplication
import com.junited31.leftovers.MainActivity
import com.junited31.leftovers.data.LeftoversDatabase
import com.junited31.leftovers.data.LeftoversPreferenceKeys
import com.junited31.leftovers.data.ActualPantryUse
import com.junited31.leftovers.data.ActualPantryUses
import com.junited31.leftovers.data.CompleteCookSessionCommand
import com.junited31.leftovers.data.CompletionResult
import com.junited31.leftovers.data.MealFeedback
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneOffset
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

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
        compose.runOnUiThread {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
        }
        database.clearAllTables()
        context.leftoversDataStore.edit { it.clear() }
        database.pantryDao().insertAll(pantry())
        context.leftoversDataStore.edit {
            it[LeftoversPreferenceKeys.EQUIPMENT_IDS] = setOf("gas_burner", "basic_cookware")
            it[LeftoversPreferenceKeys.ONBOARDING_COMPLETE] = true
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
        application.recipeApiOverride = null
        server.shutdown()
        compose.runOnUiThread {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
        }
        compose.waitUntil(5_000) {
            AppCompatDelegate.getApplicationLocales().toLanguageTags() == "en"
        }
        scenario?.close()
    }

    @Test
    fun production_recipe_screen_displays_exactly_three_complete_ranked_cards_and_saves_snapshot() {
        // Given
        server.enqueue(MockResponse().setResponseCode(200).setBody(validResponse()))
        launchRecipes()
        compose.onNodeWithTag("recipe-kind-drink").performClick()

        // When
        compose.onNodeWithTag("generate-recipes").performClick()
        val request = JSONObject(requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8())
        assertEquals("en", request.getString("locale"))
        assertEquals("drink", request.getString("recipeKind"))
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("recipe-card").fetchSemanticsNodes().size == 3 }

        // Then
        val recipeCards = compose.onAllNodesWithTag("recipe-card")
        recipeCards.assertCountEquals(3)
        val cardContent = recipeCards.fetchSemanticsNodes().map(::semanticsText)
        assertEquals(2, cardContent.count { "Equipment: Gas stove" in it })
        assertTrue(cardContent.all { content ->
            listOf("Why this recipe", "Ingredient use 67%", "Preference match 50%", "Novelty 100%").all(content::contains)
        })
        assertEquals(1, cardContent.count { "Expiration priority 80%" in it })
        assertEquals(2, cardContent.count { "Expiration priority 60%" in it })
        compose.onNodeWithText("Egg fried rice").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Ingredients used:\nRice 300 g\nEggs 2 pcs").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Missing ingredients: Salt 1 g").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Rice omelette").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Recipes").assertExists()
        captureEvidence("drink-results")
        compose.onNodeWithTag("save-recipe-0").performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking { database.recipeSnapshotDao().count() } == 1 }
        val snapshot = requireNotNull(runBlocking { database.recipeSnapshotDao().get(
            database.recipeSnapshotDao().getAllIds().single(),
        ) })
        assertEquals("Crispy spinach rice", snapshot.title)
        assertEquals("drink", snapshot.steps.recipeKind.value)
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("selected-recipe-kind").assertIsDisplayed() }.isSuccess
        }
        compose.onNodeWithTag("selected-recipe-kind").assertTextEquals("Drink")
        compose.waitUntil(5_000) { runBlocking { database.cookSessionDao().active() } != null }
        val session = requireNotNull(runBlocking { database.cookSessionDao().active() })
        val completion = runBlocking {
            database.inventoryCompletionDao().complete(
                CompleteCookSessionCommand(
                    cookSessionId = session.id,
                    mealLogId = "task-5-drink",
                    completedAtEpochMillis = System.currentTimeMillis(),
                    actualUses = ActualPantryUses(snapshot.pantryBindings.values.map { binding ->
                        ActualPantryUse(
                            binding.pantryItemId,
                            binding.sourceVersion,
                            binding.unit,
                            binding.proposedMilliUnits,
                        )
                    }),
                    feedback = MealFeedback(),
                ),
            )
        }
        assertTrue(completion is CompletionResult.Success)
        compose.onNodeWithTag("nav-history").performClick()
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("history-row-task-5-drink").assertIsDisplayed() }.isSuccess
        }
        compose.onNodeWithTag("history-row-task-5-drink").performClick()
        compose.onNodeWithTag("history-recipe-kind").assertTextEquals("Drink")
        captureEvidence("history-drink")
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
        compose.onNodeWithTag("recipe-kind-drink").performClick()

        // When
        compose.onNodeWithTag("generate-recipes").performClick()
        compose.onNodeWithText("Couldn’t create three distinct valid recipes.").assertIsDisplayed()

        // Then
        compose.onAllNodesWithTag("recipe-card").assertCountEquals(0)
        assertEquals(before, runBlocking { database.recipeSnapshotDao().count() })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun generateRequiresKindAndDisabledClickRaceSendsNoRequest() {
        launchRecipes()
        captureEvidence("kind-unselected")

        compose.onNodeWithTag("generate-recipes").assertIsNotEnabled().performClick()

        assertEquals(0, server.requestCount)
        compose.onAllNodesWithTag("recipe-card").assertCountEquals(0)
    }

    @Test
    fun exactlyFourKindControlsAreMutuallyExclusive() {
        launchRecipes()

        compose.onAllNodesWithTag("recipe-kind-control").assertCountEquals(4)
        compose.onNodeWithTag("recipe-kind-meal").performClick().assertIsSelected()
        compose.onNodeWithTag("recipe-kind-drink").assertIsNotSelected()
        compose.onNodeWithTag("recipe-kind-snack").performClick().assertIsSelected()
        compose.onNodeWithTag("recipe-kind-meal").assertIsNotSelected()
        compose.onNodeWithTag("recipe-kind-drink").assertIsNotSelected()
        compose.onNodeWithTag("recipe-kind-dessert").assertIsNotSelected()
    }

    @Test
    fun requestLocaleNormalizesAcrossActivityRestartAndKeepsCanonicalKindWire() {
        launchRecipes()
        setLocale("ko")
        scenario?.recreate()
        compose.onNodeWithTag("nav-recipes").performClick()
        compose.onNodeWithText("디저트").assertIsDisplayed()
        compose.onNodeWithTag("recipe-kind-dessert").performClick()
        server.enqueue(MockResponse().setResponseCode(200).setBody(validResponse("dessert")))
        compose.onNodeWithTag("generate-recipes").performClick()
        val korean = JSONObject(requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8())
        assertEquals("ko", korean.getString("locale"))
        assertEquals("dessert", korean.getString("recipeKind"))
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("recipe-card").fetchSemanticsNodes().size == 3 }
        captureEvidence("dessert-ko")

        setLocale("ja")
        scenario?.recreate()
        compose.onNodeWithTag("nav-recipes").performClick()
        compose.onNodeWithTag("recipe-kind-meal").performClick()
        server.enqueue(MockResponse().setResponseCode(200).setBody(validResponse("meal")))
        compose.onNodeWithTag("generate-recipes").performClick()
        val fallback = JSONObject(requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8())
        assertEquals("en", fallback.getString("locale"))
        assertEquals("meal", fallback.getString("recipeKind"))
    }

    @Test
    fun invalidNetworkKindSetsShowTypedErrorAndNeverPersistSnapshots() {
        launchRecipes()
        compose.onNodeWithTag("recipe-kind-drink").performClick()
        val cases = listOf(
            JSONObject(validResponse("drink")).apply {
                getJSONArray("recipes").getJSONObject(0).remove("recipeKind")
            },
            JSONObject(validResponse("drink")).apply {
                getJSONArray("recipes").getJSONObject(0).put("recipeKind", "Meal")
            },
            JSONObject(validResponse("drink")).apply {
                getJSONArray("recipes").getJSONObject(0).put("recipeKind", "unknown")
            },
            JSONObject(validResponse("drink")).apply {
                getJSONArray("recipes").getJSONObject(0).put("recipeKind", "snack")
            },
        )

        cases.forEachIndexed { index, body ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(body.toString()))
            compose.onNodeWithTag("generate-recipes").performClick()
            compose.waitUntil(5_000) { server.requestCount == index + 1 }
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithTag("recipe-error").assertIsDisplayed() }.isSuccess
            }
            compose.onNodeWithTag("recipe-error").assertIsDisplayed()
            compose.onAllNodesWithTag("recipe-card").assertCountEquals(0)
            assertEquals(0, runBlocking { database.recipeSnapshotDao().count() })
            assertEquals(null, runBlocking { database.cookSessionDao().active() })
            assertEquals(0, runBlocking { database.mealLogDao().count() })
        }
    }

    private fun launchRecipes() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario?.recreate()
        compose.waitForIdle()
        compose.onNodeWithTag("nav-recipes").performClick()
        compose.onNodeWithTag("generate-recipes").assertIsDisplayed()
        compose.onNodeWithText("Generate recipes").assertIsDisplayed()
    }

    private fun setLocale(languageTag: String) {
        compose.runOnUiThread {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTag))
        }
        compose.waitUntil(5_000) {
            AppCompatDelegate.getApplicationLocales().toLanguageTags() == languageTag
        }
    }

    private fun captureEvidence(stem: String) {
        val directory = requireNotNull(context.getExternalFilesDir(null))
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val appScreenshot = File(directory, "task-5-$stem.png")
        FileOutputStream(appScreenshot).use { output ->
            automation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        shell(automation.executeShellCommand(
            "cp ${appScreenshot.absolutePath} /sdcard/Download/task-5-$stem.png",
        ))
        val appXml = File(directory, "task-5-$stem.xml")
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
            "cp ${appXml.absolutePath} /sdcard/Download/task-5-$stem.xml",
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

    private fun semanticsText(node: SemanticsNode): String = buildList {
        runCatching { node.config[SemanticsProperties.Text] }
            .getOrDefault(emptyList())
            .mapTo(this) { it.text }
        node.children.forEach { child -> add(semanticsText(child)) }
    }.filter(String::isNotBlank).joinToString("\n")

    private fun pantry() = listOf(
        item("00000000-0000-0000-0000-000000000401", "Rice", 900_000, PantryUnit.GRAM, 1, 1),
        item("00000000-0000-0000-0000-000000000402", "Spinach", 300_000, PantryUnit.GRAM, 2, 2),
        item("00000000-0000-0000-0000-000000000403", "Eggs", 6_000, PantryUnit.COUNT, 3, 4),
    )

    private fun item(
        id: String,
        name: String,
        amount: Long,
        unit: PantryUnit,
        version: Int,
        daysUntilExpiry: Long,
    ) =
        PantryItemEntity(
            requireNotNull(PantryItemId.parse(id)),
            name,
            amount,
            unit,
            LocalDate.now(ZoneOffset.UTC).plusDays(daysUntilExpiry).toEpochDay(),
            version,
        )

    private fun validResponse(kind: String = "drink") = """
        {"recipes":[
          {"recipeKind":"$kind","title":"Egg fried rice","cuisine":"Korean","primaryTechnique":"stir fry","requiredEquipment":["gas burner"],"trackedUses":[{"pantryItemId":"00000000-0000-0000-0000-000000000401","version":1,"unit":"g","proposedMilliUnits":300000},{"pantryItemId":"00000000-0000-0000-0000-000000000403","version":3,"unit":"count","proposedMilliUnits":2000}],"missingIngredients":[],"steps":["Cook rice","Add eggs"]},
          {"recipeKind":"$kind","title":"Rice omelette","cuisine":"Japanese","primaryTechnique":"pan fry","requiredEquipment":["gas burner"],"trackedUses":[{"pantryItemId":"00000000-0000-0000-0000-000000000401","version":1,"unit":"g","proposedMilliUnits":200000},{"pantryItemId":"00000000-0000-0000-0000-000000000403","version":3,"unit":"count","proposedMilliUnits":2000}],"missingIngredients":[{"name":"Salt","amountMilliUnits":1000,"unit":"g"}],"steps":["Beat eggs","Fold rice"]},
          {"recipeKind":"$kind","title":"Crispy spinach rice","cuisine":"Korean","primaryTechnique":"bake","requiredEquipment":["basic cookware"],"trackedUses":[{"pantryItemId":"00000000-0000-0000-0000-000000000401","version":1,"unit":"g","proposedMilliUnits":250000},{"pantryItemId":"00000000-0000-0000-0000-000000000402","version":2,"unit":"g","proposedMilliUnits":100000}],"missingIngredients":[],"steps":["Mix","Bake"]}
        ]}
    """.trimIndent()
}
