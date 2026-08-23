package io.github.tuzfucius.personalrecorder.background

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.backgroundRuntimeDataStore by preferencesDataStore(name = "background_runtime")

data class BackgroundRuntimeState(
    val listenerConnected: Boolean = false,
    val lastListenerConnectedAt: Long? = null,
    val lastListenerDisconnectedAt: Long? = null,
    val lastEventAt: Long? = null,
    val lastArchiveAt: Long? = null,
    val lastSyncAttemptAt: Long? = null,
    val lastSyncSuccessAt: Long? = null,
    val lastSyncError: String? = null,
    val pendingUploads: Int = 0,
    val pendingDownloads: Int = 0,
    val conflicts: Int = 0,
    val lastHealthCheckAt: Long? = null,
    val lastFatalErrorTime: Long? = null,
    val lastFatalComponent: String? = null,
    val lastFatalSummary: String? = null,
)

class BackgroundRuntimeStateStore(context: Context) {
    private val appContext = context.applicationContext

    val state: Flow<BackgroundRuntimeState> = appContext.backgroundRuntimeDataStore.data
        .map { preferences ->
            BackgroundRuntimeState(
                listenerConnected = preferences[LISTENER_CONNECTED] ?: false,
                lastListenerConnectedAt = preferences[LISTENER_CONNECTED_AT],
                lastListenerDisconnectedAt = preferences[LISTENER_DISCONNECTED_AT],
                lastEventAt = preferences[LAST_EVENT_AT],
                lastArchiveAt = preferences[LAST_ARCHIVE_AT],
                lastSyncAttemptAt = preferences[LAST_SYNC_ATTEMPT_AT],
                lastSyncSuccessAt = preferences[LAST_SYNC_SUCCESS_AT],
                lastSyncError = preferences[LAST_SYNC_ERROR],
                pendingUploads = preferences[PENDING_UPLOADS] ?: 0,
                pendingDownloads = preferences[PENDING_DOWNLOADS] ?: 0,
                conflicts = preferences[CONFLICTS] ?: 0,
                lastHealthCheckAt = preferences[LAST_HEALTH_CHECK_AT],
                lastFatalErrorTime = preferences[LAST_FATAL_ERROR_TIME],
                lastFatalComponent = preferences[LAST_FATAL_COMPONENT],
                lastFatalSummary = preferences[LAST_FATAL_SUMMARY],
            )
        }
        .catch { emit(BackgroundRuntimeState()) }

    suspend fun markListenerConnected(now: Long = System.currentTimeMillis()) {
        appContext.backgroundRuntimeDataStore.edit { preferences ->
            preferences[LISTENER_CONNECTED] = true
            preferences[LISTENER_CONNECTED_AT] = now
        }
    }

    suspend fun markListenerDisconnected(now: Long = System.currentTimeMillis()) {
        appContext.backgroundRuntimeDataStore.edit { preferences ->
            preferences[LISTENER_CONNECTED] = false
            preferences[LISTENER_DISCONNECTED_AT] = now
        }
    }

    suspend fun markEvent(now: Long = System.currentTimeMillis()) = setLong(LAST_EVENT_AT, now)
    suspend fun markArchive(now: Long = System.currentTimeMillis()) = setLong(LAST_ARCHIVE_AT, now)
    suspend fun markSyncAttempt(now: Long = System.currentTimeMillis()) = setLong(LAST_SYNC_ATTEMPT_AT, now)

    suspend fun markSyncSuccess(now: Long = System.currentTimeMillis()) {
        appContext.backgroundRuntimeDataStore.edit { preferences ->
            preferences[LAST_SYNC_SUCCESS_AT] = now
            preferences.remove(LAST_SYNC_ERROR)
        }
    }

    suspend fun markSyncError(message: String) {
        appContext.backgroundRuntimeDataStore.edit { preferences -> preferences[LAST_SYNC_ERROR] = message.take(240) }
    }

    suspend fun updateCounts(pendingUploads: Int, pendingDownloads: Int, conflicts: Int) {
        appContext.backgroundRuntimeDataStore.edit { preferences ->
            preferences[PENDING_UPLOADS] = pendingUploads
            preferences[PENDING_DOWNLOADS] = pendingDownloads
            preferences[CONFLICTS] = conflicts
        }
    }

    suspend fun markHealthCheck(now: Long = System.currentTimeMillis()) = setLong(LAST_HEALTH_CHECK_AT, now)

    suspend fun recordFatal(component: String, summary: String, now: Long = System.currentTimeMillis()) {
        appContext.backgroundRuntimeDataStore.edit { preferences ->
            preferences[LAST_FATAL_ERROR_TIME] = now
            preferences[LAST_FATAL_COMPONENT] = component.take(80)
            preferences[LAST_FATAL_SUMMARY] = summary.take(240)
        }
    }

    private suspend fun setLong(key: androidx.datastore.preferences.core.Preferences.Key<Long>, value: Long) {
        appContext.backgroundRuntimeDataStore.edit { preferences -> preferences[key] = value }
    }

    private companion object {
        val LISTENER_CONNECTED = booleanPreferencesKey("listener_connected")
        val LISTENER_CONNECTED_AT = longPreferencesKey("listener_connected_at")
        val LISTENER_DISCONNECTED_AT = longPreferencesKey("listener_disconnected_at")
        val LAST_EVENT_AT = longPreferencesKey("last_event_at")
        val LAST_ARCHIVE_AT = longPreferencesKey("last_archive_at")
        val LAST_SYNC_ATTEMPT_AT = longPreferencesKey("last_sync_attempt_at")
        val LAST_SYNC_SUCCESS_AT = longPreferencesKey("last_sync_success_at")
        val LAST_SYNC_ERROR = stringPreferencesKey("last_sync_error")
        val PENDING_UPLOADS = intPreferencesKey("pending_uploads")
        val PENDING_DOWNLOADS = intPreferencesKey("pending_downloads")
        val CONFLICTS = intPreferencesKey("conflicts")
        val LAST_HEALTH_CHECK_AT = longPreferencesKey("last_health_check_at")
        val LAST_FATAL_ERROR_TIME = longPreferencesKey("last_fatal_error_time")
        val LAST_FATAL_COMPONENT = stringPreferencesKey("last_fatal_component")
        val LAST_FATAL_SUMMARY = stringPreferencesKey("last_fatal_summary")
    }
}
