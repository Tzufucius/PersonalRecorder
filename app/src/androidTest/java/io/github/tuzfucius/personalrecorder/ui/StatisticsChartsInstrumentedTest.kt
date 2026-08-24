package io.github.tuzfucius.personalrecorder.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tuzfucius.personalrecorder.statistics.AppCount
import io.github.tuzfucius.personalrecorder.statistics.HourlyBreakdown
import io.github.tuzfucius.personalrecorder.statistics.HourlyCount
import io.github.tuzfucius.personalrecorder.R
import androidx.test.core.app.ApplicationProvider
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

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule.onNodeWithContentDescription(context.getString(R.string.app_source_chart, 6)).assertIsDisplayed()
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

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule.onNodeWithText(context.getString(R.string.app_count_label, context.getString(R.string.other_label), 1)).assertIsDisplayed().performClick()
        composeRule.onNodeWithText("app.7 1").assertIsDisplayed()
    }

    @Test
    fun hourlyChartMapsFirstMiddleAndLastHourOn360Dp() {
        val clicked = mutableListOf<Int>()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val values = (0..23).map { HourlyCount(it, 0) }
        composeRule.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Box(modifier = Modifier.width(360.dp)) {
                    HourlyChart(
                        values = values,
                        breakdowns = values.map { HourlyBreakdown(it.hour) },
                        apps = emptyList(),
                        selectedHour = null,
                        onHourClick = { clicked += it },
                        labelFor = { it },
                    )
                }
            }
        }

        val description = values.joinToString(context.getString(R.string.list_separator)) {
            context.getString(R.string.hour_selected, context.getString(R.string.hour_label, it.hour), it.count)
        }
        val chart = composeRule.onNodeWithContentDescription(description)
        chart.assertIsDisplayed().performTouchInput {
            click(Offset(width * 0.15f, height / 2f))
            click(Offset(width * 0.573f, height / 2f))
            click(Offset(width * 0.97f, height / 2f))
        }

        assertEquals(listOf(0, 12, 23), clicked)
    }
}
