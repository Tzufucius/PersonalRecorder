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

@Composable
fun StatisticsScreen(
    viewModel: io.github.tuzfucius.personalrecorder.statistics.StatisticsViewModel = viewModel(),
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    val context = LocalContext.current
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
                        label = { Text(range.label) },
                        modifier = Modifier.semantics {
                            contentDescription = "统计范围 ${range.label}"
                            role = Role.RadioButton
                        },
                    )
                }
            }
        }
        state.errorMessage?.let { message ->
            item { Text(message, color = MaterialTheme.colorScheme.error) }
        }
        item { SelectionSummary(state, context, viewModel::clearSelection) }
        item { KpiOverview(state) }
        item {
            Section("小时分布") {
                HourlyChart(state.hourlyCounts, state.selection.hour, viewModel::selectHour)
                state.selection.hour?.let { hour ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("${hour.toString().padStart(2, '0')}:00  ${state.selectedHourCount} 条")
                    state.selectedHourTopApps.takeIf { it.isNotEmpty() }?.let { apps ->
                        Text(apps.joinToString("、") { appLabel(context, it.packageName) })
                    }
                }
            }
        }
        item {
            Section("应用来源") {
                AppDonutChart(
                    values = state.appCounts,
                    labelFor = { appLabel(context, it) },
                    selectedPackage = state.selection.app,
                    otherExpanded = state.isOtherAppsExpanded,
                    onAppClick = viewModel::selectApp,
                    onOtherClick = viewModel::toggleOtherApps,
                )
            }
        }
        item {
            Section("应用排行") {
                val max = state.topApps.maxOfOrNull { it.count } ?: 1
                state.topApps.forEachIndexed { index, app ->
                    AppRankRow(index + 1, app, max, app.packageName == state.selection.app) {
                        viewModel.selectApp(app.packageName)
                    }
                    HorizontalDivider()
                }
                if (state.topApps.isEmpty()) Text("暂无排行数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Section("每日趋势") {
                DailyTrendChart(state.dailyCounts, state.selection.date, viewModel::selectDate)
            }
        }
        item {
            DetailsHeader(expanded = state.isDetailsExpanded, count = state.details.size, onClick = viewModel::toggleDetails)
        }
        if (state.isDetailsExpanded) {
            if (state.details.isEmpty()) {
                item { Text("暂无明细记录", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(state.details, key = { it.id }) { detail ->
                    StatisticsDetailRow(detail)
                    HorizontalDivider()
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
            "统计口径：排除进行中通知、组摘要和当前筛选之外的应用，原始记录仍保留。",
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text("筛选：", style = MaterialTheme.typography.labelLarge)
        selection.app?.let { Text(appLabel(context, it), style = MaterialTheme.typography.labelLarge) }
        selection.date?.let { Text(it.toString(), style = MaterialTheme.typography.labelLarge) }
        selection.hour?.let { Text("${it.toString().padStart(2, '0')}:00", style = MaterialTheme.typography.labelLarge) }
        IconButton(onClick = onClear, modifier = Modifier.semantics { contentDescription = "清除统计筛选" }) {
            Icon(Icons.Default.Close, contentDescription = null)
        }
    }
}

@Composable
private fun KpiOverview(state: StatisticsUiState) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Kpi("有效事件", state.totalCount.toString(), Modifier.weight(1f))
        Kpi("活跃应用", state.activeAppCount.toString(), Modifier.weight(1f))
        Kpi("峰值小时", state.peakHour?.let { "${it}:00" } ?: "--", Modifier.weight(1f))
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
                contentDescription = "$rank ${appLabel(context, app.packageName)} ${app.count} 条"
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
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "${if (expanded) "收起" else "展开"}明细 $count 条" },
    ) {
        Text("明细（$count）${if (expanded) " ▲" else " ▼"}")
    }
}

@Composable
private fun StatisticsDetailRow(item: StatisticsEventItem) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            "${DateUtils.formatDateTime(context, item.timestamp, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME)}  ${appLabel(context, item.packageName)}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        item.title?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        Text(item.content?.takeIf { it.isNotBlank() } ?: item.bigText?.takeIf { it.isNotBlank() } ?: "（无正文）")
    }
}

private fun appLabel(context: Context, packageName: String): String = runCatching {
    context.packageManager.getApplicationInfo(packageName, 0).loadLabel(context.packageManager).toString()
}.getOrDefault(packageName)
