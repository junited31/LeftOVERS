package com.junited31.leftovers

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLocaleBootstrapTest {
    @Test
    fun `declares native locales metadata and localized navigation copy`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val localeConfig = context.resources.getIdentifier("locales_config", "xml", context.packageName)
        assertTrue("application localeConfig is missing", localeConfig != 0)

        val locales = buildList {
            context.resources.getXml(localeConfig).use { parser ->
                while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "locale") {
                        add(parser.getAttributeValue(0))
                    }
                }
            }
        }
        assertEquals(listOf("en", "ko"), locales)

        val service = context.packageManager.getServiceInfo(
            ComponentName(context, "androidx.appcompat.app.AppLocalesMetadataHolderService"),
            PackageManager.ComponentInfoFlags.of(
                (PackageManager.GET_META_DATA or PackageManager.MATCH_DISABLED_COMPONENTS).toLong(),
            ),
        )
        assertFalse(service.enabled)
        assertFalse(service.exported)
        assertTrue(service.metaData.getBoolean("autoStoreLocales"))

        val pantryLabel = context.resources.getIdentifier("nav_pantry", "string", context.packageName)
        assertTrue("nav_pantry string resource is missing", pantryLabel != 0)
        val korean = Configuration(context.resources.configuration).apply { setLocale(Locale.KOREAN) }
        assertEquals("식재료", context.createConfigurationContext(korean).getString(pantryLabel))
    }

    @Test
    fun `main activity uses AppCompat and bootstraps English before content`() {
        assertEquals("androidx.appcompat.app.AppCompatActivity", MainActivity::class.java.superclass.name)

        val source = source("src/main/java/com/junited31/leftovers/MainActivity.kt")
        val superCall = source.indexOf("super.onCreate(savedInstanceState)")
        val emptyCheck = source.indexOf("AppCompatDelegate.getApplicationLocales().isEmpty")
        val englishSet = source.indexOf("AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(\"en\"))")
        val setContent = source.indexOf("setContent {")
        assertTrue(superCall >= 0 && emptyCheck > superCall && englishSet > emptyCheck && setContent > englishSet)
    }

    @Test
    fun `remaining displayed formats are resource backed`() {
        val recipe = source("src/main/java/com/junited31/leftovers/recipes/RecipeScreen.kt")
        val history = source("src/main/java/com/junited31/leftovers/history/HistoryScreen.kt")

        assertTrue(recipe.contains("R.string.ingredient_amount_format"))
        listOf(
            "R.string.numbered_step_format",
            "R.string.history_amount_line",
            "R.string.history_adjustment_note",
            "R.string.history_adjustment_line",
            "R.string.completed_at_pattern",
        ).forEach { assertTrue("missing resource reference $it", history.contains(it)) }
        assertFalse(history.contains("DateTimeFormatter.ofPattern(\"yyyy.MM.dd HH:mm\")"))
    }

    @Test
    fun `navigation semantics use composed localized values`() {
        val source = source("src/main/java/com/junited31/leftovers/MainActivity.kt")
        assertFalse(source.contains("contentDescription = context.getString(R.string.nav_"))
    }

    @Test
    fun `device locale tests restore English after every test`() {
        val source = source("src/androidTest/java/com/junited31/leftovers/AppLocaleDeviceTest.kt")
        assertTrue(source.contains("@After"))
        assertFalse(Regex("@Test\\s+fun restoreEnglish").containsMatchIn(source))
    }

    @Test
    fun `English count unit is grammatically neutral`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val unitCount = context.resources.getIdentifier("unit_count", "string", context.packageName)
        assertEquals("pcs", context.getString(unitCount))
    }

    private fun source(relativePath: String): String = sequenceOf(
        File("app/$relativePath"),
        File(relativePath),
    ).first(File::isFile).readText()
}
