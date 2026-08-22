package io.github.tuzfucius.personalrecorder.sync

const val GOOGLE_DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

sealed interface GoogleDriveAuthorizationResult {
    data class Authorized(val accessToken: String) : GoogleDriveAuthorizationResult
    data class Denied(val message: String) : GoogleDriveAuthorizationResult
    data class Failed(val message: String, val cause: Throwable? = null) : GoogleDriveAuthorizationResult
}

/** 对 Google Identity AuthorizationClient 的最小适配层，便于替换 SDK 与单元测试。 */
interface GoogleDriveAuthorizationClient {
    suspend fun authorize(scopes: Set<String>): GoogleDriveAuthorizationResult
}

class GoogleDriveAuthorizationCoordinator(private val client: GoogleDriveAuthorizationClient) {
    suspend fun authorize(): GoogleDriveAuthorizationResult = client.authorize(setOf(GOOGLE_DRIVE_FILE_SCOPE))
}

data class GoogleDriveFile(val id: String, val name: String, val sha256: String?)
data class GoogleDriveUpload(val id: String, val sha256: String)

/**
 * Drive REST v3 边界。实现只能检索此前由本应用创建和缓存的目录中的文件，不能接管同名人工目录。
 */
interface GoogleDriveRestClient {
    suspend fun createFolder(name: String, parentId: String?): String
    suspend fun findManagedFile(parentId: String, name: String): GoogleDriveFile?
    suspend fun uploadFile(parentId: String, name: String, content: ByteArray, sha256: String): GoogleDriveUpload
}

/** folder ID 缓存必须由调用方使用 Android Keystore/加密存储实现，不保存 access token。 */
interface GoogleDriveFolderIdCache {
    suspend fun get(path: String): String?
    suspend fun put(path: String, folderId: String)
    suspend fun remove(path: String)
}

/** 只根据自己的缓存逐层创建目录，不通过全盘检索匹配名称。 */
class GoogleDriveFolderResolver(
    private val restClient: GoogleDriveRestClient,
    private val cache: GoogleDriveFolderIdCache
) {
    suspend fun resolveFolder(pathSegments: List<String>): String {
        require(pathSegments.isNotEmpty()) { "Drive 文件夹路径不能为空" }
        require(pathSegments.all { it.isNotBlank() && it != "." && it != ".." && !it.contains('/') }) {
            "Drive 文件夹路径不合法"
        }
        var parentId: String? = null
        val accumulated = mutableListOf<String>()
        pathSegments.forEach { segment ->
            accumulated += segment
            val cacheKey = accumulated.joinToString("/")
            val folderId = cache.get(cacheKey) ?: restClient.createFolder(segment, parentId).also {
                cache.put(cacheKey, it)
            }
            parentId = folderId
        }
        return requireNotNull(parentId)
    }
}

class GoogleDriveCloudSyncBackend(
    private val restClient: GoogleDriveRestClient,
    private val folderResolver: GoogleDriveFolderResolver
) : CloudSyncBackend {
    override val type = CloudBackendType.GOOGLE_DRIVE

    override suspend fun sync(archive: CloudArchive): BackendSyncResult {
        return try {
        val path = archive.relativePath.split('/').filter { it.isNotBlank() }
        if (path.size < 2) return BackendSyncResult.Failure(SyncError.InvalidArchive("Drive 归档路径缺少父目录"))
        val parentId = folderResolver.resolveFolder(path.dropLast(1))
        val fileName = path.last()
        val existing = restClient.findManagedFile(parentId, fileName)
        when {
            existing == null -> restClient.uploadFile(parentId, fileName, archive.content, archive.sha256)
                .let { BackendSyncResult.Success(it.id) }
            existing.sha256.equals(archive.sha256, ignoreCase = true) -> BackendSyncResult.Success(
                remoteReference = existing.id,
                wasAlreadyPresent = true
            )
            else -> BackendSyncResult.Failure(
                SyncError.RemoteConflict("Drive 中同路径文件 SHA-256 与本地归档不一致")
            )
        }
    } catch (error: SyncHttpException) {
        when {
            error.statusCode == 401 -> BackendSyncResult.Failure(SyncError.Authentication(error.message))
            error.statusCode == 403 -> BackendSyncResult.Failure(SyncError.Authorization(error.message))
            error.statusCode == 429 -> BackendSyncResult.Failure(SyncError.RateLimited(error.message))
            error.statusCode >= 500 -> BackendSyncResult.Failure(SyncError.ServiceUnavailable(error.message))
            else -> BackendSyncResult.Failure(SyncError.Unknown(error.message, error))
        }
    } catch (error: Throwable) {
        BackendSyncResult.Failure(SyncError.Network("Google Drive 上传失败", error))
        }
    }
}
