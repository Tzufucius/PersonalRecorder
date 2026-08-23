package io.github.tuzfucius.personalrecorder.sync

import io.github.tuzfucius.personalrecorder.archive.ArchivedEvent
import io.github.tuzfucius.personalrecorder.archive.ArchiveWriter
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

data class ArchiveEventConflict(
    val eventId: String,
    val local: ArchivedEvent,
    val remote: ArchivedEvent,
)

data class ArchiveMergeResult(
    val events: List<ArchivedEvent>,
    val conflicts: List<ArchiveEventConflict>,
)

/** Pure JSONL parsing and deterministic union logic used by sync and restore. */
class ArchiveReconciler(
    private val json: Json = ArchiveWriter.archiveJson,
) {
    fun pair(local: ArchiveDescriptor?, remote: ArchiveDescriptor?): ArchivePair =
        ArchivePair(local, remote)

    fun parseJsonl(bytes: ByteArray): List<ArchivedEvent> =
        bytes.toString(Charsets.UTF_8)
            .lineSequence()
            .map(String::trimEnd)
            .filter(String::isNotBlank)
            .map { line -> json.decodeFromString<ArchivedEvent>(line) }
            .toList()

    fun encodeJsonl(events: Iterable<ArchivedEvent>): ByteArray {
        val canonical = events.sortedWith(EVENT_ORDER)
        if (canonical.isEmpty()) return ByteArray(0)
        return canonical.joinToString(separator = "\n", postfix = "\n") {
            json.encodeToString(it)
        }.toByteArray(Charsets.UTF_8)
    }

    fun merge(localBytes: ByteArray, remoteBytes: ByteArray): ArchiveMergeResult {
        val localEvents = parseJsonl(localBytes)
        val remoteEvents = parseJsonl(remoteBytes)
        val byId = linkedMapOf<String, MutableList<ArchivedEvent>>()
        localEvents.forEach { byId.getOrPut(it.id, ::mutableListOf).add(it) }
        val conflicts = mutableListOf<ArchiveEventConflict>()
        remoteEvents.forEach { remote ->
            val variants = byId.getOrPut(remote.id, ::mutableListOf)
            val identical = variants.firstOrNull { it == remote }
            if (identical == null) {
                variants.firstOrNull()?.let { local ->
                    conflicts += ArchiveEventConflict(remote.id, local, remote)
                }
                variants += remote
            }
        }
        return ArchiveMergeResult(
            events = byId.values.flatten().sortedWith(EVENT_ORDER),
            conflicts = conflicts.distinctBy { conflict ->
                conflict.eventId to sha256(json.encodeToString(conflict.remote).toByteArray(Charsets.UTF_8))
            },
        )
    }

    private companion object {
        val EVENT_ORDER = compareBy<ArchivedEvent> { it.timestamp }
            .thenBy { it.createdAt }
            .thenBy { it.id }
            .thenBy { it.content.orEmpty() }

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
