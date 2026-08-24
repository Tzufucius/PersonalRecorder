package io.github.tuzfucius.personalrecorder.data

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "archive_conflicts",
    indices = [Index(value = ["segmentId"]), Index(value = ["resolved"])],
)
data class ArchiveConflictEntity(
    @androidx.room.PrimaryKey val conflictId: String,
    val segmentId: String,
    val relativePath: String,
    val localFilePath: String,
    val remoteFilePath: String,
    val summary: String,
    val createdAt: Long,
    val resolved: Boolean = false,
)
