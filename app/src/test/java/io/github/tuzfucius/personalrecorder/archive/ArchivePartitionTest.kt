package io.github.tuzfucius.personalrecorder.archive

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchivePartitionTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun partitionsUseLocalMiddayNotUtcModulo() {
        val date = LocalDate.of(2026, 8, 22)
        val morning = date.atTime(11, 59).atZone(zone).toInstant().toEpochMilli()
        val afternoon = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

        assertEquals(ArchiveHalf.AM, ArchivePartition.sliceForTimestamp(morning, zone).half)
        assertEquals(ArchiveHalf.PM, ArchivePartition.sliceForTimestamp(afternoon, zone).half)
    }

    @Test
    fun daylightSavingTransitionsRemainContiguous() {
        val slices = ArchivePartition.slicesForDate(LocalDate.of(2026, 11, 1), ZoneId.of("America/New_York"))
        assertEquals(slices[0].endMillis, slices[1].startMillis)
        assertTrue(slices[1].endMillis > slices[1].startMillis)
    }
}
