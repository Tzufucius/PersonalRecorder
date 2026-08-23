package io.github.tuzfucius.personalrecorder.sync

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.Base64

class SyncHttpException(
    val statusCode: Int,
    override val message: String,
    val rateLimited: Boolean = false,
) : Exception(message)

fun interface GitHubAccessTokenProvider {
    suspend fun accessToken(): String?
}

/** GitHub 用户、仓库和 Contents API 的 OkHttp 实现。 */
class GitHubArchiveClient(
    private val tokenProvider: GitHubAccessTokenProvider,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : GitHubArchiveApi, GitHubRepositoryInspector {
    override suspend fun authenticatedLogin(): String {
        val body = executeJson("验证 GitHub 账号", authorizedRequest("$API_BASE/user").get().build())
        return body["login"]?.jsonPrimitive?.content
            ?: throw SyncHttpException(422, "GitHub /user 响应缺少 login")
    }

    override suspend fun findRepository(repository: GitHubRepository): GitHubRepositoryDetails? {
        return try {
            parseRepository(
                executeJson(
                    "读取 GitHub 仓库",
                    authorizedRequest(repositoryUrl(repository)).get().build(),
                )
            )
        } catch (error: SyncHttpException) {
            if (error.statusCode == 404) null else throw error
        }
    }

    override suspend fun createPrivateRepository(name: String): GitHubRepositoryDetails {
        val body = buildJsonObject {
            put("name", name)
            put("private", true)
            put("auto_init", true)
        }.toString()
        return parseRepository(
            executeJson(
                "创建私有 GitHub 仓库",
                authorizedRequest("$API_BASE/user/repos")
                    .post(body.toRequestBody(JSON_MEDIA_TYPE))
                    .build(),
            )
        )
    }

    override suspend fun repositoryAccess(repository: GitHubRepository): GitHubRepositoryAccess {
        val details = findRepository(repository)
            ?: throw SyncHttpException(404, "GitHub 归档仓库不存在")
        return GitHubRepositoryAccess(details.owner, details.isPrivate, details.canPush)
    }

    override suspend fun getContent(repository: GitHubRepository, path: String): GitHubContent? {
        return try {
            val body = executeJson(
                "读取归档内容: $path",
                authorizedRequest(contentUrl(repository, path)).get().build(),
            )
            parseContent(body)
        } catch (error: SyncHttpException) {
            if (error.statusCode == 404) null else throw error
        }
    }

    override suspend fun putContent(
        repository: GitHubRepository,
        path: String,
        content: ByteArray,
        message: String,
        sha: String?,
    ): GitHubContent {
        val body = buildJsonObject {
            put("message", message)
            put("content", Base64.getEncoder().encodeToString(content))
            sha?.let { put("sha", it) }
        }.toString()
        val response = executeJson(
            "上传归档内容: $path",
            authorizedRequest(contentUrl(repository, path))
                .put(body.toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        )
        val contentBody = response["content"]?.jsonObject ?: response
        return parseContent(contentBody)
    }

    private fun parseRepository(body: JsonObject): GitHubRepositoryDetails {
        val owner = body["owner"]?.jsonObject?.get("login")?.jsonPrimitive?.content
            ?: throw SyncHttpException(422, "GitHub 仓库响应缺少 owner")
        val isPrivate = body["private"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val canPush = body["permissions"]?.jsonObject?.get("push")?.jsonPrimitive?.content
            ?.toBooleanStrictOrNull() ?: false
        return GitHubRepositoryDetails(owner = owner, isPrivate = isPrivate, canPush = canPush)
    }

    private fun parseContent(body: JsonObject): GitHubContent {
        val path = body["path"]?.jsonPrimitive?.content
            ?: throw SyncHttpException(422, "GitHub Contents 响应缺少 path")
        val sha = body["sha"]?.jsonPrimitive?.content
            ?: throw SyncHttpException(422, "GitHub Contents 响应缺少 sha")
        val encoded = body["content"]?.jsonPrimitive?.content
        val decoded = encoded?.let { value ->
            runCatching { Base64.getMimeDecoder().decode(value) }.getOrNull()
        }
        return GitHubContent(path = path, sha = sha, content = decoded)
    }

    private suspend fun executeJson(operation: String, request: Request): JsonObject = withContext(Dispatchers.IO) {
        val token = tokenProvider.accessToken()?.trim()
        if (token.isNullOrBlank()) throw SyncHttpException(401, "GitHub access token is missing")
        val authorized = request.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
        logDebug(operation)
        return@withContext httpClient.newCall(authorized).execute().use { response ->
            val body = response.body?.string().orEmpty()
            logDebug("$operation: HTTP ${response.code}")
            if (!response.isSuccessful) {
                throw SyncHttpException(
                    statusCode = response.code,
                    message = "GitHub HTTP ${response.code}",
                    rateLimited = response.code == 429 ||
                        (response.code == 403 && response.header("X-RateLimit-Remaining") == "0"),
                )
            }
            if (body.isBlank()) buildJsonObject { } else json.parseToJsonElement(body).jsonObject
        }
    }

    private suspend fun executeJsonElement(operation: String, request: Request): JsonElement = withContext(Dispatchers.IO) {
        val token = tokenProvider.accessToken()?.trim()
        if (token.isNullOrBlank()) throw SyncHttpException(401, "GitHub access token is missing")
        val authorized = request.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
        logDebug(operation)
        return@withContext httpClient.newCall(authorized).execute().use { response ->
            val body = response.body?.string().orEmpty()
            logDebug("$operation: HTTP ${response.code}")
            if (!response.isSuccessful) {
                throw SyncHttpException(
                    statusCode = response.code,
                    message = "GitHub HTTP ${response.code}",
                    rateLimited = response.code == 429 ||
                        (response.code == 403 && response.header("X-RateLimit-Remaining") == "0"),
                )
            }
            if (body.isBlank()) buildJsonObject { } else json.parseToJsonElement(body)
        }
    }

    override suspend fun listDirectory(
        repository: GitHubRepository,
        path: String,
    ): List<GitHubDirectoryEntry> {
        return try {
            val body = executeJsonElement(
                "发现远端目录: $path",
                authorizedRequest(contentUrl(repository, path)).get().build(),
            )
            val entries = body as? JsonArray
                ?: throw SyncHttpException(422, "GitHub 目录响应不是数组")
            entries.map { element ->
                val objectBody = element.jsonObject
                GitHubDirectoryEntry(
                    path = objectBody["path"]?.jsonPrimitive?.content
                        ?: throw SyncHttpException(422, "GitHub 目录响应缺少 path"),
                    type = objectBody["type"]?.jsonPrimitive?.content
                        ?: throw SyncHttpException(422, "GitHub 目录响应缺少 type"),
                    sha = objectBody["sha"]?.jsonPrimitive?.content,
                    size = objectBody["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                )
            }
        } catch (error: SyncHttpException) {
            if (error.statusCode == 404) emptyList() else throw error
        }
    }

    override suspend fun downloadContent(repository: GitHubRepository, path: String): GitHubContent? =
        getContent(repository, path)

    private fun authorizedRequest(url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", API_VERSION)
        .header("User-Agent", "PersonalRecorder")

    private fun repositoryUrl(repository: GitHubRepository): String =
        "$API_BASE/repos/${encodePath(repository.owner)}/${encodePath(repository.name)}"

    private fun contentUrl(repository: GitHubRepository, path: String): String =
        "${repositoryUrl(repository)}/contents/${path.split('/').filter(String::isNotEmpty).joinToString("/") { encodePath(it) }}"

    private fun encodePath(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun logDebug(message: String) {
        runCatching { Log.d(TAG, message) }
    }

    private companion object {
        const val TAG = "PR-GitHub"
        const val API_BASE = "https://api.github.com"
        const val API_VERSION = "2022-11-28"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
