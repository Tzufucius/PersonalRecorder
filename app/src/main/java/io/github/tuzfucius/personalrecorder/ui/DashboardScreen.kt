package io.github.tuzfucius.personalrecorder.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tuzfucius.personalrecorder.data.AppDatabase
import io.github.tuzfucius.personalrecorder.data.PersonalEvent
import io.github.tuzfucius.personalrecorder.settings.FilterMode
import io.github.tuzfucius.personalrecorder.settings.FilterSettings
import io.github.tuzfucius.personalrecorder.settings.FilterSettingsState
import io.github.tuzfucius.personalrecorder.settings.FilterSettingsStore
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

private enum class AppPage { RECORDS, STATISTICS, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalRecorderApp() {
    var page by rememberSaveable { mutableStateOf(AppPage.STATISTICS) }
    var showFilterSettings by rememberSaveable { mutableStateOf(false) }

    if (showFilterSettings) {
        FilterSettingsScreen(onBack = { showFilterSettings = false })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (page) {
                            AppPage.RECORDS -> "记录"
                            AppPage.STATISTICS -> "统计"
                            AppPage.SETTINGS -> "设置"
                        }
                    )
                },
                actions = {
                    if (page == AppPage.RECORDS) {
                        IconButton(onClick = { showFilterSettings = true }) {
                            Icon(Icons.Default.FilterList, contentDescription = "筛选应用")
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = page == AppPage.RECORDS,
                    onClick = { page = AppPage.RECORDS },
                    icon = { Icon(Icons.Default.List, contentDescription = null) },
                    label = { Text("记录") }
                )
                NavigationBarItem(
                    selected = page == AppPage.STATISTICS,
                    onClick = { page = AppPage.STATISTICS },
                    icon = { Icon(Icons.Default.Insights, contentDescription = null) },
                    label = { Text("统计") }
                )
                NavigationBarItem(
                    selected = page == AppPage.SETTINGS,
                    onClick = { page = AppPage.SETTINGS },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("设置") }
                )
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            when (page) {
                AppPage.RECORDS -> DashboardScreen()
                AppPage.STATISTICS -> StatisticsScreen()
                AppPage.SETTINGS -> SettingsScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val dao = remember { AppDatabase.getInstance(context).eventDao() }
    val filterStore = remember { FilterSettingsStore(context) }
    var hasNotificationAccess by remember {
        mutableStateOf(NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName))
    }
    var dayRange by remember { mutableStateOf(currentDayRange()) }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasNotificationAccess = NotificationManagerCompat.getEnabledListenerPackages(context)
                    .contains(context.packageName)
                dayRange = currentDayRange()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val recentEvents by remember(dao) { dao.getRecentEvents(limit = 50) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val todayEvents by remember(dao, dayRange) { dao.getTodayEvents(dayRange.startMillis, dayRange.endMillis) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val filterState by filterStore.state.collectAsStateWithLifecycle(
        initialValue = FilterSettingsState.Ready(FilterSettings())
    )
    val settings = (filterState as? FilterSettingsState.Ready)?.settings
    val visibleRecent = settings?.let { current ->
        recentEvents.filter { visibleFor(it.packageName, context.packageName, current) }
    }.orEmpty().take(30)
    val visibleTodayCount = settings?.let { current ->
        todayEvents.count { visibleFor(it.packageName, context.packageName, current) }
    } ?: 0

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            PermissionCard(
                enabled = hasNotificationAccess,
                onOpenSettings = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            )
        }
        if (filterState is FilterSettingsState.Error) {
            item { Text("应用筛选配置读取失败，暂不显示记录。", color = MaterialTheme.colorScheme.error) }
        }
        item {
            Column {
                Text("今日采集", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text("$visibleTodayCount 条", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        item { Text("最近事件", style = MaterialTheme.typography.titleMedium) }
        if (visibleRecent.isEmpty()) {
            item { Text("暂无通知事件", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(visibleRecent, key = { it.id }) { eventEntity ->
                EventRow(eventEntity.toPersonalEvent())
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun PermissionCard(enabled: Boolean, onOpenSettings: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("通知访问权限", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("●", color = if (enabled) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.size(8.dp))
                Text(if (enabled) "已授权" else "未授权")
            }
            if (!enabled) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = onOpenSettings) { Text("授权通知访问") }
            }
        }
    }
}

@Composable
private fun EventRow(event: PersonalEvent) {
    val context = LocalContext.current
    val appName = remember(event.packageName) { appLabel(context, event.packageName) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = "${DateUtils.formatDateTime(context, event.timestamp, DateUtils.FORMAT_SHOW_TIME)}  $appName",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        event.title?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        Text(
            text = event.content?.takeIf { it.isNotBlank() } ?: "（无正文）",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { FilterSettingsStore(context) }
    val scope = rememberCoroutineScope()
    val state by store.state.collectAsStateWithLifecycle(initialValue = FilterSettingsState.Ready(FilterSettings()))
    val settings = (state as? FilterSettingsState.Ready)?.settings
    var search by rememberSaveable { mutableStateOf("") }
    val apps = remember(context) { installedApps(context) }
    val visibleApps = apps.filter { app ->
        search.isBlank() || app.label.contains(search, ignoreCase = true) || app.packageName.contains(search, ignoreCase = true)
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("应用筛选") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") } }
        )
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("模式", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings?.mode == mode,
                            onClick = { scope.launch { store.setMode(mode) } },
                            label = { Text(modeLabel(mode)) }
                        )
                    }
                }
                Text("自身应用始终不会采集。白名单为空时不采集第三方通知。", style = MaterialTheme.typography.bodySmall)
            }
            item {
                TextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("搜索应用") },
                    singleLine = true
                )
            }
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { scope.launch { store.setSelectedPackages(apps.map { it.packageName }.toSet()) } }) { Text("全选") }
                    TextButton(onClick = { scope.launch { store.setSelectedPackages(emptySet()) } }) { Text("清空") }
                }
            }
            if (settings != null) {
                items(visibleApps, key = { it.packageName }) { app ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(app.label)
                            Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = app.packageName in settings.selectedPackages,
                            onCheckedChange = { checked ->
                                scope.launch {
                                    val next = settings.selectedPackages.toMutableSet().apply {
                                        if (checked) add(app.packageName) else remove(app.packageName)
                                    }
                                    store.setSelectedPackages(next)
                                }
                            }
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

private data class InstalledApp(val packageName: String, val label: String)

private fun installedApps(context: Context): List<InstalledApp> = runCatching {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    context.packageManager.queryIntentActivities(intent, 0)
        .mapNotNull { info ->
            val appInfo = info.activityInfo?.applicationInfo ?: return@mapNotNull null
            InstalledApp(appInfo.packageName, appInfo.loadLabel(context.packageManager).toString())
        }
        .distinctBy { it.packageName }
        .filterNot { it.packageName == context.packageName }
        .sortedWith(compareBy<InstalledApp> { it.label.lowercase(Locale.ROOT) }.thenBy { it.packageName })
}.getOrDefault(emptyList())

private fun visibleFor(packageName: String, ownPackageName: String, settings: FilterSettings): Boolean {
    if (packageName == ownPackageName) return false
    return when (settings.mode) {
        FilterMode.ALL -> true
        FilterMode.WHITELIST -> packageName in settings.selectedPackages
        FilterMode.BLACKLIST -> packageName !in settings.selectedPackages
    }
}

private fun modeLabel(mode: FilterMode): String = when (mode) {
    FilterMode.ALL -> "全部应用"
    FilterMode.WHITELIST -> "仅白名单"
    FilterMode.BLACKLIST -> "排除黑名单"
}

private fun appLabel(context: Context, packageName: String): String = runCatching {
    context.packageManager.getApplicationInfo(packageName, 0).loadLabel(context.packageManager).toString()
}.getOrDefault(packageName)

private data class DayRange(val startMillis: Long, val endMillis: Long)

private fun currentDayRange(): DayRange {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    return DayRange(
        today.atStartOfDay(zone).toInstant().toEpochMilli(),
        today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    )
}
