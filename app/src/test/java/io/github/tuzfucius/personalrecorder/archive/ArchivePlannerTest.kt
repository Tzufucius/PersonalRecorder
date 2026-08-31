package io.github.tuzfucius.personalrecorder.archive

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchivePlannerTest {
    @Test
    fun findsOnlyMissingClosedSegmentsAcrossHistory() {
        val zone = ZoneId.of("Asia/Shanghai")
        val existing = setOf(
            "2026-08-20-FIRST_HALF",
            "2026-08-20-SECOND_HALF",
            "2026-08-21-FIRST_HALF",
        )
        val now = LocalDate.of(2026, 8, 22).atTime(18, 0).atZone(zone).toInstant().toEpochMilli()

        val dates = ArchivePlanner(zone).missingClosedDates(
            minTimestamp = LocalDate.of(2026, 8, 20).atStartOfDay(zone).toInstant().toEpochMilli(),
            nowMillis = now,
            existingSegmentIds = existing,
            existingManifestDates = setOf(LocalDate.of(2026, 8, 20)),
        )

        assertEquals(listOf(LocalDate.of(2026, 8, 21), LocalDate.of(2026, 8, 22)), dates)
    }

    @Test
    fun findsClosedDayWhenBothSegmentsExistButManifestIsMissing() {
        val zone = ZoneId.of("Asia/Shanghai")
        val date = LocalDate.of(2026, 8, 29)
        val existing = setOf("$date-FIRST_HALF", "$date-SECOND_HALF")
        val now = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val dates = ArchivePlanner(zone).missingClosedDates(
            minTimestamp = date.atStartOfDay(zone).toInstant().toEpochMilli(),
            nowMillis = now,
            existingSegmentIds = existing,
            existingManifestDates = emptySet(),
            onlyFullyClosedDays = true,
        )

        assertEquals(listOf(date), dates)
    }

    @Test
    fun doesNotFinalizeTheCurrentDayBeforeMidnight() {
        val zone = ZoneId.of("Asia/Shanghai")
        val date = LocalDate.of(2026, 8, 29)
        val now = date.atTime(13, 0).atZone(zone).toInstant().toEpochMilli()

        val dates = ArchivePlanner(zone).missingClosedDates(
            minTimestamp = date.atStartOfDay(zone).toInstant().toEpochMilli(),
            nowMillis = now,
            existingSegmentIds = emptySet(),
            onlyFullyClosedDays = true,
        )

        assertTrue(dates.isEmpty())
    }
}
