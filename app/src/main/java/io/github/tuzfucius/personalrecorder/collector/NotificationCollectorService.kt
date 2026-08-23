package io.github.tuzfucius.personalrecorder.collector

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.content.ComponentName
import io.github.tuzfucius.personalrecorder.background.BackgroundHealthWorker
import io.github.tuzfucius.personalrecorder.background.BackgroundRuntimeStateStore
import io.github.tuzfucius.personalrecorder.data.AppDatabase
import io.github.tuzfucius.personalrecorder.data.EventEntity
import io.github.tuzfucius.personalrecorder.settings.FilterSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class NotificationCollectorService : NotificationListenerService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val database by lazy { AppDatabase.getInstance(applicationContext) }
    private val filterSettingsStore by lazy { FilterSettingsStore(applicationContext) }
    private val runtimeStateStore by lazy { BackgroundRuntimeStateStore(applicationContext) }

    override fun onListenerConnected() {
        Log.i(TAG, "Notification listener connected")
        serviceScope.launch {
            runtimeStateStore.markListenerConnected()
            BackgroundHealthWorker.enqueueNow(applicationContext)
        }
    }

    override fun onListenerDisconnected() {
        Log.i(TAG, "Notification listener disconnected")
        serviceScope.launch {
            runtimeStateStore.markListenerDisconnected()
            runCatching {
                requestRebind(ComponentName(applicationContext, NotificationCollectorService::class.java))
            }.onFailure { error -> Log.w(TAG, "requestRebind failed", error) }
            BackgroundHealthWorker.enqueueNow(applicationContext)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        serviceScope.launch {
            val settings = filterSettingsStore.readSettings().getOrNull() ?: run {
                Log.w(TAG, "Unable to read notification filter settings")
                return@launch
            }
            if (!NotificationFilter.shouldCollect(sbn, packageName, settings)) return@launch
            val event = NotificationParser.parse(sbn) ?: return@launch
            runCatching {
                database.eventDao().insertEvent(EventEntity.fromPersonalEvent(event))
                runtimeStateStore.markEvent()
            }.onFailure { error ->
                Log.w(TAG, "Unable to persist notification event", error)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Removal is not a new personal event in this MVP, so it is intentionally ignored.
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "PR-Collector"
    }
}
