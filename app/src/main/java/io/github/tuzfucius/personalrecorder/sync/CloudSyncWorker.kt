package io.github.tuzfucius.personalrecorder.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** 将归档查询和同步协调交给应用层组合，避免 Worker 持有 UI 或凭证。 */
fun interface CloudSyncWorkRunner {
    suspend fun runSync(): SyncBatchResult
}

/**
 * 只在有网络时运行的 WorkManager 入口。周期任务由系统调度，不提供或暗示精确执行时间。
 * 应用启动时须通过 [configure] 注入 runner；未配置时请求重试而不把任务错误标记为成功。
 */
class CloudSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val syncRunner = runner ?: return Result.retry()
        return runCatching { syncRunner.runSync() }
            .fold(
                onSuccess = { result -> if (result.needsRetry) Result.retry() else Result.success() },
                onFailure = { Result.retry() }
            )
    }

    companion object {
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
    fun cancel()
}

/** WorkManager 周期调度器；更新频率时替换同名周期任务。 */
class WorkManagerSyncScheduler(context: Context) : SyncScheduler {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun schedule(frequency: SyncFrequency) {
        val request = PeriodicWorkRequestBuilder<CloudSyncWorker>(
            frequency.repeatIntervalMillis,
            TimeUnit.MILLISECONDS
        ).setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        ).addTag(WORK_TAG).build()

        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    override fun cancel() {
        workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "cloud_archive_sync"
        const val WORK_TAG = "cloud_archive_sync"
    }
}
