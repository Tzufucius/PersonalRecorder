package io.github.tuzfucius.personalrecorder.sync

import java.security.MessageDigest

data class GitHubRepository(val owner: String, val name: String) {
    companion object {
        const val DEFAULT_NAME = "PersonalRecorder-Archive"

        fun defaultFor(owner: String): GitHubRepository = GitHubRepository(owner, DEFAULT_NAME)
    }
}

data class GitHubRepositoryAccess(val owner: String, val isPrivate: Boolean, val canPush: Boolean)

data class GitHubRepositoryDetails(
    val owner: String,
    val isPrivate: Boolean,
    val canPush: Boolean,
    val defaultBranch: String,
)

interface GitHubAccountApi {
    suspend fun authenticatedLogin(): String
}

interface GitHubRepositoryProvisioner {
    suspend fun findRepository(repository: GitHubRepository): GitHubRepositoryDetails?
    suspend fun createPrivateRepository(name: String): GitHubRepositoryDetails
}

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
            !access.owner.equals(login, ignoreCase = true) ->
                SyncError.Authorization("GitHub 仓库不属于当前账号")
            !access.isPrivate ->
                SyncError.Authorization("目标 GitHub 仓库不是私有仓库，已阻止同步")
            !access.canPush ->
                SyncError.Authorization("当前 GitHub 账号没有仓库写入权限")
            else -> null
        }
    }.getOrElse { error ->
        when (error) {
            is SyncHttpException -> when {
                error.statusCode == 401 -> SyncError.Authentication("GitHub 未授权")
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

data class GitHubHead(
    val commitSha: String,
    val treeSha: String,
    val ref: String = "heads/main",
)

data class GitHubBlob(val path: String, val sha: String)
data class GitHubTreeEntry(val path: String, val sha: String, val type: String = "blob")

sealed interface GitHubReferenceUpdate {
    data object Updated : GitHubReferenceUpdate
    data class Conflict(val message: String) : GitHubReferenceUpdate
}

/** Git Data API 边界；HTTP 鉴权和 token 存储由调用方实现，接口本身不承载 secret。 */
interface GitHubGitDataApi {
    suspend fun head(repository: GitHubRepository): GitHubHead
    suspend fun tree(repository: GitHubRepository, treeSha: String): Map<String, GitHubTreeEntry> = emptyMap()
    suspend fun createBlob(repository: GitHubRepository, content: ByteArray): String
    suspend fun createTree(repository: GitHubRepository, baseTreeSha: String, blobs: List<GitHubBlob>): String
    suspend fun createCommit(repository: GitHubRepository, parentCommitSha: String, treeSha: String, message: String): String
    suspend fun updateHead(
        repository: GitHubRepository,
        expectedCommitSha: String,
        newCommitSha: String,
        ref: String = "heads/main",
    ): GitHubReferenceUpdate
}

/** 通过 Git Data API 将同一批文件组合为一个逻辑 commit，并对远端内容做幂等保护。 */
class GitHubCloudSyncBackend(
    private val repositoryProvider: suspend () -> GitHubRepository?,
    private val guardProvider: (GitHubRepository) -> GitHubPrivateRepositoryGuard,
    private val gitDataApi: GitHubGitDataApi,
) : CloudSyncBackend {
    constructor(
        repository: GitHubRepository,
        guard: GitHubPrivateRepositoryGuard,
        gitDataApi: GitHubGitDataApi,
    ) : this({ repository }, { guard }, gitDataApi)

    override val type = CloudBackendType.GITHUB

    override suspend fun sync(archive: CloudArchive): BackendSyncResult =
        syncBatch(listOf(archive))[archive.relativePath]
            ?: BackendSyncResult.Failure(SyncError.Unknown("GitHub 同步没有返回归档结果"))

    override suspend fun syncBatch(archives: Collection<CloudArchive>): Map<String, BackendSyncResult> {
        if (archives.isEmpty()) return emptyMap()
        val repository = repositoryProvider()
            ?: return archives.associate { it.relativePath to BackendSyncResult.Failure(SyncError.NotConfigured("GitHub 尚未完成连接")) }
        guardProvider(repository).validate(repository)?.let { error ->
            return archives.associate { it.relativePath to BackendSyncResult.Failure(error) }
        }

        return runCatching {
            val head = gitDataApi.head(repository)
            val remoteEntries = gitDataApi.tree(repository, head.treeSha)
            val alreadyPresent = mutableMapOf<String, BackendSyncResult.Success>()
            val conflicts = mutableMapOf<String, BackendSyncResult.Failure>()
            val newArchives = mutableListOf<CloudArchive>()

            archives.sortedBy { it.relativePath }.forEach { archive ->
                val remote = remoteEntries[archive.relativePath]
                when {
                    remote == null -> newArchives += archive
                    remote.type != "blob" -> conflicts[archive.relativePath] = BackendSyncResult.Failure(
                        SyncError.RemoteConflict("GitHub 远端路径不是文件: ${archive.relativePath}")
                    )
                    gitBlobSha(archive.content) == remote.sha -> alreadyPresent[archive.relativePath] =
                        BackendSyncResult.Success(remoteReference = remote.sha, wasAlreadyPresent = true)
                    else -> conflicts[archive.relativePath] = BackendSyncResult.Failure(
                        SyncError.RemoteConflict("GitHub 中同路径文件内容与本地归档不一致")
                    )
                }
            }

            if (newArchives.isEmpty()) {
                return@runCatching archives.associate { archive ->
                    archive.relativePath to (alreadyPresent[archive.relativePath]
                        ?: conflicts[archive.relativePath]
                        ?: BackendSyncResult.Failure(SyncError.Unknown("GitHub 归档结果缺失")))
                }
            }

            val blobs = newArchives.map { archive ->
                GitHubBlob(archive.relativePath, gitDataApi.createBlob(repository, archive.content))
            }
            val tree = gitDataApi.createTree(repository, head.treeSha, blobs)
            val commit = gitDataApi.createCommit(
                repository = repository,
                parentCommitSha = head.commitSha,
                treeSha = tree,
                message = archiveCommitMessage(archives),
            )
            when (val update = gitDataApi.updateHead(repository, head.commitSha, commit, head.ref)) {
                GitHubReferenceUpdate.Updated -> archives.associate { archive ->
                    archive.relativePath to when {
                        alreadyPresent.containsKey(archive.relativePath) -> alreadyPresent.getValue(archive.relativePath)
                        conflicts.containsKey(archive.relativePath) -> conflicts.getValue(archive.relativePath)
                        else -> BackendSyncResult.Success(remoteReference = commit)
                    }
                }
                is GitHubReferenceUpdate.Conflict -> archives.associate { archive ->
                    archive.relativePath to (alreadyPresent[archive.relativePath]
                        ?: conflicts[archive.relativePath]
                        ?: BackendSyncResult.Failure(SyncError.RemoteConflict(update.message)))
                }
            }
        }.getOrElse { error ->
            val syncError = when (error) {
                is SyncHttpException -> when {
                    error.statusCode == 401 -> SyncError.Authentication("GitHub 未授权")
                    error.statusCode == 403 -> SyncError.Authorization("GitHub API 拒绝访问")
                    error.statusCode == 404 -> SyncError.Authorization("GitHub 归档仓库不存在")
                    error.statusCode == 409 -> SyncError.RemoteConflict("GitHub 远端引用已变化")
                    error.statusCode == 429 -> SyncError.RateLimited("GitHub API 请求过于频繁")
                    error.statusCode >= 500 -> SyncError.ServiceUnavailable("GitHub 服务暂不可用")
                    else -> SyncError.Unknown("GitHub 上传失败", error)
                }
                else -> SyncError.Network("GitHub 上传失败", error)
            }
            archives.associate { it.relativePath to BackendSyncResult.Failure(syncError) }
        }
    }

    private fun archiveCommitMessage(archives: Collection<CloudArchive>): String {
        val dates = archives.mapNotNull { ARCHIVE_PATH.find(it.relativePath)?.groupValues?.get(1) }.toSet()
        if (dates.size > 1) return "archive: 批量同步通知归档"
        val date = dates.singleOrNull() ?: return "archive: 同步通知归档"
        val slots = archives.mapNotNull { ARCHIVE_PATH.find(it.relativePath)?.groupValues?.get(2) }
            .map { it.substringBeforeLast('.') }
            .toSet()
        return if (archives.size == 1 && slots.size == 1 && slots.single() != "manifest") {
            "archive: 同步 $date ${slots.single()} 通知归档"
        } else {
            "archive: 同步 $date 通知归档"
        }
    }

    private companion object {
        val ARCHIVE_PATH = Regex("archive/\\d{4}/\\d{2}/(\\d{4}-\\d{2}-\\d{2})/([^/]+)$")

        fun gitBlobSha(content: ByteArray): String {
            val header = "blob ${content.size}\u0000".toByteArray(Charsets.UTF_8)
            return MessageDigest.getInstance("SHA-1")
                .digest(header + content)
                .joinToString("") { "%02x".format(it) }
        }
    }
}
