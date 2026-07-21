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

        val source = sequenceOf(
            File("app/src/main/java/com/junited31/leftovers/MainActivity.kt"),
            File("src/main/java/com/junited31/leftovers/MainActivity.kt"),
        ).first(File::isFile).readText()
        val superCall = source.indexOf("super.onCreate(savedInstanceState)")
        val emptyCheck = source.indexOf("AppCompatDelegate.getApplicationLocales().isEmpty")
        val englishSet = source.indexOf("AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(\"en\"))")
        val setContent = source.indexOf("setContent {")
        assertTrue(superCall >= 0 && emptyCheck > superCall && englishSet > emptyCheck && setContent > englishSet)
    }
}
