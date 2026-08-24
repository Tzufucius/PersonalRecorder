package io.github.tuzfucius.personalrecorder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.tuzfucius.personalrecorder.background.BackgroundRuntimeState
import io.github.tuzfucius.personalrecorder.background.ListenerRuntimeStatus
import io.github.tuzfucius.personalrecorder.R

@Composable
fun RuntimeStatusCard(
    state: BackgroundRuntimeState,
    githubConnected: Boolean,
    onConflictsClick: () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(context.getString(R.string.background_running), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            val listenerLabel = when (state.listenerStatus) {
                ListenerRuntimeStatus.CONNECTED -> context.getString(R.string.realtime_connected)
                ListenerRuntimeStatus.DISCONNECTED -> context.getString(R.string.realtime_disconnected)
                ListenerRuntimeStatus.UNKNOWN -> context.getString(R.string.realtime_unknown)
            }
            StatusRow(context.getString(R.string.notification_access), listenerLabel, state.listenerStatus == ListenerRuntimeStatus.CONNECTED)
            Text(context.getString(R.string.recent_collection, formatRuntimeTime(context, state.lastEventAt)), style = MaterialTheme.typography.bodySmall)
            StatusRow("GitHub", if (githubConnected) context.getString(R.string.status_connected) else context.getString(R.string.status_not_connected), githubConnected)
            Text(context.getString(R.string.pending_transfer, state.pendingUploads, state.pendingDownloads))
            Text(context.getString(R.string.conflicts_value, state.conflicts))
            if (state.conflicts > 0) {
                TextButton(onClick = onConflictsClick) { Text(context.getString(R.string.view_conflicts, state.conflicts)) }
            }
            Text(context.getString(R.string.last_sync, formatRuntimeTime(context, state.lastSyncSuccessAt)), style = MaterialTheme.typography.bodySmall)
            state.lastSyncError?.let { Text(localizedSyncError(context, it), color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, healthy: Boolean) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Text(value, color = if (healthy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
    }
}

private fun formatRuntimeTime(context: android.content.Context, timestamp: Long?): String = timestamp?.let {
    java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it))
} ?: context.getString(R.string.no_value)
