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
import androidx.compose.foundation.text.KeyboardOptions
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
    val connecting by viewModel.connecting.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
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
