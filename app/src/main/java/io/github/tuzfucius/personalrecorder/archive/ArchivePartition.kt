package io.github.tuzfucius.personalrecorder.archive

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

enum class ArchiveSegmentType(val fileName: String) {
    FIRST_HALF("00-12.jsonl"),
    SECOND_HALF("12-24.jsonl"),

    ;

    companion object {
        /** Compatibility aliases for the original AM/PM names. */
        val AM: ArchiveSegmentType get() = FIRST_HALF
        val PM: ArchiveSegmentType get() = SECOND_HALF
    }
}

typealias ArchiveHalf = ArchiveSegmentType

data class ArchiveSlice(
    val date: LocalDate,
    val half: ArchiveHalf,
    val zoneId: ZoneId,
    val start: ZonedDateTime,
    val endExclusive: ZonedDateTime,
) {
    val startMillis: Long get() = start.toInstant().toEpochMilli()
    val endMillis: Long get() = endExclusive.toInstant().toEpochMilli()
    val segmentId: String get() = "$date-${half.name}"
    val relativePath: String
        get() = "archive/${date.format(YEAR_MONTH)}/$date/${half.fileName}"

    companion object {
        private val YEAR_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM")
    }
}

/** Device-local half-day partitioning. It deliberately does not use UTC modulo arithmetic. */
object ArchivePartition {
    fun sliceForTimestamp(timestamp: Long, zoneId: ZoneId = ZoneId.systemDefault()): ArchiveSlice {
        val localDate = Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate()
        val slices = slicesForDate(localDate, zoneId)
        return slices.first { timestamp >= it.startMillis && timestamp < it.endMillis }
    }

    fun forTimestamp(timestamp: Long, zoneId: ZoneId = ZoneId.systemDefault()): ArchiveSlice =
        sliceForTimestamp(timestamp, zoneId)

    fun slicesForDate(date: LocalDate, zoneId: ZoneId = ZoneId.systemDefault()): List<ArchiveSlice> {
        val midnight = date.atStartOfDay(zoneId)
        val noon = date.atTime(12, 0).atZone(zoneId)
        val nextMidnight = date.plusDays(1).atStartOfDay(zoneId)
        return listOf(
            ArchiveSlice(date, ArchiveSegmentType.FIRST_HALF, zoneId, midnight, noon),
            ArchiveSlice(date, ArchiveSegmentType.SECOND_HALF, zoneId, noon, nextMidnight),
        )
    }
}
