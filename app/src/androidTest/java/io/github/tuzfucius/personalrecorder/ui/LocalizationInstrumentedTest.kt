package io.github.tuzfucius.personalrecorder.ui

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tuzfucius.personalrecorder.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class LocalizationInstrumentedTest {
    private val base: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun coreTabsResolveInEnglishAndSimplifiedChinese() {
        val english = localized("en-US")
        val chinese = localized("zh-CN")

        assertEquals(english.getString(R.string.nav_records), english.getString(R.string.nav_records))
        assertEquals(english.getString(R.string.nav_statistics), english.getString(R.string.nav_statistics))
        assertEquals(english.getString(R.string.nav_settings), english.getString(R.string.nav_settings))
        assertNotEquals(english.getString(R.string.nav_records), chinese.getString(R.string.nav_records))
        assertNotEquals(english.getString(R.string.nav_statistics), chinese.getString(R.string.nav_statistics))
        assertNotEquals(english.getString(R.string.nav_settings), chinese.getString(R.string.nav_settings))
    }

    @Test
    fun formattedAndPluralResourcesUseLocale() {
        val english = localized("en-US")
        val chinese = localized("zh-CN")
        val englishCount = english.resources.getQuantityString(R.plurals.notifications_count, 2, 2)
        val chineseCount = chinese.resources.getQuantityString(R.plurals.notifications_count, 2, 2)

        assertTrue(english.getString(R.string.observed_sources, 3).contains("3"))
        assertTrue(chinese.getString(R.string.observed_sources, 3).contains("3"))
        assertNotEquals(englishCount, chineseCount)
        assertTrue(englishCount.contains("2"))
        assertTrue(chineseCount.contains("2"))
    }

    private fun localized(tag: String): Context {
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocales(LocaleList.forLanguageTags(tag))
        return base.createConfigurationContext(configuration)
    }
}
