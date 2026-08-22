package io.github.tuzfucius.personalrecorder.statistics

import io.github.tuzfucius.personalrecorder.collector.NotificationFilter
import io.github.tuzfucius.personalrecorder.data.StatisticsEventRow
import io.github.tuzfucius.personalrecorder.settings.FilterSettings
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class StatisticsCalculator(
    private val clock: Clock = Clock.systemDefaultZone()
) {
    fun calculate(
        rows: List<StatisticsEventRow>,
        range: StatisticsRange,
        ownPackageName: String,
        settings: FilterSettings
    ): StatisticsUiState {
        val zone = clock.zone
        val today = LocalDate.now(clock)
        val startDate = today.minusDays(range.dayCount - 1)
        val dates = (0 until range.dayCount.toInt()).map { startDate.plusDays(it.toLong()) }
        val validRows = rows.filter { row ->
            !row.isOngoing &&
                !row.isGroupSummary &&
                NotificationFilter.shouldCollectPackage(row.packageName, ownPackageName, settings)
        }

        val hourly = IntArray(24)
        val daily = dates.associateWith { 0 }.toMutableMap()
        val appCounts = mutableMapOf<String, Int>()
        val hourlyApps = Array(24) { mutableMapOf<String, Int>() }
        validRows.forEach { row ->
            val instant = Instant.ofEpochMilli(row.timestamp).atZone(zone)
            val date = instant.toLocalDate()
            val hour = instant.hour
            if (date !in daily) return@forEach
            hourly[hour]++
            daily[date] = daily.getValue(date) + 1
            appCounts[row.packageName] = appCounts.getOrDefault(row.packageName, 0) + 1
            val hourApps = hourlyApps[hour]
            hourApps[row.packageName] = hourApps.getOrDefault(row.packageName, 0) + 1
        }

        val hourlyCounts = hourly.mapIndexed { hour, count -> HourlyCount(hour, count) }
        val appCountList = appCounts
            .map { (packageName, count) -> AppCount(packageName, count) }
            .sortedWith(compareByDescending<AppCount> { it.count }.thenBy { it.packageName })
        val dailyCounts = dates.map { DailyCount(it, daily.getValue(it)) }
        val maxHourlyCount = hourlyCounts.maxOfOrNull { it.count } ?: 0
        val peakHour = hourlyCounts.firstOrNull { it.count == maxHourlyCount && it.count > 0 }?.hour

        return StatisticsUiState(
            range = range,
            totalCount = validRows.size,
            activeAppCount = appCountList.size,
            peakHour = peakHour,
            hourlyCounts = hourlyCounts,
            appCounts = appCountList,
            topApps = appCountList.take(10),
            dailyCounts = dailyCounts,
            selectedHourTopApps = emptyList()
        ).withSelectedHour(null, hourlyApps)
    }

    fun bounds(range: StatisticsRange): DateRange {
        val zone = clock.zone
        val today = LocalDate.now(clock)
        val start = today.minusDays(range.dayCount - 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return DateRange(start, end)
    }

    fun withSelectedHour(
        state: StatisticsUiState,
        hour: Int?,
        rows: List<StatisticsEventRow>,
        ownPackageName: String,
        settings: FilterSettings
    ): StatisticsUiState {
        val zone = clock.zone
        val selectedRows = rows.filter { row ->
            !row.isOngoing &&
                !row.isGroupSummary &&
                NotificationFilter.shouldCollectPackage(row.packageName, ownPackageName, settings) &&
                Instant.ofEpochMilli(row.timestamp).atZone(zone).hour == hour
        }
        val topApps = selectedRows.groupingBy { it.packageName }
            .eachCount()
            .map { (packageName, count) -> AppCount(packageName, count) }
            .sortedWith(compareByDescending<AppCount> { it.count }.thenBy { it.packageName })
            .take(5)
        return state.copy(
            selectedHour = hour,
            selectedHourCount = selectedRows.size,
            selectedHourTopApps = topApps
        )
    }

    data class DateRange(val startMillis: Long, val endMillis: Long)
}

private fun StatisticsUiState.withSelectedHour(
    hour: Int?,
    hourlyApps: Array<MutableMap<String, Int>>
): StatisticsUiState {
    if (hour == null) return this
    val apps = hourlyApps[hour].map { (packageName, count) -> AppCount(packageName, count) }
        .sortedWith(compareByDescending<AppCount> { it.count }.thenBy { it.packageName })
        .take(5)
    return copy(
        selectedHour = hour,
        selectedHourCount = hourlyCounts.firstOrNull { it.hour == hour }?.count ?: 0,
        selectedHourTopApps = apps
    )
}
