package io.github.tuzfucius.personalrecorder.ui

import android.content.Context
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tuzfucius.personalrecorder.statistics.AppCount
import io.github.tuzfucius.personalrecorder.statistics.StatisticsRange
import io.github.tuzfucius.personalrecorder.statistics.StatisticsUiState

@Composable
fun StatisticsScreen(viewModel: io.github.tuzfucius.personalrecorder.statistics.StatisticsViewModel = viewModel()) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatisticsRange.entries.forEach { range ->
                    FilterChip(
                        selected = state.range == range,
                        onClick = { viewModel.selectRange(range) },
                        label = { Text(range.label) }
                    )
                }
            }
        }
        state.errorMessage?.let { message ->
            item { Text(message, color = MaterialTheme.colorScheme.error) }
        }
        item {
            Text("统计口径：排除进行中通知、组摘要和当前筛选之外的应用，原始记录仍保留。", style = MaterialTheme.typography.bodySmall)
        }
        item { KpiOverview(state) }
        item {
            Section("小时分布") {
                HourlyChart(state.hourlyCounts, state.selectedHour, viewModel::selectHour)
                state.selectedHour?.let { hour ->
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
                AppDonutChart(state.appCounts, { appLabel(context, it) })
            }
        }
        item {
            Section("应用排行") {
                state.topApps.forEachIndexed { index, app ->
                    AppRankRow(index + 1, app)
                    HorizontalDivider()
                }
            }
        }
        item {
            Section("每日趋势") {
                DailyTrendChart(state.dailyCounts)
            }
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
private fun AppRankRow(rank: Int, app: AppCount) {
    val context = LocalContext.current
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("$rank", modifier = Modifier.padding(end = 12.dp))
        Text(appLabel(context, app.packageName), modifier = Modifier.weight(1f))
        Text(app.count.toString(), color = MaterialTheme.colorScheme.primary)
    }
}

private fun appLabel(context: Context, packageName: String): String = runCatching {
    context.packageManager.getApplicationInfo(packageName, 0)
        .loadLabel(context.packageManager).toString()
}.getOrDefault(packageName)
