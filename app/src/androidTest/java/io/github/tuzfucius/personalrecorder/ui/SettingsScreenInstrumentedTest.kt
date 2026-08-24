package io.github.tuzfucius.personalrecorder.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import io.github.tuzfucius.personalrecorder.R
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

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule.onNodeWithText(context.getString(R.string.cloud_archive)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.github_private_repository)).assertIsDisplayed()
        composeRule.onNodeWithTag("github-sync-switch").assertIsDisplayed()
    }
}
