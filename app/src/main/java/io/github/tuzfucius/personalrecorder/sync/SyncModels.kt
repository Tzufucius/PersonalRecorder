package io.github.tuzfucius.personalrecorder.sync

import java.time.LocalDate

/** 云端后端的稳定标识。Room 仍以字符串保存历史 backend 值。 */
enum class CloudBackendType {
    GITHUB
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
    PENDING_UPLOAD,
    PENDING_DOWNLOAD,
    SYNCING,
    SYNCED,
    CONFLICT,
    FAILED
}

enum class ArchivePairState {
    LOCAL_ONLY,
    REMOTE_ONLY,
    BOTH_IDENTICAL,
    BOTH_DIFFERENT,
}

enum class ReconcileMode {
    INCREMENTAL,
    FULL_RESTORE,
}

/** The exact date set both local and remote inventory scans are allowed to touch. */
data class ReconcileScope(
    val dates: Set<LocalDate>? = null,
    val full: Boolean = dates == null,
) {
    init {
        require(full == (dates == null)) { "FULL scope must not contain dates" }
    }

    fun includes(date: LocalDate): Boolean = full || date in dates.orEmpty()

    companion object {
        fun full(): ReconcileScope = ReconcileScope(full = true)

        fun dates(dates: Set<LocalDate>): ReconcileScope = ReconcileScope(
            dates = dates.toSet(),
            full = false,
        )
    }
}

enum class ArchiveVerificationStatus {
    VERIFIED,
    LEGACY_UNVERIFIED,
}

data class ReconcileProgress(
    val phase: String,
    val discovered: Int = 0,
    val processed: Int = 0,
    val total: Int = 0,
    val downloaded: Int = 0,
    val uploaded: Int = 0,
    val skipped: Int = 0,
    val conflicts: Int = 0,
    val currentPath: String? = null,
)

class InvalidArchiveException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

enum class RestoreState {
    IDLE,
    DISCOVERING,
    DOWNLOADING,
    VERIFYING,
    IMPORTING,
    COMPLETED,
    FAILED,
}

data class ArchiveDescriptor(
    val segmentId: String,
    val relativePath: String,
    val sha256: String,
    val date: String,
    val slot: String,
    val size: Long,
    val isManifest: Boolean = false,
    val remoteSha: String? = null,
)

data class ArchivePair(
    val local: ArchiveDescriptor?,
    val remote: ArchiveDescriptor?,
) {
    val state: ArchivePairState = when {
        local == null && remote != null -> ArchivePairState.REMOTE_ONLY
        local != null && remote == null -> ArchivePairState.LOCAL_ONLY
        local != null && remote != null && local.sha256.equals(remote.sha256, ignoreCase = true) ->
            ArchivePairState.BOTH_IDENTICAL
        else -> ArchivePairState.BOTH_DIFFERENT
    }
}

data class LocalArchiveInventory(val descriptors: List<ArchiveDescriptor>)

data class RemoteArchiveInventory(val descriptors: List<ArchiveDescriptor>)

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
    val wasAlreadyPresent: Boolean = false,
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
        get() = results.any { it.error?.retryable == true }
}

/** Result of the local daily finalize phase; cloud publication is deliberately separate. */
data class DailyFinalizeResult(
    val localFinalizeSuccessful: Boolean,
    val needsCloudSync: Boolean,
)

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
