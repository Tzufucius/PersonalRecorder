package io.github.tuzfucius.personalrecorder.sync

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubDeviceFlowTest {
    @Test
    fun parsesDeviceCodePayload() {
        val device = parseGitHubDeviceCode(
            Json.parseToJsonElement(
                """{"device_code":"device","user_code":"ABCD-EFGH","verification_uri":"https://github.com/login/device","expires_in":900,"interval":5}"""
            ).jsonObject
        )

        assertEquals("device", device?.deviceCode)
        assertEquals("ABCD-EFGH", device?.userCode)
        assertEquals(900L, device?.expiresInSeconds)
        assertEquals(5L, device?.intervalSeconds)
    }

    @Test
    fun pollingHonorsPendingAndSlowDownIntervals() = runBlocking {
        var now = 0L
        val delays = mutableListOf<Long>()
        val api = FakeDeviceApi(
            mutableListOf(
                GitHubDevicePollResult.Pending,
                GitHubDevicePollResult.SlowDown(5),
                GitHubDevicePollResult.Authorized("token", "bearer", "repo"),
            )
        )
        val result = GitHubDeviceFlowCoordinator(
            api = api,
            delayMillis = { millis -> delays += millis; now += millis },
            nowMillis = { now },
        ).pollForToken(
            clientId = "client",
            device = GitHubDeviceCode("device", "user", "https://github.com/login/device", 60, 5),
        )

        assertTrue(result is GitHubDevicePollResult.Authorized)
        assertEquals(listOf(5_000L, 5_000L, 10_000L), delays)
        assertEquals(3, api.pollCount)
    }

    @Test
    fun mapsTerminalErrors() {
        assertTrue(parseGitHubDevicePoll(Json.parseToJsonElement("""{"error":"expired_token"}""").jsonObject) is GitHubDevicePollResult.Expired)
        assertTrue(parseGitHubDevicePoll(Json.parseToJsonElement("""{"error":"access_denied"}""").jsonObject) is GitHubDevicePollResult.AccessDenied)
        assertTrue(parseGitHubDevicePoll(Json.parseToJsonElement("""{"error":"device_flow_disabled"}""").jsonObject) is GitHubDevicePollResult.Failed)
    }

    private class FakeDeviceApi(private val responses: MutableList<GitHubDevicePollResult>) : GitHubDeviceFlowApi {
        var pollCount = 0
        override suspend fun requestDeviceCode(clientId: String, scope: String): GitHubDeviceCode =
            GitHubDeviceCode("device", "user", "https://github.com/login/device", 60, 5)

        override suspend fun pollDeviceToken(clientId: String, deviceCode: String): GitHubDevicePollResult {
            pollCount++
            return responses.removeAt(0)
        }
    }
}
