package com.junited31.leftovers.ui

import android.database.sqlite.SQLiteDatabase
import android.os.ParcelFileDescriptor.AutoCloseInputStream
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
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

        compose.onNodeWithText("Name is required").assertIsDisplayed()
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
        compose.onNodeWithText("Basic cookware").assertIsOff().performClick()
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithText("Basic cookware").assertIsOn() }.isSuccess
        }

        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("nav-equipment").performClick()
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithText("Basic cookware").assertIsOn() }.isSuccess
        }

        compose.onNodeWithText("Basic cookware").assertIsOn()
    }

    @Test
    fun pantryEditPersistsAcrossActivityRecreation() {
        openAddForm()
        compose.onNodeWithTag("name-input").performTextInput("Carrot")
        compose.onNodeWithTag("quantity-input").performTextInput("2.5")
        compose.onNodeWithTag("save-pantry").performClick()
        compose.onNodeWithText("Carrot").assertIsDisplayed()

        compose.onNodeWithContentDescription("Edit Carrot").performClick()
        compose.onNodeWithTag("name-input").performTextClearance()
        compose.onNodeWithTag("name-input").performTextInput("Baby carrot")
        compose.onNodeWithTag("save-pantry").performClick()
        compose.activityRule.scenario.recreate()

        compose.onNodeWithText("Baby carrot").assertIsDisplayed()
        assertEquals(1, pantryRowCount())
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
    }

    private fun assertInvalidQuantity(quantity: String) {
        val countBefore = pantryRowCount()
        openAddForm()
        compose.onNodeWithTag("name-input").performTextInput("Invalid $quantity")
        compose.onNodeWithTag("quantity-input").performTextInput(quantity)
        compose.onNodeWithTag("save-pantry").performClick()

        compose.onNodeWithText("Enter a positive quantity with up to 3 decimals").assertIsDisplayed()
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
}
