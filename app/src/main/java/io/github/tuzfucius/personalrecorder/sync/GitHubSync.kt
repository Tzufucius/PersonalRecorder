package io.github.tuzfucius.personalrecorder.sync

import kotlinx.coroutines.CancellationException

data class GitHubRepository(val owner: String, val name: String) {
    init {
        require(owner.isNotBlank()) { "GitHub owner 不能为空" }
        require(name.isNotBlank() && name != "." && name != ".." && !name.contains('/')) {
            "GitHub 仓库名称不合法"
        }
    }

    companion object {
        const val DEFAULT_NAME = "PersonalRecorder-Archive"

        fun defaultFor(owner: String): GitHubRepository = GitHubRepository(owner, DEFAULT_NAME)
    }
}

data class GitHubRepositoryDetails(
    val owner: String,
    val isPrivate: Boolean,
    val canPush: Boolean,
)

data class GitHubContent(
    val path: String,
    val sha: String,
    val content: ByteArray? = null,
)

data class GitHubContentMetadata(
    val path: String,
    val sha: String,
    val size: Long,
)

data class GitHubDirectoryEntry(
    val path: String,
    val type: String,
    val sha: String? = null,
    val size: Long = 0L,
)

/** GitHub API 的最小业务边界，连接和归档上传共用同一客户端。 */
interface GitHubArchiveApi {
    suspend fun authenticatedLogin(): String
    suspend fun findRepository(repository: GitHubRepository): GitHubRepositoryDetails?
    suspend fun createPrivateRepository(name: String): GitHubRepositoryDetails
    suspend fun getContent(repository: GitHubRepository, path: String): GitHubContent?

    suspend fun getContentMetadata(
        repository: GitHubRepository,
        path: String,
    ): GitHubContentMetadata? = getContent(repository, path)?.let {
        GitHubContentMetadata(it.path, it.sha, it.content?.size?.toLong() ?: 0L)
    }

    suspend fun listDirectory(
        repository: GitHubRepository,
        path: String,
    ): List<GitHubDirectoryEntry> = emptyList()

    suspend fun downloadContent(
        repository: GitHubRepository,
        path: String,
    ): ByteArray? = getContent(repository, path)?.content
    suspend fun putContent(
        repository: GitHubRepository,
        path: String,
        content: ByteArray,
        message: String,
        sha: String? = null,
    ): GitHubContent
}

interface GitHubConnectionSettings {
    suspend fun setGithubUsername(username: String?)
    suspend fun setGithubRepository(repository: String)
    suspend fun setGithubConnected(connected: Boolean)
}

interface GitHubAccountApi {
    suspend fun authenticatedLogin(): String
}

interface GitHubRepositoryInspector : GitHubAccountApi {
    suspend fun repositoryAccess(repository: GitHubRepository): GitHubRepositoryAccess
}

data class GitHubRepositoryAccess(val owner: String, val isPrivate: Boolean, val canPush: Boolean)

/** 连接时强制校验“当前账号拥有、私有、可写”三个条件。 */
class GitHubPrivateRepositoryGuard(private val inspector: GitHubRepositoryInspector) {
    suspend fun validate(repository: GitHubRepository): SyncError? = runCatching {
        val login = inspector.authenticatedLogin()
        val access = inspector.repositoryAccess(repository)
        when {
            !access.owner.equals(login, ignoreCase = true) ->
                SyncError.Authorization("GitHub 仓库不属于当前账号")
            !access.isPrivate ->
                SyncError.Authorization("目标仓库不是私有仓库，已拒绝保存个人数据")
            !access.canPush ->
                SyncError.Authorization("当前 GitHub 账号没有仓库写入权限")
            else -> null
        }
    }.getOrElse { error ->
        if (error is CancellationException) throw error
        when (error) {
            is SyncHttpException -> when {
                error.statusCode == 401 -> SyncError.Authentication("GitHub Token 已失效")
                error.statusCode == 403 -> SyncError.Authorization("GitHub API 拒绝访问")
                error.statusCode == 404 -> SyncError.Authorization("GitHub 归档仓库不存在")
                error.statusCode == 429 -> SyncError.RateLimited("GitHub API 请求过于频繁")
                error.statusCode >= 500 -> SyncError.ServiceUnavailable("GitHub 服务暂不可用")
                else -> SyncError.Network("无法验证 GitHub 私有仓库", error)
            }
            else -> SyncError.Network("无法验证 GitHub 私有仓库", error)
        }
    }
}

/** 使用未经持久化的 PAT 完成验证，验证成功后才写入 Keystore 和 DataStore。 */
class GitHubConnectionCoordinator(
    private val clientFactory: (String) -> GitHubArchiveApi,
    private val secrets: SecretStore,
    private val settings: GitHubConnectionSettings,
) {
    suspend fun connect(token: String, repositoryName: String): Result<String> {
        val candidateToken = token.trim()
        val candidateRepository = repositoryName.trim()
        if (candidateToken.isBlank()) return Result.failure(GitHubConnectionException("Personal Access Token 不能为空"))
        if (candidateRepository.isBlank()) return Result.failure(GitHubConnectionException("仓库名称不能为空"))

        return try {
            val client = clientFactory(candidateToken)
            val login = client.authenticatedLogin()
            val repository = GitHubRepository(login, candidateRepository)
            val details = client.findRepository(repository) ?: client.createPrivateRepository(repository.name)
            when {
                !details.owner.equals(login, ignoreCase = true) ->
                    error("目标仓库不属于当前 GitHub 账号")
                !details.isPrivate -> error("目标仓库不是私有仓库，已拒绝保存个人数据")
                !details.canPush -> error("当前 GitHub Token 没有仓库写入权限")
            }

            try {
                secrets.put(CloudCredentialStore.GITHUB_ACCESS_TOKEN, candidateToken)
                settings.setGithubUsername(login)
                settings.setGithubRepository(repository.name)
                settings.setGithubConnected(true)
            } catch (persistError: Throwable) {
                try {
                    secrets.remove(CloudCredentialStore.GITHUB_ACCESS_TOKEN)
                } catch (cleanupError: Throwable) {
                    persistError.addSuppressed(cleanupError)
                }
                if (persistError is CancellationException) throw persistError
                throw GitHubConnectionException("GitHub 连接状态保存失败", persistError)
            }
            Result.success(login)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (error is SyncHttpException) {
                Result.failure(GitHubConnectionException(error.userMessage(), error))
            } else {
                Result.failure(
                    if (error is GitHubConnectionException) error
                    else GitHubConnectionException(error.message ?: "GitHub 连接失败", error)
                )
            }
        }
    }
}

class GitHubConnectionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Contents API 上传后端；每个文件独立幂等，成功后由 ArchiveSyncRunner 写入 Room 状态。 */
class GitHubCloudSyncBackend(
    internal val repositoryProvider: suspend () -> GitHubRepository?,
    internal val api: GitHubArchiveApi,
) : CloudSyncBackend {
    constructor(repository: GitHubRepository, api: GitHubArchiveApi) : this({ repository }, api)

    override val type = CloudBackendType.GITHUB

    override suspend fun sync(archive: CloudArchive): BackendSyncResult =
        syncBatch(listOf(archive))[archive.relativePath]
            ?: BackendSyncResult.Failure(SyncError.Unknown("GitHub 同步没有返回归档结果"))

    override suspend fun syncBatch(archives: Collection<CloudArchive>): Map<String, BackendSyncResult> {
        val repository = repositoryProvider()
            ?: return archives.associate {
                it.relativePath to BackendSyncResult.Failure(SyncError.NotConfigured("GitHub 尚未完成连接"))
            }

        val ordered = archives.sortedWith(compareBy<CloudArchive> {
            if (it.relativePath.endsWith("/manifest.json")) 1 else 0
        }.thenBy { it.relativePath })
        val results = linkedMapOf<String, BackendSyncResult>()
        var segmentFailed = false
        ordered.forEach { archive ->
            if (archive.relativePath.endsWith("/manifest.json") && segmentFailed) {
                results[archive.relativePath] = BackendSyncResult.Failure(
                    SyncError.Network("segment upload failed; manifest deferred"),
                )
            } else {
                val result = syncOne(repository, archive)
                results[archive.relativePath] = result
                if (!archive.relativePath.endsWith("/manifest.json") && result is BackendSyncResult.Failure) {
                    segmentFailed = true
                }
            }
        }
        return results
    }

    private suspend fun syncOne(repository: GitHubRepository, archive: CloudArchive): BackendSyncResult {
        return try {
            val remote = api.getContentMetadata(repository, archive.relativePath)
            val remoteBytes = remote?.let { api.downloadContent(repository, archive.relativePath) }
            if (remote != null && remoteBytes?.contentEquals(archive.content) == true) {
                BackendSyncResult.Success(remoteReference = remote.sha, wasAlreadyPresent = true)
            } else {
                val uploaded = api.putContent(
                    repository = repository,
                    path = archive.relativePath,
                    content = archive.content,
                    message = archiveCommitMessage(archive.relativePath),
                    sha = remote?.sha,
                )
                BackendSyncResult.Success(remoteReference = uploaded.sha)
            }
        } catch (error: SyncHttpException) {
            BackendSyncResult.Failure(error.toSyncError())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            BackendSyncResult.Failure(SyncError.Network("GitHub 上传失败", error))
        }
    }

    private fun archiveCommitMessage(path: String): String =
        "chore(archive): 更新 ${path.substringAfterLast('/').substringBeforeLast('.')} 归档"
}

fun SyncHttpException.toSyncError(): SyncError = when {
    statusCode == 401 -> SyncError.Authentication("GitHub Token 已失效，请重新连接")
    rateLimited || statusCode == 429 -> SyncError.RateLimited("GitHub API 请求过于频繁")
    statusCode == 403 -> SyncError.Authorization("GitHub 权限不足或触发 API 限制")
    statusCode == 404 -> SyncError.RemoteConflict("GitHub 归档仓库或文件不存在")
    statusCode == 409 -> SyncError.RemoteConflict("GitHub 远端状态发生冲突")
    statusCode == 422 -> SyncError.InvalidArchive("GitHub 请求参数无效")
    statusCode >= 500 -> SyncError.ServiceUnavailable("GitHub 服务暂不可用")
    else -> SyncError.Unknown("GitHub HTTP $statusCode", this)
}

private fun SyncHttpException.userMessage(): String = when (statusCode) {
    401 -> "GitHub Token 无效或已撤销"
    403 -> "GitHub 账号验证成功，但当前 Token 无权访问或创建该仓库"
    404 -> "GitHub 目标仓库不存在，且当前 Token 无法创建仓库"
    422 -> "GitHub 仓库名称或请求参数无效"
    else -> "GitHub 连接失败，请稍后重试"
}
