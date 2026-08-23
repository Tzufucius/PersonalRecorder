package io.github.tuzfucius.personalrecorder.background

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.tuzfucius.personalrecorder.MainActivity
import io.github.tuzfucius.personalrecorder.R
import java.text.DateFormat
import java.util.Date

class StatusNotificationManager(context: Context) {
    private val appContext = context.applicationContext

    fun ensureChannel() {
        val manager = appContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "后台运行状态",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Personal Recorder 监听与归档同步状态"
                setShowBadge(false)
            }
        )
    }

    fun cancel() {
        NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_ID)
    }

    fun show(state: BackgroundRuntimeState, todayCount: Int) {
        if (!canPostNotifications()) return
        ensureChannel()
        val listener = when (state.listenerStatus) {
            ListenerRuntimeStatus.CONNECTED -> "● 通知监听正常"
            ListenerRuntimeStatus.DISCONNECTED -> "⚠ 通知监听已断开"
            ListenerRuntimeStatus.UNKNOWN -> "? 通知监听状态未知"
        }
        val sync = state.lastSyncSuccessAt?.let { formatTime(it) } ?: "暂无"
        val content = buildString {
            append(listener)
            append("\n今日记录：$todayCount")
            append("\n待上传：${state.pendingUploads}  待下载：${state.pendingDownloads}")
            append("\n最近同步：$sync")
            state.lastSyncError?.let { append("\n错误：${it.take(80)}") }
        }
        val openIntent = Intent(appContext, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_DIAGNOSTICS, true)
        val syncIntent = Intent(appContext, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_SYNC_NOW, true)
        val openPending = android.app.PendingIntent.getActivity(
            appContext,
            1001,
            openIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val syncPending = android.app.PendingIntent.getActivity(
            appContext,
            1002,
            syncIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Personal Recorder")
            .setContentText(listener)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openPending)
            .addAction(0, "立即同步", syncPending)
            .addAction(0, "打开诊断", openPending)
            .build()
        NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification)
    }

    private fun canPostNotifications(): Boolean =
        android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun formatTime(timestamp: Long): String =
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timestamp))

    private companion object {
        const val CHANNEL_ID = "background_runtime"
        const val NOTIFICATION_ID = 3001
    }
}
