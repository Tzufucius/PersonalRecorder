package io.github.tuzfucius.personalrecorder.ui

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tuzfucius.personalrecorder.statistics.AppCount
import io.github.tuzfucius.personalrecorder.statistics.StatisticsEventItem
import io.github.tuzfucius.personalrecorder.statistics.StatisticsRange
import io.github.tuzfucius.personalrecorder.statistics.StatisticsUiState
import io.github.tuzfucius.personalrecorder.R
import java.time.LocalDate

@Composable
fun StatisticsScreen(
    viewModel: io.github.tuzfucius.personalrecorder.statistics.StatisticsViewModel = viewModel(),
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    val appColorIndices = remember(state.appCounts) {
        packageColorIndices(state.appCounts.map { it.packageName })
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatisticsRange.entries.forEach { range ->
                    FilterChip(
                        selected = state.range == range,
                        onClick = { viewModel.selectRange(range) },
                        label = { Text(rangeLabel(context, range)) },
                        modifier = Modifier.semantics {
                            contentDescription = context.getString(
                                R.string.statistics_range_cd,
                                rangeLabel(context, range),
                            )
                            role = Role.RadioButton
                        },
                    )
                }
            }
        }
        state.errorMessage?.let { message ->
            item { Text(message.resolve(context), color = MaterialTheme.colorScheme.error) }
        }
        item { SelectionSummary(state, context, viewModel::clearSelection) }
        item { KpiOverview(state) }
        item {
            Section(context.getString(R.string.section_hourly)) {
                HourlyChart(
                    values = state.hourlyCounts,
                    breakdowns = state.hourlyBreakdowns,
                    apps = state.appCounts,
                    colorIndices = appColorIndices,
                    selectedHour = state.selection.hour,
                    onHourClick = viewModel::selectHour,
                    labelFor = { appLabel(context, it) },
                )
                state.selection.hour?.let { hour ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(context.getString(R.string.hour_selected, hourLabel(context, hour), state.selectedHourCount))
                    state.selectedHourTopApps.takeIf { it.isNotEmpty() }?.let { apps ->
                        Text(apps.joinToString(context.getString(R.string.list_separator)) { appLabel(context, it.packageName) })
                    }
                }
            }
        }
        item {
            Section(context.getString(R.string.section_app_source)) {
                AppDonutChart(
                    values = state.appCounts,
                    labelFor = { appLabel(context, it) },
                    colorIndices = appColorIndices,
                    selectedPackage = state.selection.app,
                    otherExpanded = state.isOtherAppsExpanded,
                    onAppClick = viewModel::selectApp,
                    onOtherClick = viewModel::toggleOtherApps,
                )
            }
        }
        item {
            Section(context.getString(R.string.section_app_ranking)) {
                val max = state.topApps.maxOfOrNull { it.count } ?: 1
                state.topApps.forEachIndexed { index, app ->
                    AppRankRow(index + 1, app, max, app.packageName == state.selection.app) {
                        viewModel.selectApp(app.packageName)
                    }
                    HorizontalDivider()
                }
                if (state.topApps.isEmpty()) Text(context.getString(R.string.no_rank_data), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (state.range != StatisticsRange.TODAY) {
            item {
                Section(context.getString(R.string.section_daily_trend)) {
                    DailyTrendChart(state.dailyCounts, state.selection.date, viewModel::selectDate)
                }
            }
        }
        if (state.selection.app != null || state.selection.hour != null || state.selection.date != null) {
            item {
                DetailsHeader(expanded = state.isDetailsExpanded, count = state.details.size, onClick = viewModel::toggleDetails)
            }
            if (state.isDetailsExpanded) {
                if (state.details.isEmpty()) {
                    item { Text(context.getString(R.string.no_details), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(state.details, key = { it.id }) { detail ->
                        StatisticsDetailRow(
                            item = detail,
                            expanded = state.expandedEventId == detail.id,
                            onClick = { viewModel.toggleEventDetails(detail.id) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionSummary(
    state: StatisticsUiState,
    context: Context,
    onClear: () -> Unit,
) {
    val selection = state.selection
    if (selection.app == null && selection.hour == null && selection.date == null) {
        Text(
            context.getString(R.string.statistics_scope_note),
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(context.getString(R.string.statistics_filter_prefix), style = MaterialTheme.typography.labelLarge)
        selection.app?.let { Text(appLabel(context, it), style = MaterialTheme.typography.labelLarge) }
        selection.date?.let { Text(formatDate(context, it), style = MaterialTheme.typography.labelLarge) }
        selection.hour?.let { Text(hourLabel(context, it), style = MaterialTheme.typography.labelLarge) }
        IconButton(onClick = onClear, modifier = Modifier.semantics { contentDescription = context.getString(R.string.clear_statistics_filter) }) {
            Icon(Icons.Default.Close, contentDescription = null)
        }
    }
}

@Composable
private fun KpiOverview(state: StatisticsUiState) {
    val context = LocalContext.current
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Kpi(context.getString(R.string.valid_events), state.totalCount.toString(), Modifier.weight(1f))
        Kpi(context.getString(R.string.active_apps), state.activeAppCount.toString(), Modifier.weight(1f))
        Kpi(context.getString(R.string.peak_hour), state.peakHour?.let { hourLabel(context, it) } ?: "--", Modifier.weight(1f))
    }
}

@Composable
private fun Kpi(title: String, value: String, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(title, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun AppRankRow(
    rank: Int,
    app: AppCount,
    maxCount: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = context.getString(R.string.rank_accessibility, rank, appLabel(context, app.packageName), app.count)
                role = Role.Button
            }
            .padding(vertical = 8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("$rank", modifier = Modifier.padding(end = 12.dp))
            Text(appLabel(context, app.packageName), modifier = Modifier.weight(1f))
            Text(app.count.toString(), color = MaterialTheme.colorScheme.primary)
        }
        LinearProgressIndicator(
            progress = { app.count.toFloat() / maxCount.coerceAtLeast(1) },
            modifier = Modifier.fillMaxWidth().padding(start = 28.dp, top = 4.dp),
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun DetailsHeader(expanded: Boolean, count: Int, onClick: () -> Unit) {
    val context = LocalContext.current
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = context.getString(
                if (expanded) R.string.details_accessibility_expanded else R.string.details_accessibility_collapsed,
                count,
            )
        },
    ) {
        Text(context.getString(if (expanded) R.string.details_header_expanded else R.string.details_header_collapsed, count))
    }
}

@Composable
private fun StatisticsDetailRow(
    item: StatisticsEventItem,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = context.getString(
                    R.string.notification_detail,
                    appLabel(context, item.packageName),
                    item.title.orEmpty(),
                )
                role = Role.Button
            }
            .padding(vertical = 6.dp),
    ) {
        Text(
            "${DateUtils.formatDateTime(context, item.timestamp, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME)}  ${appLabel(context, item.packageName)}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        item.title?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        Text(item.content?.takeIf { it.isNotBlank() } ?: item.bigText?.takeIf { it.isNotBlank() } ?: context.getString(R.string.no_content))
        if (expanded) {
            item.bigText?.takeIf { it.isNotBlank() && it != item.content }?.let {
                Text(context.getString(R.string.big_text_detail, it), style = MaterialTheme.typography.bodySmall)
            }
            if (item.textLines.isNotEmpty()) {
                Text(context.getString(R.string.text_lines_detail, item.textLines.joinToString(context.getString(R.string.list_separator))), style = MaterialTheme.typography.bodySmall)
            }
            item.channelId?.takeIf { it.isNotBlank() }?.let {
                Text(context.getString(R.string.channel_detail, it), style = MaterialTheme.typography.bodySmall)
            }
            item.category?.takeIf { it.isNotBlank() }?.let {
                Text(context.getString(R.string.category_detail, it), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun appLabel(context: Context, packageName: String): String = runCatching {
    context.packageManager.getApplicationInfo(packageName, 0).loadLabel(context.packageManager).toString()
}.getOrDefault(packageName)

private fun rangeLabel(context: Context, range: StatisticsRange): String = when (range) {
    StatisticsRange.TODAY -> context.getString(R.string.range_today)
    StatisticsRange.LAST_7_DAYS -> context.getString(R.string.range_last_7_days)
    StatisticsRange.LAST_30_DAYS -> context.getString(R.string.range_last_30_days)
}

private fun hourLabel(context: Context, hour: Int): String = context.getString(R.string.hour_label, hour)

private fun formatDate(context: Context, date: LocalDate): String = formatLocalizedDate(context, date)
