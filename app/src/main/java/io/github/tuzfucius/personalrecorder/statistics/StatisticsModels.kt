package io.github.tuzfucius.personalrecorder.statistics

import java.time.LocalDate

enum class StatisticsRange(val dayCount: Long, val label: String) {
    TODAY(1, "今日"),
    LAST_7_DAYS(7, "近 7 日"),
    LAST_30_DAYS(30, "近 30 日")
}

data class HourlyCount(val hour: Int, val count: Int)

data class AppCount(val packageName: String, val count: Int)

data class DailyCount(val date: LocalDate, val count: Int)

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
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)
