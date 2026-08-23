package io.github.tuzfucius.personalrecorder.archive

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Computes missing closed dates without touching files, Room events, or network. */
class ArchivePlanner(private val zoneId: ZoneId) {
    fun missingClosedDates(
        minTimestamp: Long?,
        nowMillis: Long,
        existingSegmentIds: Set<String>,
    ): List<LocalDate> {
        minTimestamp ?: return emptyList()
        val startDate = Instant.ofEpochMilli(minTimestamp).atZone(zoneId).toLocalDate()
        val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
        val today = now.toLocalDate()
        val missing = mutableListOf<LocalDate>()
        var date = startDate
        while (!date.isAfter(today)) {
            val hasMissing = ArchivePartition.slicesForDate(date, zoneId)
                .filter { it.endMillis <= nowMillis }
                .any { it.segmentId !in existingSegmentIds }
            if (hasMissing) missing += date
            date = date.plusDays(1)
        }
        return missing
    }
}
