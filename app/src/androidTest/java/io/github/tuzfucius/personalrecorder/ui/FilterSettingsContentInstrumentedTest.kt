package io.github.tuzfucius.personalrecorder.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tuzfucius.personalrecorder.data.NotificationSourceEntity
import io.github.tuzfucius.personalrecorder.settings.FilterSettings
import io.github.tuzfucius.personalrecorder.ui.theme.PersonalRecorderTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FilterSettingsContentInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun observedSourceWithoutLauncherIsVisibleSearchableAndSelectable() {
        var selectedPackages = emptySet<String>()
        val apps = buildFilterAppCatalog(
            observedSources = listOf(
                NotificationSourceEntity(
                    packageName = "com.example.hidden",
                    lastKnownLabel = null,
                    firstSeenAt = 1L,
                    lastSeenAt = 2L,
                    observedNotificationCount = 1L,
                    lastKnownHasLauncher = false,
                )
            ),
            launcherApps = emptyList(),
            selectedPackages = emptySet(),
            ownPackageName = "self.app",
        )

        composeRule.setContent {
            PersonalRecorderTheme {
                FilterSettingsContent(
                    settings = FilterSettings(),
                    apps = apps,
                    observedSourceCount = 1,
                    search = "",
                    onSearchChange = {},
                    onBack = {},
                    onModeChange = {},
                    onSelectedPackagesChange = { selectedPackages = it },
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("filter-switch-com.example.hidden").assertIsDisplayed().performClick()
        assertEquals(setOf("com.example.hidden"), selectedPackages)

        composeRule.onNodeWithTag("filter-search").performTextInput("hidden")
        composeRule.onNodeWithTag("filter-switch-com.example.hidden").assertIsDisplayed()
    }
}
