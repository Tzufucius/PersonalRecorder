package io.github.tuzfucius.personalrecorder

import android.app.Application
import io.github.tuzfucius.personalrecorder.background.BackgroundHealthWorker
import io.github.tuzfucius.personalrecorder.background.BackgroundSettingsStore
import io.github.tuzfucius.personalrecorder.background.RecentTaskController
import io.github.tuzfucius.personalrecorder.background.BackgroundRuntimeStateStore
import io.github.tuzfucius.personalrecorder.sync.CloudSyncRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

/** Process-level setup shared by the activity, listener and WorkManager workers. */
class PersonalRecorderApplication : Application() {
    val processInstanceId: String = UUID.randomUUID().toString()
    val processStartedAt: Long = System.currentTimeMillis()
    lateinit var recentTaskController: RecentTaskController
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        recentTaskController = RecentTaskController.create(this)
        CloudSyncRuntime.configure(this)
        applicationScope.launch {
            BackgroundRuntimeStateStore(this@PersonalRecorderApplication)
                .markProcessStarted(processInstanceId, processStartedAt)
        }
        BackgroundHealthWorker.schedule(this)
        applicationScope.launch {
            BackgroundSettingsStore(this@PersonalRecorderApplication).hideFromRecents
                .collectLatest { recentTaskController.setExcludeFromRecents(it) }
        }
    }

    /** Re-applies the policy after an Activity creates the app task. */
    fun refreshRecentTaskPolicy(taskId: Int? = null) {
        applicationScope.launch {
            val hidden = BackgroundSettingsStore(this@PersonalRecorderApplication).hideFromRecents.first()
            recentTaskController.setExcludeFromRecents(hidden, taskId)
        }
    }

    override fun onTerminate() {
        applicationScope.cancel()
        super.onTerminate()
    }
}
