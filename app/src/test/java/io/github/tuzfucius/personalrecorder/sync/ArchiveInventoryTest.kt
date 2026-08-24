package io.github.tuzfucius.personalrecorder.sync

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ArchiveInventoryTest {
    @Test
    fun scopedLocalScanDoesNotExposeOldSyncedArchiveOutsideIncrementalWindow() = runBlocking {
        val root = Files.createTempDirectory("archive-scope").toFile()
        val oldPath = "archive/2026/07/2026-07-01/00-12.jsonl"
        val recentPath = "archive/2026/08/2026-08-23/00-12.jsonl"
        ArchiveFileStore.atomicWrite(root, oldPath, "old".toByteArray())
        ArchiveFileStore.atomicWrite(root, recentPath, "recent".toByteArray())

        val inventory = LocalArchiveInventoryScanner(root).scan(
            ReconcileScope.dates(setOf(LocalDate.of(2026, 8, 23)))
        )

        assertEquals(listOf(recentPath), inventory.descriptors.map { it.relativePath })
    }

    @Test
    fun descriptorRecognizesSegmentsAndManifestOnlyInsideArchiveDateDirectory() {
        val segment = ArchivePathDescriptor.fromPath(
            "archive/2026/08/2026-08-23/00-12.jsonl",
            "a".repeat(64),
            4,
        )
        val manifest = ArchivePathDescriptor.fromPath(
            "archive/2026/08/2026-08-23/manifest.json",
            "b".repeat(64),
            8,
        )

        assertEquals("2026-08-23-FIRST_HALF", segment?.segmentId)
        assertEquals("MANIFEST", manifest?.slot)
        assertFalse(ArchivePathDescriptor.fromPath("notes/manifest.json", "c", 1) != null)
    }

    @Test
    fun shaMismatchDoesNotReplaceExistingArchive() {
        val root = Files.createTempDirectory("archive-atomic").toFile()
        val path = "archive/2026/08/2026-08-23/00-12.jsonl"
        ArchiveFileStore.atomicWrite(root, path, "old".toByteArray())

        assertThrows(IllegalArgumentException::class.java) {
            ArchiveFileStore.atomicWrite(root, path, "new".toByteArray(), "0".repeat(64))
        }

        assertEquals("old", ArchiveFileStore.file(root, path).readText())
        assertTrue(!ArchiveFileStore.file(root, "$path.download").exists())
    }
}
