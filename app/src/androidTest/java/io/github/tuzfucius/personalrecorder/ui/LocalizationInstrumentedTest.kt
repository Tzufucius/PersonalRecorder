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
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class LocalizationInstrumentedTest {
    private val base: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun coreTabsResolveInEnglishAndSimplifiedChinese() {
        val english = localized("en-US")
        val chinese = localized("zh-CN")

        assertEquals("Records", english.getString(R.string.nav_records))
        assertEquals("Statistics", english.getString(R.string.nav_statistics))
        assertEquals("Settings", english.getString(R.string.nav_settings))
        assertEquals("记录", chinese.getString(R.string.nav_records))
        assertEquals("统计", chinese.getString(R.string.nav_statistics))
        assertEquals("设置", chinese.getString(R.string.nav_settings))
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

    @Test
    fun dailyPointAccessibilityUsesLocaleDateAndPlural() {
        val english = localized("en-US")
        val chinese = localized("zh-CN")
        val date = LocalDate.of(2026, 8, 24)
        val englishPoint = english.getString(
            R.string.daily_point_accessibility,
            formatLocalizedDate(english, date),
            english.resources.getQuantityString(R.plurals.notifications_count, 2, 2),
        )
        val chinesePoint = chinese.getString(
            R.string.daily_point_accessibility,
            formatLocalizedDate(chinese, date),
            chinese.resources.getQuantityString(R.plurals.notifications_count, 2, 2),
        )

        assertTrue(englishPoint.contains("2026"))
        assertTrue(englishPoint.contains("2 notifications"))
        assertTrue(chinesePoint.contains("2026"))
        assertTrue(chinesePoint.contains("2 条通知"))
        assertNotEquals(englishPoint, chinesePoint)
    }

    private fun localized(tag: String): Context {
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocales(LocaleList.forLanguageTags(tag))
        return base.createConfigurationContext(configuration)
    }
}
