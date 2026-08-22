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
    val date: String,
    val timeZone: String,
    val segments: List<ArchiveManifestSegment>,
    val totalEventCount: Int,
)

@Serializable
data class ArchiveManifestSegment(
    val fileName: String,
    val eventCount: Int,
    val sha256: String,
)
