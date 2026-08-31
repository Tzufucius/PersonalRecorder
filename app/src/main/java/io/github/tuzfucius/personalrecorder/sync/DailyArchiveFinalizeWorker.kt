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
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

object DailyArchiveFinalizeScheduler {
    fun nextTargetMillis(
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Long {
        val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
        var target = now.with(LocalTime.of(0, 30))
        if (!target.isAfter(now)) target = target.plusDays(1)
        return target.toInstant().toEpochMilli()
    }

    fun nextDelayMillis(
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Long = (nextTargetMillis(nowMillis, zoneId) - nowMillis).coerceAtLeast(0L)

    fun uniqueWorkName(targetMillis: Long): String =
        "${WorkManagerSyncScheduler.DAILY_SCHEDULE_NAME}_$targetMillis"
}

/** Finalizes all closed archive dates offline, then lets ordinary sync publish them. */
class DailyArchiveFinalizeWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val runner = configuredRunner ?: CloudSyncRuntime.ensureConfigured(applicationContext)
        val finalizeResult: kotlin.Result<DailyFinalizeResult> = try {
            kotlin.Result.success(runner.runDailyFinalize())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            kotlin.Result.failure(error)
        }
        val scheduleResult = runCatching { enqueueNextDailyFinalize() }
        if (scheduleResult.isFailure) return Result.retry()

        val result = finalizeResult.getOrElse { error ->
            if (error is SyncHttpException && (error.statusCode >= 500 || error.rateLimited)) {
                return Result.retry()
            }
            return Result.failure()
        }
        if (result.needsCloudSync) {
            runCatching { CloudSyncRuntime.scheduler(applicationContext).enqueueNow() }
        }
        return if (result.localFinalizeSuccessful) Result.success() else Result.failure()
    }

    private fun enqueueNextDailyFinalize() {
        val now = System.currentTimeMillis()
        val target = DailyArchiveFinalizeScheduler.nextTargetMillis(now)
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            DailyArchiveFinalizeScheduler.uniqueWorkName(target),
            ExistingWorkPolicy.REPLACE,
            request(
                scheduled = true,
                initialDelayMillis = (target - now).coerceAtLeast(0L),
            ),
        )
    }

    companion object {
        const val KEY_SCHEDULED = "scheduled"
        const val WORK_TAG = "daily_archive_finalize"
        const val CATCH_UP_WORK_TAG = "daily_archive_finalize_catch_up"

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
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build()
                )
                .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
                .setInputData(Data.Builder().putBoolean(KEY_SCHEDULED, scheduled).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(if (scheduled) WORK_TAG else CATCH_UP_WORK_TAG)
                .build()
    }
}
