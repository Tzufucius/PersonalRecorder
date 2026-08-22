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
            val sortedArchives = archives.sortedBy { it.relativePath }
            backendTypes.sortedBy { it.name }.forEach { backendType ->
                val backend = backendsByType[backendType]
                if (backend == null) {
                    val error = SyncError.NotConfigured("未配置 ${backendType.name} 同步后端")
                    sortedArchives.forEach { archive ->
                        observer.onStateChanged(
                            ArchiveSyncState(archive, backendType, ArchiveSyncStatus.FAILED, error = error)
                        )
                        add(ArchiveSyncResult(archive, backendType, ArchiveSyncStatus.FAILED, 0, error = error))
                    }
                } else {
                    addAll(syncBackendBatch(backend, sortedArchives, observer))
                }
            }
        }
        return SyncBatchResult(results)
    }

    private suspend fun syncBackendBatch(
        backend: CloudSyncBackend,
        archives: List<CloudArchive>,
        observer: SyncObserver
    ): List<ArchiveSyncResult> {
        if (archives.isEmpty()) return emptyList()
        val completed = mutableListOf<ArchiveSyncResult>()
        var pending = archives
        var attempt = 1
        pending.forEach { observer.onStateChanged(ArchiveSyncState(it, backend.type, ArchiveSyncStatus.PENDING)) }
        while (pending.isNotEmpty()) {
            pending.forEach { observer.onStateChanged(ArchiveSyncState(it, backend.type, ArchiveSyncStatus.SYNCING, attempt)) }
            val results = runCatching { backend.syncBatch(pending) }.getOrElse { error ->
                pending.associate { it.relativePath to BackendSyncResult.Failure(SyncError.Network("同步请求失败", error)) }
            }
            val retry = mutableListOf<CloudArchive>()
            var retryError: SyncError? = null
            pending.forEach { archive ->
                val backendResult = results[archive.relativePath]
                    ?: BackendSyncResult.Failure(SyncError.Unknown("后端没有返回归档结果"))
                when (backendResult) {
                    is BackendSyncResult.Success -> {
                        val final = ArchiveSyncResult(
                            archive, backend.type, ArchiveSyncStatus.SYNCED, attempt,
                            remoteReference = backendResult.remoteReference
                        )
                        completed += final
                        observer.onStateChanged(ArchiveSyncState(archive, backend.type, final.status, attempt))
                    }
                    is BackendSyncResult.Failure -> {
                        if (backendResult.error.retryable && attempt < retryPolicy.maxAttempts) {
                            retry += archive
                            retryError = retryError ?: backendResult.error
                        } else {
                            val final = ArchiveSyncResult(
                                archive, backend.type, ArchiveSyncStatus.FAILED, attempt,
                                error = backendResult.error,
                                retryExhausted = backendResult.error.retryable && attempt >= retryPolicy.maxAttempts,
                            )
                            completed += final
                            observer.onStateChanged(
                                ArchiveSyncState(archive, backend.type, final.status, attempt, backendResult.error)
                            )
                        }
                    }
                }
            }
            if (retry.isEmpty()) break
            retryPolicy.waitBeforeRetry(attempt, requireNotNull(retryError))
            pending = retry
            attempt++
        }
        return completed.sortedBy { it.archive.relativePath }
    }
}
