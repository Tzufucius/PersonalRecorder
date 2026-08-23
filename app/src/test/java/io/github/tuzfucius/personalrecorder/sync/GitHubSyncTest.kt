package io.github.tuzfucius.personalrecorder.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        val error = guard.validate(GitHubRepository("alice", GitHubRepository.DEFAULT_NAME))

        assertTrue(error is SyncError.Authorization)
        assertTrue(error?.message?.contains("私有") == true)
    }

    @Test
    fun repositoryWithoutPushPermissionIsRejected() = runBlocking {
        val guard = GitHubPrivateRepositoryGuard(object : GitHubRepositoryInspector {
            override suspend fun authenticatedLogin() = "alice"
            override suspend fun repositoryAccess(repository: GitHubRepository) =
                GitHubRepositoryAccess("alice", isPrivate = true, canPush = false)
        })

        val error = guard.validate(GitHubRepository("alice", GitHubRepository.DEFAULT_NAME))

        assertTrue(error is SyncError.Authorization)
        assertTrue(error?.message?.contains("写入") == true)
    }

    @Test
    fun httpErrorsMapToRetryableOrPermanentSyncErrors() {
        assertFalse(SyncHttpException(401, "ignored").toSyncError().retryable)
        assertFalse(SyncHttpException(403, "ignored").toSyncError().retryable)
        assertTrue(SyncHttpException(500, "ignored").toSyncError().retryable)
        assertTrue(SyncHttpException(503, "ignored").toSyncError() is SyncError.ServiceUnavailable)
    }

    @Test
    fun emptyTokenIsRejectedBeforeClientCreation() = runBlocking {
        var created = false
        val result = GitHubConnectionCoordinator(
            clientFactory = {
                created = true
                error("client should not be created")
            },
            secrets = FakeSecretStore(),
            settings = FakeConnectionSettings(),
        ).connect("  ", GitHubRepository.DEFAULT_NAME)

        assertTrue(result.isFailure)
        assertFalse(created)
        assertEquals("Personal Access Token 不能为空", result.exceptionOrNull()?.message)
    }

    @Test
    fun validConnectionPersistsTokenOnlyAfterRepositoryValidation() = runBlocking {
        val secrets = RecordingSecretStore()
        val settings = RecordingConnectionSettings()
        val api = FakeConnectionApi(GitHubRepositoryDetails("alice", isPrivate = true, canPush = true))

        val result = GitHubConnectionCoordinator(
            clientFactory = { token ->
                assertEquals("test-token", token)
                api
            },
            secrets = secrets,
            settings = settings,
        ).connect("test-token", "archive")

        assertTrue(result.isSuccess)
        assertEquals("test-token", secrets.value)
        assertEquals("alice", settings.username)
        assertEquals("archive", settings.repository)
        assertTrue(settings.connected)
    }

    @Test
    fun publicRepositoryNeverPersistsToken() = runBlocking {
        val secrets = RecordingSecretStore()
        val settings = RecordingConnectionSettings()
        val result = GitHubConnectionCoordinator(
            clientFactory = { FakeConnectionApi(GitHubRepositoryDetails("alice", isPrivate = false, canPush = true)) },
            secrets = secrets,
            settings = settings,
        ).connect("test-token", "archive")

        assertTrue(result.isFailure)
        assertEquals(null, secrets.value)
        assertFalse(settings.connected)
    }

    @Test
    fun missingRepositoryTriggersPrivateRepositoryCreation() = runBlocking {
        val api = FakeConnectionApi(details = null, created = GitHubRepositoryDetails("alice", true, true))
        val result = GitHubConnectionCoordinator(
            clientFactory = { api },
            secrets = RecordingSecretStore(),
            settings = RecordingConnectionSettings(),
        ).connect("test-token", "archive")

        assertTrue(result.isSuccess)
        assertEquals("archive", api.createdName)
    }

    @Test
    fun cancellationDuringValidationIsPropagatedAndDoesNotPersist() = runBlocking {
        val secrets = RecordingSecretStore()
        val cancellation = CancellationException("cancelled")
        var propagated: CancellationException? = null

        try {
            GitHubConnectionCoordinator(
                clientFactory = { object : FakeConnectionApi(null) {
                    override suspend fun authenticatedLogin(): String = throw cancellation
                } },
                secrets = secrets,
                settings = RecordingConnectionSettings(),
            ).connect("test-token", "archive")
        } catch (error: CancellationException) {
            propagated = error
        }

        assertTrue(propagated === cancellation)
        assertEquals(null, secrets.value)
    }

    @Test
    fun cancellationDuringPersistenceCleansUpTokenAndIsPropagated() = runBlocking {
        val secrets = RecordingSecretStore()
        val cancellation = CancellationException("cancelled")
        var propagated: CancellationException? = null

        try {
            GitHubConnectionCoordinator(
                clientFactory = { FakeConnectionApi(GitHubRepositoryDetails("alice", true, true)) },
                secrets = secrets,
                settings = ThrowingConnectionSettings(cancellation),
            ).connect("test-token", "archive")
        } catch (error: CancellationException) {
            propagated = error
        }

        assertTrue(propagated === cancellation)
        assertEquals(null, secrets.value)
    }

    @Test
    fun persistenceFailureDoesNotLeaveTokenBehind() = runBlocking {
        val secrets = RecordingSecretStore()
        val result = GitHubConnectionCoordinator(
            clientFactory = { FakeConnectionApi(GitHubRepositoryDetails("alice", true, true)) },
            secrets = secrets,
            settings = ThrowingConnectionSettings(IllegalStateException("store failed")),
        ).connect("test-token", "archive")

        assertTrue(result.isFailure)
        assertEquals(null, secrets.value)
    }

    private class FakeSecretStore : SecretStore {
        override fun put(name: String, value: String) = Unit
        override fun get(name: String): String? = null
        override fun remove(name: String) = Unit
    }

    private class FakeConnectionSettings : GitHubConnectionSettings {
        override suspend fun setGithubUsername(username: String?) = Unit
        override suspend fun setGithubRepository(repository: String) = Unit
        override suspend fun setGithubConnected(connected: Boolean) = Unit
    }

    private class RecordingSecretStore : SecretStore {
        var value: String? = null
        override fun put(name: String, value: String) { this.value = value }
        override fun get(name: String): String? = value
        override fun remove(name: String) { value = null }
    }

    private class RecordingConnectionSettings : GitHubConnectionSettings {
        var username: String? = null
        var repository: String? = null
        var connected = false
        override suspend fun setGithubUsername(username: String?) { this.username = username }
        override suspend fun setGithubRepository(repository: String) { this.repository = repository }
        override suspend fun setGithubConnected(connected: Boolean) { this.connected = connected }
    }

    private class ThrowingConnectionSettings(private val failure: Throwable) : GitHubConnectionSettings {
        override suspend fun setGithubUsername(username: String?) = throw failure
        override suspend fun setGithubRepository(repository: String) = Unit
        override suspend fun setGithubConnected(connected: Boolean) = Unit
    }

    private open class FakeConnectionApi(
        private val details: GitHubRepositoryDetails?,
        private val created: GitHubRepositoryDetails? = null,
    ) : GitHubArchiveApi {
        var createdName: String? = null
        override suspend fun authenticatedLogin() = "alice"
        override suspend fun findRepository(repository: GitHubRepository) = details
        override suspend fun createPrivateRepository(name: String): GitHubRepositoryDetails {
            createdName = name
            return requireNotNull(created)
        }
        override suspend fun getContent(repository: GitHubRepository, path: String): GitHubContent? = null
        override suspend fun putContent(
            repository: GitHubRepository,
            path: String,
            content: ByteArray,
            message: String,
            sha: String?,
        ) = GitHubContent(path, "sha")
    }
}
