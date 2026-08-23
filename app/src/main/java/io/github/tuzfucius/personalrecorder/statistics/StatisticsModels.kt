package io.github.tuzfucius.personalrecorder.statistics

import io.github.tuzfucius.personalrecorder.collector.NotificationFilter
import io.github.tuzfucius.personalrecorder.data.StatisticsEventRow
import io.github.tuzfucius.personalrecorder.settings.FilterSettings
import java.time.LocalDate
import java.time.ZoneId
import java.time.Instant

enum class StatisticsRange(val dayCount: Long, val label: String) {
    TODAY(1, "今日"),
    LAST_7_DAYS(7, "近 7 日"),
    LAST_30_DAYS(30, "近 30 日")
}

data class HourlyCount(val hour: Int, val count: Int)

data class AppCount(val packageName: String, val count: Int)

data class DailyCount(val date: LocalDate, val count: Int)

/** Filters applied to every statistics aggregate and to the detail list. */
data class StatisticsSelection(
    val app: String? = null,
    val hour: Int? = null,
    val date: LocalDate? = null,
) {
    init {
        require(hour == null || hour in 0..23) { "hour must be between 0 and 23" }
    }

    fun withoutTimeFilters(): StatisticsSelection = copy(hour = null, date = null)

    fun matches(row: StatisticsEventRow, zone: ZoneId): Boolean {
        val local = Instant.ofEpochMilli(row.timestamp).atZone(zone)
        return (app == null || app == row.packageName) &&
            (hour == null || hour == local.hour) &&
            (date == null || date == local.toLocalDate())
    }
}

/** The projection used by the detail list. It deliberately contains no Room entity. */
data class StatisticsEventItem(
    val id: String,
    val timestamp: Long,
    val packageName: String,
    val title: String? = null,
    val content: String? = null,
    val bigText: String? = null,
    val isOngoing: Boolean = false,
    val isGroupSummary: Boolean = false,
)

/** The common eligibility rule for charts, KPI values, and details. */
fun StatisticsEventRow.isEligible(ownPackageName: String, settings: FilterSettings): Boolean =
    !isOngoing && !isGroupSummary &&
        NotificationFilter.shouldCollectPackage(packageName, ownPackageName, settings)

fun StatisticsEventItem.isEligible(ownPackageName: String, settings: FilterSettings): Boolean =
    !isOngoing && !isGroupSummary &&
        NotificationFilter.shouldCollectPackage(packageName, ownPackageName, settings)

data class StatisticsUiState(
    val range: StatisticsRange = StatisticsRange.TODAY,
    val totalCount: Int = 0,
    val activeAppCount: Int = 0,
    val peakHour: Int? = null,
    val hourlyCounts: List<HourlyCount> = emptyList(),
    val appCounts: List<AppCount> = emptyList(),
    val topApps: List<AppCount> = emptyList(),
    val dailyCounts: List<DailyCount> = emptyList(),
    val selectedHour: Int? = null,
    val selectedHourCount: Int = 0,
    val selectedHourTopApps: List<AppCount> = emptyList(),
    val selection: StatisticsSelection = StatisticsSelection(),
    val details: List<StatisticsEventItem> = emptyList(),
    val isDetailsExpanded: Boolean = false,
    val isOtherAppsExpanded: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)
