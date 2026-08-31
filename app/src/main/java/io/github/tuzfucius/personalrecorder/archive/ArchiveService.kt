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
        val manifestSynced = database.eventDao().getArchiveSyncState(
            segmentId = "$date-MANIFEST",
            backend = CloudBackendType.GITHUB.name,
        )?.status == ArchiveSyncStateEntity.Status.SYNCED
        val slices = ArchivePartition.slicesForDate(date, writer.zoneId)
        val events = database.eventDao().getEventsForArchive(
            startMillis = slices.first().startMillis,
            endMillis = slices.last().endMillis,
        )
        val result = writer.writeDay(
            date = date,
            events = events.map { it.toPersonalEvent() },
            nowMillis = nowMillis,
            rewriteExisting = rewriteExisting && !manifestSynced,
            allowRewriteValidManifest = allowRewriteValidManifest && !manifestSynced,
        )
        result.segments.forEach { database.eventDao().upsertArchiveSegment(it.toEntity()) }
        result.manifest?.let { ensureManifestSyncState(date, nowMillis) }
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
        val finalTarget = java.time.Instant.ofEpochMilli(nowMillis)
            .atZone(writer.zoneId)
            .toLocalDate()
            .minusDays(1)
        val dates = (missing + finalTarget.takeIf { hasArchiveInputs(it) })
            .filterNotNull()
            .distinct()
            .sorted()
        dates.forEach {
            archiveDay(
                it,
                nowMillis = nowMillis,
                rewriteExisting = true,
                allowRewriteValidManifest = true,
            )
        }
        return dates
    }

    suspend fun hasClosedArchiveGaps(nowMillis: Long): Boolean =
        missingDates(nowMillis, onlyFullyClosedDays = true).isNotEmpty()

    private suspend fun ensureManifestSyncState(date: LocalDate, nowMillis: Long) {
        val dao = database.eventDao()
        val segmentId = "$date-MANIFEST"
        val existing = dao.getArchiveSyncState(segmentId, CloudBackendType.GITHUB.name)
        val nextStatus = ManifestSyncStatePolicy.nextStatus(existing?.status) ?: return
        if (existing?.status == nextStatus) return
        dao.upsertArchiveSyncState(
            ArchiveSyncStateEntity(
                segmentId = segmentId,
                backend = CloudBackendType.GITHUB.name,
                status = nextStatus,
                attempts = existing?.attempts ?: 0,
                lastAttemptAt = existing?.lastAttemptAt,
                lastError = null,
                remoteId = existing?.remoteId,
                updatedAt = nowMillis,
            )
        )
    }

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
        return ArchivePlanner(writer.zoneId).missingClosedDates(
            minTimestamp = bounds.minTimestamp,
            nowMillis = nowMillis,
            existingSegmentIds = existingSegments,
            existingManifestDates = validManifestDates,
            onlyFullyClosedDays = onlyFullyClosedDays,
        )
    }

    private suspend fun hasArchiveInputs(date: LocalDate): Boolean {
        val dao = database.eventDao()
        if (dao.getArchiveSyncState(
                segmentId = "$date-MANIFEST",
                backend = CloudBackendType.GITHUB.name,
            )?.status == ArchiveSyncStateEntity.Status.SYNCED
        ) return false
        if (dao.getArchiveSegmentsForDate(date.toString()).isNotEmpty()) return true
        val slices = ArchivePartition.slicesForDate(date, writer.zoneId)
        return dao.getEventsForArchive(
            startMillis = slices.first().startMillis,
            endMillis = slices.last().endMillis,
        ).isNotEmpty()
    }
}

/** Keeps a generated manifest discoverable without regressing an already published one. */
internal object ManifestSyncStatePolicy {
    fun nextStatus(existingStatus: String?): String? = when (existingStatus) {
        ArchiveSyncStateEntity.Status.SYNCED -> null
        ArchiveSyncStateEntity.Status.PENDING,
        ArchiveSyncStateEntity.Status.PENDING_UPLOAD,
        ArchiveSyncStateEntity.Status.SYNCING -> existingStatus
        else -> ArchiveSyncStateEntity.Status.PENDING_UPLOAD
    }
}
