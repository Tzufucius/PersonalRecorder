package io.github.tuzfucius.personalrecorder.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.TimeUnit

/** 将归档查询和同步协调交给应用层组合，避免 Worker 持有 UI 或凭证。 */
interface CloudSyncWorkRunner {
    suspend fun runSync(): SyncBatchResult

    suspend fun runSync(force: Boolean): SyncBatchResult = runSync()

    suspend fun runDailyFinalize(): SyncBatchResult = runSync(force = false)
}

class CloudSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val syncRunner = runner ?: CloudSyncRuntime.ensureConfigured(applicationContext)
        return runCatching {
            syncRunner.runSync(inputData.getBoolean(MANUAL_SYNC_KEY, false))
        }.fold(
            onSuccess = { result -> if (result.needsRetry) Result.retry() else Result.success() },
            onFailure = { Result.failure() },
        )
    }

    companion object {
        private const val MANUAL_SYNC_KEY = "manual_sync"

        @Volatile
        private var runner: CloudSyncWorkRunner? = null

        fun configure(workRunner: CloudSyncWorkRunner) {
            runner = workRunner
        }

        fun clearConfigurationForTests() {
            runner = null
        }
    }
}

interface SyncScheduler {
    fun schedule(frequency: SyncFrequency)
    fun enqueueNow()
    suspend fun ensureDailyFinalizeScheduled()
    suspend fun enqueueDailyFinalizeCatchUp()
    fun observeNowWork(): Flow<List<WorkInfo>>
    fun cancel()
}

/** WorkManager 周期与手动任务调度器。 */
class WorkManagerSyncScheduler(context: Context) : SyncScheduler {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun schedule(frequency: SyncFrequency) {
        val request = PeriodicWorkRequestBuilder<CloudSyncWorker>(
            frequency.repeatIntervalMillis,
            TimeUnit.MILLISECONDS,
        ).setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        ).addTag(WORK_TAG).build()
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    override fun enqueueNow() {
        val request = OneTimeWorkRequestBuilder<CloudSyncWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setInputData(Data.Builder().putBoolean("manual_sync", true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(MANUAL_WORK_TAG)
            .build()
        workManager.enqueueUniqueWork(UNIQUE_NOW_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    override suspend fun ensureDailyFinalizeScheduled() {
        val hasActiveWork = withContext(Dispatchers.IO) {
            workManager.getWorkInfosForUniqueWork(DAILY_SCHEDULE_NAME).get()
                .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED }
        }
        if (!hasActiveWork) {
            workManager.enqueueUniqueWork(
                DAILY_SCHEDULE_NAME,
                ExistingWorkPolicy.REPLACE,
                DailyArchiveFinalizeWorker.request(
                    scheduled = true,
                    initialDelayMillis = DailyArchiveFinalizeScheduler.nextDelayMillis(),
                ),
            )
        }
    }

    override suspend fun enqueueDailyFinalizeCatchUp() {
        val request = DailyArchiveFinalizeWorker.request(
            scheduled = false,
            initialDelayMillis = 0L,
        )
        workManager.enqueueUniqueWork(DAILY_CATCH_UP_NAME, ExistingWorkPolicy.KEEP, request)
    }

    override fun observeNowWork(): Flow<List<WorkInfo>> =
        workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_NOW_WORK_NAME)

    override fun cancel() {
        workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
        workManager.cancelUniqueWork(UNIQUE_NOW_WORK_NAME)
        workManager.cancelUniqueWork(DAILY_SCHEDULE_NAME)
        workManager.cancelUniqueWork(DAILY_CATCH_UP_NAME)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "cloud_archive_sync"
        const val UNIQUE_NOW_WORK_NAME = "cloud_archive_sync_now"
        const val WORK_TAG = "cloud_archive_sync"
        const val MANUAL_WORK_TAG = "cloud_archive_sync_now"
        const val DAILY_SCHEDULE_NAME = "daily_archive_finalize_schedule"
        const val DAILY_CATCH_UP_NAME = "daily_archive_finalize_catch_up"
    }
}
