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

@Composable
fun RuntimeStatusCard(
    state: BackgroundRuntimeState,
    githubConnected: Boolean,
    onConflictsClick: () -> Unit = {},
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("运行状态", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            val listenerLabel = when (state.listenerStatus) {
                ListenerRuntimeStatus.CONNECTED -> "● 正常"
                ListenerRuntimeStatus.DISCONNECTED -> "△ 已断开"
                ListenerRuntimeStatus.UNKNOWN -> "? 状态未知"
            }
            StatusRow("通知采集", listenerLabel, state.listenerStatus == ListenerRuntimeStatus.CONNECTED)
            Text("最近采集：${formatRuntimeTime(state.lastEventAt)}", style = MaterialTheme.typography.bodySmall)
            StatusRow("GitHub", if (githubConnected) "● 已连接" else "△ 未连接", githubConnected)
            Text("待上传：${state.pendingUploads}    待下载：${state.pendingDownloads}    冲突：${state.conflicts}")
            if (state.conflicts > 0) {
                TextButton(onClick = onConflictsClick) { Text("查看冲突详情") }
            }
            Text("最近同步：${formatRuntimeTime(state.lastSyncSuccessAt)}", style = MaterialTheme.typography.bodySmall)
            state.lastSyncError?.let { Text("最近错误：$it", color = MaterialTheme.colorScheme.error) }
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

private fun formatRuntimeTime(timestamp: Long?): String = timestamp?.let {
    java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it))
} ?: "暂无"
