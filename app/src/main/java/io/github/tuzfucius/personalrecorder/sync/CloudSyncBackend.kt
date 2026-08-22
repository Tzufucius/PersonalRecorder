package io.github.tuzfucius.personalrecorder.sync

/** 所有云端实现都只能接收已封存的 JSONL 或 manifest 文件，而不能接收 Room 数据库。 */
interface CloudSyncBackend {
    val type: CloudBackendType

    suspend fun sync(archive: CloudArchive): BackendSyncResult

    /** Backends may override this to commit a whole upload batch atomically. */
    suspend fun syncBatch(archives: Collection<CloudArchive>): Map<String, BackendSyncResult> =
        archives.associate { archive -> archive.relativePath to sync(archive) }
}
