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

    private fun backend(api: FakeContentsApi) = GitHubCloudSyncBackend(
        repository = GitHubRepository("alice", GitHubRepository.DEFAULT_NAME),
        api = api,
    )

    private fun archive(content: ByteArray) = CloudArchive(path(), "a".repeat(64), content)
    private fun path() = "archive/2026/08/2026-08-22/00-12.jsonl"

    private class FakeContentsApi(private val remote: GitHubContent?) : GitHubArchiveApi {
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
            putPaths += path
            putShas += sha
            return GitHubContent(path, "new-sha")
        }
    }
}
