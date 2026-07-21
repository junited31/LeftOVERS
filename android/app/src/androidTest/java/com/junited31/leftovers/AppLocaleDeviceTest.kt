package com.junited31.leftovers

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.core.os.LocaleListCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLocaleDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun setKoreanAndVerifyPersistence() {
        setLocaleAndRecreate("ko")
        composeRule.onNodeWithText("지금 사용할 수 있는 재료").assertExists()
    }

    @Test
    fun setUnsupportedJapaneseAndVerifyEnglishFallback() {
        setLocaleAndRecreate("ja")
        composeRule.onNodeWithText("Ingredients available now").assertExists()
    }

    @Test
    fun restoreEnglish() {
        setLocaleAndRecreate("en")
        composeRule.onNodeWithText("Ingredients available now").assertExists()
    }

    private fun setLocaleAndRecreate(languageTag: String) {
        composeRule.runOnUiThread {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTag))
        }
        composeRule.waitUntil(5_000) {
            AppCompatDelegate.getApplicationLocales().toLanguageTags() == languageTag
        }
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
    }
}
