package io.github.tuzfucius.personalrecorder.data

import androidx.room.Entity
import androidx.room.Index

/** Per-backend upload state. A row is intentionally separate from segment metadata. */
@Entity(
    tableName = "archive_sync_states",
    primaryKeys = ["segmentId", "backend"],
    indices = [Index(value = ["status"]), Index(value = ["backend", "status"])],
)
data class ArchiveSyncStateEntity(
    val segmentId: String,
    val backend: String,
    val status: String,
    val attempts: Int = 0,
    val lastAttemptAt: Long? = null,
    val lastError: String? = null,
    val remoteId: String? = null,
    val updatedAt: Long,
) {
    object Status {
        const val PENDING = "PENDING"
        const val SYNCING = "SYNCING"
        const val SUCCEEDED = "SUCCEEDED"
        const val FAILED = "FAILED"
        const val CONFLICT = "CONFLICT"
    }
}
