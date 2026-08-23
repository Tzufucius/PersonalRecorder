package io.github.tuzfucius.personalrecorder.data

import androidx.room.Entity
import androidx.room.Index
import io.github.tuzfucius.personalrecorder.sync.ArchiveVerificationStatus

/** Metadata for an immutable half-day JSONL archive segment. */
@Entity(
    tableName = "archive_segments",
    indices = [Index(value = ["date"]), Index(value = ["closed"])],
)
data class ArchiveSegmentEntity(
    @androidx.room.PrimaryKey val segmentId: String,
    val date: String,
    val slot: String,
    val relativePath: String,
    val startMillis: Long,
    val endMillis: Long,
    val eventCount: Int,
    val sha256: String,
    val closed: Boolean = true,
    val createdAt: Long,
    val verificationStatus: String = ArchiveVerificationStatus.VERIFIED.name,
)
