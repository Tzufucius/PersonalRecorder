package io.github.tuzfucius.personalrecorder.data

/**
 * Application-level representation of one captured personal event.
 *
 * This model deliberately does not expose Android's StatusBarNotification so
 * the rest of the app stays independent from the notification framework.
 */
data class PersonalEvent(
    val id: String,
    val timestamp: Long,
    val source: String,
    val packageName: String,
    val title: String?,
    val content: String?,
    val bigText: String?,
    val textLines: List<String>,
    val notificationKey: String,
    val notificationId: Int,
    val category: String?,
    val channelId: String?,
    val groupKey: String?,
    val isOngoing: Boolean,
    val isGroupSummary: Boolean,
    val isClearable: Boolean,
    val createdAt: Long
)
