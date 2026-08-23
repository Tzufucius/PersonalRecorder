package io.github.tuzfucius.personalrecorder.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull

data class GitHubDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Long,
    val intervalSeconds: Long,
)

sealed interface GitHubDevicePollResult {
    data class Authorized(
        val accessToken: String,
        val tokenType: String,
        val scope: String,
    ) : GitHubDevicePollResult

    data object Pending : GitHubDevicePollResult
    data class SlowDown(val additionalDelaySeconds: Long) : GitHubDevicePollResult
    data object Expired : GitHubDevicePollResult
    data object AccessDenied : GitHubDevicePollResult
    data class Failed(val message: String) : GitHubDevicePollResult
}

interface GitHubDeviceFlowApi {
    suspend fun requestDeviceCode(clientId: String, scope: String): GitHubDeviceCode
    suspend fun pollDeviceToken(clientId: String, deviceCode: String): GitHubDevicePollResult
}

class GitHubDeviceFlowCoordinator(
    private val api: GitHubDeviceFlowApi,
    private val delayMillis: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun requestDeviceCode(clientId: String): GitHubDeviceCode =
        api.requestDeviceCode(clientId, scope = "repo")

    suspend fun pollForToken(clientId: String, device: GitHubDeviceCode): GitHubDevicePollResult {
        var intervalSeconds = device.intervalSeconds.coerceAtLeast(1)
        val deadline = nowMillis() + device.expiresInSeconds.coerceAtLeast(1) * 1_000L
        while (nowMillis() < deadline) {
            val remainingMillis = (deadline - nowMillis()).coerceAtLeast(0L)
            delayMillis((intervalSeconds * 1_000L).coerceAtMost(remainingMillis))
            if (nowMillis() >= deadline) return GitHubDevicePollResult.Expired
            when (val result = api.pollDeviceToken(clientId, device.deviceCode)) {
                GitHubDevicePollResult.Pending -> Unit
                is GitHubDevicePollResult.SlowDown -> {
                    intervalSeconds += result.additionalDelaySeconds.coerceAtLeast(5)
                }
                is GitHubDevicePollResult.Authorized,
                GitHubDevicePollResult.AccessDenied,
                GitHubDevicePollResult.Expired,
                is GitHubDevicePollResult.Failed -> return result
            }
        }
        return GitHubDevicePollResult.Expired
    }
}

class GitHubConnectionCoordinator(
    private val accountApi: GitHubAccountApi,
    private val repositoryProvisioner: GitHubRepositoryProvisioner,
    private val secrets: SecureSecretStore,
    private val settings: io.github.tuzfucius.personalrecorder.settings.CloudSyncSettingsStore,
) {
    suspend fun completeConnection(accessToken: String): Result<String> = runCatching {
        require(accessToken.isNotBlank()) { "GitHub 未返回 access token" }
        secrets.put(CloudCredentialStore.GITHUB_ACCESS_TOKEN, accessToken)
        val login = accountApi.authenticatedLogin()
        val repository = GitHubRepository.defaultFor(login)
        val details = repositoryProvisioner.findRepository(repository)
            ?: repositoryProvisioner.createPrivateRepository(repository.name)
        require(details.owner.equals(login, ignoreCase = true)) { "GitHub 仓库不属于当前账号" }
        require(details.isPrivate) { "目标 GitHub 仓库不是私有仓库，已阻止同步" }
        require(details.canPush) { "当前 GitHub 账号没有仓库写入权限" }
        settings.setGithubUsername(login)
        settings.setGithubConnected(true)
        login
    }.onFailure {
        secrets.remove(CloudCredentialStore.GITHUB_ACCESS_TOKEN)
        settings.setGithubConnected(false)
    }

    suspend fun disconnect() {
        secrets.remove(CloudCredentialStore.GITHUB_ACCESS_TOKEN)
        settings.setGithubUsername(null)
        settings.setGithubConnected(false)
        settings.setGithubEnabled(false)
    }
}

internal fun parseGitHubDeviceCode(body: JsonObject): GitHubDeviceCode? {
    val deviceCode = body["device_code"]?.jsonPrimitive?.contentOrNull ?: return null
    val userCode = body["user_code"]?.jsonPrimitive?.contentOrNull ?: return null
    val verificationUri = body["verification_uri"]?.jsonPrimitive?.contentOrNull ?: return null
    return GitHubDeviceCode(
        deviceCode = deviceCode,
        userCode = userCode,
        verificationUri = verificationUri,
        expiresInSeconds = body["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: return null,
        intervalSeconds = body["interval"]?.jsonPrimitive?.content?.toLongOrNull() ?: return null,
    )
}

internal fun parseGitHubDevicePoll(body: JsonObject): GitHubDevicePollResult {
    body["access_token"]?.jsonPrimitive?.contentOrNull?.let { token ->
        return GitHubDevicePollResult.Authorized(
            accessToken = token,
            tokenType = body["token_type"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            scope = body["scope"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }
    return when (body["error"]?.jsonPrimitive?.contentOrNull) {
        "authorization_pending" -> GitHubDevicePollResult.Pending
        "slow_down" -> GitHubDevicePollResult.SlowDown(
            5L
        )
        "expired_token", "token_expired" -> GitHubDevicePollResult.Expired
        "access_denied" -> GitHubDevicePollResult.AccessDenied
        "device_flow_disabled" -> GitHubDevicePollResult.Failed("GitHub OAuth App 未开启 Device Flow")
        "incorrect_client_credentials" -> GitHubDevicePollResult.Failed("GitHub client ID 不正确")
        "incorrect_device_code" -> GitHubDevicePollResult.Failed("GitHub device code 不正确")
        else -> GitHubDevicePollResult.Failed(
            body["error_description"]?.jsonPrimitive?.contentOrNull
                ?: body["error"]?.jsonPrimitive?.contentOrNull
                ?: "GitHub Device Flow 失败"
        )
    }
}
