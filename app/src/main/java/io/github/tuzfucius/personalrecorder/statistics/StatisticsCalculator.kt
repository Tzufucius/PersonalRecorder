package io.github.tuzfucius.personalrecorder.statistics

import io.github.tuzfucius.personalrecorder.data.StatisticsEventRow
import io.github.tuzfucius.personalrecorder.settings.FilterSettings
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/** Pure, local-time statistics calculation shared by the screen and tests. */
class StatisticsCalculator(
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    fun calculate(
        rows: List<StatisticsEventRow>,
        range: StatisticsRange,
        ownPackageName: String,
        settings: FilterSettings,
        selection: StatisticsSelection = StatisticsSelection(),
        details: List<StatisticsEventItem> = emptyList(),
    ): StatisticsUiState {
        val zone = clock.zone
        val dates = datesFor(range)
        val dateSet = dates.toSet()
        val validRows = rows.asSequence()
            .filter { it.isEligible(ownPackageName, settings) }
            .filter { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() in dateSet }
            .filter { selection.matches(it, zone) }
            .toList()

        val hourly = IntArray(24)
        val hourlyAppCounts = Array(24) { mutableMapOf<String, Int>() }
        val daily = dates.associateWith { 0 }.toMutableMap()
        val appCounts = mutableMapOf<String, Int>()
        validRows.forEach { row ->
            val local = Instant.ofEpochMilli(row.timestamp).atZone(zone)
            hourly[local.hour]++
            hourlyAppCounts[local.hour][row.packageName] =
                hourlyAppCounts[local.hour].getOrDefault(row.packageName, 0) + 1
            daily[local.toLocalDate()] = daily.getValue(local.toLocalDate()) + 1
            appCounts[row.packageName] = appCounts.getOrDefault(row.packageName, 0) + 1
        }

        val hourlyCounts = hourly.mapIndexed(::HourlyCount)
        val hourlyBreakdowns = hourlyAppCounts.mapIndexed { hour, counts ->
            HourlyBreakdown(hour = hour, appCounts = counts.toAppCounts())
        }
        val apps = appCounts.toAppCounts()
        val dailyCounts = dates.map { DailyCount(it, daily.getValue(it)) }
        val maxHourlyCount = hourlyCounts.maxOfOrNull { it.count } ?: 0
        val detailsForState = details.asSequence()
            .filter { it.isEligible(ownPackageName, settings) }
            .filter { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() in dateSet }
            .filter { selection.matches(it.asRow(), zone) }
            .sortedByDescending { it.timestamp }
            .take(MAX_DETAILS)
            .toList()

        return StatisticsUiState(
            range = range,
            totalCount = validRows.size,
            activeAppCount = apps.size,
            peakHour = hourlyCounts.firstOrNull { it.count == maxHourlyCount && it.count > 0 }?.hour,
            hourlyCounts = hourlyCounts,
            hourlyBreakdowns = hourlyBreakdowns,
            appCounts = apps,
            topApps = apps.take(TOP_APPS),
            dailyCounts = dailyCounts,
            selectedHour = selection.hour,
            selectedHourCount = if (selection.hour == null) 0 else validRows.size,
            selectedHourTopApps = if (selection.hour == null) emptyList() else apps.take(5),
            selection = selection,
            details = detailsForState,
        )
    }

    fun bounds(range: StatisticsRange): DateRange {
        val zone = clock.zone
        val today = LocalDate.now(clock)
        val start = today.minusDays(range.dayCount - 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return DateRange(start, end)
    }

    /** Recomputes the hour summary without changing the selected app/date filters. */
    fun withSelectedHour(
        state: StatisticsUiState,
        hour: Int?,
        rows: List<StatisticsEventRow>,
        ownPackageName: String,
        settings: FilterSettings,
    ): StatisticsUiState {
        val selection = state.selection.copy(hour = hour)
        val updated = calculate(rows, state.range, ownPackageName, settings, selection)
        return state.copy(
            selection = selection,
            selectedHour = hour,
            selectedHourCount = updated.totalCount,
            selectedHourTopApps = updated.topApps.take(5),
        )
    }

    fun datesFor(range: StatisticsRange): List<LocalDate> {
        val today = LocalDate.now(clock)
        val startDate = today.minusDays(range.dayCount - 1)
        return (0 until range.dayCount.toInt()).map { startDate.plusDays(it.toLong()) }
    }

    data class DateRange(val startMillis: Long, val endMillis: Long)

    private companion object {
        const val MAX_DETAILS = 200
        const val TOP_APPS = 10
    }
}

private fun List<Map.Entry<String, Int>>.toAppCounts(): List<AppCount> = map { (packageName, count) ->
    AppCount(packageName, count)
}.sortedWith(compareByDescending<AppCount> { it.count }.thenBy { it.packageName })

private fun Map<String, Int>.toAppCounts(): List<AppCount> = entries.toList().toAppCounts()

private fun StatisticsEventItem.asRow() = StatisticsEventRow(
    timestamp = timestamp,
    packageName = packageName,
    isOngoing = isOngoing,
    isGroupSummary = isGroupSummary,
)
