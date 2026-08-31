package io.github.tuzfucius.personalrecorder.sync

import io.github.tuzfucius.personalrecorder.settings.CloudSyncSettings
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncSchedulingCoordinatorTest {
    @Test
    fun githubConnectionSchedulesCloudDailyAndCatchUpWork() = runBlocking {
        val scheduler = RecordingScheduler()

        SyncSchedulingCoordinator.ensure(
            settings = CloudSyncSettings(githubConnected = true, githubEnabled = true),
            scheduler = scheduler,
            triggerCatchUp = true,
        )

        assertEquals(listOf("cloud", "daily", "catch-up"), scheduler.calls)
    }

    private class RecordingScheduler : SyncScheduler {
        val calls = mutableListOf<String>()
        override fun schedule(frequency: SyncFrequency) { calls += "cloud" }
        override fun enqueueNow() = Unit
        override suspend fun ensureDailyFinalizeScheduled() { calls += "daily" }
        override suspend fun enqueueDailyFinalizeCatchUp() { calls += "catch-up" }
        override fun observeNowWork(): Flow<List<WorkInfo>> = emptyFlow()
        override fun cancel() = Unit
    }
}
