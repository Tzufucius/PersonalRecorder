package io.github.tuzfucius.personalrecorder.sync

/** 所有云端实现都只能接收已封存的 JSONL 或 manifest 文件，而不能接收 Room 数据库。 */
interface CloudSyncBackend {
    val type: CloudBackendType

    suspend fun sync(archive: CloudArchive): BackendSyncResult
}
