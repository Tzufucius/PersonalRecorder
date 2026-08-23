package io.github.tuzfucius.personalrecorder.background

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.tuzfucius.personalrecorder.collector.NotificationCollectorService
import io.github.tuzfucius.personalrecorder.data.AppDatabase
import io.github.tuzfucius.personalrecorder.settings.CloudSyncSettingsState
import io.github.tuzfucius.personalrecorder.settings.CloudSyncSettingsStore
import io.github.tuzfucius.personalrecorder.sync.CloudBackendType
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class BackgroundHealthWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = runCatching {
        val context = applicationContext
        val runtime = BackgroundRuntimeStateStore(context)
        val database = AppDatabase.getInstance(context)
        val listenerEnabled = NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
        val current = runtime.state.first()
        if (listenerEnabled && !current.listenerConnected) {
            runCatching {
                NotificationListenerService.requestRebind(
                    ComponentName(context, NotificationCollectorService::class.java)
                )
            }
        }
        val pendingUploads = database.eventDao().countPendingUploads(CloudBackendType.GITHUB.name)
        val pendingDownloads = database.eventDao().countPendingDownloads(CloudBackendType.GITHUB.name)
        val conflicts = database.eventDao().getUnresolvedConflictCount().first()
        runtime.updateCounts(pendingUploads, pendingDownloads, conflicts)
        runtime.markHealthCheck()

        val dayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val todayCount = database.eventDao().countEventsBetween(dayStart, System.currentTimeMillis() + 1)
        val settings = CloudSyncSettingsStore(context).state.first()
        val githubConnected = (settings as? CloudSyncSettingsState.Ready)?.settings?.githubConnected == true
        if (BackgroundSettingsStore(context).statusNotificationEnabled.first()) {
            StatusNotificationManager(context).show(runtime.state.first(), todayCount)
        }
        if (!githubConnected) {
            runtime.markSyncError("GitHub 尚未连接")
        }
        Result.success()
    }.getOrElse { Result.retry() }

    companion object {
        private const val PERIODIC_NAME = "background_health_check"
        private const val NOW_NAME = "background_health_check_now"

        fun schedule(context: Context) {
            val manager = WorkManager.getInstance(context.applicationContext)
            val request = PeriodicWorkRequestBuilder<BackgroundHealthWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
                .addTag(PERIODIC_NAME)
                .build()
            manager.enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun enqueueNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<BackgroundHealthWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(NOW_NAME)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                NOW_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
