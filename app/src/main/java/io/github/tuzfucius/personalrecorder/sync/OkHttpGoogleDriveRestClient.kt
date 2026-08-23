package io.github.tuzfucius.personalrecorder.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class SyncHttpException(val statusCode: Int, override val message: String) : Exception(message)

/** Drive v3 REST implementation. Tokens are supplied at call time and never logged. */
class OkHttpGoogleDriveRestClient(
    private val tokenProvider: GoogleDriveAccessTokenProvider,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : GoogleDriveRestClient {
    override suspend fun createFolder(name: String, parentId: String?): String {
        val metadata = buildJsonObject {
            put("name", name)
            put("mimeType", FOLDER_MIME_TYPE)
            parentId?.let { put("parents", buildJsonArray { add(JsonPrimitive(it)) }) }
        }.toString()
        val correctedRequest = authorizedRequest("https://www.googleapis.com/drive/v3/files")
            .post(metadata.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return executeJson(correctedRequest).jsonObject.getValue("id").jsonPrimitive.content
    }

    override suspend fun findManagedFile(parentId: String, name: String): GoogleDriveFile? {
        val escapedName = name.replace("'", "\\'")
        val url = "https://www.googleapis.com/drive/v3/files" +
            "?q=${encodeQuery("'$parentId' in parents and name = '$escapedName' and trashed = false")}" +
            "&spaces=drive&fields=files(id,name,appProperties)&pageSize=10"
        val response = executeJson(authorizedRequest(url).get().build()).jsonObject
        val parsed = response["files"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        return GoogleDriveFile(
            id = parsed.getValue("id").jsonPrimitive.content,
            name = parsed.getValue("name").jsonPrimitive.content,
            sha256 = parsed["appProperties"]?.jsonObject?.get("sha256")?.jsonPrimitive?.content,
        )
    }

    override suspend fun uploadFile(parentId: String, name: String, content: ByteArray, sha256: String): GoogleDriveUpload {
        val metadata = buildJsonObject {
            put("name", name)
            put("parents", buildJsonArray { add(JsonPrimitive(parentId)) })
            putJsonObject("appProperties") { put("sha256", sha256) }
        }.toString()
        val multipart = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metadata.toRequestBody(JSON_MEDIA_TYPE))
            .addPart(content.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        val request = authorizedRequest("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,appProperties")
            .post(multipart)
            .build()
        val response = executeJson(request).jsonObject
        return GoogleDriveUpload(
            id = response.getValue("id").jsonPrimitive.content,
            sha256 = response["appProperties"]?.jsonObject?.get("sha256")?.jsonPrimitive?.content ?: sha256,
        )
    }

    private fun authorizedRequest(url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("Accept", "application/json")

    private suspend fun executeJson(request: Request): kotlinx.serialization.json.JsonObject {
        var lastToken: String? = null
        repeat(2) { attempt ->
            val token = when (val result = tokenProvider.getAccessToken()) {
                is GoogleTokenResult.Available -> result.accessToken
                GoogleTokenResult.AuthorizationRequired -> throw SyncHttpException(401, "Google Drive 需要重新授权")
                is GoogleTokenResult.Failed -> throw SyncHttpException(401, result.message)
            }
            lastToken = token
            val authorized = request.newBuilder().header("Authorization", "Bearer $token").build()
            httpClient.newCall(authorized).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    return if (body.isBlank()) buildJsonObject { } else json.parseToJsonElement(body).jsonObject
                }
                if (response.code != 401 || attempt == 1) {
                    throw SyncHttpException(response.code, "Google Drive HTTP ${response.code}")
                }
            }
            tokenProvider.clearToken(requireNotNull(lastToken))
        }
        throw SyncHttpException(401, "Google Drive 未授权")
    }

    private fun encodeQuery(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
    }
}
