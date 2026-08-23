package io.github.tuzfucius.personalrecorder.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import io.github.tuzfucius.personalrecorder.data.AppDatabase
import io.github.tuzfucius.personalrecorder.settings.CloudSyncSettingsState
import io.github.tuzfucius.personalrecorder.settings.CloudSyncSettingsStore
import io.github.tuzfucius.personalrecorder.sync.CloudCredentialStore
import io.github.tuzfucius.personalrecorder.sync.CloudSyncRuntime
import io.github.tuzfucius.personalrecorder.sync.GitHubAccessTokenProvider
import io.github.tuzfucius.personalrecorder.sync.GitHubArchiveClient
import io.github.tuzfucius.personalrecorder.sync.GitHubConnectionCoordinator
import io.github.tuzfucius.personalrecorder.sync.GitHubConnectionException
import io.github.tuzfucius.personalrecorder.sync.SecureSecretStore
import io.github.tuzfucius.personalrecorder.sync.SyncFrequency
import io.github.tuzfucius.personalrecorder.sync.SyncScheduler
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    val settingsStore = CloudSyncSettingsStore(context)
    val database = AppDatabase.getInstance(context)
    val scheduler: SyncScheduler = runCatching { CloudSyncRuntime.scheduler(context) }
        .getOrElse { NoOpSyncScheduler }

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _connecting = MutableStateFlow(false)
    val connecting: StateFlow<Boolean> = _connecting.asStateFlow()
    private var githubJob: Job? = null

    fun setGithubEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsStore.setGithubEnabled(enabled)
    }

    fun setFrequency(frequency: SyncFrequency) = viewModelScope.launch {
        settingsStore.setFrequency(frequency)
        scheduler.schedule(frequency)
    }

    fun connectGithub(token: String, repositoryName: String) {
        githubJob?.cancel()
        githubJob = viewModelScope.launch {
            _connecting.value = true
            _message.value = null
            try {
                val secrets = SecureSecretStore(context)
                val coordinator = GitHubConnectionCoordinator(
                    clientFactory = { candidate ->
                        GitHubArchiveClient(GitHubAccessTokenProvider { candidate })
                    },
                    secrets = secrets,
                    settings = settingsStore,
                )
                coordinator.connect(token, repositoryName)
                    .onSuccess { login -> _message.value = "GitHub 已连接：$login" }
                    .onFailure { error ->
                        _message.value = when (error) {
                            is GitHubConnectionException -> error.message
                            else -> "GitHub 连接失败"
                        }
                    }
            } catch (error: CancellationException) {
                throw error
            } finally {
                _connecting.value = false
            }
        }
    }

    fun disconnectGithub() = viewModelScope.launch {
        CloudCredentialStore(context).clearGithub()
        settingsStore.setGithubUsername(null)
        settingsStore.setGithubConnected(false)
        settingsStore.setGithubEnabled(false)
        _message.value = "GitHub 已断开，本地归档与同步历史已保留"
    }

    fun enqueueNow() {
        viewModelScope.launch {
            val settings = (settingsStore.state.first() as? CloudSyncSettingsState.Ready)?.settings
            if (settings == null || !settings.githubConnected) {
                _message.value = "请先连接 GitHub"
            } else {
                scheduler.enqueueNow()
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}

private object NoOpSyncScheduler : SyncScheduler {
    override fun schedule(frequency: SyncFrequency) = Unit
    override fun enqueueNow() = Unit
    override fun observeNowWork(): Flow<List<WorkInfo>> = emptyFlow()
    override fun cancel() = Unit
}
