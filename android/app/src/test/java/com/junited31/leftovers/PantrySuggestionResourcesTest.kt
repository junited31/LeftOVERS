package com.junited31.leftovers

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class PantrySuggestionResourcesTest {
    @Test
    fun allStaplesHaveExactEnglishAndKoreanLabels() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val expected = listOf(
            Triple("eggs", "Eggs", "달걀"),
            Triple("milk", "Milk", "우유"),
            Triple("butter", "Butter", "버터"),
            Triple("cheese", "Cheese", "치즈"),
            Triple("onion", "Onion", "양파"),
            Triple("garlic", "Garlic", "마늘"),
            Triple("rice", "Rice", "쌀"),
            Triple("bread", "Bread", "빵"),
            Triple("flour", "Flour", "밀가루"),
            Triple("sugar", "Sugar", "설탕"),
            Triple("salt", "Salt", "소금"),
            Triple("black_pepper", "Black pepper", "후추"),
            Triple("cooking_oil", "Cooking oil", "식용유"),
            Triple("tomato", "Tomato", "토마토"),
            Triple("potato", "Potato", "감자"),
            Triple("carrot", "Carrot", "당근"),
            Triple("spinach", "Spinach", "시금치"),
        )
        val english = context.resourcesFor(Locale.ENGLISH)
        val korean = context.resourcesFor(Locale.KOREAN)

        expected.forEach { (resourceSuffix, englishLabel, koreanLabel) ->
            val id = context.resources.getIdentifier("pantry_suggestion_$resourceSuffix", "string", context.packageName)
            assertNotEquals("Missing pantry_suggestion_$resourceSuffix", 0, id)
            assertEquals(englishLabel, english.getString(id))
            assertEquals(koreanLabel, korean.getString(id))
        }
    }

    private fun Context.resourcesFor(locale: Locale) = createConfigurationContext(
        Configuration(resources.configuration).apply { setLocale(locale) },
    ).resources
}
