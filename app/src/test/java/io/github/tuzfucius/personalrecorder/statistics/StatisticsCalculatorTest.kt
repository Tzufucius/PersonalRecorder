package io.github.tuzfucius.personalrecorder.statistics

import io.github.tuzfucius.personalrecorder.data.StatisticsEventRow
import io.github.tuzfucius.personalrecorder.settings.FilterSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class StatisticsCalculatorTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-08-22T12:00:00Z")
    private val calculator = StatisticsCalculator(Clock.fixed(now, zone))

    @Test
    fun calculates24HourlyBucketsAndEarliestPeak() {
        val rows = listOf(
            row("a", "2026-08-22T01:15:00Z"),
            row("b", "2026-08-22T01:30:00Z"),
            row("c", "2026-08-22T02:00:00Z"),
            row("a", "2026-08-22T02:10:00Z")
        )

        val state = calculator.calculate(rows, StatisticsRange.TODAY, "self.app", FilterSettings())

        assertEquals(24, state.hourlyCounts.size)
        assertEquals(2, state.hourlyCounts[9].count)
        assertEquals(9, state.peakHour)
    }

    @Test
    fun filtersOngoingGroupSummaryAndBuildsZeroDays() {
        val rows = listOf(
            row("a", "2026-08-21T04:00:00Z"),
            row("b", "2026-08-21T05:00:00Z", isOngoing = true),
            row("c", "2026-08-20T05:00:00Z", isGroupSummary = true)
        )

        val state = calculator.calculate(rows, StatisticsRange.LAST_7_DAYS, "self.app", FilterSettings())

        assertEquals(1, state.totalCount)
        assertEquals(7, state.dailyCounts.size)
        assertTrue(state.dailyCounts.any { it.count == 0 })
    }

    @Test
    fun emptyDataHasNoPeakAndNoDivisionByZero() {
        val state = calculator.calculate(emptyList(), StatisticsRange.TODAY, "self.app", FilterSettings())

        assertEquals(0, state.totalCount)
        assertEquals(0, state.activeAppCount)
        assertNull(state.peakHour)
        assertTrue(state.hourlyCounts.all { it.count == 0 })
    }

    @Test
    fun selectionCombinesAppHourAndDate() {
        val rows = listOf(
            row("a", "2026-08-22T01:15:00Z"), // 09:15 on the selected date
            row("a", "2026-08-22T02:15:00Z"),
            row("b", "2026-08-22T01:15:00Z"),
            row("a", "2026-08-21T01:15:00Z"),
        )

        val state = calculator.calculate(
            rows,
            StatisticsRange.LAST_7_DAYS,
            "self.app",
            FilterSettings(),
            StatisticsSelection(app = "a", hour = 9, date = LocalDate.of(2026, 8, 22)),
        )

        assertEquals(1, state.totalCount)
        assertEquals("a", state.selection.app)
        assertEquals(9, state.selection.hour)
        assertEquals(LocalDate.of(2026, 8, 22), state.selection.date)
    }

    @Test
    fun detailsAreEligibleSortedNewestAndLimitedTo200() {
        val details = (0..205).map { index ->
            StatisticsEventItem(
                id = index.toString(),
                timestamp = now.toEpochMilli() + index,
                packageName = "a",
                isOngoing = index == 0,
            )
        }
        val state = calculator.calculate(
            emptyList(),
            StatisticsRange.TODAY,
            "self.app",
            FilterSettings(),
            details = details,
        )

        assertEquals(200, state.details.size)
        assertEquals("205", state.details.first().id)
        assertTrue(state.details.none { it.isOngoing || it.id == "0" })
    }

    @Test
    fun rangeChangeCanClearOnlyTimeSelection() {
        val selection = StatisticsSelection("a", hour = 4, date = LocalDate.of(2026, 8, 22))
        assertEquals(StatisticsSelection(app = "a"), selection.withoutTimeFilters())
    }

    private fun row(
        packageName: String,
        timestamp: String,
        isOngoing: Boolean = false,
        isGroupSummary: Boolean = false
    ) = StatisticsEventRow(
        timestamp = Instant.parse(timestamp).toEpochMilli(),
        packageName = packageName,
        isOngoing = isOngoing,
        isGroupSummary = isGroupSummary
    )
}
