package io.github.tuzfucius.personalrecorder.archive

import io.github.tuzfucius.personalrecorder.data.ArchiveSegmentEntity
import io.github.tuzfucius.personalrecorder.data.PersonalEvent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId

data class ArchiveSegmentResult(
    val slice: ArchiveSlice,
    val file: File,
    val eventCount: Int,
    val sha256: String,
) {
    fun toEntity(createdAt: Long = System.currentTimeMillis()): ArchiveSegmentEntity = ArchiveSegmentEntity(
        segmentId = slice.segmentId,
        date = slice.date.toString(),
        slot = slice.half.name,
        relativePath = slice.relativePath,
        startMillis = slice.startMillis,
        endMillis = slice.endMillis,
        eventCount = eventCount,
        sha256 = sha256,
        closed = true,
        createdAt = createdAt,
    )
}

data class ArchiveDayResult(
    val date: LocalDate,
    val segments: List<ArchiveSegmentResult>,
    val manifest: File?,
)

/** Writes immutable JSONL files below filesDir/archive. */
class ArchiveWriter(
    filesDir: File,
    val zoneId: ZoneId = ZoneId.systemDefault(),
    private val json: Json = archiveJson,
) {
    private val archiveRoot = File(filesDir, "archive")

    fun writeSegment(date: LocalDate, half: ArchiveHalf, events: Iterable<PersonalEvent>): ArchiveSegmentResult {
        val slice = ArchivePartition.slicesForDate(date, zoneId).first { it.half == half }
        val file = File(archiveRoot, "${date.format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM"))}/$date/${half.fileName}")
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            val lines = events.asSequence()
                .filter { it.timestamp >= slice.startMillis && it.timestamp < slice.endMillis }
                .sortedWith(compareBy<PersonalEvent> { it.timestamp }.thenBy { it.id })
                .map { json.encodeToString(ArchivedEvent.fromPersonalEvent(it)) }
                .toList()
            file.writeText(if (lines.isEmpty()) "" else lines.joinToString(separator = "\n", postfix = "\n"), StandardCharsets.UTF_8)
        }
        return ArchiveSegmentResult(slice, file, countLines(file), sha256(file))
    }

    fun writeDay(
        date: LocalDate,
        events: Iterable<PersonalEvent>,
        nowMillis: Long = System.currentTimeMillis(),
    ): ArchiveDayResult {
        val now = java.time.Instant.ofEpochMilli(nowMillis).atZone(zoneId)
        val today = now.toLocalDate()
        val closedHalves = when {
            date.isBefore(today) -> ArchiveSegmentType.entries.toList()
            date.isAfter(today) -> emptyList()
            now.toLocalTime() >= java.time.LocalTime.NOON -> listOf(ArchiveSegmentType.FIRST_HALF)
            else -> emptyList()
        }
        val segments = ArchiveSegmentType.entries
            .filter { half ->
                half in closedHalves || segmentFile(date, half).isFile
            }
            .map { writeSegment(date, it, events) }
        val manifest = writeManifestIfComplete(date, segments)
        return ArchiveDayResult(date, segments, manifest)
    }

    fun writeManifestIfComplete(date: LocalDate, segments: List<ArchiveSegmentResult>): File? {
        val expected = ArchiveSegmentType.entries.map { it.fileName }
        if (segments.map { it.slice.half.fileName }.toSet() != expected.toSet()) return null
        val directory = segments.first().file.parentFile ?: return null
        val manifest = File(directory, "manifest.json")
        if (!manifest.exists()) {
            val body = ArchiveManifest(
                schemaVersion = 1,
                date = date.toString(),
                timeZone = zoneId.id,
                segments = segments.sortedBy { it.slice.startMillis }.map {
                    ArchiveManifestSegment(it.slice.half.fileName, it.eventCount, it.sha256)
                },
                totalEventCount = segments.sumOf { it.eventCount },
            )
            manifest.writeText(json.encodeToString(body) + "\n", StandardCharsets.UTF_8)
        }
        return manifest
    }

    private fun segmentFile(date: LocalDate, half: ArchiveSegmentType): File =
        File(archiveRoot, "${date.format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM"))}/$date/${half.fileName}")

    private fun countLines(file: File): Int = file.useLines { lines -> lines.count() }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        val archiveJson: Json = Json {
            encodeDefaults = true
            explicitNulls = true
            prettyPrint = false
        }
    }
}
