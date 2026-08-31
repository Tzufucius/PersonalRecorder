package io.github.tuzfucius.personalrecorder.sync

import io.github.tuzfucius.personalrecorder.settings.CloudSyncSettings

/** Keeps ordinary sync frequency independent from the daily archive finalizer. */
object SyncSchedulingCoordinator {
    suspend fun ensure(
        settings: CloudSyncSettings?,
        scheduler: SyncScheduler,
        triggerCatchUp: Boolean,
    ) {
        if (settings?.githubConnected == true && settings.githubEnabled) {
            scheduler.schedule(settings.frequency)
        }
        scheduler.ensureDailyFinalizeScheduled()
        if (triggerCatchUp) scheduler.enqueueDailyFinalizeCatchUp()
    }
}
