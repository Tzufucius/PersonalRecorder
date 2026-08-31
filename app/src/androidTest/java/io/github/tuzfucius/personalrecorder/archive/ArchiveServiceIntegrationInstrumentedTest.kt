package io.github.tuzfucius.personalrecorder.archive

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tuzfucius.personalrecorder.data.AppDatabase
import io.github.tuzfucius.personalrecorder.data.ArchiveSegmentEntity
import io.github.tuzfucius.personalrecorder.data.ArchiveSyncStateEntity
import io.github.tuzfucius.personalrecorder.data.EventEntity
import io.github.tuzfucius.personalrecorder.sync.CloudBackendType
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArchiveServiceIntegrationInstrumentedTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private lateinit var database: AppDatabase
    private lateinit var archiveRoot: File
    private lateinit var writer: ArchiveWriter

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        archiveRoot = File(context.cacheDir, "archive-service-${System.nanoTime()}")
        archiveRoot.mkdirs()
        writer = ArchiveWriter(archiveRoot, zone)
    }

    @After
    fun tearDown() {
        database.close()
        archiveRoot.deleteRecursively()
    }

    @Test
    fun historicalMissingManifestIsFinalizedAndEntersReconcileScope() = runBlocking {
        val date = LocalDate.of(2026, 8, 10)
        val now = LocalDate.of(2026, 8, 31).atTime(1, 0).atZone(zone).toInstant().toEpochMilli()
        val event = sampleEvent(date.atTime(9, 0).atZone(zone).toInstant().toEpochMilli())
        database.eventDao().insertEvent(event)
        val slices = ArchivePartition.slicesForDate(date, zone)
        slices.forEach { slice ->
            database.eventDao().upsertArchiveSegment(
                ArchiveSegmentEntity(
                    segmentId = slice.segmentId,
                    date = date.toString(),
                    slot = slice.half.name,
                    relativePath = slice.relativePath,
                    startMillis = slice.startMillis,
                    endMillis = slice.endMillis,
                    eventCount = 0,
                    sha256 = "placeholder",
                    createdAt = now,
                )
            )
            database.eventDao().upsertArchiveSyncState(
                ArchiveSyncStateEntity(
                    segmentId = slice.segmentId,
                    backend = CloudBackendType.GITHUB.name,
                    status = ArchiveSyncStateEntity.Status.SYNCED,
                    updatedAt = now,
                )
            )
        }

        val service = ArchiveService(database, writer)
        service.finalizeClosedArchives(now)

        assertTrue(writer.isManifestComplete(date))
        assertEquals(
            ArchiveSyncStateEntity.Status.PENDING_UPLOAD,
            database.eventDao().getArchiveSyncState(
                "$date-MANIFEST",
                CloudBackendType.GITHUB.name,
            )?.status,
        )
        assertTrue(
            database.eventDao().getReconcileScopeDates(CloudBackendType.GITHUB.name)
                .contains(date.toString())
        )
        assertFalse(service.hasClosedArchiveGaps(now))
    }

    @Test
    fun historicalPendingCompleteArchiveIsNotFinalizedAgain() = runBlocking {
        val date = LocalDate.of(2026, 8, 10)
        val now = LocalDate.of(2026, 8, 31).atTime(1, 0).atZone(zone).toInstant().toEpochMilli()
        database.eventDao().insertEvent(sampleEvent(date.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()))
        val first = writer.writeDay(
            date = date,
            events = listOf(database.eventDao().getEventById("event-${date}")!!.toPersonalEvent()),
            nowMillis = now,
        )
        first.segments.forEach { database.eventDao().upsertArchiveSegment(it.toEntity(now)) }
        database.eventDao().upsertArchiveSyncState(
            ArchiveSyncStateEntity(
                segmentId = "$date-MANIFEST",
                backend = CloudBackendType.GITHUB.name,
                status = ArchiveSyncStateEntity.Status.PENDING_UPLOAD,
                updatedAt = now,
            )
        )
        // Seed the intervening days so this test isolates a historical pending date.
        var day = date.plusDays(1)
        while (!day.isAfter(LocalDate.of(2026, 8, 30))) {
            val seeded = writer.writeDay(day, emptyList(), nowMillis = now)
            seeded.segments.forEach { database.eventDao().upsertArchiveSegment(it.toEntity(now)) }
            if (day == LocalDate.of(2026, 8, 30)) {
                database.eventDao().upsertArchiveSyncState(
                    ArchiveSyncStateEntity(
                        segmentId = "$day-MANIFEST",
                        backend = CloudBackendType.GITHUB.name,
                        status = ArchiveSyncStateEntity.Status.SYNCED,
                        updatedAt = now,
                    )
                )
            }
            day = day.plusDays(1)
        }
        val segmentBytes = first.segments.associate { it.slice.half to it.file.readBytes() }
        val manifestBytes = requireNotNull(first.manifest).readBytes()

        val service = ArchiveService(database, writer)
        assertTrue(service.finalizeClosedArchives(now).isEmpty())
        assertTrue(service.finalizeClosedArchives(now).isEmpty())

        first.segments.forEach { segment ->
            assertEquals(segmentBytes.getValue(segment.slice.half).toList(), segment.file.readBytes().toList())
        }
        assertEquals(manifestBytes.toList(), requireNotNull(first.manifest).readBytes().toList())
        assertFalse(service.hasClosedArchiveGaps(now))
    }

    private fun sampleEvent(timestamp: Long) = EventEntity(
        id = "event-${LocalDate.ofInstant(java.time.Instant.ofEpochMilli(timestamp), zone)}",
        timestamp = timestamp,
        source = "notification",
        packageName = "source.app",
        title = "title",
        content = "content",
        bigText = null,
        textLines = emptyList(),
        notificationKey = "key",
        notificationId = 1,
        category = null,
        channelId = null,
        groupKey = null,
        isOngoing = false,
        isGroupSummary = false,
        isClearable = true,
        createdAt = timestamp,
    )
}
