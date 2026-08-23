package io.github.tuzfucius.personalrecorder.archive

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
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
        )

        assertEquals(listOf(LocalDate.of(2026, 8, 21), LocalDate.of(2026, 8, 22)), dates)
    }
}
