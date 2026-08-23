package io.github.tuzfucius.personalrecorder

import android.app.Application
import io.github.tuzfucius.personalrecorder.background.BackgroundHealthWorker
import io.github.tuzfucius.personalrecorder.background.BackgroundSettingsStore
import io.github.tuzfucius.personalrecorder.background.RecentTaskController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Process-level setup shared by the activity, listener and WorkManager workers. */
class PersonalRecorderApplication : Application() {
    lateinit var recentTaskController: RecentTaskController
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        recentTaskController = RecentTaskController.create(this)
        BackgroundHealthWorker.schedule(this)
        applicationScope.launch {
            BackgroundSettingsStore(this@PersonalRecorderApplication).hideFromRecents
                .collectLatest { recentTaskController.setExcludeFromRecents(it) }
        }
    }

    override fun onTerminate() {
        applicationScope.cancel()
        super.onTerminate()
    }
}
