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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
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
import io.github.tuzfucius.personalrecorder.background.BackgroundDiagnostics
import io.github.tuzfucius.personalrecorder.background.ListenerRuntimeStatus
import io.github.tuzfucius.personalrecorder.data.ArchiveConflictEntity
import androidx.core.app.NotificationManagerCompat
import java.text.DateFormat
import java.util.Date
import io.github.tuzfucius.personalrecorder.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settingsViewModel: SettingsViewModel? = null) {
    val context = LocalContext.current
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
    val hideFromRecents by viewModel.backgroundSettingsStore.hideFromRecents.collectAsStateWithLifecycle(initialValue = false)
    val conflicts by remember(dao) { dao.getUnresolvedConflicts() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
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
    var showConflictDetails by rememberSaveable { mutableStateOf(false) }
    var repository by remember(settings?.githubRepository) {
        mutableStateOf(settings?.githubRepository ?: GitHubRepository.DEFAULT_NAME)
    }

    LaunchedEffect(settings?.githubConnected) {
        if (settings?.githubConnected == true) token = ""
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
            item {
                Text(context.getString(R.string.cloud_archive), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    context.getString(R.string.room_archive_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (settingsState is CloudSyncSettingsState.Error) {
                item { Text(context.getString(R.string.settings_read_error), color = MaterialTheme.colorScheme.error) }
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
                RestoreProgressCard(restoreState, context)
            }
            item {
                BackgroundDiagnosticsCard(
                    runtimeState = runtimeState,
                    githubConnected = currentSettings.githubConnected,
                    statusNotificationEnabled = statusNotificationEnabled,
                    hideFromRecents = hideFromRecents,
                    conflicts = conflicts,
                    onStatusNotificationChange = { enabled ->
                        if (enabled && Build.VERSION.SDK_INT >= 33) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            viewModel.setStatusNotificationEnabled(enabled)
                        }
                    },
                    onSync = viewModel::enqueueNow,
                    onHideFromRecentsChange = viewModel::setHideFromRecents,
                    onShowConflicts = { showConflictDetails = true },
                )
            }
            if (showConflictDetails) {
                item {
                    ConflictDetailsCard(
                        conflicts = conflicts,
                        onClose = { showConflictDetails = false },
                        onResolve = viewModel::resolveConflict,
                    )
                }
            }
            item { FrequencyCard(currentSettings.frequency, viewModel::setFrequency) }
            message?.let { current ->
                item {
                    Text(current.resolve(context), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = viewModel::clearMessage) { Text(context.getString(R.string.close)) }
                }
            }
    }
    restorePrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = viewModel::dismissRestorePrompt,
            title = { Text(context.getString(R.string.github_history_found)) },
            text = { Text(context.getString(R.string.restore_prompt, prompt.days, prompt.archives)) },
            confirmButton = {
                TextButton(onClick = viewModel::restoreFromGithub) { Text(context.getString(R.string.restore)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissRestorePrompt) { Text(context.getString(R.string.not_now)) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreenFallback() {
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
            item { Text(context.getString(R.string.cloud_archive), style = MaterialTheme.typography.headlineSmall) }
            item {
                Text(context.getString(R.string.github_private_repository), style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = false,
                    onCheckedChange = {},
                    modifier = Modifier.testTag("github-sync-switch"),
                )
                OutlinedTextField(
                    value = "",
                    onValueChange = {},
                    label = { Text(context.getString(R.string.personal_access_token)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().testTag("github-token-input"),
                )
                OutlinedTextField(
                    value = GitHubRepository.DEFAULT_NAME,
                    onValueChange = {},
                    label = { Text(context.getString(R.string.repository_name)) },
                    modifier = Modifier.fillMaxWidth().testTag("github-repository-input"),
                )
            }
            item { Text(context.getString(R.string.twice_daily)) }
            item { Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text(context.getString(R.string.sync_now)) } }
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
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    if (settings.githubConnected) Icons.Default.CloudDone else Icons.Default.Cloud,
                    contentDescription = null,
                )
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text(context.getString(R.string.github_private_repository), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(
                    checked = settings.githubEnabled,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier.testTag("github-sync-switch"),
                )
            }
            HorizontalDivider()
            Text(
                context.getString(if (settings.githubConnected) R.string.status_connected else R.string.status_not_connected),
                color = if (settings.githubConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            if (settings.githubConnected) {
                settings.githubUsername?.let { Text(context.getString(R.string.account_value, it)) }
                Text(context.getString(R.string.repository_value, settings.githubRepository))
                Text(context.getString(R.string.pending_archives, pendingCount))
                Text(context.getString(R.string.last_sync, formatSyncTime(context, lastSync)), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text(context.getString(R.string.disconnect_github)) }
                    Button(onClick = onSync, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Sync, contentDescription = null)
                        Text(
                            when (workState) {
                                androidx.work.WorkInfo.State.RUNNING -> context.getString(R.string.syncing)
                                androidx.work.WorkInfo.State.ENQUEUED -> context.getString(R.string.waiting_network)
                                else -> context.getString(R.string.sync_now)
                            },
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onValidate, modifier = Modifier.fillMaxWidth()) { Text(context.getString(R.string.validate_archive)) }
                    OutlinedButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) { Text(context.getString(R.string.restore_history)) }
                }
            } else {
                OutlinedTextField(
                    value = token,
                    onValueChange = onTokenChange,
                    label = { Text(context.getString(R.string.personal_access_token)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().testTag("github-token-input"),
                )
                OutlinedTextField(
                    value = repository,
                    onValueChange = onRepositoryChange,
                    label = { Text(context.getString(R.string.repository_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("github-repository-input"),
                )
                Button(
                    onClick = onConnect,
                    enabled = token.isNotBlank() && repository.isNotBlank() && !connecting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(context.getString(if (connecting) R.string.verifying_github else R.string.connect_github))
                }
            }
        }
    }
}

@Composable
private fun FrequencyCard(selected: SyncFrequency, onSelected: (SyncFrequency) -> Unit) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(context.getString(R.string.sync_frequency), style = MaterialTheme.typography.titleMedium)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SyncFrequency.entries.forEach { frequency ->
                    FilterChip(
                        selected = selected == frequency,
                        onClick = { onSelected(frequency) },
                        label = { Text(frequencyLabel(context, frequency)) },
                    )
                }
            }
        }
    }
}

private fun frequencyLabel(context: android.content.Context, frequency: SyncFrequency): String = when (frequency) {
    SyncFrequency.TWICE_DAILY -> context.getString(R.string.twice_daily)
    SyncFrequency.DAILY -> context.getString(R.string.daily)
    SyncFrequency.WEEKLY -> context.getString(R.string.weekly)
}

private fun formatSyncTime(context: android.content.Context, timestamp: Long?): String = timestamp?.let {
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
} ?: context.getString(R.string.no_value)

@Composable
private fun RestoreProgressCard(state: RestoreUiState, context: android.content.Context) {
    if (state.state == io.github.tuzfucius.personalrecorder.sync.RestoreState.IDLE) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(context.getString(R.string.archive_coordination), style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    state.running && state.state == io.github.tuzfucius.personalrecorder.sync.RestoreState.DISCOVERING -> context.getString(R.string.scanning_github)
                    state.running -> context.getString(R.string.restoring_archive)
                    state.state == io.github.tuzfucius.personalrecorder.sync.RestoreState.COMPLETED -> context.getString(R.string.coordination_complete)
                    else -> context.getString(R.string.coordination_failed)
                }
            )
            if (state.discovered > 0) Text(context.resources.getQuantityString(R.plurals.remote_files_count, state.discovered, state.discovered))
            if (state.downloaded > 0 || state.uploaded > 0 || state.skipped > 0) {
                Text(context.getString(R.string.transfer_counts, state.downloaded, state.uploaded, state.skipped))
            }
            if (state.conflicts > 0) {
                Text(context.getString(R.string.conflicts_value, state.conflicts), color = MaterialTheme.colorScheme.error)
            }
            state.error?.let { Text(it.resolve(context), color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun BackgroundDiagnosticsCard(
    runtimeState: BackgroundRuntimeState,
    githubConnected: Boolean,
    statusNotificationEnabled: Boolean,
    hideFromRecents: Boolean,
    conflicts: List<ArchiveConflictEntity>,
    onStatusNotificationChange: (Boolean) -> Unit,
    onSync: () -> Unit,
    onHideFromRecentsChange: (Boolean) -> Unit,
    onShowConflicts: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val diagnostics = remember(context) { BackgroundDiagnostics.read(context) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(context.getString(R.string.background_running), style = MaterialTheme.typography.titleMedium)
            val listenerPermission = NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)
            StatusLine(context.getString(R.string.notification_access), listenerPermission, context.getString(if (listenerPermission) R.string.permission_granted else R.string.permission_not_granted))
            val listenerStatus = when (runtimeState.listenerStatus) {
                ListenerRuntimeStatus.CONNECTED -> context.getString(R.string.realtime_connected)
                ListenerRuntimeStatus.DISCONNECTED -> context.getString(R.string.realtime_disconnected)
                ListenerRuntimeStatus.UNKNOWN -> context.getString(R.string.realtime_unknown)
            }
            StatusLine(context.getString(R.string.realtime_status), runtimeState.listenerStatus == ListenerRuntimeStatus.CONNECTED, listenerStatus)
            StatusLine("GitHub", githubConnected, context.getString(if (githubConnected) R.string.permission_granted else R.string.permission_not_granted))
            Text(context.getString(R.string.pending_transfer, runtimeState.pendingUploads, runtimeState.pendingDownloads))
            Text(context.getString(R.string.conflicts_value, runtimeState.conflicts))
            if (conflicts.isNotEmpty()) {
                OutlinedButton(onClick = onShowConflicts, modifier = Modifier.fillMaxWidth()) { Text(context.getString(R.string.view_conflicts, conflicts.size)) }
            }
            Text(context.getString(R.string.recent_collection, formatSyncTime(context, runtimeState.lastEventAt)))
            Text(context.getString(R.string.recent_health_check, formatSyncTime(context, runtimeState.lastHealthCheckAt)))
            Text(
                context.getString(R.string.battery_optimization, context.getString(if (diagnostics.batteryOptimizationIgnored) R.string.battery_ignored else R.string.battery_limited)),
                color = if (diagnostics.batteryOptimizationIgnored) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Text(context.getString(R.string.device_value, diagnostics.manufacturer, diagnostics.model))
            Text(context.getString(R.string.status_notification, context.getString(if (statusNotificationEnabled) R.string.enabled else R.string.disabled)))
            Text(context.getString(R.string.recent_task, context.getString(if (hideFromRecents) R.string.hidden else R.string.visible)))
            Text(context.getString(R.string.last_sync, formatSyncTime(context, runtimeState.lastSyncSuccessAt)))
            diagnostics.vendorGuidance?.let {
                Text(context.getString(it), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            runtimeState.lastSyncError?.let { Text(localizedSyncError(context, it), color = MaterialTheme.colorScheme.error) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(context.getString(R.string.background_status_notification), modifier = Modifier.weight(1f))
                Switch(checked = statusNotificationEnabled, onCheckedChange = onStatusNotificationChange)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(context.getString(R.string.hide_from_recents), modifier = Modifier.weight(1f))
                Switch(checked = hideFromRecents, onCheckedChange = onHideFromRecentsChange)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }, modifier = Modifier.fillMaxWidth()) { Text(context.getString(R.string.notification_settings)) }
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }, modifier = Modifier.fillMaxWidth()) { Text(context.getString(R.string.battery_settings)) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:${context.packageName}"))
                    context.startActivity(intent)
                }, modifier = Modifier.fillMaxWidth()) { Text(context.getString(R.string.app_background_settings)) }
                Button(onClick = onSync, modifier = Modifier.fillMaxWidth()) { Text(context.getString(R.string.sync_now)) }
            }
            Text(
                context.getString(R.string.background_runtime_note),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ConflictDetailsCard(
    conflicts: List<ArchiveConflictEntity>,
    onClose: () -> Unit,
    onResolve: (String) -> Unit,
) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(context.getString(R.string.unresolved_conflicts), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text(context.getString(R.string.collapse)) }
            }
            if (conflicts.isEmpty()) {
                Text(context.getString(R.string.no_unresolved_conflicts), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                conflicts.forEach { conflict ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val conflictCount = extractConflictCount(conflict.summary)
                        Text(conflict.relativePath, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            context.getString(
                                R.string.conflict_metadata,
                                conflict.relativePath.split('/').getOrNull(3) ?: context.getString(R.string.no_value),
                                conflict.segmentId,
                                conflictCount?.toString() ?: context.getString(R.string.no_value),
                                formatSyncTime(context, conflict.createdAt),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            conflictCount?.let {
                                context.resources.getQuantityString(R.plurals.conflicting_events, it, it)
                            } ?: context.getString(R.string.conflict_unknown_summary),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            context.getString(R.string.local_remote_paths, conflict.localFilePath, conflict.remoteFilePath),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { onResolve(conflict.conflictId) }) { Text(context.getString(R.string.mark_handled)) }
                    }
                    if (conflict != conflicts.last()) HorizontalDivider()
                }
            }
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
