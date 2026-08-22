package io.github.tuzfucius.personalrecorder.archive

import io.github.tuzfucius.personalrecorder.data.PersonalEvent
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveWriterTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun writesEmptyClosedSegmentsAndManifestWithSha256() {
        val root = Files.createTempDirectory("archive-test").toFile()
        val date = LocalDate.of(2020, 1, 2)
        val writer = ArchiveWriter(root, zone)
        val result = writer.writeDay(date, emptyList<PersonalEvent>(), nowMillis = date.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli())

        assertNotNull(result.manifest)
        assertEquals(2, result.segments.size)
        result.segments.forEach {
            assertEquals(0, it.eventCount)
            assertEquals(64, it.sha256.length)
            assertTrue(it.file.exists())
            assertEquals("", it.file.readText())
        }
        assertTrue(requireNotNull(result.manifest).exists())
    }

    @Test
    fun doesNotCreateManifestBeforeBothHalvesClose() {
        val root = Files.createTempDirectory("archive-test").toFile()
        val date = LocalDate.of(2026, 8, 22)
        val now = date.atTime(13, 0).atZone(zone).toInstant().toEpochMilli()
        val result = ArchiveWriter(root, zone).writeDay(date, emptyList<PersonalEvent>(), now)

        assertEquals(listOf(ArchiveHalf.AM), result.segments.map { it.slice.half })
        assertEquals(null, result.manifest)
    }
}
