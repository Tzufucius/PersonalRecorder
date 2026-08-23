package io.github.tuzfucius.personalrecorder.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import io.github.tuzfucius.personalrecorder.settings.CloudSyncSettings
import io.github.tuzfucius.personalrecorder.settings.CloudSyncSettingsState
import io.github.tuzfucius.personalrecorder.sync.CloudBackendType
import io.github.tuzfucius.personalrecorder.sync.GOOGLE_DRIVE_FILE_SCOPE
import io.github.tuzfucius.personalrecorder.sync.SyncFrequency
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val context = LocalContext.current
    val settingsState by viewModel.settingsStore.state.collectAsStateWithLifecycle(
        initialValue = CloudSyncSettingsState.Ready(CloudSyncSettings())
    )
    val settings = (settingsState as? CloudSyncSettingsState.Ready)?.settings
    val dao = viewModel.database.eventDao()
    val githubPending by remember(dao) { dao.getPendingArchiveSegments(CloudBackendType.GITHUB.name) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val drivePending by remember(dao) { dao.getPendingArchiveSegments(CloudBackendType.GOOGLE_DRIVE.name) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val githubLastSync by remember(dao) { dao.getLastSyncedAt(CloudBackendType.GITHUB.name) }
        .collectAsStateWithLifecycle(initialValue = null)
    val driveLastSync by remember(dao) { dao.getLastSyncedAt(CloudBackendType.GOOGLE_DRIVE.name) }
        .collectAsStateWithLifecycle(initialValue = null)
    val workInfos by viewModel.scheduler.observeNowWork().collectAsStateWithLifecycle(initialValue = emptyList())
    val githubDeviceState by viewModel.githubDeviceState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val activity = context as? Activity

    val googleAuthLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            runCatching {
                Identity.getAuthorizationClient(activity ?: return@rememberLauncherForActivityResult)
                    .getAuthorizationResultFromIntent(result.data)
            }.onSuccess { authorizationResult ->
                if (!authorizationResult.accessToken.isNullOrBlank()) viewModel.markGoogleDriveConnected()
            }
        }
    }

    githubDeviceState?.let { state ->
        val device = state.deviceCode
        AlertDialog(
            onDismissRequest = { viewModel.cancelGithubDeviceFlow() },
            title = { Text("连接 GitHub") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when {
                        device != null -> {
                            Text("验证码")
                            Text(device.userCode, style = MaterialTheme.typography.headlineSmall)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("复制验证码", modifier = Modifier.weight(1f))
                                IconButton(onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("GitHub 验证码", device.userCode))
                                }) { Icon(Icons.Default.ContentCopy, contentDescription = "复制验证码") }
                            }
                            Text("请在 GitHub 授权页面输入此代码。")
                        }
                        else -> Text(state.message ?: "GitHub 连接失败")
                    }
                }
            },
            confirmButton = {
                if (device != null) {
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(device.verificationUri)))
                    }) {
                        Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Text("打开 GitHub")
                    }
                }
            },
            dismissButton = { OutlinedButton(onClick = { viewModel.cancelGithubDeviceFlow() }) { Text("取消") } },
        )
    }

    Scaffold(topBar = { TopAppBar(title = { Text("设置") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text("云端同步", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "云端只保存半日 JSONL 归档，Room 仍是本地实时数据源。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        connected = settings.githubConnected,
                        account = settings.githubUsername,
                        onEnabledChange = viewModel::setGithubEnabled,
                        onConnect = viewModel::startGithubDeviceFlow,
                        onDisconnect = viewModel::disconnectGithub,
                    )
                }
                item {
                    BackendCard(
                        title = "Google Drive",
                        enabled = settings.googleDriveEnabled,
                        pendingCount = drivePending.size,
                        lastSync = driveLastSync,
                        connected = settings.googleDriveConnected,
                        account = null,
                        onEnabledChange = viewModel::setGoogleDriveEnabled,
                        onConnect = {
                            activity?.let { currentActivity ->
                                val request = AuthorizationRequest.builder()
                                    .setRequestedScopes(mutableListOf(Scope(GOOGLE_DRIVE_FILE_SCOPE)))
                                    .build()
                                Identity.getAuthorizationClient(currentActivity).authorize(request)
                                    .addOnSuccessListener { result ->
                                        if (result.hasResolution() && result.pendingIntent != null) {
                                            googleAuthLauncher.launch(
                                                IntentSenderRequest.Builder(result.pendingIntent!!.intentSender).build()
                                            )
                                        } else if (!result.accessToken.isNullOrBlank()) {
                                            viewModel.markGoogleDriveConnected()
                                        }
                                    }
                            }
                        },
                        onDisconnect = viewModel::disconnectGoogleDrive,
                    )
                }
                item {
                    FrequencyCard(selected = settings.frequency, onSelected = viewModel::setFrequency)
                }
                item {
                    val state = workInfos.firstOrNull()?.state
                    Button(onClick = viewModel::enqueueNow, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Sync, contentDescription = null)
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Text(
                            when (state) {
                                androidx.work.WorkInfo.State.RUNNING -> "同步中…"
                                androidx.work.WorkInfo.State.ENQUEUED -> "等待网络"
                                else -> "立即同步"
                            }
                        )
                    }
                }
            }
            message?.let { current -> item { Text(current, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
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
    account: String?,
    onEnabledChange: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(if (connected) Icons.Default.CloudDone else Icons.Default.Cloud, contentDescription = null)
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier.testTag("${title.lowercase().replace(' ', '-')}-sync-switch"),
                )
            }
            HorizontalDivider()
            Text(if (connected) "已连接" else "未连接", color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            account?.let { Text("账号：$it") }
            Text("待同步归档：$pendingCount")
            Text("最近同步：${formatSyncTime(lastSync)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (connected) OutlinedButton(onClick = onDisconnect) { Text("断开") }
                else Button(onClick = onConnect) { Text("连接") }
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
                    FilterChip(selected = selected == frequency, onClick = { onSelected(frequency) }, label = { Text(frequencyLabel(frequency)) })
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
