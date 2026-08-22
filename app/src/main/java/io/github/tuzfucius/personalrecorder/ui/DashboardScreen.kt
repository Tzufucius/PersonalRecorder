package io.github.tuzfucius.personalrecorder.ui

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val dao = remember { AppDatabase.getInstance(context).eventDao() }
    var hasNotificationAccess by remember {
        mutableStateOf(
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)
        )
    }
    var dayRange by remember { mutableStateOf(currentDayRange()) }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasNotificationAccess = NotificationManagerCompat
                    .getEnabledListenerPackages(context)
                    .contains(context.packageName)
                dayRange = currentDayRange()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val recentEvents by remember(dao) {
        dao.getRecentEvents(limit = 30)
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val todayCount by remember(dao, dayRange) {
        dao.getEventCount(dayRange.startMillis, dayRange.endMillis)
    }.collectAsStateWithLifecycle(initialValue = 0)

    Scaffold(
        topBar = { TopAppBar(title = { Text("Personal Recorder") }) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                PermissionCard(
                    enabled = hasNotificationAccess,
                    onOpenSettings = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                )
            }
            item {
                Column {
                    Text("今日采集", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "$todayCount 条",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            item {
                Text("最近事件", style = MaterialTheme.typography.titleMedium)
            }
            if (recentEvents.isEmpty()) {
                item {
                    Text(
                        text = "暂无通知事件",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(recentEvents, key = { it.id }) { eventEntity ->
                    EventRow(event = eventEntity.toPersonalEvent())
                    HorizontalDivider()
                }
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
                Text(
                    text = "●",
                    color = if (enabled) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(if (enabled) "已授权" else "未授权")
            }
            if (!enabled) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = onOpenSettings) {
                    Text("授权通知访问")
                }
            }
        }
    }
}

@Composable
private fun EventRow(event: PersonalEvent) {
    val context = LocalContext.current
    val appLabel = remember(event.packageName) {
        runCatching {
            context.packageManager
                .getApplicationInfo(event.packageName, 0)
                .loadLabel(context.packageManager)
                .toString()
        }.getOrDefault(event.packageName)
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = "${DateUtils.formatDateTime(context, event.timestamp, DateUtils.FORMAT_SHOW_TIME)}  $appLabel",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        event.title?.takeIf { it.isNotBlank() }?.let {
            Text(text = it, style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            text = event.content?.takeIf { it.isNotBlank() } ?: "（无正文）",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private data class DayRange(val startMillis: Long, val endMillis: Long)

private fun currentDayRange(): DayRange {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    return DayRange(
        startMillis = today.atStartOfDay(zone).toInstant().toEpochMilli(),
        endMillis = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    )
}
