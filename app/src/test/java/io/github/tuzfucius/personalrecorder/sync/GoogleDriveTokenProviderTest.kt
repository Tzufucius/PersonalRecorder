package io.github.tuzfucius.personalrecorder.sync

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleDriveTokenProviderTest {
    @Test
    fun refreshesOnceAfter401() = runBlocking {
        val provider = FakeProvider(listOf("token-1", "token-2"))
        val client = OkHttpGoogleDriveRestClient(provider, cannedClient(listOf(401, 200)))

        client.createFolder("archive", null)

        assertEquals(1, provider.clearCount)
        assertEquals(2, provider.requestCount)
    }

    @Test
    fun stopsAfterSecond401() = runBlocking {
        val provider = FakeProvider(listOf("token-1", "token-2"))
        val client = OkHttpGoogleDriveRestClient(provider, cannedClient(listOf(401, 401)))

        val error = runCatching { client.createFolder("archive", null) }.exceptionOrNull()

        assertTrue(error is SyncHttpException)
        assertEquals(1, provider.clearCount)
        assertEquals(2, provider.requestCount)
    }

    @Test
    fun authorizationResolutionIsReturnedAsAuthenticationError() = runBlocking {
        val provider = object : GoogleDriveAccessTokenProvider {
            override suspend fun getAccessToken() = GoogleTokenResult.AuthorizationRequired
            override suspend fun clearToken(accessToken: String) = Unit
        }
        val client = OkHttpGoogleDriveRestClient(provider, cannedClient(emptyList()))

        val error = runCatching { client.createFolder("archive", null) }.exceptionOrNull()

        assertTrue(error is SyncHttpException && error.statusCode == 401)
    }

    private fun cannedClient(codes: List<Int>): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(SequenceInterceptor(codes))
        .build()

    private class FakeProvider(private val tokens: List<String>) : GoogleDriveAccessTokenProvider {
        var requestCount = 0
        var clearCount = 0
        override suspend fun getAccessToken(): GoogleTokenResult {
            val token = tokens[requestCount++]
            return GoogleTokenResult.Available(token)
        }

        override suspend fun clearToken(accessToken: String) {
            clearCount++
        }
    }

    private class SequenceInterceptor(private val codes: List<Int>) : Interceptor {
        private var index = 0
        override fun intercept(chain: Interceptor.Chain): Response = Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(codes[index++])
            .message("test")
            .body(if (codes[index - 1] == 200) "{\"id\":\"folder\"}".toResponseBody() else "".toResponseBody())
            .build()
    }
}
