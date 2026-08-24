package io.github.tuzfucius.personalrecorder.collector

import io.github.tuzfucius.personalrecorder.data.PersonalEvent
import io.github.tuzfucius.personalrecorder.settings.FilterSettings

/**
 * Keeps source discovery ahead of filtering while leaving the notification content path
 * entirely opt-in. The parser callback is not invoked for rejected notifications.
 */
class NotificationCollectorPipeline(
    private val ownPackageName: String,
    private val observeSource: suspend (String) -> Unit,
    private val persistEvent: suspend (PersonalEvent) -> Unit,
    private val onSourceObserved: (String) -> Unit = {},
    private val onSourceObservationFailure: (String, Throwable) -> Unit = { _, _ -> },
    private val onSettingsReadFailure: (Throwable) -> Unit = {},
    private val onEventPersistenceFailure: (Throwable) -> Unit = {},
) {
    suspend fun collect(
        packageName: String,
        readSettings: suspend () -> Result<FilterSettings>,
        parseEvent: suspend () -> PersonalEvent?,
    ) {
        runCatching { observeSource(packageName) }
            .onSuccess { onSourceObserved(packageName) }
            .onFailure { onSourceObservationFailure(packageName, it) }

        val settings = readSettings().onFailure(onSettingsReadFailure).getOrNull() ?: return
        if (!NotificationFilter.shouldCollectPackage(packageName, ownPackageName, settings)) return

        val event = parseEvent() ?: return
        runCatching { persistEvent(event) }
            .onFailure(onEventPersistenceFailure)
    }
}
