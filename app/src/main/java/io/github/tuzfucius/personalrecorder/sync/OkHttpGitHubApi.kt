package io.github.tuzfucius.personalrecorder.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.Base64

fun interface GitHubAccessTokenProvider {
    suspend fun accessToken(): String?
}

/** GitHub REST 与 Git Data API 的轻量实现；每次请求读取当前 token。 */
class OkHttpGitHubApi(
    private val tokenProvider: GitHubAccessTokenProvider,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : GitHubAccountApi,
    GitHubRepositoryProvisioner,
    GitHubRepositoryInspector,
    GitHubGitDataApi,
    GitHubDeviceFlowApi {

    override suspend fun requestDeviceCode(clientId: String, scope: String): GitHubDeviceCode {
        val request = Request.Builder()
            .url(DEVICE_CODE_URL)
            .header("Accept", "application/json")
            .post(FormBody.Builder().add("client_id", clientId).add("scope", scope).build())
            .build()
        return parseGitHubDeviceCode(executeJson(request, withAuth = false))
            ?: throw SyncHttpException(422, "GitHub device code 响应缺少字段")
    }

    override suspend fun pollDeviceToken(clientId: String, deviceCode: String): GitHubDevicePollResult {
        val request = Request.Builder()
            .url("$OAUTH_BASE/access_token")
            .header("Accept", "application/json")
            .header("Accept", "application/json")
            .post(
                FormBody.Builder()
                    .add("client_id", clientId)
                    .add("device_code", deviceCode)
                    .add("grant_type", DEVICE_GRANT_TYPE)
                    .build()
            )
            .build()
        return parseGitHubDevicePoll(executeJson(request, withAuth = false))
    }

    override suspend fun authenticatedLogin(): String =
        executeJson(authorizedRequest("$API_BASE/user").get().build())
            .getValue("login").jsonPrimitive.content

    override suspend fun findRepository(repository: GitHubRepository): GitHubRepositoryDetails? {
        val request = authorizedRequest(repositoryUrl(repository)).get().build()
        return try {
            parseRepository(executeJson(request))
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
                authorizedRequest("$API_BASE/user/repos")
                    .post(body.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            )
        )
    }

    override suspend fun repositoryAccess(repository: GitHubRepository): GitHubRepositoryAccess =
        parseRepository(executeJson(authorizedRequest(repositoryUrl(repository)).get().build()))
            .let { GitHubRepositoryAccess(it.owner, it.isPrivate, it.canPush) }

    override suspend fun head(repository: GitHubRepository): GitHubHead {
        val details = parseRepository(executeJson(authorizedRequest(repositoryUrl(repository)).get().build()))
        val refName = "heads/${details.defaultBranch}"
        val ref = executeJson(
            authorizedRequest("${repositoryUrl(repository)}/git/ref/${encodeRef(refName)}").get().build()
        )
        val commitSha = ref.getValue("object").jsonObject.getValue("sha").jsonPrimitive.content
        val commit = executeJson(
            authorizedRequest("${repositoryUrl(repository)}/git/commits/$commitSha").get().build()
        )
        val treeSha = commit.getValue("tree").jsonObject.getValue("sha").jsonPrimitive.content
        return GitHubHead(commitSha, treeSha, refName)
    }

    override suspend fun tree(repository: GitHubRepository, treeSha: String): Map<String, GitHubTreeEntry> {
        val tree = executeJson(
            authorizedRequest("${repositoryUrl(repository)}/git/trees/$treeSha?recursive=1").get().build()
        )
        return tree["tree"]?.jsonArray.orEmpty().mapNotNull { element ->
            val item = element.jsonObject
            val path = item["path"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val sha = item["sha"]?.jsonPrimitive?.content ?: return@mapNotNull null
            path to GitHubTreeEntry(path, sha, item["type"]?.jsonPrimitive?.content ?: "blob")
        }.toMap()
    }

    override suspend fun createBlob(repository: GitHubRepository, content: ByteArray): String {
        val body = buildJsonObject {
            put("content", Base64.getEncoder().encodeToString(content))
            put("encoding", "base64")
        }.toString()
        return executeJson(
            authorizedRequest("${repositoryUrl(repository)}/git/blobs")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
        ).getValue("sha").jsonPrimitive.content
    }

    override suspend fun createTree(
        repository: GitHubRepository,
        baseTreeSha: String,
        blobs: List<GitHubBlob>,
    ): String {
        val body = buildJsonObject {
            put("base_tree", baseTreeSha)
            putJsonArray("tree") {
                blobs.forEach { blob ->
                    add(
                        buildJsonObject {
                            put("path", blob.path)
                            put("mode", "100644")
                            put("type", "blob")
                            put("sha", blob.sha)
                        }
                    )
                }
            }
        }.toString()
        return executeJson(
            authorizedRequest("${repositoryUrl(repository)}/git/trees")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
        ).getValue("sha").jsonPrimitive.content
    }

    override suspend fun createCommit(
        repository: GitHubRepository,
        parentCommitSha: String,
        treeSha: String,
        message: String,
    ): String {
        val body = buildJsonObject {
            put("message", message)
            put("tree", treeSha)
            putJsonArray("parents") { add(JsonPrimitive(parentCommitSha)) }
        }.toString()
        return executeJson(
            authorizedRequest("${repositoryUrl(repository)}/git/commits")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
        ).getValue("sha").jsonPrimitive.content
    }

    override suspend fun updateHead(
        repository: GitHubRepository,
        expectedCommitSha: String,
        newCommitSha: String,
        ref: String,
    ): GitHubReferenceUpdate {
        return try {
            val body = buildJsonObject {
                put("sha", newCommitSha)
                put("force", false)
            }.toString()
            executeJson(
                authorizedRequest("${repositoryUrl(repository)}/git/refs/${encodeRef(ref)}")
                    .method("PATCH", body.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            )
            GitHubReferenceUpdate.Updated
        } catch (error: SyncHttpException) {
            if (error.statusCode == 409) GitHubReferenceUpdate.Conflict("GitHub 远端引用已变化") else throw error
        }
    }

    private fun parseRepository(body: JsonObject): GitHubRepositoryDetails = GitHubRepositoryDetails(
        owner = body.getValue("owner").jsonObject.getValue("login").jsonPrimitive.content,
        isPrivate = body.getValue("private").jsonPrimitive.content.toBooleanStrictOrNull() ?: false,
        canPush = body["permissions"]?.jsonObject?.get("push")?.jsonPrimitive?.content
            ?.toBooleanStrictOrNull() ?: false,
        defaultBranch = body["default_branch"]?.jsonPrimitive?.content ?: "main",
    )

    private fun authorizedRequest(url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", API_VERSION)
        .header("User-Agent", "PersonalRecorder")

    private suspend fun executeJson(request: Request, withAuth: Boolean = true): JsonObject {
        val authorized = if (!withAuth) {
            request
        } else {
            val token = tokenProvider.accessToken()
            if (token.isNullOrBlank()) request else request.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        }
        httpClient.newCall(authorized).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw SyncHttpException(response.code, "GitHub HTTP ${response.code}")
            return if (body.isBlank()) buildJsonObject { } else json.parseToJsonElement(body).jsonObject
        }
    }

    private fun repositoryUrl(repository: GitHubRepository): String =
        "$API_BASE/repos/${encodePath(repository.owner)}/${encodePath(repository.name)}"

    private fun encodePath(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun encodeRef(ref: String): String = ref.split('/').joinToString("/") { encodePath(it) }

    private companion object {
        const val API_BASE = "https://api.github.com"
        const val DEVICE_CODE_URL = "https://github.com/login/device/code"
        const val OAUTH_BASE = "https://github.com/login/oauth"
        const val API_VERSION = "2026-03-10"
        const val DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
