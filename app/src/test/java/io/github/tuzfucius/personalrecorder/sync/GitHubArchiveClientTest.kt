package io.github.tuzfucius.personalrecorder.sync

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class GitHubArchiveClientTest {
    @Test
    fun rawDownloadPreservesLargePayloadByteForByte() = runBlocking {
        listOf(100_000, 1_500_000, 5_000_000).forEach { size ->
            val payload = ByteArray(size) { (it % 126).toByte() }
            val fake = FakeHttp(listOf(ResponseSpec(200, payload.toString(Charsets.US_ASCII))))
            val result = client(fake).downloadContent(
                GitHubRepository("alice", "archive"),
                "archive/2026/08/2026-08-23/00-12.jsonl",
            )

            assertArrayEquals("payload size $size", payload, result)
            assertEquals("application/vnd.github.raw+json", fake.requests.single().header("Accept"))
        }
    }
    @Test
    fun authenticatedUserUsesBearerTokenWithoutExposingItInErrors() = runBlocking {
        val fake = FakeHttp(listOf(ResponseSpec(200, "{\"login\":\"alice\"}")))
        val client = client(fake)
        val callerThread = Thread.currentThread()

        assertEquals("alice", client.authenticatedLogin())
        assertEquals("Bearer test-token", fake.requests.single().header("Authorization"))
        assertNotEquals(callerThread, fake.requestThreads.single())
    }

    @Test
    fun accessTokenIsNotIncludedInHttpErrors() = runBlocking {
        val fake = FakeHttp(listOf(ResponseSpec(500, "{\"message\":\"failed\"}")))

        val error = runCatching { client(fake).authenticatedLogin() }.exceptionOrNull()

        assertTrue(error is SyncHttpException)
        assertTrue(error?.message.orEmpty().contains("GitHub HTTP 500"))
        assertTrue(!error?.message.orEmpty().contains("test-token"))
    }

    @Test
    fun missingRepositoryIsReturnedAsNull() = runBlocking {
        val fake = FakeHttp(listOf(ResponseSpec(404, "{}")))

        assertNull(client(fake).findRepository(GitHubRepository("alice", "archive")))
    }

    @Test
    fun repositoryCreationForcesPrivateAndAutoInit() = runBlocking {
        val fake = FakeHttp(
            listOf(
                ResponseSpec(
                    201,
                    """{"owner":{"login":"alice"},"private":true,"permissions":{"push":true}}""",
                )
            )
        )

        val result = client(fake).createPrivateRepository("archive")

        assertTrue(result.isPrivate)
        assertTrue(result.canPush)
        val request = fake.requests.single()
        assertEquals("POST", request.method)
        val body = request.body!!.let { buffer ->
            okio.Buffer().also { target -> buffer.writeTo(target) }.readUtf8()
        }
        assertTrue(body.contains("\"private\":true"))
        assertTrue(body.contains("\"auto_init\":true"))
    }

    @Test
    fun existingContentIsDecodedAndUpdatedWithSha() = runBlocking {
        val encoded = Base64.getEncoder().encodeToString("old\n".toByteArray())
        val fake = FakeHttp(
            listOf(
                ResponseSpec(200, """{"path":"archive/a.jsonl","sha":"old-sha","content":"$encoded"}"""),
                ResponseSpec(200, """{"content":{"path":"archive/a.jsonl","sha":"new-sha"}}"""),
            )
        )
        val client = client(fake)
        val repository = GitHubRepository("alice", "archive")

        val remote = client.getContent(repository, "archive/a.jsonl")
        val uploaded = client.putContent(repository, "archive/a.jsonl", "new\n".toByteArray(), "update", remote?.sha)

        assertNotNull(remote)
        assertEquals("old-sha", remote?.sha)
        assertEquals("new-sha", uploaded.sha)
        assertEquals("PUT", fake.requests[1].method)
        val body = fake.requests[1].body!!.let { source ->
            okio.Buffer().also { target -> source.writeTo(target) }.readUtf8()
        }
        assertTrue(body.contains("\"sha\":\"old-sha\""))
    }

    @Test
    fun directoryEntriesAreDiscoveredWithoutTreatingDirectoryAsFile() = runBlocking {
        val fake = FakeHttp(
            listOf(
                ResponseSpec(
                    200,
                    """[{"path":"archive/2026/08","type":"dir","sha":"tree"},{"path":"archive/2026/08/manifest.json","type":"file","sha":"blob","size":12}]""",
                )
            )
        )

        val entries = client(fake).listDirectory(GitHubRepository("alice", "archive"), "archive")

        assertEquals(2, entries.size)
        assertEquals("dir", entries.first().type)
        assertEquals(12L, entries.last().size)
    }

    private fun client(fake: FakeHttp) = GitHubArchiveClient(
        tokenProvider = GitHubAccessTokenProvider { "test-token" },
        httpClient = OkHttpClient.Builder().addInterceptor(fake).build(),
    )

    private data class ResponseSpec(val code: Int, val body: String)

    private class FakeHttp(private val responses: List<ResponseSpec>) : Interceptor {
        val requests = mutableListOf<okhttp3.Request>()
        val requestThreads = mutableListOf<Thread>()
        private var index = 0

        override fun intercept(chain: Interceptor.Chain): Response {
            requests += chain.request()
            requestThreads += Thread.currentThread()
            val spec = responses[index++]
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(spec.code)
                .message("test")
                .body(spec.body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }
}
