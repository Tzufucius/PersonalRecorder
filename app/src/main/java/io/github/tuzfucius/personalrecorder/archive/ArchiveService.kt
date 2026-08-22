package io.github.tuzfucius.personalrecorder.archive

import io.github.tuzfucius.personalrecorder.data.AppDatabase
import java.time.LocalDate

/** Coordinates the immutable file write with the Room metadata transaction. */
class ArchiveService(
    private val database: AppDatabase,
    private val writer: ArchiveWriter,
) {
    suspend fun archiveDay(date: LocalDate): ArchiveDayResult {
        val slices = ArchivePartition.slicesForDate(date, writer.zoneId)
        val events = database.eventDao().getEventsForArchive(
            startMillis = slices.first().startMillis,
            endMillis = slices.last().endMillis,
        )
        val result = writer.writeDay(date, events.map { it.toPersonalEvent() })
        result.segments.forEach { database.eventDao().upsertArchiveSegment(it.toEntity()) }
        return result
    }
}
