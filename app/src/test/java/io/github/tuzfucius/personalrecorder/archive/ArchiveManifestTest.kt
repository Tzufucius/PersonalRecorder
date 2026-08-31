package io.github.tuzfucius.personalrecorder.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ArchiveManifestTest {
    @Test
    fun sourceDeviceIdsAreMergedWithoutDroppingHistory() {
        assertEquals(
            listOf("A", "B", "C", "D"),
            mergeSourceDeviceIds(listOf("B", "C"), listOf("A", "B"), "D"),
        )
    }

    @Test
    fun validatesCountsAndHashesForBothSegments() {
        val date = LocalDate.of(2026, 8, 22)
        val snapshots = listOf(
            ArchiveSegmentSnapshot("00-12.jsonl", 2, "a".repeat(64)),
            ArchiveSegmentSnapshot("12-24.jsonl", 1, "b".repeat(64)),
        )
        val manifest = ArchiveManifest(
            date = date.toString(),
            timeZone = "Asia/Shanghai",
            segments = listOf(
                ArchiveManifestSegment("00-12.jsonl", 2, "a".repeat(64)),
                ArchiveManifestSegment("12-24.jsonl", 1, "b".repeat(64)),
            ),
            totalEventCount = 3,
        )

        assertNull(ArchiveManifestValidator.validate(manifest, date, snapshots))
        assertNotNull(
            ArchiveManifestValidator.validate(
                manifest.copy(totalEventCount = 4),
                date,
                snapshots,
            )
        )
        assertNotNull(
            ArchiveManifestValidator.validate(
                manifest.copy(segments = manifest.segments.map { it.copy(sha256 = "") }),
                date,
                snapshots,
            )
        )
        assertNotNull(
            ArchiveManifestValidator.validate(
                manifest,
                date,
                snapshots.filterNot { it.fileName == "12-24.jsonl" },
            )
        )
    }
}
