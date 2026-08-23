package io.github.tuzfucius.personalrecorder.sync

import androidx.room.withTransaction
import io.github.tuzfucius.personalrecorder.archive.ArchivedEvent
import io.github.tuzfucius.personalrecorder.archive.ArchivePartition
import io.github.tuzfucius.personalrecorder.archive.ArchiveSegmentType
import io.github.tuzfucius.personalrecorder.data.AppDatabase
import io.github.tuzfucius.personalrecorder.data.EventEntity
import java.time.LocalDate
import java.time.ZoneId

data class ArchiveImportResult(
    val imported: Int,
    val skipped: Int,
    val conflictingEventIds: List<String>,
)

/** Validates the complete JSONL payload before inserting any Room row. */
class ArchiveImportService(
    private val database: AppDatabase,
    private val reconciler: ArchiveReconciler = ArchiveReconciler(),
) {
    suspend fun importSegment(
        bytes: ByteArray,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): ArchiveImportResult {
        val events = reconciler.parseJsonl(bytes)
        if (events.isEmpty()) return ArchiveImportResult(0, 0, emptyList())
        val entities = events.map { event -> EventEntity.fromPersonalEvent(event.toPersonalEvent()) }
        return database.withTransaction {
            val dao = database.eventDao()
            var imported = 0
            var skipped = 0
            val conflicts = mutableListOf<String>()
            entities.forEach { entity ->
                val existing = dao.getEventById(entity.id)
                when {
                    existing == null -> {
                        dao.insertEventsIgnore(listOf(entity))
                        imported++
                    }
                    existing == entity -> skipped++
                    else -> {
                        skipped++
                        conflicts += entity.id
                    }
                }
            }
            ArchiveImportResult(imported, skipped, conflicts.distinct())
        }
    }

    suspend fun importAndRegisterSegment(
        descriptor: ArchiveDescriptor,
        bytes: ByteArray,
        zoneId: ZoneId,
        createdAt: Long,
    ): ArchiveImportResult {
        require(!descriptor.isManifest) { "manifest 不能作为事件 segment 导入" }
        val result = importSegment(bytes, zoneId)
        val date = LocalDate.parse(descriptor.date)
        val slice = ArchivePartition.slicesForDate(date, zoneId).first {
            it.half.name == descriptor.slot
        }
        database.eventDao().upsertArchiveSegment(
            io.github.tuzfucius.personalrecorder.data.ArchiveSegmentEntity(
                segmentId = descriptor.segmentId,
                date = descriptor.date,
                slot = descriptor.slot,
                relativePath = descriptor.relativePath,
                startMillis = slice.startMillis,
                endMillis = slice.endMillis,
                eventCount = reconciler.parseJsonl(bytes).size,
                sha256 = descriptor.sha256,
                closed = true,
                createdAt = createdAt,
            )
        )
        return result
    }
}
