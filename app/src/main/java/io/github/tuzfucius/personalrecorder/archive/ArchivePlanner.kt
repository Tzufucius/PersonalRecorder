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
        existingManifestDates: Set<LocalDate> = emptySet(),
        onlyFullyClosedDays: Boolean = false,
    ): List<LocalDate> {
        minTimestamp ?: return emptyList()
        val startDate = Instant.ofEpochMilli(minTimestamp).atZone(zoneId).toLocalDate()
        val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
        val today = now.toLocalDate()
        val missing = mutableListOf<LocalDate>()
        var date = startDate
        while (!date.isAfter(today)) {
            if (date in existingManifestDates) {
                date = date.plusDays(1)
                continue
            }
            val slices = ArchivePartition.slicesForDate(date, zoneId)
            val closedSlices = slices.filter { it.endMillis <= nowMillis }
            val fullyClosed = closedSlices.size == slices.size
            val hasMissingSegments = closedSlices.any { it.segmentId !in existingSegmentIds }
            val hasMissingManifest = fullyClosed
            val hasMissing = if (onlyFullyClosedDays) {
                fullyClosed && (hasMissingSegments || hasMissingManifest)
            } else {
                hasMissingSegments || hasMissingManifest
            }
            if (hasMissing) missing += date
            date = date.plusDays(1)
        }
        return missing
    }
}
