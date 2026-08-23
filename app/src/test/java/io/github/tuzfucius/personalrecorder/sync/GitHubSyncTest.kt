package io.github.tuzfucius.personalrecorder.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubSyncTest {
    @Test
    fun publicRepositoryIsRejectedBeforeUpload() = runBlocking {
        val guard = GitHubPrivateRepositoryGuard(object : GitHubRepositoryInspector {
            override suspend fun authenticatedLogin() = "alice"
            override suspend fun repositoryAccess(repository: GitHubRepository) =
                GitHubRepositoryAccess("alice", isPrivate = false, canPush = true)
        })

        val error = guard.validate(GitHubRepository("alice", "PersonalRecorder-Archive"))

        assertTrue(error is SyncError.Authorization)
        assertTrue(error?.message?.contains("私有") == true)
    }

    @Test
    fun gitDataBackendBuildsOneCommitForAnArchiveBatch() = runBlocking {
        val calls = mutableListOf<String>()
        val backend = GitHubCloudSyncBackend(
            repository = GitHubRepository("alice", "PersonalRecorder-Archive"),
            guard = GitHubPrivateRepositoryGuard(object : GitHubRepositoryInspector {
                override suspend fun authenticatedLogin() = "alice"
                override suspend fun repositoryAccess(repository: GitHubRepository) =
                    GitHubRepositoryAccess("alice", isPrivate = true, canPush = true)
            }),
            gitDataApi = object : GitHubGitDataApi {
                override suspend fun head(repository: GitHubRepository): GitHubHead {
                    calls += "head"
                    return GitHubHead("commit-0", "tree-0")
                }

                override suspend fun createBlob(repository: GitHubRepository, content: ByteArray): String {
                    calls += "blob"
                    return "blob-${calls.count { it == "blob" }}"
                }

                override suspend fun createTree(repository: GitHubRepository, baseTreeSha: String, blobs: List<GitHubBlob>): String {
                    calls += "tree:${blobs.size}"
                    return "tree-1"
                }

                override suspend fun createCommit(repository: GitHubRepository, parentCommitSha: String, treeSha: String, message: String): String {
                    calls += "commit"
                    return "commit-1"
                }

                override suspend fun updateHead(repository: GitHubRepository, expectedCommitSha: String, newCommitSha: String, ref: String): GitHubReferenceUpdate {
                    calls += "update"
                    return GitHubReferenceUpdate.Updated
                }
            }
        )

        val archives = listOf(archive("00-12.jsonl"), archive("12-24.jsonl"))
        val result = backend.syncBatch(archives)

        assertTrue(result.values.all { syncResult: BackendSyncResult -> syncResult is BackendSyncResult.Success })
        assertEquals(listOf("head", "blob", "blob", "tree:2", "commit", "update"), calls)
    }

    private fun archive(name: String) = CloudArchive(
        relativePath = "archive/2026/08/2026-08-22/$name",
        sha256 = "a".repeat(64),
        content = "{}\n".toByteArray()
    )
}
