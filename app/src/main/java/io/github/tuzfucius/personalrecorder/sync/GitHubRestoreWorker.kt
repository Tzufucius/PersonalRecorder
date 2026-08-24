package io.github.tuzfucius.personalrecorder.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit

/** Durable, idempotent full archive restore owned by WorkManager rather than a ViewModel. */
class GitHubRestoreWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        return try {
            val report = CloudSyncRuntime.ensureConfigured(applicationContext).runReconcile(
                mode = ReconcileMode.FULL_RESTORE,
                onProgress = { progress -> setProgress(progress.toData()) },
            )
            when {
                report.needsRetry -> Result.retry()
                report.isSuccessful -> Result.success(report.toData())
                else -> Result.failure(report.toData())
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (error is SyncHttpException && (error.statusCode >= 500 || error.rateLimited)) {
                Result.retry()
            } else {
                Result.failure(
                    Data.Builder()
                        .putString(KEY_PHASE, "FAILED")
                        .putString(KEY_ERROR, error.message ?: "归档恢复失败")
                        .build()
                )
            }
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "github_archive_full_restore"
        const val KEY_PHASE = "phase"
        const val KEY_DISCOVERED = "discovered"
        const val KEY_PROCESSED = "processed"
        const val KEY_TOTAL = "total"
        const val KEY_DOWNLOADED = "downloaded"
        const val KEY_UPLOADED = "uploaded"
        const val KEY_SKIPPED = "skipped"
        const val KEY_CONFLICTS = "conflicts"
        const val KEY_CURRENT_PATH = "currentPath"
        const val KEY_ERROR = "error"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<GitHubRestoreWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        fun observe(context: Context): Flow<List<WorkInfo>> =
            WorkManager.getInstance(context.applicationContext)
                .getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME)
    }
}

private fun ReconcileProgress.toData(): Data = Data.Builder()
    .putString(GitHubRestoreWorker.KEY_PHASE, phase)
    .putInt(GitHubRestoreWorker.KEY_DISCOVERED, discovered)
    .putInt(GitHubRestoreWorker.KEY_PROCESSED, processed)
    .putInt(GitHubRestoreWorker.KEY_TOTAL, total)
    .putInt(GitHubRestoreWorker.KEY_DOWNLOADED, downloaded)
    .putInt(GitHubRestoreWorker.KEY_UPLOADED, uploaded)
    .putInt(GitHubRestoreWorker.KEY_SKIPPED, skipped)
    .putInt(GitHubRestoreWorker.KEY_CONFLICTS, conflicts)
    .putString(GitHubRestoreWorker.KEY_CURRENT_PATH, currentPath)
    .build()

private fun ReconcileReport.toData(): Data = Data.Builder()
    .putString(GitHubRestoreWorker.KEY_PHASE, restoreState.name)
    .putInt(GitHubRestoreWorker.KEY_DISCOVERED, discoveredRemote)
    .putInt(GitHubRestoreWorker.KEY_DOWNLOADED, downloaded)
    .putInt(GitHubRestoreWorker.KEY_UPLOADED, uploaded)
    .putInt(GitHubRestoreWorker.KEY_SKIPPED, skipped)
    .putInt(GitHubRestoreWorker.KEY_CONFLICTS, conflicts)
    .putString(GitHubRestoreWorker.KEY_ERROR, results.firstOrNull { it.error != null }?.error?.message)
    .build()
