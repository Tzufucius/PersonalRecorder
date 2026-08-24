package io.github.tuzfucius.personalrecorder.sync

import io.github.tuzfucius.personalrecorder.archive.ArchivedEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveReconcilerTest {
    private val reconciler = ArchiveReconciler()

    @Test
    fun archivePairClassifiesAllLocalRemoteStates() {
        val local = descriptor("local", "a")
        val remoteSame = descriptor("remote", "a")
        val remoteDifferent = descriptor("remote", "b")

        assertEquals(ArchivePairState.LOCAL_ONLY, reconciler.pair(local, null).state)
        assertEquals(ArchivePairState.REMOTE_ONLY, reconciler.pair(null, remoteSame).state)
        assertEquals(ArchivePairState.BOTH_IDENTICAL, reconciler.pair(local, remoteSame).state)
        assertEquals(ArchivePairState.BOTH_DIFFERENT, reconciler.pair(local, remoteDifferent).state)
    }

    @Test
    fun mergeDeduplicatesIdenticalIdsAndPreservesDifferentIds() {
        val local = event("same", 1L, "one")
        val remote = event("same", 1L, "one")
        val other = event("other", 2L, "two")

        val result = reconciler.merge(
            reconciler.encodeJsonl(listOf(local)),
            reconciler.encodeJsonl(listOf(remote, other)),
        )

        assertEquals(listOf("same", "other"), result.events.map { it.id })
        assertTrue(result.conflicts.isEmpty())
    }

    @Test
    fun mergeRecordsSameIdDifferentContentWithoutDroppingEitherVariant() {
        val local = event("same", 1L, "local")
        val remote = event("same", 1L, "remote")

        val result = reconciler.merge(
            reconciler.encodeJsonl(listOf(local)),
            reconciler.encodeJsonl(listOf(remote)),
        )

        assertEquals(2, result.events.size)
        assertEquals(1, result.conflicts.size)
        assertEquals(setOf("local", "remote"), result.events.map { it.content }.toSet())
    }

    private fun descriptor(id: String, sha: String) = ArchiveDescriptor(
        segmentId = id,
        relativePath = "archive/2026/08/2026-08-23/00-12.jsonl",
        sha256 = sha,
        date = "2026-08-23",
        slot = "FIRST_HALF",
        size = 1,
    )

    private fun event(id: String, timestamp: Long, content: String) = ArchivedEvent(
        id = id,
        timestamp = timestamp,
        source = "notification",
        packageName = "com.example",
        title = null,
        content = content,
        bigText = null,
        textLines = emptyList(),
        notificationKey = id,
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
