package io.github.tuzfucius.personalrecorder.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCoordinatorTest {
    @Test
    fun fakeBackendRetriesTransientFailureThenSucceeds() = runBlocking {
        val backend = FakeBackend(
            CloudBackendType.GITHUB,
            mutableListOf(
                BackendSyncResult.Failure(SyncError.Network("offline")),
                BackendSyncResult.Success("commit-1")
            )
        )
        val coordinator = SyncCoordinator(listOf(backend), noWaitRetryPolicy())

        val result = coordinator.syncBatch(listOf(archive()))

        assertTrue(result.isSuccessful)
        assertEquals(2, result.results.single().attempts)
        assertEquals(2, backend.callCount)
    }

    @Test
    fun fakeBackendDoesNotRetryConflictAndReportsFailedStatus() = runBlocking {
        val backend = FakeBackend(
            CloudBackendType.GOOGLE_DRIVE,
            mutableListOf(BackendSyncResult.Failure(SyncError.RemoteConflict("sha mismatch")))
        )
        val coordinator = SyncCoordinator(listOf(backend), noWaitRetryPolicy())

        val result = coordinator.syncBatch(listOf(archive()), setOf(CloudBackendType.GOOGLE_DRIVE))

        assertEquals(ArchiveSyncStatus.FAILED, result.results.single().status)
        assertEquals(1, backend.callCount)
        assertFalse(result.needsRetry)
    }

    @Test
    fun exhaustedNetworkFailureDoesNotKeepWorkerInInfiniteRetryLoop() = runBlocking {
        val backend = FakeBackend(
            CloudBackendType.GITHUB,
            mutableListOf(
                BackendSyncResult.Failure(SyncError.Network("offline")),
                BackendSyncResult.Failure(SyncError.Network("offline")),
                BackendSyncResult.Failure(SyncError.Network("offline")),
            )
        )
        val result = SyncCoordinator(listOf(backend), noWaitRetryPolicy())
            .syncBatch(listOf(archive()))

        assertTrue(result.needsRetry)
        assertTrue(result.results.single().retryExhausted)
        assertEquals(3, backend.callCount)
    }

    @Test
    fun oneBackendFailureDoesNotPreventAnotherBackend() = runBlocking {
        val github = FakeBackend(
            CloudBackendType.GITHUB,
            mutableListOf(BackendSyncResult.Failure(SyncError.Authorization("forbidden")))
        )
        val drive = FakeBackend(CloudBackendType.GOOGLE_DRIVE, mutableListOf(BackendSyncResult.Success("file-1")))
        val coordinator = SyncCoordinator(listOf(github, drive), noWaitRetryPolicy())

        val result = coordinator.syncBatch(listOf(archive()))

        assertEquals(2, result.results.size)
        assertTrue(result.results.any { it.backend == CloudBackendType.GOOGLE_DRIVE && it.isSuccessful })
        assertTrue(result.results.any { it.backend == CloudBackendType.GITHUB && !it.isSuccessful })
    }

    private fun noWaitRetryPolicy() = SyncRetryPolicy(maxAttempts = 3) { _, _ -> }

    private fun archive() = CloudArchive(
        relativePath = "archive/2026/08/2026-08-22/00-12.jsonl",
        sha256 = "a".repeat(64),
        content = "{}\n".toByteArray()
    )

    private class FakeBackend(
        override val type: CloudBackendType,
        private val responses: MutableList<BackendSyncResult>
    ) : CloudSyncBackend {
        var callCount = 0

        override suspend fun sync(archive: CloudArchive): BackendSyncResult {
            callCount++
            return responses.removeAt(0)
        }
    }
}
