package io.github.tuzfucius.personalrecorder.sync

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class DailyArchiveFinalizeSchedulerTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun schedulesTheNextLocal0030() {
        val now = LocalDate.of(2026, 8, 30).atTime(0, 29).atZone(zone).toInstant().toEpochMilli()
        val expected = 60_000L
        assertEquals(expected, DailyArchiveFinalizeScheduler.nextDelayMillis(now, zone))
    }

    @Test
    fun rollsToTomorrowAfterTheTargetTime() {
        val now = LocalDate.of(2026, 8, 30).atTime(1, 0).atZone(zone).toInstant().toEpochMilli()
        val target = LocalDate.of(2026, 8, 31).atTime(0, 30).atZone(zone).toInstant().toEpochMilli()
        assertEquals(target - now, DailyArchiveFinalizeScheduler.nextDelayMillis(now, zone))
    }
}
