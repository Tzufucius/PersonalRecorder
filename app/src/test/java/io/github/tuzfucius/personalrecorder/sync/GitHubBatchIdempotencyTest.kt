package io.github.tuzfucius.personalrecorder.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class GitHubBatchIdempotencyTest {
    @Test
    fun identicalRemoteContentDoesNotCreateCommit() = runBlocking {
        val content = "{}\n".toByteArray()
        val calls = mutableListOf<String>()
        val backend = backend(
            api = FakeGitApi(
                tree = mapOf(
                    path() to GitHubTreeEntry(path(), gitBlobSha(content)),
                ),
                calls = calls,
            )
        )

        val result = backend.syncBatch(listOf(archive(content))).getValue(path())

        assertTrue(result is BackendSyncResult.Success && result.wasAlreadyPresent)
        assertEquals(listOf("head", "tree"), calls)
    }

    @Test
    fun differentRemoteContentIsConflictWithoutOverwrite() = runBlocking {
        val calls = mutableListOf<String>()
        val backend = backend(
            api = FakeGitApi(
                tree = mapOf(path() to GitHubTreeEntry(path(), "different")),
                calls = calls,
            )
        )

        val result = backend.syncBatch(listOf(archive("local".toByteArray()))).getValue(path())

        assertTrue(result is BackendSyncResult.Failure && result.error is SyncError.RemoteConflict)
        assertEquals(listOf("head", "tree"), calls)
    }

    private fun backend(api: FakeGitApi) = GitHubCloudSyncBackend(
        repository = GitHubRepository("alice", GitHubRepository.DEFAULT_NAME),
        guard = GitHubPrivateRepositoryGuard(object : GitHubRepositoryInspector {
            override suspend fun authenticatedLogin() = "alice"
            override suspend fun repositoryAccess(repository: GitHubRepository) =
                GitHubRepositoryAccess("alice", isPrivate = true, canPush = true)
        }),
        gitDataApi = api,
    )

    private fun archive(content: ByteArray) = CloudArchive(path(), "a".repeat(64), content)
    private fun path() = "archive/2026/08/2026-08-22/00-12.jsonl"
    private fun gitBlobSha(content: ByteArray): String {
        val header = "blob ${content.size}\u0000".toByteArray()
        return MessageDigest.getInstance("SHA-1").digest(header + content).joinToString("") { "%02x".format(it) }
    }

    private class FakeGitApi(
        private val tree: Map<String, GitHubTreeEntry>,
        private val calls: MutableList<String>,
    ) : GitHubGitDataApi {
        override suspend fun head(repository: GitHubRepository): GitHubHead {
            calls += "head"
            return GitHubHead("commit", "tree")
        }

        override suspend fun tree(repository: GitHubRepository, treeSha: String): Map<String, GitHubTreeEntry> {
            calls += "tree"
            return tree
        }

        override suspend fun createBlob(repository: GitHubRepository, content: ByteArray): String = error("not expected")
        override suspend fun createTree(repository: GitHubRepository, baseTreeSha: String, blobs: List<GitHubBlob>): String = error("not expected")
        override suspend fun createCommit(repository: GitHubRepository, parentCommitSha: String, treeSha: String, message: String): String = error("not expected")
        override suspend fun updateHead(repository: GitHubRepository, expectedCommitSha: String, newCommitSha: String, ref: String): GitHubReferenceUpdate = error("not expected")
    }
}
