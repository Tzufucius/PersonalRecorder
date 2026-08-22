package io.github.tuzfucius.personalrecorder.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tuzfucius.personalrecorder.data.AppDatabase
import io.github.tuzfucius.personalrecorder.settings.CloudSyncSettings
import io.github.tuzfucius.personalrecorder.settings.CloudSyncSettingsState
import io.github.tuzfucius.personalrecorder.settings.CloudSyncSettingsStore
import io.github.tuzfucius.personalrecorder.sync.CloudBackendType
import io.github.tuzfucius.personalrecorder.sync.CloudSyncRuntime
import io.github.tuzfucius.personalrecorder.sync.SyncFrequency
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { CloudSyncSettingsStore(context) }
    val database = remember { AppDatabase.getInstance(context) }
    val dao = remember(database) { database.eventDao() }
    val settingsState by store.state.collectAsStateWithLifecycle(
        initialValue = CloudSyncSettingsState.Ready(CloudSyncSettings())
    )
    val settings = (settingsState as? CloudSyncSettingsState.Ready)?.settings
    val githubPending by remember(dao) { dao.getPendingArchiveSegments(CloudBackendType.GITHUB.name) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val drivePending by remember(dao) { dao.getPendingArchiveSegments(CloudBackendType.GOOGLE_DRIVE.name) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val githubLastSync by remember(dao) { dao.getLastSyncedAt(CloudBackendType.GITHUB.name) }
        .collectAsStateWithLifecycle(initialValue = null)
    val driveLastSync by remember(dao) { dao.getLastSyncedAt(CloudBackendType.GOOGLE_DRIVE.name) }
        .collectAsStateWithLifecycle(initialValue = null)
    var syncing by rememberSaveable { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(topBar = { TopAppBar(title = { Text("设置") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text("云端同步", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "云端只保存半日 JSONL 归档，Room 仍是本地实时数据源。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (settingsState is CloudSyncSettingsState.Error) {
                item { Text("同步设置读取失败", color = MaterialTheme.colorScheme.error) }
            }
            if (settings != null) {
                item {
                    BackendCard(
                        title = "GitHub",
                        enabled = settings.githubEnabled,
                        pendingCount = githubPending.size,
                        lastSync = githubLastSync,
                        connected = false,
                        onEnabledChange = { enabled -> scope.launch { store.setGithubEnabled(enabled) } },
                        onConnect = { message = "GitHub OAuth 需要配置可信 token-exchange 服务" },
                        onDisconnect = { message = "GitHub 当前未连接" },
                    )
                }
                item {
                    BackendCard(
                        title = "Google Drive",
                        enabled = settings.googleDriveEnabled,
                        pendingCount = drivePending.size,
                        lastSync = driveLastSync,
                        connected = false,
                        onEnabledChange = { enabled -> scope.launch { store.setGoogleDriveEnabled(enabled) } },
                        onConnect = { message = "Google Drive 连接需要配置 OAuth Client" },
                        onDisconnect = { message = "Google Drive 当前未连接" },
                    )
                }
                item {
                    FrequencyCard(
                        selected = settings.frequency,
                        onSelected = { frequency ->
                            scope.launch {
                                store.setFrequency(frequency)
                                CloudSyncRuntime.scheduler(context).schedule(frequency)
                            }
                        }
                    )
                }
                item {
                    Button(
                        onClick = {
                            if (!syncing) {
                                syncing = true
                                message = null
                                scope.launch {
                                    val result = runCatching { CloudSyncRuntime.syncNow(context) }
                                    syncing = false
                                    message = result.fold(
                                        onSuccess = { syncResult ->
                                            if (syncResult.needsRetry) "同步遇到网络问题，将自动重试"
                                            else "同步检查完成"
                                        },
                                        onFailure = { "同步失败：${it.message ?: "未知错误"}" }
                                    )
                                }
                            }
                        },
                        enabled = !syncing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = null)
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Text(if (syncing) "同步中…" else "立即同步")
                    }
                }
            }
            message?.let { current ->
                item { Text(current, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun BackendCard(
    title: String,
    enabled: Boolean,
    pendingCount: Int,
    lastSync: Long?,
    connected: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    if (connected) Icons.Default.CloudDone else Icons.Default.Cloud,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            HorizontalDivider()
            Text(if (connected) "已连接" else "未连接", color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            Text("待同步归档：$pendingCount")
            Text("最近同步：${formatSyncTime(lastSync)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (connected) {
                    OutlinedButton(onClick = onDisconnect) { Text("断开") }
                } else {
                    Button(onClick = onConnect) { Text("连接") }
                }
            }
        }
    }
}

@Composable
private fun FrequencyCard(selected: SyncFrequency, onSelected: (SyncFrequency) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("同步频率", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SyncFrequency.entries.forEach { frequency ->
                    FilterChip(
                        selected = selected == frequency,
                        onClick = { onSelected(frequency) },
                        label = { Text(frequencyLabel(frequency)) }
                    )
                }
            }
        }
    }
}

private fun frequencyLabel(frequency: SyncFrequency): String = when (frequency) {
    SyncFrequency.TWICE_DAILY -> "每天两次"
    SyncFrequency.DAILY -> "每天一次"
    SyncFrequency.WEEKLY -> "每周一次"
}

private fun formatSyncTime(timestamp: Long?): String = timestamp?.let {
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
} ?: "暂无"
