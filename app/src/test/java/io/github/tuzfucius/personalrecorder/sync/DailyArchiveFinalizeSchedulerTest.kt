package io.github.tuzfucius.personalrecorder.sync

import androidx.work.NetworkType
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun dailyFinalizeDoesNotRequireNetwork() {
        val request = DailyArchiveFinalizeWorker.request(scheduled = true, initialDelayMillis = 0L)

        assertEquals(NetworkType.NOT_REQUIRED, request.workSpec.constraints.requiredNetworkType)
    }

    @Test
    fun targetBasedNamesAreIndependentPerDay() {
        val first = DailyArchiveFinalizeScheduler.nextTargetMillis(
            LocalDate.of(2026, 8, 30).atTime(1, 0).atZone(zone).toInstant().toEpochMilli(),
            zone,
        )
        val second = first + 24 * 60 * 60 * 1000L

        assertTrue(DailyArchiveFinalizeScheduler.uniqueWorkName(first) != DailyArchiveFinalizeScheduler.uniqueWorkName(second))
    }
}
