package io.github.tuzfucius.personalrecorder.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.tuzfucius.personalrecorder.sync.GitHubConnectionSettings
import io.github.tuzfucius.personalrecorder.sync.GitHubRepository
import io.github.tuzfucius.personalrecorder.sync.SyncFrequency
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.cloudSyncDataStore by preferencesDataStore(name = "cloud_sync_settings")

data class CloudSyncSettings(
    val githubEnabled: Boolean = false,
    val githubConnected: Boolean = false,
    val githubUsername: String? = null,
    val githubRepository: String = GitHubRepository.DEFAULT_NAME,
    val frequency: SyncFrequency = SyncFrequency.TWICE_DAILY,
)

sealed interface CloudSyncSettingsState {
    data class Ready(val settings: CloudSyncSettings) : CloudSyncSettingsState
    data class Error(val cause: Throwable) : CloudSyncSettingsState
}

class CloudSyncSettingsStore(context: Context) : GitHubConnectionSettings {
    private val appContext = context.applicationContext

    val state: Flow<CloudSyncSettingsState> = appContext.cloudSyncDataStore.data
        .map { preferences ->
            CloudSyncSettingsState.Ready(
                CloudSyncSettings(
                    githubEnabled = preferences[GITHUB_ENABLED] ?: false,
                    githubConnected = preferences[GITHUB_CONNECTED] ?: false,
                    githubUsername = preferences[GITHUB_USERNAME],
                    githubRepository = preferences[GITHUB_REPOSITORY]
                        ?.takeIf(String::isNotBlank)
                        ?: GitHubRepository.DEFAULT_NAME,
                    frequency = preferences[FREQUENCY]
                        ?.let { value -> runCatching { SyncFrequency.valueOf(value) }.getOrNull() }
                        ?: SyncFrequency.TWICE_DAILY,
                )
            ) as CloudSyncSettingsState
        }
        .catch { emit(CloudSyncSettingsState.Error(it)) }

    suspend fun setGithubEnabled(enabled: Boolean) {
        appContext.cloudSyncDataStore.edit { it[GITHUB_ENABLED] = enabled }
    }

    override suspend fun setGithubConnected(connected: Boolean) {
        appContext.cloudSyncDataStore.edit { it[GITHUB_CONNECTED] = connected }
    }

    override suspend fun setGithubUsername(username: String?) {
        appContext.cloudSyncDataStore.edit {
            if (username.isNullOrBlank()) it.remove(GITHUB_USERNAME) else it[GITHUB_USERNAME] = username
        }
    }

    override suspend fun setGithubRepository(repository: String) {
        appContext.cloudSyncDataStore.edit {
            it[GITHUB_REPOSITORY] = repository.trim().ifBlank { GitHubRepository.DEFAULT_NAME }
        }
    }

    suspend fun setFrequency(frequency: SyncFrequency) {
        appContext.cloudSyncDataStore.edit { it[FREQUENCY] = frequency.name }
    }

    private companion object {
        val GITHUB_ENABLED = booleanPreferencesKey("github_enabled")
        val GITHUB_CONNECTED = booleanPreferencesKey("github_connected")
        val GITHUB_USERNAME = stringPreferencesKey("github_username")
        val GITHUB_REPOSITORY = stringPreferencesKey("github_repository")
        val FREQUENCY = stringPreferencesKey("frequency")
    }
}
