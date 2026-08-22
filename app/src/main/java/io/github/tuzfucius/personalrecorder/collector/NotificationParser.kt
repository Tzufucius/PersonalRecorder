package io.github.tuzfucius.personalrecorder.collector

import android.app.Notification
import android.os.Bundle
import android.os.Build
import android.service.notification.StatusBarNotification
import io.github.tuzfucius.personalrecorder.data.PersonalEvent
import java.util.UUID

object NotificationParser {
    private const val SOURCE = "notification"

    fun parse(
        statusBarNotification: StatusBarNotification,
        now: Long = System.currentTimeMillis()
    ): PersonalEvent? = try {
        val notification = statusBarNotification.notification
        val extras = runCatching { notification.extras }.getOrNull()
        val title = readText(extras, Notification.EXTRA_TITLE)
        val text = readText(extras, Notification.EXTRA_TEXT)
        val bigText = readText(extras, Notification.EXTRA_BIG_TEXT)
        val textLines = readTextLines(extras)
        val content = bigText ?: text ?: textLines.takeIf { it.isNotEmpty() }?.joinToString("\n")

        PersonalEvent(
            id = UUID.randomUUID().toString(),
            timestamp = statusBarNotification.postTime.takeIf { it > 0 } ?: now,
            source = SOURCE,
            packageName = statusBarNotification.packageName,
            title = title,
            content = content,
            bigText = bigText,
            textLines = textLines,
            notificationKey = statusBarNotification.key,
            notificationId = statusBarNotification.id,
            category = runCatching { notification.category }.getOrNull(),
            channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { notification.channelId }.getOrNull()
            } else {
                null
            },
            groupKey = runCatching { statusBarNotification.groupKey }.getOrNull(),
            isOngoing = runCatching { statusBarNotification.isOngoing }.getOrDefault(false),
            isClearable = runCatching { statusBarNotification.isClearable }.getOrDefault(false),
            createdAt = now
        )
    } catch (_: Throwable) {
        // A malformed or vendor-specific notification must not terminate the listener.
        null
    }

    private fun readText(extras: Bundle?, key: String): String? = runCatching {
        extras?.getCharSequence(key)?.toString()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun readTextLines(extras: Bundle?): List<String> {
        val arrayLines = runCatching {
            extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
                .orEmpty()
        }.getOrDefault(emptyList())
        if (arrayLines.isNotEmpty()) return arrayLines

        return runCatching {
            extras?.getCharSequenceArrayList(Notification.EXTRA_TEXT_LINES)
                ?.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
                .orEmpty()
        }.getOrDefault(emptyList())
    }
}
