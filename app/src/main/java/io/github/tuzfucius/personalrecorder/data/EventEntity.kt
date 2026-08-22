package io.github.tuzfucius.personalrecorder.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

@Entity(
    tableName = "events",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["notificationKey", "packageName"])
    ]
)
data class EventEntity(
    @PrimaryKey val id: String,
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
) {
    fun toPersonalEvent(): PersonalEvent = PersonalEvent(
        id = id,
        timestamp = timestamp,
        source = source,
        packageName = packageName,
        title = title,
        content = content,
        bigText = bigText,
        textLines = textLines,
        notificationKey = notificationKey,
        notificationId = notificationId,
        category = category,
        channelId = channelId,
        groupKey = groupKey,
        isOngoing = isOngoing,
        isGroupSummary = isGroupSummary,
        isClearable = isClearable,
        createdAt = createdAt
    )

    companion object {
        fun fromPersonalEvent(event: PersonalEvent): EventEntity = EventEntity(
            id = event.id,
            timestamp = event.timestamp,
            source = event.source,
            packageName = event.packageName,
            title = event.title,
            content = event.content,
            bigText = event.bigText,
            textLines = event.textLines,
            notificationKey = event.notificationKey,
            notificationId = event.notificationId,
            category = event.category,
            channelId = event.channelId,
            groupKey = event.groupKey,
            isOngoing = event.isOngoing,
            isGroupSummary = event.isGroupSummary,
            isClearable = event.isClearable,
            createdAt = event.createdAt
        )
    }
}

class StringListConverter {
    @TypeConverter
    fun fromStringList(value: List<String>?): String = value.orEmpty().joinToString("\n")

    @TypeConverter
    fun toStringList(value: String?): List<String> = value
        ?.takeIf { it.isNotEmpty() }
        ?.split("\n")
        .orEmpty()
}
