package io.github.tuzfucius.personalrecorder.sync

import kotlinx.coroutines.delay

class SyncRetryPolicy(
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val waitBeforeRetry: suspend (attempt: Int, error: SyncError) -> Unit = { attempt, error ->
        delay(defaultRetryDelay(error, attempt))
    }
) {
    init {
        require(maxAttempts >= 1) { "最大同步尝试次数至少为 1" }
    }

    suspend fun waitBeforeRetry(attempt: Int, error: SyncError) = waitBeforeRetry.invoke(attempt, error)

    private companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3
        const val INITIAL_RETRY_DELAY_MILLIS = 1_000L
        const val MAX_RETRY_DELAY_MILLIS = 30_000L
        const val MAX_BACKOFF_SHIFT = 5
    }
}

private fun defaultRetryDelay(error: SyncError, attempt: Int): Long {
    if (error is SyncError.RateLimited && error.retryAfterMillis != null) return error.retryAfterMillis
    return (1_000L * (1L shl (attempt - 1).coerceAtMost(5))).coerceAtMost(30_000L)
}

/**
 * 协调多个独立后端的上传和重试。每个 (archive, backend) 都有独立结果；一个后端失败不会阻断其余后端。
 */
class SyncCoordinator(
    backends: Collection<CloudSyncBackend>,
    private val retryPolicy: SyncRetryPolicy = SyncRetryPolicy()
) {
    private val backendsByType = backends.associateBy { it.type }

    init {
        require(backendsByType.size == backends.size) { "同一种云端后端只能注册一次" }
    }

    suspend fun syncBatch(
        archives: Collection<CloudArchive>,
        backendTypes: Set<CloudBackendType> = backendsByType.keys,
        observer: SyncObserver = SyncObserver.NONE
    ): SyncBatchResult {
        val results = buildList {
            archives.sortedBy { it.relativePath }.forEach { archive ->
                backendTypes.sortedBy { it.name }.forEach { backendType ->
                    val backend = backendsByType[backendType]
                    if (backend == null) {
                        val error = SyncError.NotConfigured("未配置 ${backendType.name} 同步后端")
                        observer.onStateChanged(
                            ArchiveSyncState(archive, backendType, ArchiveSyncStatus.FAILED, error = error)
                        )
                        add(ArchiveSyncResult(archive, backendType, ArchiveSyncStatus.FAILED, 0, error = error))
                    } else {
                        add(syncOne(backend, archive, observer))
                    }
                }
            }
        }
        return SyncBatchResult(results)
    }

    private suspend fun syncOne(
        backend: CloudSyncBackend,
        archive: CloudArchive,
        observer: SyncObserver
    ): ArchiveSyncResult {
        observer.onStateChanged(ArchiveSyncState(archive, backend.type, ArchiveSyncStatus.PENDING))
        var attempt = 1
        while (true) {
            observer.onStateChanged(ArchiveSyncState(archive, backend.type, ArchiveSyncStatus.SYNCING, attempt))
            when (val result = runCatching { backend.sync(archive) }
                .getOrElse { BackendSyncResult.Failure(SyncError.Network("同步请求失败", it)) }) {
                is BackendSyncResult.Success -> {
                    val final = ArchiveSyncResult(
                        archive, backend.type, ArchiveSyncStatus.SUCCEEDED, attempt,
                        remoteReference = result.remoteReference
                    )
                    observer.onStateChanged(ArchiveSyncState(archive, backend.type, final.status, attempt))
                    return final
                }

                is BackendSyncResult.Failure -> {
                    val status = if (result.error is SyncError.RemoteConflict) {
                        ArchiveSyncStatus.CONFLICT
                    } else {
                        ArchiveSyncStatus.FAILED
                    }
                    if (!result.error.retryable || attempt >= retryPolicy.maxAttempts) {
                        val final = ArchiveSyncResult(archive, backend.type, status, attempt, error = result.error)
                        observer.onStateChanged(ArchiveSyncState(archive, backend.type, status, attempt, result.error))
                        return final
                    }
                    retryPolicy.waitBeforeRetry(attempt, result.error)
                    attempt++
                }
            }
        }
    }
}
