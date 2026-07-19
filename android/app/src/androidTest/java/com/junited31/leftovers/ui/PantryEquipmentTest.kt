package com.junited31.leftovers.ui

import android.database.sqlite.SQLiteDatabase
import android.graphics.Rect
import android.os.ParcelFileDescriptor.AutoCloseInputStream
import android.util.Xml
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.junited31.leftovers.MainActivity
import com.junited31.leftovers.data.LeftoversDatabase
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
        shell("am broadcast -a com.junited31.leftovers.DEBUG_RESET")
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
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
    fun equipmentSelectionPersistsAcrossActivityRecreation() {
        compose.onNodeWithTag("nav-equipment").performClick()
        compose.onNodeWithText("기본 조리도구").assertIsOff().performClick()
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithText("기본 조리도구").assertIsOn() }.isSuccess
        }

        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("nav-equipment").performClick()
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

        compose.onNodeWithTag("nav-equipment").performClick()
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

        compose.onNodeWithTag("nav-equipment").performClick()
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
