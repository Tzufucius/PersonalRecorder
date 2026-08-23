package io.github.tuzfucius.personalrecorder.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel as composeViewModel
import io.github.tuzfucius.personalrecorder.settings.CloudSyncSettings
import io.github.tuzfucius.personalrecorder.settings.CloudSyncSettingsState
import io.github.tuzfucius.personalrecorder.sync.CloudBackendType
import io.github.tuzfucius.personalrecorder.sync.GitHubRepository
import io.github.tuzfucius.personalrecorder.sync.SyncFrequency
import io.github.tuzfucius.personalrecorder.background.BackgroundRuntimeState
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settingsViewModel: SettingsViewModel? = null) {
    val viewModel = settingsViewModel ?: runCatching { composeViewModel<SettingsViewModel>() }.getOrNull()
    if (viewModel == null) {
        SettingsScreenFallback()
        return
    }
    val settingsState by viewModel.settingsStore.state.collectAsStateWithLifecycle(
        initialValue = CloudSyncSettingsState.Ready(CloudSyncSettings())
    )
    val settings = (settingsState as? CloudSyncSettingsState.Ready)?.settings
    val currentSettings = settings ?: CloudSyncSettings()
    val dao = viewModel.database.eventDao()
    val pending by remember(dao) { dao.getPendingArchiveSegments(CloudBackendType.GITHUB.name) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val lastSync by remember(dao) { dao.getLastSyncedAt(CloudBackendType.GITHUB.name) }
        .collectAsStateWithLifecycle(initialValue = null)
    val workInfos by viewModel.scheduler.observeNowWork().collectAsStateWithLifecycle(initialValue = emptyList())
    val runtimeState by viewModel.runtimeStateStore.state.collectAsStateWithLifecycle(initialValue = BackgroundRuntimeState())
    val statusNotificationEnabled by viewModel.backgroundSettingsStore.statusNotificationEnabled.collectAsStateWithLifecycle(initialValue = false)
    val conflictCount by remember(dao) { dao.getUnresolvedConflictCount() }
        .collectAsStateWithLifecycle(initialValue = 0)
    val connecting by viewModel.connecting.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val restoreState by viewModel.restoreState.collectAsStateWithLifecycle()
    val restorePrompt by viewModel.restorePrompt.collectAsStateWithLifecycle()
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.setStatusNotificationEnabled(true)
    }
    var token by remember { mutableStateOf("") }
    var repository by remember(settings?.githubRepository) {
        mutableStateOf(settings?.githubRepository ?: GitHubRepository.DEFAULT_NAME)
    }

    LaunchedEffect(settings?.githubConnected) {
        if (settings?.githubConnected == true) token = ""
    }

    Scaffold(topBar = { TopAppBar(title = { Text("设置") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text("云端归档", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Room 保存本地实时数据，半日 JSONL 归档通过 GitHub 私有仓库保存。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (settingsState is CloudSyncSettingsState.Error) {
                item { Text("同步设置读取失败", color = MaterialTheme.colorScheme.error) }
            }
            item {
                GitHubCard(
                        settings = currentSettings,
                        pendingCount = pending.size,
                        lastSync = lastSync,
                        workState = workInfos.firstOrNull()?.state,
                        token = token,
                        repository = repository,
                        connecting = connecting,
                        onTokenChange = { token = it },
                        onRepositoryChange = { repository = it },
                        onEnabledChange = viewModel::setGithubEnabled,
                        onConnect = { viewModel.connectGithub(token, repository) },
                        onDisconnect = viewModel::disconnectGithub,
                        onSync = viewModel::enqueueNow,
                        onValidate = viewModel::validateCloud,
                        onRestore = viewModel::restoreFromGithub,
                    )
            }
            item {
                RestoreProgressCard(restoreState)
            }
            item {
                BackgroundDiagnosticsCard(
                    runtimeState = runtimeState,
                    githubConnected = currentSettings.githubConnected,
                    statusNotificationEnabled = statusNotificationEnabled,
                    onStatusNotificationChange = { enabled ->
                        if (enabled && Build.VERSION.SDK_INT >= 33) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            viewModel.setStatusNotificationEnabled(enabled)
                        }
                    },
                    onSync = viewModel::enqueueNow,
                )
            }
            item { FrequencyCard(currentSettings.frequency, viewModel::setFrequency) }
            message?.let { current ->
                item {
                    Text(current, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = viewModel::clearMessage) { Text("关闭") }
                }
            }
        }
    }
    restorePrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = viewModel::dismissRestorePrompt,
            title = { Text("发现 GitHub 历史归档") },
            text = { Text("发现 ${prompt.days} 天、${prompt.archives} 个归档，是否恢复到本机？") },
            confirmButton = {
                TextButton(onClick = viewModel::restoreFromGithub) { Text("恢复") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissRestorePrompt) { Text("暂不") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreenFallback() {
    Scaffold(topBar = { TopAppBar(title = { Text("设置") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { Text("云端归档", style = MaterialTheme.typography.headlineSmall) }
            item {
                Text("GitHub 私有仓库", style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = false,
                    onCheckedChange = {},
                    modifier = Modifier.testTag("github-sync-switch"),
                )
                OutlinedTextField(
                    value = "",
                    onValueChange = {},
                    label = { Text("Personal Access Token") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().testTag("github-token-input"),
                )
                OutlinedTextField(
                    value = GitHubRepository.DEFAULT_NAME,
                    onValueChange = {},
                    label = { Text("仓库名称") },
                    modifier = Modifier.fillMaxWidth().testTag("github-repository-input"),
                )
            }
            item { Text("每天两次") }
            item { Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("立即同步") } }
        }
    }
}

@Composable
private fun GitHubCard(
    settings: CloudSyncSettings,
    pendingCount: Int,
    lastSync: Long?,
    workState: androidx.work.WorkInfo.State?,
    token: String,
    repository: String,
    connecting: Boolean,
    onTokenChange: (String) -> Unit,
    onRepositoryChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSync: () -> Unit,
    onValidate: () -> Unit,
    onRestore: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    if (settings.githubConnected) Icons.Default.CloudDone else Icons.Default.Cloud,
                    contentDescription = null,
                )
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text("GitHub 私有仓库", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(
                    checked = settings.githubEnabled,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier.testTag("github-sync-switch"),
                )
            }
            HorizontalDivider()
            Text(
                if (settings.githubConnected) "状态：已连接" else "状态：未连接",
                color = if (settings.githubConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            if (settings.githubConnected) {
                settings.githubUsername?.let { Text("账号：$it") }
                Text("仓库：${settings.githubRepository}")
                Text("待同步归档：$pendingCount")
                Text("最近同步：${formatSyncTime(lastSync)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDisconnect) { Text("断开 GitHub") }
                    Button(onClick = onSync) {
                        Icon(Icons.Default.Sync, contentDescription = null)
                        Text(
                            when (workState) {
                                androidx.work.WorkInfo.State.RUNNING -> "同步中"
                                androidx.work.WorkInfo.State.ENQUEUED -> "等待网络"
                                else -> "立即同步"
                            },
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onValidate) { Text("校验云端归档") }
                    OutlinedButton(onClick = onRestore) { Text("从 GitHub 恢复历史") }
                }
            } else {
                OutlinedTextField(
                    value = token,
                    onValueChange = onTokenChange,
                    label = { Text("Personal Access Token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().testTag("github-token-input"),
                )
                OutlinedTextField(
                    value = repository,
                    onValueChange = onRepositoryChange,
                    label = { Text("仓库名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("github-repository-input"),
                )
                Button(
                    onClick = onConnect,
                    enabled = token.isNotBlank() && repository.isNotBlank() && !connecting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (connecting) "正在验证 GitHub…" else "连接 GitHub")
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
                        label = { Text(frequencyLabel(frequency)) },
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

@Composable
private fun RestoreProgressCard(state: RestoreUiState) {
    if (state.state == io.github.tuzfucius.personalrecorder.sync.RestoreState.IDLE) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("归档协调", style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    state.running && state.state == io.github.tuzfucius.personalrecorder.sync.RestoreState.DISCOVERING -> "正在扫描 GitHub…"
                    state.running -> "正在恢复归档…"
                    state.state == io.github.tuzfucius.personalrecorder.sync.RestoreState.COMPLETED -> "协调完成"
                    else -> "协调失败"
                }
            )
            if (state.discovered > 0) Text("发现 ${state.discovered} 个远端文件")
            if (state.downloaded > 0 || state.uploaded > 0 || state.skipped > 0) {
                Text("下载：${state.downloaded}  上传：${state.uploaded}  跳过：${state.skipped}")
            }
            if (state.conflicts > 0) {
                Text("冲突：${state.conflicts}", color = MaterialTheme.colorScheme.error)
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun BackgroundDiagnosticsCard(
    runtimeState: BackgroundRuntimeState,
    githubConnected: Boolean,
    statusNotificationEnabled: Boolean,
    onStatusNotificationChange: (Boolean) -> Unit,
    onSync: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("后台运行", style = MaterialTheme.typography.titleMedium)
            StatusLine("通知访问", runtimeState.listenerConnected, if (runtimeState.listenerConnected) "正常" else "未连接")
            StatusLine("GitHub", githubConnected, if (githubConnected) "已连接" else "未连接")
            Text("待上传：${runtimeState.pendingUploads}    待下载：${runtimeState.pendingDownloads}")
            Text("冲突：${runtimeState.conflicts}")
            Text("最近采集：${formatSyncTime(runtimeState.lastEventAt)}")
            Text("最近健康检查：${formatSyncTime(runtimeState.lastHealthCheckAt)}")
            runtimeState.lastSyncError?.let { Text("最近错误：$it", color = MaterialTheme.colorScheme.error) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("后台状态通知", modifier = Modifier.weight(1f))
                Switch(checked = statusNotificationEnabled, onCheckedChange = onStatusNotificationChange)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }) { Text("通知访问设置") }
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }) { Text("电池设置") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:${context.packageName}"))
                    context.startActivity(intent)
                }) { Text("应用后台设置") }
                Button(onClick = onSync) { Text("立即同步") }
            }
            Text(
                "Android 与各厂商系统不保证普通应用永久存活，请按系统提示确认后台权限。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun StatusLine(label: String, healthy: Boolean, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Text(if (healthy) "● $value" else "△ $value", color = if (healthy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
    }
}
