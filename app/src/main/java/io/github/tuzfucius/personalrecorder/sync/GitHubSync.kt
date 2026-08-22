package io.github.tuzfucius.personalrecorder.sync

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class GitHubOAuthSession(
    val state: String,
    val codeVerifier: String,
    val authorizationUri: String
)

sealed interface GitHubOAuthCallback {
    data class Authorized(val code: String) : GitHubOAuthCallback
    data class Denied(val error: String, val description: String?) : GitHubOAuthCallback
    data class Invalid(val reason: String) : GitHubOAuthCallback
}

/**
 * GitHub 没有适合原生 APK 直接交换 token 的公开客户端流程。本协调器仅生成 PKCE/state 和验证回调；
 * token 交换必须交给可信服务，绝不在 APK、BuildConfig 或资源中保存 client secret。
 */
class GitHubOAuthCoordinator(
    private val clientId: String,
    private val redirectUri: String = DEFAULT_REDIRECT_URI,
    private val secureRandom: SecureRandom = SecureRandom()
) {
    init {
        require(clientId.isNotBlank()) { "GitHub OAuth client ID 不能为空" }
        require(URI(redirectUri).scheme == "personalrecorder") { "GitHub 回调必须使用应用 deep link" }
    }

    fun beginAuthorization(): GitHubOAuthSession {
        val state = randomUrlSafeValue(32)
        val verifier = randomUrlSafeValue(64)
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        )
        val uri = buildString {
            append(AUTHORIZATION_ENDPOINT)
            append("?client_id=").append(encode(clientId))
            append("&redirect_uri=").append(encode(redirectUri))
            append("&scope=").append(encode("repo"))
            append("&state=").append(encode(state))
            append("&code_challenge=").append(encode(challenge))
            append("&code_challenge_method=S256")
        }
        return GitHubOAuthSession(state, verifier, uri)
    }

    fun parseCallback(callbackUri: String, expectedState: String): GitHubOAuthCallback {
        val parsed = runCatching { URI(callbackUri) }.getOrElse {
            return GitHubOAuthCallback.Invalid("无效的 OAuth 回调地址")
        }
        if (!isExpectedRedirect(parsed)) return GitHubOAuthCallback.Invalid("OAuth 回调地址不匹配")
        val parameters = parsed.query.orEmpty().split("&").mapNotNull { pair ->
            pair.substringBefore("=", "").takeIf { it.isNotBlank() }?.let { key ->
                key to decode(pair.substringAfter("=", ""))
            }
        }.toMap()
        if (parameters["state"] != expectedState) return GitHubOAuthCallback.Invalid("OAuth state 不匹配")
        parameters["error"]?.let { return GitHubOAuthCallback.Denied(it, parameters["error_description"]) }
        val code = parameters["code"] ?: return GitHubOAuthCallback.Invalid("OAuth 回调缺少授权码")
        return GitHubOAuthCallback.Authorized(code)
    }

    private fun isExpectedRedirect(uri: URI): Boolean {
        val expected = URI(redirectUri)
        return uri.scheme.equals(expected.scheme, ignoreCase = true) &&
            uri.authority.equals(expected.authority, ignoreCase = true) &&
            uri.path == expected.path
    }

    private fun randomUrlSafeValue(byteCount: Int): String = ByteArray(byteCount).also(secureRandom::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    companion object {
        const val DEFAULT_REDIRECT_URI = "personalrecorder://oauth/github"
        private const val AUTHORIZATION_ENDPOINT = "https://github.com/login/oauth/authorize"
    }
}

/** 进程内暂存 PKCE verifier，避免将其写入普通 DataStore 或日志。 */
class GitHubOAuthDeepLinkCoordinator(
    private val oauth: GitHubOAuthCoordinator,
    private val secureSecrets: SecureSecretStore? = null,
) {
    private var pendingSession: GitHubOAuthSession? = null

    @Synchronized
    fun startAuthorization(): GitHubOAuthSession = oauth.beginAuthorization().also {
        pendingSession = it
        secureSecrets?.put(CloudCredentialStore.GITHUB_PENDING_STATE, it.state)
        secureSecrets?.put(CloudCredentialStore.GITHUB_PENDING_VERIFIER, it.codeVerifier)
    }

    @Synchronized
    fun consumeCallback(callbackUri: String): Pair<GitHubOAuthCallback, String?> {
        val session = pendingSession ?: secureSecrets?.get(CloudCredentialStore.GITHUB_PENDING_STATE)?.let { state ->
            secureSecrets.get(CloudCredentialStore.GITHUB_PENDING_VERIFIER)?.let { verifier ->
                GitHubOAuthSession(state, verifier, "")
            }
        } ?: return GitHubOAuthCallback.Invalid("没有待处理的 OAuth 请求") to null
        val callback = oauth.parseCallback(callbackUri, session.state)
        if (callback !is GitHubOAuthCallback.Invalid) {
            pendingSession = null
            secureSecrets?.remove(CloudCredentialStore.GITHUB_PENDING_STATE)
            secureSecrets?.remove(CloudCredentialStore.GITHUB_PENDING_VERIFIER)
        }
        return callback to if (callback is GitHubOAuthCallback.Authorized) session.codeVerifier else null
    }
}

data class GitHubTokenExchangeRequest(val code: String, val codeVerifier: String)

interface GitHubTokenExchange {
    suspend fun exchange(request: GitHubTokenExchangeRequest): BackendSyncResult
}

/** 默认安全占位：未配置可信交换服务时不会伪造已授权。 */
object UnconfiguredGitHubTokenExchange : GitHubTokenExchange {
    override suspend fun exchange(request: GitHubTokenExchangeRequest): BackendSyncResult =
        BackendSyncResult.Failure(SyncError.NotConfigured("未配置 GitHub 可信 token 交换服务"))
}

data class GitHubRepository(val owner: String, val name: String) {
    companion object {
        const val DEFAULT_NAME = "PersonalRecorder-Archive"

        fun defaultFor(owner: String): GitHubRepository = GitHubRepository(owner, DEFAULT_NAME)
    }
}
data class GitHubRepositoryAccess(val owner: String, val isPrivate: Boolean, val canPush: Boolean)

interface GitHubRepositoryInspector {
    suspend fun authenticatedLogin(): String
    suspend fun repositoryAccess(repository: GitHubRepository): GitHubRepositoryAccess
}

/** 上传前强制校验“当前账号拥有、私有、可写”三个条件。 */
class GitHubPrivateRepositoryGuard(private val inspector: GitHubRepositoryInspector) {
    suspend fun validate(repository: GitHubRepository): SyncError? = runCatching {
        val login = inspector.authenticatedLogin()
        val access = inspector.repositoryAccess(repository)
        when {
            !access.owner.equals(login, ignoreCase = true) -> SyncError.Authorization("GitHub 仓库不属于当前账号")
            !access.isPrivate -> SyncError.Authorization("禁止向公开 GitHub 仓库同步")
            !access.canPush -> SyncError.Authorization("当前 GitHub 账号没有仓库写入权限")
            else -> null
        }
    }.getOrElse { SyncError.Network("无法验证 GitHub 私有仓库", it) }
}

data class GitHubHead(val commitSha: String, val treeSha: String)
data class GitHubBlob(val path: String, val sha: String)
sealed interface GitHubReferenceUpdate {
    data object Updated : GitHubReferenceUpdate
    data class Conflict(val message: String) : GitHubReferenceUpdate
}

/** Git Data API 边界；HTTP 鉴权和 token 存储由调用方实现，接口本身不承载 secret。 */
interface GitHubGitDataApi {
    suspend fun head(repository: GitHubRepository): GitHubHead
    suspend fun createBlob(repository: GitHubRepository, content: ByteArray): String
    suspend fun createTree(repository: GitHubRepository, baseTreeSha: String, blobs: List<GitHubBlob>): String
    suspend fun createCommit(repository: GitHubRepository, parentCommitSha: String, treeSha: String, message: String): String
    suspend fun updateHead(repository: GitHubRepository, expectedCommitSha: String, newCommitSha: String): GitHubReferenceUpdate
}

/** 通过 Git Data API 将同一批文件组合为一个逻辑 commit。 */
class GitHubCloudSyncBackend(
    private val repository: GitHubRepository,
    private val guard: GitHubPrivateRepositoryGuard,
    private val gitDataApi: GitHubGitDataApi
) : CloudSyncBackend {
    override val type = CloudBackendType.GITHUB

    override suspend fun sync(archive: CloudArchive): BackendSyncResult = syncBatch(listOf(archive))[archive.relativePath]
        ?: BackendSyncResult.Failure(SyncError.Unknown("GitHub 同步没有返回归档结果"))

    override suspend fun syncBatch(archives: Collection<CloudArchive>): Map<String, BackendSyncResult> {
        if (archives.isEmpty()) return emptyMap()
        guard.validate(repository)?.let { error ->
            return archives.associate { it.relativePath to BackendSyncResult.Failure(error) }
        }
        return runCatching {
            val head = gitDataApi.head(repository)
            val blobs = archives.map { archive -> GitHubBlob(archive.relativePath, gitDataApi.createBlob(repository, archive.content)) }
            val tree = gitDataApi.createTree(repository, head.treeSha, blobs)
            val commit = gitDataApi.createCommit(repository, head.commitSha, tree, "sync: upload ${archives.size} archive file(s)")
            when (val update = gitDataApi.updateHead(repository, head.commitSha, commit)) {
                GitHubReferenceUpdate.Updated -> archives.associate {
                    it.relativePath to BackendSyncResult.Success(commit)
                }
                is GitHubReferenceUpdate.Conflict -> archives.associate {
                    it.relativePath to BackendSyncResult.Failure(SyncError.RemoteConflict(update.message))
                }
            }
        }.getOrElse { throwable ->
            archives.associate { it.relativePath to BackendSyncResult.Failure(SyncError.Network("GitHub 上传失败", throwable)) }
        }
    }
}
