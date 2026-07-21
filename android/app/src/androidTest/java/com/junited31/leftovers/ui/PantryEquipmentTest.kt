package com.junited31.leftovers.ui

import android.database.sqlite.SQLiteDatabase
import android.graphics.Rect
import android.os.ParcelFileDescriptor.AutoCloseInputStream
import android.util.Xml
import android.view.accessibility.AccessibilityNodeInfo
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.core.os.LocaleListCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.junited31.leftovers.MainActivity
import com.junited31.leftovers.data.LeftoversDatabase
import com.junited31.leftovers.data.LeftoversPreferenceKeys
import com.junited31.leftovers.data.PantryItemEntity
import com.junited31.leftovers.data.PantryItemId
import com.junited31.leftovers.data.PantryUnit
import com.junited31.leftovers.data.leftoversDataStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class PantryEquipmentTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun resetDebugState() {
        compose.runOnUiThread {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ko"))
        }
        shell("am broadcast -a com.junited31.leftovers.DEBUG_RESET")
        runBlocking {
            InstrumentationRegistry.getInstrumentation().targetContext.leftoversDataStore.edit {
                it[LeftoversPreferenceKeys.ONBOARDING_COMPLETE] = true
            }
        }
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
    }

    @After
    fun restoreEnglish() {
        compose.runOnUiThread {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
        }
    }

    @Test
    fun blankNameShowsInlineErrorWithoutInsertingRoomRow() {
        val countBefore = pantryRowCount()

        openAddForm()
        compose.onNodeWithTag("quantity-input").performTextInput("1")
        compose.onNodeWithTag("save-pantry").performClick()

        compose.onNodeWithText("재료 이름을 입력해 주세요.").assertIsDisplayed()
        assertEquals(countBefore, pantryRowCount())
    }

    @Test
    fun zeroQuantityShowsInlineErrorWithoutInsertingRoomRow() {
        assertInvalidQuantity("0")
    }

    @Test
    fun negativeQuantityShowsInlineErrorWithoutInsertingRoomRow() {
        assertInvalidQuantity("-1")
    }

    @Test
    fun overThreeDecimalQuantityShowsInlineErrorWithoutInsertingRoomRow() {
        assertInvalidQuantity("1.0001")
    }

    @Test
    fun allStapleSuggestionsFillExactCanonicalIdAndMappedUnitWithoutWriting() {
        val expected = listOf(
            "eggs" to PantryUnit.COUNT,
            "milk" to PantryUnit.MILLILITER,
            "butter" to PantryUnit.GRAM,
            "cheese" to PantryUnit.GRAM,
            "onion" to PantryUnit.COUNT,
            "garlic" to PantryUnit.COUNT,
            "rice" to PantryUnit.GRAM,
            "bread" to PantryUnit.COUNT,
            "flour" to PantryUnit.GRAM,
            "sugar" to PantryUnit.GRAM,
            "salt" to PantryUnit.GRAM,
            "black pepper" to PantryUnit.GRAM,
            "cooking oil" to PantryUnit.MILLILITER,
            "tomato" to PantryUnit.COUNT,
            "potato" to PantryUnit.COUNT,
            "carrot" to PantryUnit.COUNT,
            "spinach" to PantryUnit.GRAM,
        )
        compose.runOnUiThread {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
        }
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithText("Ingredients available now").assertIsDisplayed() }.isSuccess
        }
        openAddForm()

        expected.forEach { (id, unit) ->
            selectSuggestion(id)
            assertInput("name-input", id)
            assertInput("quantity-input", "")
            compose.onNodeWithTag("unit-${unit.value}").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.Selected, true),
            )
            assertEquals(0, pantryRowCount())
            if (id == "onion") captureScreen("suggestions-en")
        }
    }

    @Test
    fun countFractionChipsWriteExactDecimalsAndAreAbsentForNonCount() {
        openAddForm()
        selectSuggestion("onion")

        listOf("0.25", "0.5", "1", "2").forEach { value ->
            compose.onNodeWithTag("fraction-$value").performScrollTo().performClick()
            assertInput("quantity-input", value)
        }

        selectSuggestion("milk")
        listOf("0.25", "0.5", "1", "2").forEach { value ->
            compose.onNodeWithTag("fraction-$value").assertDoesNotExist()
        }
    }

    @Test
    fun blankSuggestedQuantityDoesNotWriteAndFreeDecimalStillStoresExactly() {
        openAddForm()
        selectSuggestion("milk")
        captureScreen("failure-unit")
        assertEquals(0, pantryRowCount())

        compose.onNodeWithTag("save-pantry").performScrollTo().performClick()
        compose.onNodeWithText("수량은 0보다 큰 값으로 소수점 셋째 자리까지 입력해 주세요.")
            .assertIsDisplayed()
        assertEquals(0, pantryRowCount())

        compose.onNodeWithTag("quantity-input").performScrollTo().performTextInput("0.375")
        compose.onNodeWithTag("save-pantry").performScrollTo().performClick()
        compose.waitUntil(5_000) { pantryRowCount() == 1 }
        assertEquals(PantryRow("milk", 375, "ml"), pantryRows().single())
    }

    @Test
    fun suggestedQuarterAndHalfSurviveEditRecreationAndTypedCanonicalLocalizesTheSame() {
        openAddForm()
        selectSuggestion("onion")
        compose.onNodeWithTag("fraction-0.25").performScrollTo().performClick()
        compose.onNodeWithTag("save-pantry").performScrollTo().performClick()
        compose.waitUntil(5_000) { pantryRowCount() == 1 }
        assertEquals(PantryRow("onion", 250, "count"), pantryRows().single())

        compose.onNodeWithContentDescription("양파 수정").performClick()
        compose.onNodeWithTag("fraction-0.5").performScrollTo().performClick()
        compose.onNodeWithTag("save-pantry").performScrollTo().performClick()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertEquals(PantryRow("onion", 500, "count"), pantryRows().single())
        compose.onNodeWithText("양파").assertIsDisplayed()
        compose.onNodeWithText("0.5 개").assertIsDisplayed()
        captureScreen("fraction-half-ko")

        openAddForm()
        compose.onNodeWithTag("name-input").performTextClearance()
        compose.onNodeWithTag("name-input").performTextInput("onion")
        assertInput("quantity-input", "")
        compose.onNodeWithTag("quantity-input").performTextInput("1")
        compose.onNodeWithTag("unit-count").performScrollTo().performClick()
        compose.onNodeWithTag("save-pantry").performScrollTo().performClick()
        compose.waitUntil(5_000) { pantryRowCount() == 2 }
        assertEquals(listOf("onion", "onion"), pantryRows().map(PantryRow::name))
        compose.onNodeWithTag("pantry-items").performScrollToIndex(1)
        compose.onAllNodesWithText("양파").assertCountEquals(2)
        captureScreen("typed-onion-ko")
    }

    @Test
    fun exactCanonicalLocalizesWhileCaseSpaceAndUnicodeNearMatchesRemainVerbatim() {
        insertRows("onion", "Onion", "Onion ", "onion ", "оnion")
        compose.waitUntil(5_000) { pantryRowCount() == 5 }

        listOf("양파", "Onion", "Onion ", "onion ", "оnion").forEach { label ->
            assertPantryLabel(label)
        }

        compose.runOnUiThread {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
        }
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithText("Ingredients available now").assertIsDisplayed() }.isSuccess
        }
        compose.onAllNodesWithText("Onion").assertCountEquals(2)
        listOf("Onion ", "onion ", "оnion").forEach { label ->
            assertPantryLabel(label)
        }
        assertEquals(listOf("onion", "Onion", "Onion ", "onion ", "оnion"), pantryRows().map(PantryRow::name))
    }

    @Test
    fun typedNearMatchAndFreeDecimalPersistByteVerbatimAcrossRecreation() {
        openAddForm()
        compose.onNodeWithTag("name-input").performTextInput("Onion ")
        compose.onNodeWithTag("quantity-input").performTextInput("0.375")
        compose.onNodeWithTag("unit-count").performScrollTo().performClick()
        compose.onNodeWithTag("save-pantry").performScrollTo().performClick()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()

        assertEquals(PantryRow("Onion ", 375, "count"), pantryRows().single())
        compose.onNodeWithText("Onion ").assertIsDisplayed()
    }

    @Test
    fun equipmentSelectionPersistsAcrossActivityRecreation() {
        compose.onNodeWithTag("nav-settings").performClick()
        compose.onNodeWithText("기본 조리도구").assertIsOff().performClick()
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithText("기본 조리도구").assertIsOn() }.isSuccess
        }

        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("nav-settings").performClick()
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithText("기본 조리도구").assertIsOn() }.isSuccess
        }

        compose.onNodeWithText("기본 조리도구").assertIsOn()
    }

    @Test
    fun pantryEditPersistsAcrossActivityRecreation() {
        openAddForm()
        compose.onNodeWithTag("name-input").performTextInput("Carrot")
        compose.onNodeWithTag("quantity-input").performTextInput("2.5")
        compose.onNodeWithTag("save-pantry").performClick()
        compose.onNodeWithText("Carrot").assertIsDisplayed()

        compose.onNodeWithContentDescription("Carrot 수정").performClick()
        compose.onNodeWithTag("name-input").performTextClearance()
        compose.onNodeWithTag("name-input").performTextInput("Baby carrot")
        compose.onNodeWithTag("save-pantry").performClick()
        compose.activityRule.scenario.recreate()

        compose.onNodeWithText("Baby carrot").assertIsDisplayed()
        assertEquals(1, pantryRowCount())
    }

    @Test
    fun unsavedPantryDraftAndScreenSurviveActivityRecreation() {
        openAddForm()
        compose.onNodeWithTag("name-input").performTextInput("Draft carrot")
        compose.onNodeWithTag("quantity-input").performTextInput("2.5")

        compose.activityRule.scenario.recreate()

        compose.onNodeWithTag("name-input").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("Draft carrot")),
        )
        compose.onNodeWithTag("quantity-input").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("2.5")),
        )
    }

    @Test
    fun pantryAndEquipmentTitlesExposeHeadingSemantics() {
        compose.onNodeWithText("지금 사용할 수 있는 재료")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))

        compose.onNodeWithTag("nav-settings").performClick()
        compose.onNodeWithText("주방에 있는 조리도구를 선택해 주세요")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    @Test
    fun unscopedDebugSeedBroadcastPersistsAcrossActivityRecreation() {
        shell("am broadcast -a com.junited31.leftovers.DEBUG_SEED")
        compose.waitUntil(5_000) { pantryRowCount() == 3 }

        compose.activityRule.scenario.recreate()
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithText("Spinach").assertIsDisplayed() }.isSuccess
        }

        compose.onNodeWithText("Spinach").assertIsDisplayed()
        assertEquals(3, pantryRowCount())
        compose.onNodeWithText("지금 사용할 수 있는 재료").assertIsDisplayed()
        captureScreen("task-6-korean-pantry")

        compose.onNodeWithTag("nav-settings").performClick()
        compose.onNodeWithText("주방에 있는 조리도구를 선택해 주세요").assertIsDisplayed()
        captureScreen("task-6-korean-equipment")
    }

    private fun assertInvalidQuantity(quantity: String) {
        val countBefore = pantryRowCount()
        openAddForm()
        compose.onNodeWithTag("name-input").performTextInput("Invalid $quantity")
        compose.onNodeWithTag("quantity-input").performTextInput(quantity)
        compose.onNodeWithTag("save-pantry").performClick()

        compose.onNodeWithText("수량은 0보다 큰 값으로 소수점 셋째 자리까지 입력해 주세요.").assertIsDisplayed()
        assertEquals(countBefore, pantryRowCount())
    }

    private fun openAddForm() {
        compose.onNodeWithTag("nav-pantry").performClick()
        compose.onNodeWithTag("add-pantry").performClick()
    }

    private fun selectSuggestion(id: String) {
        compose.onNodeWithTag("suggestion-$id").performScrollTo().performClick()
    }

    private fun assertInput(tag: String, value: String) {
        compose.onNodeWithTag(tag).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value)),
        )
    }

    private fun assertPantryLabel(label: String) {
        compose.onNodeWithTag("pantry-items").performScrollToNode(hasText(label))
        compose.onNodeWithText(label).assertIsDisplayed()
    }

    private fun insertRows(vararg names: String) = runBlocking {
        val database = LeftoversDatabase.get(InstrumentationRegistry.getInstrumentation().targetContext)
        database.pantryDao().insertAll(names.mapIndexed { index, name ->
            PantryItemEntity(
                id = requireNotNull(PantryItemId.parse("00000000-0000-4000-8000-${(index + 1).toString().padStart(12, '0')}")),
                name = name,
                quantityMilliUnits = 1_000,
                unit = PantryUnit.COUNT,
                expiryEpochDay = null,
                version = 1,
            )
        })
    }

    private fun pantryRowCount(): Int {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val path = context.getDatabasePath("leftovers.db")
        if (!path.exists()) {
            val room = Room.databaseBuilder(context, LeftoversDatabase::class.java, "leftovers.db").build()
            room.openHelper.writableDatabase
            room.close()
        }
        val database = SQLiteDatabase.openDatabase(
            path.path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        return database.rawQuery("SELECT COUNT(*) FROM pantry_items", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }.also { database.close() }
    }

    private fun pantryRows(): List<PantryRow> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = SQLiteDatabase.openDatabase(
            context.getDatabasePath("leftovers.db").path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        return database.rawQuery(
            "SELECT name, quantityMilliUnits, unit FROM pantry_items ORDER BY rowid",
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(PantryRow(cursor.getString(0), cursor.getLong(1), cursor.getString(2)))
                }
            }
        }.also { database.close() }
    }

    private data class PantryRow(val name: String, val quantityMilliUnits: Long, val unit: String)

    private fun shell(command: String): String =
        AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
        ).bufferedReader().use { it.readText() }

    private fun captureScreen(name: String) {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        shell("screencap -p /sdcard/Download/$name.png")
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val appXml = File(requireNotNull(compose.activity.getExternalFilesDir(null)), "$name.xml")
        FileOutputStream(appXml).use { output ->
            val serializer = Xml.newSerializer()
            serializer.setOutput(output, "UTF-8")
            serializer.startDocument("UTF-8", true)
            serializer.startTag(null, "hierarchy")
            writeNode(serializer, requireNotNull(automation.rootInActiveWindow))
            serializer.endTag(null, "hierarchy")
            serializer.endDocument()
        }
        shell("cp ${appXml.absolutePath} /sdcard/Download/$name.xml")
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
}
