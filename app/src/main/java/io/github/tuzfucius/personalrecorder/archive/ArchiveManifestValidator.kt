package io.github.tuzfucius.personalrecorder.archive

import java.time.LocalDate
import java.time.ZoneId

data class ArchiveSegmentSnapshot(
    val fileName: String,
    val eventCount: Int,
    val sha256: String,
)

/** Shared manifest contract used by local finalization and remote reconciliation. */
object ArchiveManifestValidator {
    private val expectedFiles = ArchiveSegmentType.entries.map { it.fileName }.toSet()

    fun validate(
        manifest: ArchiveManifest,
        expectedDate: LocalDate,
        snapshots: Collection<ArchiveSegmentSnapshot>,
    ): String? {
        validateStructure(manifest, expectedDate)?.let { return it }
        if (snapshots.size != expectedFiles.size || snapshots.map { it.fileName }.toSet() != expectedFiles) {
            return "archive files are incomplete"
        }
        val actualByName = snapshots.associateBy { it.fileName }
        manifest.segments.forEach { segment ->
            val actual = actualByName[segment.fileName] ?: return "archive file is missing"
            if (actual.eventCount < 0) return "eventCount is invalid for ${segment.fileName}"
            if (segment.eventCount != actual.eventCount) return "eventCount mismatch for ${segment.fileName}"
            if (segment.sha256.isBlank()) return "sha256 is missing for ${segment.fileName}"
            if (!segment.sha256.equals(actual.sha256, ignoreCase = true)) {
                return "sha256 mismatch for ${segment.fileName}"
            }
        }
        if (manifest.totalEventCount != manifest.segments.sumOf { it.eventCount }) {
            return "totalEventCount mismatch"
        }
        return null
    }

    fun validateStructure(manifest: ArchiveManifest, expectedDate: LocalDate): String? {
        if (manifest.schemaVersion !in setOf(1, 2)) return "unsupported schemaVersion"
        if (manifest.date != expectedDate.toString()) return "manifest date does not match path"
        runCatching { ZoneId.of(manifest.timeZone) }
            .getOrElse { return "manifest timeZone is invalid" }
        if (manifest.segments.size != expectedFiles.size) return "manifest must contain two segments"
        if (manifest.segments.map { it.fileName }.toSet() != expectedFiles) {
            return "manifest segment names are invalid"
        }
        return null
    }
}
