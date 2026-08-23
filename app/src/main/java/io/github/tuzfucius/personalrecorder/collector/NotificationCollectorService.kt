package io.github.tuzfucius.personalrecorder.collector

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.content.ComponentName
import io.github.tuzfucius.personalrecorder.background.BackgroundHealthWorker
import io.github.tuzfucius.personalrecorder.background.BackgroundRuntimeStateStore
import io.github.tuzfucius.personalrecorder.PersonalRecorderApplication
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
    private val sourceRegistry by lazy {
        NotificationSourceRegistry(
            dao = database.notificationSourceDao(),
            metadataResolver = AndroidNotificationSourceMetadataResolver(applicationContext),
        )
    }
    private val collectorPipeline by lazy {
        NotificationCollectorPipeline(
            ownPackageName = applicationContext.packageName,
            observeSource = sourceRegistry::observe,
            persistEvent = { event ->
                database.eventDao().insertEvent(EventEntity.fromPersonalEvent(event))
                runtimeStateStore.markEvent()
            },
            onSourceObserved = { packageName ->
                Log.i(TAG, "Notification source observed: package=$packageName")
            },
            onSourceObservationFailure = { packageName, error ->
                Log.w(TAG, "Unable to register notification source: package=$packageName", error)
            },
            onSettingsReadFailure = { error ->
                Log.w(TAG, "Unable to read notification filter settings", error)
            },
            onEventPersistenceFailure = { error ->
                Log.w(TAG, "Unable to persist notification event", error)
            },
        )
    }

    override fun onListenerConnected() {
        Log.i(TAG, "Notification listener connected")
        serviceScope.launch {
            val application = application as? PersonalRecorderApplication
            runtimeStateStore.markListenerConnected(application?.processInstanceId)
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
            collectorPipeline.collect(
                packageName = sbn.packageName,
                readSettings = filterSettingsStore::readSettings,
                parseEvent = { NotificationParser.parse(sbn) },
            )
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
