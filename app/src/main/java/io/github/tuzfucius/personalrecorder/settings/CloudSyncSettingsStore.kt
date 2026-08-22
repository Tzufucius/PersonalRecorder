package io.github.tuzfucius.personalrecorder.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.tuzfucius.personalrecorder.sync.SyncFrequency
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.cloudSyncDataStore by preferencesDataStore(name = "cloud_sync_settings")

data class CloudSyncSettings(
    val githubEnabled: Boolean = false,
    val googleDriveEnabled: Boolean = false,
    val frequency: SyncFrequency = SyncFrequency.TWICE_DAILY
)

sealed interface CloudSyncSettingsState {
    data class Ready(val settings: CloudSyncSettings) : CloudSyncSettingsState
    data class Error(val cause: Throwable) : CloudSyncSettingsState
}

class CloudSyncSettingsStore(context: Context) {
    private val appContext = context.applicationContext

    val state: Flow<CloudSyncSettingsState> = appContext.cloudSyncDataStore.data
        .map { preferences ->
            CloudSyncSettingsState.Ready(
                CloudSyncSettings(
                    githubEnabled = preferences[GITHUB_ENABLED] ?: false,
                    googleDriveEnabled = preferences[GOOGLE_DRIVE_ENABLED] ?: false,
                    frequency = preferences[FREQUENCY]
                        ?.let { value -> runCatching { SyncFrequency.valueOf(value) }.getOrNull() }
                        ?: SyncFrequency.TWICE_DAILY
                )
            )
        }
        .catch { emit(CloudSyncSettingsState.Error(it)) }

    suspend fun setGithubEnabled(enabled: Boolean) {
        appContext.cloudSyncDataStore.edit { it[GITHUB_ENABLED] = enabled }
    }

    suspend fun setGoogleDriveEnabled(enabled: Boolean) {
        appContext.cloudSyncDataStore.edit { it[GOOGLE_DRIVE_ENABLED] = enabled }
    }

    suspend fun setFrequency(frequency: SyncFrequency) {
        appContext.cloudSyncDataStore.edit { it[FREQUENCY] = frequency.name }
    }

    private companion object {
        val GITHUB_ENABLED = booleanPreferencesKey("github_enabled")
        val GOOGLE_DRIVE_ENABLED = booleanPreferencesKey("google_drive_enabled")
        val FREQUENCY = stringPreferencesKey("frequency")
    }
}
