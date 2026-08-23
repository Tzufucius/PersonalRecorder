package io.github.tuzfucius.personalrecorder.sync

/** 保留类型以兼容调用方；跨时间重试由 WorkManager 负责。 */
class SyncRetryPolicy(val maxAttempts: Int = 1) {
    init {
        require(maxAttempts == 1) { "同步 Coordinator 只允许单次网络请求" }
    }
}

/** 每个 backend 只发起一次 batch 请求，返回值保留独立 archive/backend 结果。 */
class SyncCoordinator(
    backends: Collection<CloudSyncBackend>,
    @Suppress("UNUSED_PARAMETER") retryPolicy: SyncRetryPolicy = SyncRetryPolicy(),
) {
    private val backendsByType = backends.associateBy { it.type }

    init {
        require(backendsByType.size == backends.size) { "同一种云端后端只能注册一次" }
    }

    suspend fun syncBatch(
        archives: Collection<CloudArchive>,
        backendTypes: Set<CloudBackendType> = backendsByType.keys,
        observer: SyncObserver = SyncObserver.NONE,
    ): SyncBatchResult {
        val sortedArchives = archives.sortedBy { it.relativePath }
        val results = buildList {
            backendTypes.sortedBy { it.name }.forEach { backendType ->
                val backend = backendsByType[backendType]
                if (backend == null) {
                    val error = SyncError.NotConfigured("未配置 ${backendType.name} 同步后端")
                    sortedArchives.forEach { archive ->
                        val result = ArchiveSyncResult(archive, backendType, ArchiveSyncStatus.FAILED, 0, error = error)
                        observer.onStateChanged(ArchiveSyncState(archive, backendType, result.status, error = error))
                        add(result)
                    }
                } else if (sortedArchives.isNotEmpty()) {
                    sortedArchives.forEach { archive ->
                        observer.onStateChanged(ArchiveSyncState(archive, backendType, ArchiveSyncStatus.SYNCING, 1))
                    }
                    val backendResults = runCatching { backend.syncBatch(sortedArchives) }.getOrElse { error ->
                        sortedArchives.associate {
                            it.relativePath to BackendSyncResult.Failure(SyncError.Network("同步请求失败", error))
                        }
                    }
                    sortedArchives.forEach { archive ->
                        val backendResult = backendResults[archive.relativePath]
                            ?: BackendSyncResult.Failure(SyncError.Unknown("后端没有返回归档结果"))
                        val result = when (backendResult) {
                            is BackendSyncResult.Success -> ArchiveSyncResult(
                                archive = archive,
                                backend = backendType,
                                status = ArchiveSyncStatus.SYNCED,
                                attempts = 1,
                                remoteReference = backendResult.remoteReference,
                                wasAlreadyPresent = backendResult.wasAlreadyPresent,
                            )
                            is BackendSyncResult.Failure -> ArchiveSyncResult(
                                archive = archive,
                                backend = backendType,
                                status = ArchiveSyncStatus.FAILED,
                                attempts = 1,
                                error = backendResult.error,
                            )
                        }
                        observer.onStateChanged(
                            ArchiveSyncState(archive, backendType, result.status, result.attempts, result.error)
                        )
                        add(result)
                    }
                }
            }
        }
        return SyncBatchResult(results)
    }
}
