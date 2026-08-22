package io.github.tuzfucius.personalrecorder.collector

import android.service.notification.StatusBarNotification

/** Central extension point for notification collection rules. */
object NotificationFilter {
    fun shouldCollect(
        statusBarNotification: StatusBarNotification,
        ownPackageName: String
    ): Boolean = runCatching {
        statusBarNotification.packageName != ownPackageName
    }.getOrDefault(false)
}
