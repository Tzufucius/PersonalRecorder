package io.github.tuzfucius.personalrecorder.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DailyArchiveFinalizeWorkerInstrumentedTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
    }

    @After
    fun tearDown() = runBlocking {
        DailyArchiveFinalizeWorker.clearConfigurationForTests()
        workManager.cancelAllWork().result.get(10, TimeUnit.SECONDS)
    }

    @Test
    fun nonRetryableFailureStillLeavesNextDailyWorkEnqueued() {
        DailyArchiveFinalizeWorker.configure(object : CloudSyncWorkRunner {
            override suspend fun runSync(): SyncBatchResult = SyncBatchResult(emptyList())

            override suspend fun runDailyFinalize(): DailyFinalizeResult {
                throw IllegalStateException("invalid local archive")
            }
        })

        val worker = TestListenableWorkerBuilder<DailyArchiveFinalizeWorker>(context).build()
        val result = worker.startWork().get(10, TimeUnit.SECONDS)

        assertTrue(result is ListenableWorker.Result.Failure)
        assertTrue(
            workManager.getWorkInfosByTag(DailyArchiveFinalizeWorker.WORK_TAG)
                .get(10, TimeUnit.SECONDS)
                .any { it.state == WorkInfo.State.ENQUEUED }
        )
    }

    @Test
    fun ensureDoesNotDuplicateAnAlreadyEnqueuedTarget() = runBlocking {
        workManager.cancelAllWork().result.get(10, TimeUnit.SECONDS)
        val now = System.currentTimeMillis()
        val target = DailyArchiveFinalizeScheduler.nextTargetMillis(now)
        workManager.enqueueUniqueWork(
            DailyArchiveFinalizeScheduler.uniqueWorkName(target),
            androidx.work.ExistingWorkPolicy.REPLACE,
            DailyArchiveFinalizeWorker.request(
                scheduled = true,
                initialDelayMillis = (target - now).coerceAtLeast(0L),
            ),
        )

        WorkManagerSyncScheduler(context).ensureDailyFinalizeScheduled()

        val active = workManager.getWorkInfosByTag(DailyArchiveFinalizeWorker.WORK_TAG)
            .get(10, TimeUnit.SECONDS)
            .count { !it.state.isFinished }
        assertEquals(1, active)
    }
}
