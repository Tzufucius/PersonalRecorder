package io.github.tuzfucius.personalrecorder.collector

import android.service.notification.StatusBarNotification
import io.github.tuzfucius.personalrecorder.settings.FilterMode
import io.github.tuzfucius.personalrecorder.settings.FilterSettings

/** Central extension point for notification collection rules. */
object NotificationFilter {
    fun shouldCollect(
        statusBarNotification: StatusBarNotification,
        ownPackageName: String,
        settings: FilterSettings = FilterSettings()
    ): Boolean = runCatching {
        shouldCollectPackage(statusBarNotification.packageName, ownPackageName, settings)
    }.getOrDefault(false)

    fun shouldCollectPackage(
        packageName: String,
        ownPackageName: String,
        settings: FilterSettings = FilterSettings()
    ): Boolean {
        if (packageName == ownPackageName) return false
        return when (settings.mode) {
            FilterMode.ALL -> true
            FilterMode.WHITELIST -> packageName in settings.selectedPackages
            FilterMode.BLACKLIST -> packageName !in settings.selectedPackages
        }
    }
}
