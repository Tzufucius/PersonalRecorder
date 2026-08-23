package io.github.tuzfucius.personalrecorder.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import io.github.tuzfucius.personalrecorder.data.AppDatabase
import io.github.tuzfucius.personalrecorder.background.BackgroundRuntimeStateStore
import io.github.tuzfucius.personalrecorder.background.BackgroundSettingsStore
import io.github.tuzfucius.personalrecorder.background.BackgroundHealthWorker
import io.github.tuzfucius.personalrecorder.background.StatusNotificationManager
import io.github.tuzfucius.personalrecorder.settings.CloudSyncSettingsState
import io.github.tuzfucius.personalrecorder.settings.CloudSyncSettingsStore
import io.github.tuzfucius.personalrecorder.sync.CloudCredentialStore
import io.github.tuzfucius.personalrecorder.sync.CloudSyncRuntime
import io.github.tuzfucius.personalrecorder.sync.GitHubAccessTokenProvider
import io.github.tuzfucius.personalrecorder.sync.GitHubArchiveClient
import io.github.tuzfucius.personalrecorder.sync.GitHubConnectionCoordinator
import io.github.tuzfucius.personalrecorder.sync.GitHubConnectionException
import io.github.tuzfucius.personalrecorder.sync.GitHubRestoreWorker
import io.github.tuzfucius.personalrecorder.sync.SecureSecretStore
import io.github.tuzfucius.personalrecorder.sync.SyncFrequency
import io.github.tuzfucius.personalrecorder.sync.SyncScheduler
import io.github.tuzfucius.personalrecorder.sync.ReconcileMode
import io.github.tuzfucius.personalrecorder.sync.RestoreState
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    val settingsStore = CloudSyncSettingsStore(context)
    val database = AppDatabase.getInstance(context)
    val scheduler: SyncScheduler = runCatching { CloudSyncRuntime.scheduler(context) }
        .getOrElse { NoOpSyncScheduler }
    val runtimeStateStore = BackgroundRuntimeStateStore(context)
    val backgroundSettingsStore = BackgroundSettingsStore(context)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _connecting = MutableStateFlow(false)
    val connecting: StateFlow<Boolean> = _connecting.asStateFlow()
    private var githubJob: Job? = null
    private val _restoreState = MutableStateFlow(RestoreUiState())
    val restoreState: StateFlow<RestoreUiState> = _restoreState.asStateFlow()
    private val _restorePrompt = MutableStateFlow<RestorePrompt?>(null)
    val restorePrompt: StateFlow<RestorePrompt?> = _restorePrompt.asStateFlow()

    init {
        viewModelScope.launch {
            GitHubRestoreWorker.observe(context).collectLatest { workInfos ->
                workInfos.firstOrNull()?.let { workInfo ->
                    _restoreState.value = workInfo.toRestoreUiState()
                }
            }
        }
    }

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
                val result = coordinator.connect(token, repositoryName)
                result.onSuccess { login ->
                    viewModelScope.launch {
                        settingsStore.setGithubEnabled(true)
                        _message.value = "GitHub 已连接：$login"
                        discoverAfterConnect()
                    }
                }.onFailure { error ->
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

    fun validateCloud() {
        runReconcile(ReconcileMode.FULL_RESTORE, discoverOnly = true)
    }

    fun restoreFromGithub() {
        _restorePrompt.value = null
        _restoreState.value = RestoreUiState(
            running = true,
            state = RestoreState.DISCOVERING,
            mode = ReconcileMode.FULL_RESTORE,
        )
        GitHubRestoreWorker.enqueue(context)
    }

    fun dismissRestorePrompt() {
        _restorePrompt.value = null
    }

    fun setStatusNotificationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            backgroundSettingsStore.setStatusNotificationEnabled(enabled)
            if (enabled) {
                BackgroundHealthWorker.enqueueNow(context)
            } else {
                StatusNotificationManager(context).cancel()
            }
        }
    }

    fun setHideFromRecents(enabled: Boolean) {
        viewModelScope.launch {
            backgroundSettingsStore.setHideFromRecents(enabled)
        }
    }

    private fun discoverAfterConnect() {
        viewModelScope.launch {
            val inventory = CloudSyncRuntime.discoverRemote(context, ReconcileMode.FULL_RESTORE) ?: return@launch
            val localArchive = java.io.File(context.filesDir, "archive")
            if (!localArchive.exists() || localArchive.walkTopDown().none { it.isFile && it.extension == "jsonl" }) {
                val archives = inventory.descriptors.count { !it.isManifest }
                if (archives > 0) {
                    _restorePrompt.value = RestorePrompt(
                        days = inventory.descriptors.map { it.date }.distinct().size,
                        archives = archives,
                    )
                }
            }
        }
    }

    private fun runReconcile(mode: ReconcileMode, discoverOnly: Boolean) {
        viewModelScope.launch {
            _restoreState.value = RestoreUiState(running = true, state = RestoreState.DISCOVERING, mode = mode)
            try {
                if (discoverOnly) {
                    val inventory = CloudSyncRuntime.discoverRemote(context, mode)
                    _restoreState.value = RestoreUiState(
                        running = false,
                        state = RestoreState.COMPLETED,
                        mode = mode,
                        discovered = inventory?.descriptors?.size ?: 0,
                    )
                } else {
                    _restoreState.value = RestoreUiState(running = true, state = RestoreState.DOWNLOADING, mode = mode)
                    val report = CloudSyncRuntime.reconcileNow(context, mode)
                    _restoreState.value = RestoreUiState(
                        running = false,
                        state = if (report.isSuccessful) RestoreState.COMPLETED else RestoreState.FAILED,
                        mode = mode,
                        discovered = report.discoveredRemote,
                        downloaded = report.downloaded,
                        uploaded = report.uploaded,
                        skipped = report.skipped,
                        conflicts = report.conflicts,
                        error = report.results.firstOrNull { it.error != null }?.error?.message,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _restoreState.value = RestoreUiState(
                    running = false,
                    state = RestoreState.FAILED,
                    mode = mode,
                    error = error.message ?: "归档协调失败",
                )
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}

data class RestoreUiState(
    val running: Boolean = false,
    val state: RestoreState = RestoreState.IDLE,
    val mode: ReconcileMode = ReconcileMode.INCREMENTAL,
    val discovered: Int = 0,
    val downloaded: Int = 0,
    val uploaded: Int = 0,
    val skipped: Int = 0,
    val conflicts: Int = 0,
    val error: String? = null,
)

data class RestorePrompt(val days: Int, val archives: Int)

private object NoOpSyncScheduler : SyncScheduler {
    override fun schedule(frequency: SyncFrequency) = Unit
    override fun enqueueNow() = Unit
    override fun observeNowWork(): Flow<List<WorkInfo>> = emptyFlow()
    override fun cancel() = Unit
}

private fun WorkInfo.toRestoreUiState(): RestoreUiState {
    val data = if (state.isFinished) outputData else progress
    val phase = data.getString(GitHubRestoreWorker.KEY_PHASE).orEmpty()
    val restoreState = when {
        state == WorkInfo.State.SUCCEEDED || phase == RestoreState.COMPLETED.name -> RestoreState.COMPLETED
        state == WorkInfo.State.FAILED || phase == RestoreState.FAILED.name -> RestoreState.FAILED
        phase == "PROCESSING" -> RestoreState.DOWNLOADING
        phase == "DISCOVERING" -> RestoreState.DISCOVERING
        else -> RestoreState.DOWNLOADING
    }
    return RestoreUiState(
        running = state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.RUNNING || state == WorkInfo.State.BLOCKED,
        state = restoreState,
        mode = ReconcileMode.FULL_RESTORE,
        discovered = data.getInt(GitHubRestoreWorker.KEY_DISCOVERED, 0),
        downloaded = data.getInt(GitHubRestoreWorker.KEY_DOWNLOADED, 0),
        uploaded = data.getInt(GitHubRestoreWorker.KEY_UPLOADED, 0),
        skipped = data.getInt(GitHubRestoreWorker.KEY_SKIPPED, 0),
        conflicts = data.getInt(GitHubRestoreWorker.KEY_CONFLICTS, 0),
        error = data.getString(GitHubRestoreWorker.KEY_ERROR),
    )
}
