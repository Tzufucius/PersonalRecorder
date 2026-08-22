package io.github.tuzfucius.personalrecorder.sync

/** 云端后端的稳定标识，可同时启用多个后端。 */
enum class CloudBackendType {
    GITHUB,
    GOOGLE_DRIVE
}

/** WorkManager 的周期为尽力而为，不表示固定时刻执行。 */
enum class SyncFrequency(val repeatIntervalMillis: Long) {
    TWICE_DAILY(12L * 60L * 60L * 1000L),
    DAILY(24L * 60L * 60L * 1000L),
    WEEKLY(7L * 24L * 60L * 60L * 1000L)
}

/** 单个归档文件在某个云端后端中的状态。 */
enum class ArchiveSyncStatus {
    PENDING,
    SYNCING,
    SYNCED,
    FAILED
}

/**
 * 与 archive 模块解耦的不可变上传载荷。
 * relativePath 必须是相对于后端根目录的正斜杠路径，例如 archive/2026/08/2026-08-22/00-12.jsonl。
 */
data class CloudArchive(
    val relativePath: String,
    val sha256: String,
    val content: ByteArray
) {
    init {
        require(relativePath.isNotBlank()) { "归档相对路径不能为空" }
        require(!relativePath.startsWith("/") && !relativePath.contains("..")) {
            "归档路径必须位于后端根目录内"
        }
        require(sha256.length == SHA256_HEX_LENGTH && sha256.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            "归档 SHA-256 必须是 64 位十六进制字符串"
        }
    }

    companion object {
        private const val SHA256_HEX_LENGTH = 64
    }
}

sealed interface SyncError {
    val retryable: Boolean
    val message: String

    data class Network(override val message: String, val cause: Throwable? = null) : SyncError {
        override val retryable = true
    }

    data class ServiceUnavailable(override val message: String) : SyncError {
        override val retryable = true
    }

    data class RateLimited(override val message: String, val retryAfterMillis: Long? = null) : SyncError {
        override val retryable = true
    }

    data class Authentication(override val message: String) : SyncError {
        override val retryable = false
    }

    data class Authorization(override val message: String) : SyncError {
        override val retryable = false
    }

    data class RemoteConflict(override val message: String) : SyncError {
        override val retryable = false
    }

    data class InvalidArchive(override val message: String) : SyncError {
        override val retryable = false
    }

    data class NotConfigured(override val message: String) : SyncError {
        override val retryable = false
    }

    data class Unknown(override val message: String, val cause: Throwable? = null) : SyncError {
        override val retryable = false
    }
}

sealed interface BackendSyncResult {
    data class Success(
        val remoteReference: String? = null,
        val wasAlreadyPresent: Boolean = false
    ) : BackendSyncResult

    data class Failure(val error: SyncError) : BackendSyncResult
}

data class ArchiveSyncResult(
    val archive: CloudArchive,
    val backend: CloudBackendType,
    val status: ArchiveSyncStatus,
    val attempts: Int,
    val remoteReference: String? = null,
    val error: SyncError? = null,
    val retryExhausted: Boolean = false,
) {
    val isSuccessful: Boolean
        get() = status == ArchiveSyncStatus.SYNCED
}

data class SyncBatchResult(val results: List<ArchiveSyncResult>) {
    val isSuccessful: Boolean
        get() = results.all { it.isSuccessful }

    val needsRetry: Boolean
        get() = results.any { it.error?.retryable == true && !it.retryExhausted }
}

data class ArchiveSyncState(
    val archive: CloudArchive,
    val backend: CloudBackendType,
    val status: ArchiveSyncStatus,
    val attempt: Int = 0,
    val error: SyncError? = null
)

fun interface SyncObserver {
    fun onStateChanged(state: ArchiveSyncState)

    companion object {
        val NONE = SyncObserver { }
    }
}
