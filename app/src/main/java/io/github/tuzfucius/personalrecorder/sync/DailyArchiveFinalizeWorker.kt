package io.github.tuzfucius.personalrecorder.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

object DailyArchiveFinalizeScheduler {
    fun nextDelayMillis(
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Long {
        val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
        var target = now.with(LocalTime.of(0, 30))
        if (!target.isAfter(now)) target = target.plusDays(1)
        return (target.toInstant().toEpochMilli() - nowMillis).coerceAtLeast(0L)
    }
}

/** Finalizes all closed archive dates, then publishes their manifest last. */
class DailyArchiveFinalizeWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val runner = configuredRunner ?: CloudSyncRuntime.ensureConfigured(applicationContext)
        val result = runCatching { runner.runDailyFinalize() }
            .getOrElse { error ->
                if (error is SyncHttpException && (error.statusCode >= 500 || error.rateLimited)) {
                    return Result.retry()
                }
                return Result.failure()
            }
        if (result.needsRetry) return Result.retry()

        if (inputData.getBoolean(KEY_SCHEDULED, false)) {
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                WorkManagerSyncScheduler.DAILY_SCHEDULE_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request(scheduled = true, initialDelayMillis = DailyArchiveFinalizeScheduler.nextDelayMillis()),
            )
        }
        return if (result.isSuccessful) Result.success() else Result.failure()
    }

    companion object {
        const val KEY_SCHEDULED = "scheduled"
        const val WORK_TAG = "daily_archive_finalize"

        @Volatile
        private var configuredRunner: CloudSyncWorkRunner? = null

        fun configure(workRunner: CloudSyncWorkRunner) {
            configuredRunner = workRunner
        }

        fun clearConfigurationForTests() {
            configuredRunner = null
        }

        fun request(scheduled: Boolean, initialDelayMillis: Long): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<DailyArchiveFinalizeWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
                .setInputData(Data.Builder().putBoolean(KEY_SCHEDULED, scheduled).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(WORK_TAG)
                .build()
    }
}
