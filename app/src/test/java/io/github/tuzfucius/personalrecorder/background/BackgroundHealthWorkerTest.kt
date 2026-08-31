package io.github.tuzfucius.personalrecorder.background

import androidx.work.WorkInfo
import io.github.tuzfucius.personalrecorder.sync.SyncFrequency
import io.github.tuzfucius.personalrecorder.sync.SyncScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundHealthWorkerTest {
    @Test
    fun unknownListenerRequestsRebindWhenPermissionIsGranted() {
        assertTrue(shouldRequestListenerRebind(BackgroundRuntimeState(), true, now = 120_000L))
        assertFalse(shouldRequestListenerRebind(BackgroundRuntimeState(), false, now = 120_000L))
    }

    @Test
    fun connectedFreshListenerDoesNotRebindAndCooldownBlocksRepeats() {
        val fresh = BackgroundRuntimeState(
            listenerStatus = ListenerRuntimeStatus.CONNECTED,
            lastListenerCallbackAt = 100_000L,
        )
        assertFalse(shouldRequestListenerRebind(fresh, true, now = 100_500L))
        val staleInCooldown = fresh.copy(
            lastListenerCallbackAt = 0L,
            lastRebindRequestAt = 100_000L,
        )
        assertFalse(shouldRequestListenerRebind(staleInCooldown, true, now = 100_500L))
        assertTrue(shouldRequestListenerRebind(staleInCooldown, true, now = 161_000L))
    }

    @Test
    fun healthWatchdogEnsuresDailyFinalizeScheduling() = runBlocking {
        val scheduler = RecordingScheduler()

        ensureDailyFinalizeWatchdog(scheduler)

        assertTrue(scheduler.ensureCalled)
    }

    private class RecordingScheduler : SyncScheduler {
        var ensureCalled = false

        override fun schedule(frequency: SyncFrequency) = Unit
        override fun enqueueNow() = Unit
        override suspend fun ensureDailyFinalizeScheduled() {
            ensureCalled = true
        }
        override suspend fun enqueueDailyFinalizeCatchUp() = Unit
        override fun observeNowWork(): Flow<List<WorkInfo>> = emptyFlow()
        override fun cancel() = Unit
    }
}
