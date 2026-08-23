package io.github.tuzfucius.personalrecorder.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCoordinatorTest {
    @Test
    fun transientFailureIsReturnedForWorkManagerRetry() = runBlocking {
        val backend = FakeBackend(
            CloudBackendType.GITHUB,
            mutableListOf(BackendSyncResult.Failure(SyncError.Network("offline"))),
        )
        val result = SyncCoordinator(listOf(backend)).syncBatch(listOf(archive()))

        assertFalse(result.isSuccessful)
        assertTrue(result.needsRetry)
        assertEquals(1, result.results.single().attempts)
        assertEquals(1, backend.callCount)
    }

    @Test
    fun conflictIsNotRetryable() = runBlocking {
        val backend = FakeBackend(
            CloudBackendType.GOOGLE_DRIVE,
            mutableListOf(BackendSyncResult.Failure(SyncError.RemoteConflict("sha mismatch"))),
        )
        val result = SyncCoordinator(listOf(backend)).syncBatch(
            listOf(archive()),
            setOf(CloudBackendType.GOOGLE_DRIVE),
        )

        assertEquals(ArchiveSyncStatus.FAILED, result.results.single().status)
        assertFalse(result.needsRetry)
        assertEquals(1, backend.callCount)
    }

    @Test
    fun oneBackendFailureDoesNotPreventAnotherBackend() = runBlocking {
        val github = FakeBackend(
            CloudBackendType.GITHUB,
            mutableListOf(BackendSyncResult.Failure(SyncError.Authorization("forbidden"))),
        )
        val drive = FakeBackend(
            CloudBackendType.GOOGLE_DRIVE,
            mutableListOf(BackendSyncResult.Success("file-1")),
        )
        val result = SyncCoordinator(listOf(github, drive)).syncBatch(listOf(archive()))

        assertEquals(2, result.results.size)
        assertTrue(result.results.any { it.backend == CloudBackendType.GOOGLE_DRIVE && it.isSuccessful })
        assertTrue(result.results.any { it.backend == CloudBackendType.GITHUB && !it.isSuccessful })
    }

    private fun archive() = CloudArchive(
        relativePath = "archive/2026/08/2026-08-22/00-12.jsonl",
        sha256 = "a".repeat(64),
        content = "{}\n".toByteArray(),
    )

    private class FakeBackend(
        override val type: CloudBackendType,
        private val responses: MutableList<BackendSyncResult>,
    ) : CloudSyncBackend {
        var callCount = 0

        override suspend fun sync(archive: CloudArchive): BackendSyncResult {
            callCount++
            return responses.removeAt(0)
        }
    }
}
