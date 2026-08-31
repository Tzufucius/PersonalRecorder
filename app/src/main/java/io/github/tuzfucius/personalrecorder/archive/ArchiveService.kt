package io.github.tuzfucius.personalrecorder.archive

import io.github.tuzfucius.personalrecorder.data.AppDatabase
import io.github.tuzfucius.personalrecorder.data.ArchiveSyncStateEntity
import io.github.tuzfucius.personalrecorder.sync.CloudBackendType
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/** Coordinates the immutable file write with the Room metadata transaction. */
class ArchiveService(
    private val database: AppDatabase,
    private val writer: ArchiveWriter,
) {
    suspend fun archiveDay(
        date: LocalDate,
        nowMillis: Long = System.currentTimeMillis(),
        rewriteExisting: Boolean = false,
        allowRewriteValidManifest: Boolean = false,
    ): ArchiveDayResult {
        val slices = ArchivePartition.slicesForDate(date, writer.zoneId)
        val events = database.eventDao().getEventsForArchive(
            startMillis = slices.first().startMillis,
            endMillis = slices.last().endMillis,
        )
        val result = writer.writeDay(
            date = date,
            events = events.map { it.toPersonalEvent() },
            nowMillis = nowMillis,
            rewriteExisting = rewriteExisting,
            allowRewriteValidManifest = allowRewriteValidManifest,
        )
        result.segments.forEach { database.eventDao().upsertArchiveSegment(it.toEntity()) }
        return result
    }

    /** Writes newly closed segments while preserving existing immutable files. */
    suspend fun archiveClosedSegments(nowMillis: Long): List<LocalDate> {
        val missing = missingDates(nowMillis, onlyFullyClosedDays = false)
        missing.forEach { archiveDay(it, nowMillis = nowMillis) }
        return missing
    }

    /** Finalizes every closed day whose manifest is absent or invalid. */
    suspend fun finalizeClosedArchives(nowMillis: Long): List<LocalDate> {
        val missing = missingDates(nowMillis, onlyFullyClosedDays = true)
        missing.forEach {
            archiveDay(
                it,
                nowMillis = nowMillis,
                rewriteExisting = true,
                allowRewriteValidManifest = true,
            )
        }
        return missing
    }

    suspend fun hasClosedArchiveGaps(nowMillis: Long): Boolean =
        missingDates(nowMillis, onlyFullyClosedDays = true).isNotEmpty()

    private suspend fun missingDates(nowMillis: Long, onlyFullyClosedDays: Boolean): List<LocalDate> {
        val dao = database.eventDao()
        val bounds = dao.getEventTimestampBounds()
        val existingSegments = dao.getArchivedSegmentIds().toSet()
        val validManifestDates = dao.getArchiveSegments()
            .first()
            .asSequence()
            .mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
            .filter(writer::isManifestComplete)
            .toSet()
        val existingManifestDates = if (onlyFullyClosedDays) {
            validManifestDates.filter { date ->
                dao.getArchiveSyncState(
                    segmentId = "$date-MANIFEST",
                    backend = CloudBackendType.GITHUB.name,
                )?.status == ArchiveSyncStateEntity.Status.SYNCED
            }.toSet()
        } else {
            validManifestDates
        }
        return ArchivePlanner(writer.zoneId).missingClosedDates(
            minTimestamp = bounds.minTimestamp,
            nowMillis = nowMillis,
            existingSegmentIds = existingSegments,
            existingManifestDates = existingManifestDates,
            onlyFullyClosedDays = onlyFullyClosedDays,
        )
    }
}
