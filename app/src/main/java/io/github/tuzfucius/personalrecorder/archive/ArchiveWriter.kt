package io.github.tuzfucius.personalrecorder.archive

import io.github.tuzfucius.personalrecorder.data.ArchiveSegmentEntity
import io.github.tuzfucius.personalrecorder.data.PersonalEvent
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
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

/** Writes JSONL files below filesDir/archive and freezes them once manifest is valid. */
class ArchiveWriter(
    filesDir: File,
    val zoneId: ZoneId = ZoneId.systemDefault(),
    private val json: Json = archiveJson,
    var deviceInstanceId: String? = null,
) {
    private val archiveRoot = File(filesDir, "archive")

    fun writeSegment(
        date: LocalDate,
        half: ArchiveHalf,
        events: Iterable<PersonalEvent>,
        rewriteExisting: Boolean = false,
    ): ArchiveSegmentResult {
        val slice = ArchivePartition.slicesForDate(date, zoneId).first { it.half == half }
        val file = segmentFile(date, half)
        if (rewriteExisting || !file.exists()) {
            val lines = events.asSequence()
                .filter { it.timestamp >= slice.startMillis && it.timestamp < slice.endMillis }
                .sortedWith(compareBy<PersonalEvent> { it.timestamp }.thenBy { it.id })
                .map { json.encodeToString(ArchivedEvent.fromPersonalEvent(it)) }
                .toList()
            atomicWrite(
                file,
                (if (lines.isEmpty()) "" else lines.joinToString(separator = "\n", postfix = "\n"))
                    .toByteArray(StandardCharsets.UTF_8),
            )
        }
        return ArchiveSegmentResult(slice, file, countLines(file), sha256(file))
    }

    fun writeDay(
        date: LocalDate,
        events: Iterable<PersonalEvent>,
        nowMillis: Long = System.currentTimeMillis(),
        rewriteExisting: Boolean = false,
        allowRewriteValidManifest: Boolean = false,
    ): ArchiveDayResult {
        val eventList = events.toList()
        val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
        val today = now.toLocalDate()
        val canRewrite = rewriteExisting && (allowRewriteValidManifest || !isManifestComplete(date))
        val closedHalves = when {
            date.isBefore(today) -> ArchiveSegmentType.entries.toList()
            date.isAfter(today) -> emptyList()
            now.toLocalTime() >= LocalTime.NOON -> listOf(ArchiveSegmentType.FIRST_HALF)
            else -> emptyList()
        }
        val segments = ArchiveSegmentType.entries
            .filter { half ->
                half in closedHalves || segmentFile(date, half).isFile
            }
            .map { writeSegment(date, it, eventList, rewriteExisting = canRewrite) }
        val manifest = if (date.isBefore(today)) {
            writeManifestIfComplete(
                date = date,
                segments = segments,
                completedAt = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toOffsetDateTime().toString(),
                rewriteExisting = canRewrite,
            )
        } else {
            null
        }
        return ArchiveDayResult(date, segments, manifest)
    }

    fun writeManifestIfComplete(
        date: LocalDate,
        segments: List<ArchiveSegmentResult>,
        completedAt: String? = null,
        rewriteExisting: Boolean = false,
    ): File? {
        val expected = ArchiveSegmentType.entries.map { it.fileName }
        if (segments.map { it.slice.half.fileName }.toSet() != expected.toSet()) return null
        val directory = segments.first().file.parentFile ?: return null
        val manifest = File(directory, "manifest.json")
        val snapshots = segments.map { ArchiveSegmentSnapshot(it.slice.half.fileName, it.eventCount, it.sha256) }
        if (manifest.isFile) {
            val existing = runCatching {
                json.decodeFromString<ArchiveManifest>(manifest.readText(StandardCharsets.UTF_8))
            }.getOrNull()
            if (existing != null && ArchiveManifestValidator.validate(existing, date, snapshots) == null) {
                return manifest
            }
            if (!rewriteExisting) return null
        }
        val body = ArchiveManifest(
            schemaVersion = if (deviceInstanceId.isNullOrBlank()) 1 else 2,
            date = date.toString(),
            timeZone = zoneId.id,
            segments = segments.sortedBy { it.slice.startMillis }.map {
                ArchiveManifestSegment(it.slice.half.fileName, it.eventCount, it.sha256)
            },
            totalEventCount = segments.sumOf { it.eventCount },
            sourceDeviceIds = deviceInstanceId?.let(::listOf).orEmpty(),
            lastWriterDeviceId = deviceInstanceId,
            completedAt = completedAt,
        )
        atomicWrite(manifest, (json.encodeToString(body) + "\n").toByteArray(StandardCharsets.UTF_8))
        return manifest
    }

    fun manifestFile(date: LocalDate): File =
        File(archiveRoot, "${date.format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM"))}/$date/manifest.json")

    fun isManifestComplete(date: LocalDate): Boolean {
        val manifest = manifestFile(date)
        if (!manifest.isFile) return false
        val parsed = runCatching {
            json.decodeFromString<ArchiveManifest>(manifest.readText(StandardCharsets.UTF_8))
        }.getOrNull() ?: return false
        val snapshots = ArchiveSegmentType.entries.map { half ->
            val file = segmentFile(date, half)
            if (!file.isFile) return false
            ArchiveSegmentSnapshot(half.fileName, countLines(file), sha256(file))
        }
        return ArchiveManifestValidator.validate(parsed, date, snapshots) == null
    }

    fun segmentFile(date: LocalDate, half: ArchiveSegmentType): File =
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

    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeBytes(bytes)
        try {
            runCatching {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    companion object {
        val archiveJson: Json = Json {
            encodeDefaults = true
            explicitNulls = true
            prettyPrint = false
            ignoreUnknownKeys = true
        }
    }
}
