package io.github.tuzfucius.personalrecorder.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubBatchIdempotencyTest {
    @Test
    fun identicalRemoteContentDoesNotCreateCommit() = runBlocking {
        val content = "{}\n".toByteArray()
        val api = FakeContentsApi(remote = GitHubContent(path(), "remote-sha", content))
        val result = backend(api).sync(archive(content))

        assertTrue(result is BackendSyncResult.Success && result.wasAlreadyPresent)
        assertEquals(emptyList<String>(), api.putPaths)
    }

    @Test
    fun differentRemoteContentUpdatesWithCurrentSha() = runBlocking {
        val api = FakeContentsApi(remote = GitHubContent(path(), "remote-sha", "old\n".toByteArray()))
        val result = backend(api).sync(archive("new\n".toByteArray()))

        assertTrue(result is BackendSyncResult.Success)
        assertEquals(listOf("remote-sha"), api.putShas)
    }

    @Test
    fun missingRemoteContentCreatesWithoutSha() = runBlocking {
        val api = FakeContentsApi(remote = null)
        val result = backend(api).sync(archive("new\n".toByteArray()))

        assertTrue(result is BackendSyncResult.Success)
        assertEquals(listOf(null), api.putShas)
    }

    @Test
    fun batchPlacesManifestAfterBothSegments() = runBlocking {
        val api = FakeContentsApi(remote = null)
        val archives = listOf(
            CloudArchive("archive/2026/08/2026-08-22/manifest.json", "a".repeat(64), "m".toByteArray()),
            CloudArchive("archive/2026/08/2026-08-22/12-24.jsonl", "b".repeat(64), "p".toByteArray()),
            CloudArchive("archive/2026/08/2026-08-22/00-12.jsonl", "c".repeat(64), "a".toByteArray()),
        )

        backend(api).syncBatch(archives)

        assertEquals(
            listOf(
                "archive/2026/08/2026-08-22/00-12.jsonl",
                "archive/2026/08/2026-08-22/12-24.jsonl",
                "archive/2026/08/2026-08-22/manifest.json",
            ),
            api.putPaths,
        )
    }

    @Test
    fun failedSegmentPreventsManifestPut() = runBlocking {
        val manifestPath = "archive/2026/08/2026-08-22/manifest.json"
        val secondHalfPath = "archive/2026/08/2026-08-22/12-24.jsonl"
        val api = FakeContentsApi(remote = null, failPath = secondHalfPath)
        val archives = listOf(
            CloudArchive("archive/2026/08/2026-08-22/00-12.jsonl", "a".repeat(64), "a".toByteArray()),
            CloudArchive(secondHalfPath, "b".repeat(64), "b".toByteArray()),
            CloudArchive(manifestPath, "c".repeat(64), "m".toByteArray()),
        )

        val results = backend(api).syncBatch(archives)

        assertEquals(
            BackendSyncResult.Failure(SyncError.Network("segment upload failed; manifest deferred")),
            results[manifestPath],
        )
        assertTrue(manifestPath !in api.putPaths)
    }

    private fun backend(api: FakeContentsApi) = GitHubCloudSyncBackend(
        repository = GitHubRepository("alice", GitHubRepository.DEFAULT_NAME),
        api = api,
    )

    private fun archive(content: ByteArray) = CloudArchive(path(), "a".repeat(64), content)
    private fun path() = "archive/2026/08/2026-08-22/00-12.jsonl"

    private class FakeContentsApi(
        private val remote: GitHubContent?,
        private val failPath: String? = null,
    ) : GitHubArchiveApi {
        val putPaths = mutableListOf<String>()
        val putShas = mutableListOf<String?>()

        override suspend fun authenticatedLogin() = "alice"
        override suspend fun findRepository(repository: GitHubRepository) =
            GitHubRepositoryDetails("alice", isPrivate = true, canPush = true)
        override suspend fun createPrivateRepository(name: String) =
            GitHubRepositoryDetails("alice", isPrivate = true, canPush = true)
        override suspend fun getContent(repository: GitHubRepository, path: String) = remote
        override suspend fun putContent(
            repository: GitHubRepository,
            path: String,
            content: ByteArray,
            message: String,
            sha: String?,
        ): GitHubContent {
            if (path == failPath) throw SyncHttpException(500, "test failure")
            putPaths += path
            putShas += sha
            return GitHubContent(path, "new-sha")
        }
    }
}
