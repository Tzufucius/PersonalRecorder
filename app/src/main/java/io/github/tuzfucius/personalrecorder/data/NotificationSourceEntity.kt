package io.github.tuzfucius.personalrecorder.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Metadata about a package that the notification listener has observed. */
@Entity(tableName = "notification_sources")
data class NotificationSourceEntity(
    @PrimaryKey val packageName: String,
    val lastKnownLabel: String?,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val observedNotificationCount: Long,
    val lastKnownHasLauncher: Boolean? = null,
)
