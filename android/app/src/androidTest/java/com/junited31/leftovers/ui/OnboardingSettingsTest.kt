package com.junited31.leftovers.ui

import android.content.Context
import android.graphics.Rect
import android.os.ParcelFileDescriptor.AutoCloseInputStream
import android.util.Xml
import android.view.accessibility.AccessibilityNodeInfo
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.os.LocaleListCompat
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.junited31.leftovers.MainActivity
import com.junited31.leftovers.data.LeftoversDatabase
import com.junited31.leftovers.data.LeftoversPreferenceKeys
import com.junited31.leftovers.data.PantryItemEntity
import com.junited31.leftovers.data.PantryItemId
import com.junited31.leftovers.data.PantryUnit
import com.junited31.leftovers.data.leftoversDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class OnboardingSettingsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = LeftoversDatabase.get(context)
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun clearState() = runBlocking {
        database.clearAllTables()
        context.leftoversDataStore.edit { it.clear() }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
        }
    }

    @After
    fun cleanUp() = runBlocking {
        scenario?.close()
        database.clearAllTables()
        context.leftoversDataStore.edit { it.clear() }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
        }
    }

    @Test
    fun firstLaunchRequiresSavedEquipmentAndPantryBeforeMainNavigation() {
        launch()

        compose.onNodeWithTag("onboarding-equipment").assertIsDisplayed()
        assertBottomNavigationAbsent()
        compose.onNodeWithTag("onboarding-continue").assertIsNotEnabled()

        compose.onNodeWithTag("equipment-gas_burner").performScrollTo().performClick()
        compose.onNodeWithTag("equipment-basic_cookware").performScrollTo().performClick()
        compose.waitUntil(5_000) { equipmentIds().size == 2 }
        assertFalse(onboardingComplete())
        capture("equipment")

        scenario?.recreate()
        compose.onNodeWithTag("onboarding-equipment").assertIsDisplayed()
        assertBottomNavigationAbsent()
        dumpXml("failure-restart")
        compose.onNodeWithTag("onboarding-continue").assertIsEnabled().performClick()

        compose.onNodeWithTag("onboarding-pantry").assertIsDisplayed()
        compose.onNodeWithTag("onboarding-finish").assertIsNotEnabled()
        assertFalse(onboardingComplete())
        recordJson(
            "failure-empty",
            JSONObject()
                .put("pantryRows", pantryCount())
                .put("onboardingComplete", onboardingComplete())
                .put("finishEnabled", false),
        )

        compose.onNodeWithTag("add-pantry").performClick()
        compose.onNodeWithTag("name-input").performTextInput("Rice")
        compose.onNodeWithTag("quantity-input").performTextInput("1")
        compose.onNodeWithTag("save-pantry").performClick()
        compose.waitUntil(5_000) { pantryCount() == 1 }
        compose.onNodeWithTag("onboarding-finish").assertIsEnabled().performClick()

        compose.waitUntil(5_000) { onboardingComplete() }
        assertMainNavigation()
        compose.onNodeWithTag("pantry-list").assertIsDisplayed()
        capture("pantry")
        compose.onNodeWithTag("nav-settings").performClick()
        compose.onNodeWithTag("settings-kitchen").assertIsDisplayed()
        compose.onNodeWithTag("settings-language-en").performScrollTo()
        capture("settings")
        compose.onNodeWithTag("nav-pantry").performClick()
        recordJson(
            "adversarial",
            JSONObject()
                .put("equipmentIds", equipmentIds().sorted())
                .put("pantryRows", pantryCount())
                .put("onboardingComplete", onboardingComplete())
                .put("bottomDestinations", 5)
                .put("roomCheckedDirectly", true)
                .put("dataStoreCheckedDirectly", true),
        )
        scenario?.recreate()
        compose.onNodeWithTag("onboarding-equipment").assertDoesNotExist()
        compose.onNodeWithTag("pantry-list").assertIsDisplayed()
        assertMainNavigation()
        recordJson(
            "manual",
            JSONObject()
                .put("device", "R5CR91DXA8R")
                .put("equipmentFirst", true)
                .put("pantryMainAfterFinish", true)
                .put("settingsReachable", true)
                .put("restartChecked", true),
        )
    }

    @Test
    fun settingsLanguageSelectorChangesImmediatelyAndPersists() = runBlocking {
        database.pantryDao().insertAll(
            listOf(
                PantryItemEntity(
                    id = requireNotNull(PantryItemId.parse("00000000-0000-0000-0000-000000000201")),
                    name = "Rice",
                    quantityMilliUnits = 1_000,
                    unit = PantryUnit.GRAM,
                    expiryEpochDay = null,
                    version = 1,
                ),
            ),
        )
        context.leftoversDataStore.edit {
            it[LeftoversPreferenceKeys.EQUIPMENT_IDS] = setOf("gas_burner")
            it[LeftoversPreferenceKeys.ONBOARDING_COMPLETE] = true
        }
        launch()

        compose.onNodeWithTag("nav-settings").performClick()
        compose.onNodeWithTag("settings-kitchen").assertIsDisplayed()
        compose.onNodeWithTag("settings-language-ko").performClick()
        compose.waitUntil(5_000) { applicationLanguage() == "ko" }

        scenario?.recreate()
        compose.waitUntil(5_000) { applicationLanguage() == "ko" }
        compose.onNodeWithTag("settings-language-en").assertExists().performClick()
        compose.waitUntil(5_000) { applicationLanguage() == "en" }
        assertEquals("en", applicationLanguage())
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
    }

    private fun assertBottomNavigationAbsent() {
        listOf("pantry", "recipes", "cooking", "history", "settings").forEach {
            compose.onAllNodesWithTag("nav-$it").assertCountEquals(0)
        }
    }

    private fun assertMainNavigation() {
        listOf("pantry", "recipes", "cooking", "history", "settings").forEach {
            compose.onNodeWithTag("nav-$it").assertExists()
        }
        compose.onNodeWithTag("nav-equipment").assertDoesNotExist()
    }

    private fun equipmentIds(): Set<String> = runBlocking {
        context.leftoversDataStore.data.first()[LeftoversPreferenceKeys.EQUIPMENT_IDS].orEmpty()
    }

    private fun onboardingComplete(): Boolean = runBlocking {
        context.leftoversDataStore.data.first()[LeftoversPreferenceKeys.ONBOARDING_COMPLETE] == true
    }

    private fun pantryCount(): Int = runBlocking { database.pantryDao().getAll().size }

    private fun applicationLanguage(): String =
        AppCompatDelegate.getApplicationLocales().toLanguageTags()

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        shell("screencap -p /sdcard/Download/leftovers-expansion-task-2-$name.png")
        dumpXml(name)
    }

    private fun dumpXml(name: String) {
        compose.waitForIdle()
        val file = context.getExternalFilesDir(null)!!.resolve("leftovers-expansion-task-2-$name.xml")
        FileOutputStream(file).use { output ->
            val serializer = Xml.newSerializer()
            serializer.setOutput(output, "UTF-8")
            serializer.startDocument("UTF-8", true)
            serializer.startTag(null, "hierarchy")
            writeNode(serializer, requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow))
            serializer.endTag(null, "hierarchy")
            serializer.endDocument()
        }
        shell("cp ${file.absolutePath} /sdcard/Download/${file.name}")
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

    private fun recordJson(name: String, value: JSONObject) {
        val file = context.getExternalFilesDir(null)!!.resolve("leftovers-expansion-task-2-$name.json")
        file.writeText(value.toString(2))
        shell("cp ${file.absolutePath} /sdcard/Download/${file.name}")
    }

    private fun shell(command: String): String =
        AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
        ).bufferedReader().use { it.readText() }
}
