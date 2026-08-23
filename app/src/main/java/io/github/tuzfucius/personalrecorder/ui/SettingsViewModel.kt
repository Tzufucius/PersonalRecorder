package io.github.tuzfucius.personalrecorder.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.tuzfucius.personalrecorder.BuildConfig
import io.github.tuzfucius.personalrecorder.data.AppDatabase
import io.github.tuzfucius.personalrecorder.settings.CloudSyncSettingsStore
import io.github.tuzfucius.personalrecorder.sync.CloudCredentialStore
import io.github.tuzfucius.personalrecorder.sync.CloudSyncRuntime
import io.github.tuzfucius.personalrecorder.sync.GitHubConnectionCoordinator
import io.github.tuzfucius.personalrecorder.sync.GitHubDeviceCode
import io.github.tuzfucius.personalrecorder.sync.GitHubDeviceFlowCoordinator
import io.github.tuzfucius.personalrecorder.sync.GitHubDevicePollResult
import io.github.tuzfucius.personalrecorder.sync.GitHubAccessTokenProvider
import io.github.tuzfucius.personalrecorder.sync.OkHttpGitHubApi
import io.github.tuzfucius.personalrecorder.sync.PlayServicesGoogleDriveAccessTokenProvider
import io.github.tuzfucius.personalrecorder.sync.SyncScheduler
import io.github.tuzfucius.personalrecorder.sync.SecureSecretStore
import io.github.tuzfucius.personalrecorder.sync.SyncFrequency
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class GitHubDeviceUiState(
    val deviceCode: GitHubDeviceCode? = null,
    val message: String? = null,
    val completed: Boolean = false,
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    val settingsStore = CloudSyncSettingsStore(context)
    val database = AppDatabase.getInstance(context)
    val scheduler: SyncScheduler = CloudSyncRuntime.scheduler(context)

    private val _githubDeviceState = MutableStateFlow<GitHubDeviceUiState?>(null)
    val githubDeviceState: StateFlow<GitHubDeviceUiState?> = _githubDeviceState.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private var githubJob: Job? = null

    fun setGithubEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsStore.setGithubEnabled(enabled)
    }

    fun setGoogleDriveEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsStore.setGoogleDriveEnabled(enabled)
    }

    fun setFrequency(frequency: SyncFrequency) = viewModelScope.launch {
        settingsStore.setFrequency(frequency)
        scheduler.schedule(frequency)
    }

    fun enqueueNow() {
        scheduler.enqueueNow()
    }

    fun markGoogleDriveConnected() = viewModelScope.launch {
        settingsStore.setGoogleDriveConnected(true)
        _message.value = "Google Drive 已连接"
    }

    fun disconnectGoogleDrive() = viewModelScope.launch {
        runCatching { PlayServicesGoogleDriveAccessTokenProvider(context).revokeAccess() }
        CloudCredentialStore(context).clearGoogleDrive()
        settingsStore.setGoogleDriveConnected(false)
        settingsStore.setGoogleDriveEnabled(false)
        _message.value = "Google Drive 已断开，本地归档与同步历史已保留"
    }

    fun startGithubDeviceFlow() {
        githubJob?.cancel()
        val clientId = BuildConfig.GITHUB_CLIENT_ID.trim()
        if (clientId.isBlank()) {
            _message.value = "未配置 GitHub OAuth client ID"
            return
        }
        githubJob = viewModelScope.launch {
            val secrets = SecureSecretStore(context)
            val api = OkHttpGitHubApi(
                tokenProvider = GitHubAccessTokenProvider {
                    secrets.get(CloudCredentialStore.GITHUB_ACCESS_TOKEN)
                }
            )
            val flow = GitHubDeviceFlowCoordinator(api)
            runCatching {
                val device = flow.requestDeviceCode(clientId)
                _githubDeviceState.value = GitHubDeviceUiState(deviceCode = device)
                when (val result = flow.pollForToken(clientId, device)) {
                    is GitHubDevicePollResult.Authorized -> {
                        val connected = GitHubConnectionCoordinator(api, api, secrets, settingsStore)
                            .completeConnection(result.accessToken)
                        connected.fold(
                            onSuccess = { login ->
                                _githubDeviceState.value = null
                                _message.value = "GitHub 已连接：$login"
                            },
                            onFailure = { error ->
                                _githubDeviceState.value = GitHubDeviceUiState(message = error.message)
                            }
                        )
                    }
                    GitHubDevicePollResult.AccessDenied -> _githubDeviceState.value =
                        GitHubDeviceUiState(message = "GitHub 授权已拒绝")
                    GitHubDevicePollResult.Expired -> _githubDeviceState.value =
                        GitHubDeviceUiState(message = "GitHub 验证码已过期，请重新连接")
                    is GitHubDevicePollResult.Failed -> _githubDeviceState.value =
                        GitHubDeviceUiState(message = result.message)
                    GitHubDevicePollResult.Pending,
                    is GitHubDevicePollResult.SlowDown -> Unit
                }
            }.onFailure { error ->
                _githubDeviceState.value = GitHubDeviceUiState(message = error.message ?: "GitHub 连接失败")
            }
        }
    }

    fun cancelGithubDeviceFlow() {
        githubJob?.cancel()
        githubJob = null
        _githubDeviceState.value = null
    }

    fun disconnectGithub() = viewModelScope.launch {
        SecureSecretStore(context).remove(CloudCredentialStore.GITHUB_ACCESS_TOKEN)
        settingsStore.setGithubUsername(null)
        settingsStore.setGithubConnected(false)
        settingsStore.setGithubEnabled(false)
        _message.value = "GitHub 已断开，本地归档与同步历史已保留"
    }

    fun clearMessage() {
        _message.value = null
    }
}
