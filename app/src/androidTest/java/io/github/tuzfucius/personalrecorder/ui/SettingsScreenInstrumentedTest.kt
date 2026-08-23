package io.github.tuzfucius.personalrecorder.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsGithubArchiveControlsWithoutLegacyProvider() {
        composeRule.setContent { SettingsScreen() }

        composeRule.onNodeWithText("云端归档").assertIsDisplayed()
        composeRule.onNodeWithText("GitHub 私有仓库").assertIsDisplayed()
        composeRule.onNodeWithTag("github-sync-switch").assertIsDisplayed()
    }
}
