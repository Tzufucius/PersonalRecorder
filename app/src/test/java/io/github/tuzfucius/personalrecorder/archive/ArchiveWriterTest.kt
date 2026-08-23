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
        assertTrue(requireNotNull(result.manifest).readText().contains("\"schemaVersion\":1"))
    }

    @Test
    fun doesNotCreateManifestBeforeBothHalvesClose() {
        val root = Files.createTempDirectory("archive-test").toFile()
        val date = LocalDate.of(2026, 8, 22)
        val now = date.atTime(13, 0).atZone(zone).toInstant().toEpochMilli()
        val result = ArchiveWriter(root, zone).writeDay(date, emptyList<PersonalEvent>(), now)

        assertEquals(listOf(ArchiveSegmentType.FIRST_HALF), result.segments.map { it.slice.half })
        assertEquals(null, result.manifest)
    }

    @Test
    fun writesEventsToLocalHalfAndEscapesJsonlDeterministically() {
        val root = Files.createTempDirectory("archive-test").toFile()
        val date = LocalDate.of(2026, 8, 22)
        fun event(id: String, hour: Int, minute: Int, content: String) = PersonalEvent(
            id = id,
            timestamp = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli(),
            source = "notification",
            packageName = "com.example",
            title = "标题",
            content = content,
            bigText = null,
            textLines = emptyList(),
            notificationKey = "key-$id",
            notificationId = 1,
            category = null,
            channelId = null,
            groupKey = null,
            isOngoing = false,
            isGroupSummary = false,
            isClearable = true,
            createdAt = 1L,
        )
        val events = listOf(
            event("late", 23, 59, "中文\n换行"),
            event("early", 0, 1, "first"),
            event("noon", 12, 0, "second"),
            event("morning", 11, 59, "third"),
        )
        val result = ArchiveWriter(root, zone).writeDay(
            date,
            events,
            nowMillis = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        )

        val morning = result.segments.first { it.slice.half == ArchiveSegmentType.FIRST_HALF }.file.readLines()
        val afternoon = result.segments.first { it.slice.half == ArchiveSegmentType.SECOND_HALF }.file.readLines()
        assertEquals(2, morning.size)
        assertEquals(2, afternoon.size)
        assertTrue(morning[0].contains("\"id\":\"early\""))
        assertTrue(morning[1].contains("\"id\":\"morning\""))
        assertTrue(afternoon[0].contains("\"id\":\"noon\""))
        assertTrue(afternoon[1].contains("\\n"))
        assertTrue(afternoon[1].contains("中文"))
    }
}
