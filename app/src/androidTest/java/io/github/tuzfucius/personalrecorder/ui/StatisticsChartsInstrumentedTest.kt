package io.github.tuzfucius.personalrecorder.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tuzfucius.personalrecorder.statistics.AppCount
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StatisticsChartsInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun donutLegendHasSemanticLabelAndSelectsApp() {
        var selected: String? = null
        composeRule.setContent {
            MaterialTheme {
                AppDonutChart(
                    values = listOf(AppCount("app.one", 4), AppCount("app.two", 2)),
                    labelFor = { it },
                    onAppClick = { selected = it },
                )
            }
        }

        composeRule.onNodeWithContentDescription("应用来源环图，共 6 条").assertIsDisplayed()
        composeRule.onNodeWithText("app.one 4").performClick()
        assertEquals("app.one", selected)
    }

    @Test
    fun otherLegendCanExpandRemainingApps() {
        composeRule.setContent {
            MaterialTheme {
                var expanded by remember { mutableStateOf(false) }
                AppDonutChart(
                    values = (1..7).map { AppCount("app.$it", 1) },
                    labelFor = { it },
                    otherExpanded = expanded,
                    onOtherClick = { expanded = !expanded },
                )
            }
        }

        composeRule.onNodeWithText("其他 1").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("app.7 1").assertIsDisplayed()
    }
}
