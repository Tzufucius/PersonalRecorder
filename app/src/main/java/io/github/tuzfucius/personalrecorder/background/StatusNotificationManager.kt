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
                appContext.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = appContext.getString(R.string.notification_channel_description)
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
            ListenerRuntimeStatus.CONNECTED -> appContext.getString(R.string.listener_normal)
            ListenerRuntimeStatus.DISCONNECTED -> appContext.getString(R.string.listener_disconnected)
            ListenerRuntimeStatus.UNKNOWN -> appContext.getString(R.string.listener_unknown)
        }
        val sync = state.lastSyncSuccessAt?.let { formatTime(it) } ?: appContext.getString(R.string.no_value)
        val content = buildList {
            add(listener)
            add(appContext.getString(R.string.notification_today_count, todayCount))
            add(appContext.getString(R.string.notification_pending, state.pendingUploads, state.pendingDownloads))
            add(appContext.getString(R.string.notification_last_sync, sync))
            state.lastSyncError?.let { add(appContext.getString(R.string.notification_error, it.take(80))) }
        }.joinToString("\n")
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
            .setContentTitle(appContext.getString(R.string.app_name))
            .setContentText(listener)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openPending)
            .addAction(0, appContext.getString(R.string.sync_now), syncPending)
            .addAction(0, appContext.getString(R.string.open_diagnostics), openPending)
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
