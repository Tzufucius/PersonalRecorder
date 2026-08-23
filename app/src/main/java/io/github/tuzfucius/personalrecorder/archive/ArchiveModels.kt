package io.github.tuzfucius.personalrecorder.archive

import io.github.tuzfucius.personalrecorder.data.PersonalEvent
import kotlinx.serialization.Serializable

/** Versioned wire representation. Keep this type independent of Room entities. */
@Serializable
data class ArchivedEvent(
    val schemaVersion: Int = 1,
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
    val createdAt: Long,
) {
    init {
        require(schemaVersion == 1) { "Unsupported archive event schema: $schemaVersion" }
    }

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
        createdAt = createdAt,
    )

    companion object {
        fun fromPersonalEvent(event: PersonalEvent): ArchivedEvent = ArchivedEvent(
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
            createdAt = event.createdAt,
        )
    }
}

@Serializable
data class ArchiveManifest(
    val schemaVersion: Int = 1,
    val date: String,
    val timeZone: String,
    val segments: List<ArchiveManifestSegment>,
    val totalEventCount: Int,
    val sourceDeviceIds: List<String> = emptyList(),
    val lastWriterDeviceId: String? = null,
)

@Serializable
data class ArchiveManifestSegment(
    val fileName: String,
    val eventCount: Int,
    val sha256: String,
)
