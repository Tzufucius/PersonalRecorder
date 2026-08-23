package io.github.tuzfucius.personalrecorder.sync

import android.content.Context
import io.github.tuzfucius.personalrecorder.archive.ArchivePlanner
import io.github.tuzfucius.personalrecorder.archive.ArchiveService
import io.github.tuzfucius.personalrecorder.archive.ArchiveWriter
import io.github.tuzfucius.personalrecorder.data.AppDatabase
import io.github.tuzfucius.personalrecorder.data.ArchiveSegmentEntity
import io.github.tuzfucius.personalrecorder.data.ArchiveSyncStateEntity
import io.github.tuzfucius.personalrecorder.settings.CloudSyncSettings
import io.github.tuzfucius.personalrecorder.settings.CloudSyncSettingsState
import io.github.tuzfucius.personalrecorder.settings.CloudSyncSettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest

data class PendingArchiveUpload(
    val stateId: String,
    val archive: CloudArchive,
)

/** Bridges Room/archive generation and the backend coordinator. */
class ArchiveSyncRunner(
    context: Context,
    private val backends: Collection<CloudSyncBackend> = emptyList(),
    database: AppDatabase? = null,
    writer: ArchiveWriter? = null,
    settingsStore: CloudSyncSettingsStore? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : CloudSyncWorkRunner {
    private val appContext = context.applicationContext
    private val database = database ?: AppDatabase.getInstance(appContext)
    private val writer = writer ?: ArchiveWriter(appContext.filesDir)
    private val archiveService = ArchiveService(this.database, this.writer)
    private val settings = settingsStore ?: CloudSyncSettingsStore(appContext)
    private val syncMutex = Mutex()

    override suspend fun runSync(): SyncBatchResult = runSync(force = false)

    override suspend fun runSync(force: Boolean): SyncBatchResult = syncMutex.withLock {
        finalizeClosedArchives()
        val settings = (this@ArchiveSyncRunner.settings.state.first() as? CloudSyncSettingsState.Ready)
            ?.settings ?: return@withLock SyncBatchResult(emptyList())
        val enabled = buildSet {
            if (settings.githubEnabled) add(CloudBackendType.GITHUB)
        }
        if (enabled.isEmpty()) return@withLock SyncBatchResult(emptyList())

        val coordinator = SyncCoordinator(backends)
        val results = mutableListOf<ArchiveSyncResult>()
        enabled.sortedBy { it.name }.forEach { backendType ->
            val pending = collectPendingUploads(backendType, force)
            results += pending.failures
            if (pending.uploads.isEmpty()) return@forEach

            val connected = settings.githubConnected
            if (!connected) {
                return@forEach
            }

            val batchResult = coordinator.syncBatch(
                archives = pending.uploads.map { it.archive },
                backendTypes = setOf(backendType),
            )
            val stateByPath = pending.uploads.associateBy { it.archive.relativePath }
            batchResult.results.forEach { result ->
                stateByPath[result.archive.relativePath]?.let { upload ->
                    persistState(upload.stateId, backendType, result)
                }
                if (result.error is SyncError.Authentication) {
                    this@ArchiveSyncRunner.settings.setGithubConnected(false)
                }
            }
            results += batchResult.results
        }
        SyncBatchResult(results)
    }

    private suspend fun collectPendingUploads(
        backendType: CloudBackendType,
        force: Boolean,
    ): PendingUploads {
        val dao = database.eventDao()
        val uploads = mutableListOf<PendingArchiveUpload>()
        val failures = mutableListOf<ArchiveSyncResult>()
        val segments = dao.getPendingArchiveSegmentsForSync(backendType.name, force)
        segments.forEach { segment ->
            readUpload(segment.segmentId, segment.relativePath, segment.sha256)?.let { upload ->
                uploads += upload
            } ?: run {
                val result = invalidArchiveResult(
                    stateId = segment.segmentId,
                    backendType = backendType,
                    relativePath = segment.relativePath,
                    sha256 = segment.sha256,
                )
                persistState(segment.segmentId, backendType, result)
                failures += result
            }
        }
        pendingManifests(backendType, force).forEach { manifest ->
            readUpload(manifest.stateId, manifest.relativePath, manifest.sha256)?.let { upload ->
                uploads += upload
            } ?: run {
                val result = invalidArchiveResult(
                    stateId = manifest.stateId,
                    backendType = backendType,
                    relativePath = manifest.relativePath,
                    sha256 = manifest.sha256,
                )
                persistState(manifest.stateId, backendType, result)
                failures += result
            }
        }
        return PendingUploads(uploads, failures)
    }

    private suspend fun readUpload(
        stateId: String,
        relativePath: String,
        expectedSha256: String,
    ): PendingArchiveUpload? {
        val file = File(appContext.filesDir, relativePath)
        if (!file.isFile) return null
        val bytes = file.readBytes()
        if (!sha256(bytes).equals(expectedSha256, ignoreCase = true)) return null
        return PendingArchiveUpload(stateId, CloudArchive(relativePath, expectedSha256, bytes))
    }

    private fun invalidArchiveResult(
        stateId: String,
        backendType: CloudBackendType,
        relativePath: String,
        sha256: String,
    ): ArchiveSyncResult {
        val file = File(appContext.filesDir, relativePath)
        val error = if (!file.isFile) {
            SyncError.InvalidArchive("本地归档文件不存在: $relativePath")
        } else {
            SyncError.InvalidArchive("本地归档 SHA-256 校验失败: $relativePath")
        }
        return ArchiveSyncResult(
            archive = CloudArchive(relativePath, sha256, if (file.isFile) file.readBytes() else ByteArray(0)),
            backend = backendType,
            status = ArchiveSyncStatus.FAILED,
            attempts = 0,
            error = error,
        )
    }

    private suspend fun persistState(
        stateId: String,
        backendType: CloudBackendType,
        result: ArchiveSyncResult,
    ) {
        val persistedStatus = when {
            result.status == ArchiveSyncStatus.SYNCED -> ArchiveSyncStateEntity.Status.SYNCED
            result.error?.retryable == true -> ArchiveSyncStateEntity.Status.PENDING
            else -> ArchiveSyncStateEntity.Status.FAILED
        }
        val now = nowMillis()
        database.eventDao().upsertArchiveSyncState(
            ArchiveSyncStateEntity(
                segmentId = stateId,
                backend = backendType.name,
                status = persistedStatus,
                attempts = result.attempts,
                lastAttemptAt = now,
                lastError = result.error?.message,
                remoteId = result.remoteReference,
                updatedAt = now,
            )
        )
    }

    private suspend fun finalizeClosedArchives() {
        val dao = database.eventDao()
        val bounds = dao.getEventTimestampBounds()
        val planner = ArchivePlanner(writer.zoneId)
        val missingDates = planner.missingClosedDates(
            minTimestamp = bounds.minTimestamp,
            nowMillis = nowMillis(),
            existingSegmentIds = dao.getArchivedSegmentIds().toSet(),
        )
        missingDates.forEach { archiveService.archiveDay(it) }
    }

    private suspend fun pendingManifests(backendType: CloudBackendType, force: Boolean): List<ManifestUpload> {
        val segments = database.eventDao().getArchiveSegments().first()
        return segments.groupBy { it.date }.mapNotNull { (date, daySegments) ->
            if (daySegments.map { it.slot }.toSet().size < 2) return@mapNotNull null
            val normalizedPath = "archive/${date.substring(0, 4)}/${date.substring(5, 7)}/$date/manifest.json"
            val file = File(appContext.filesDir, normalizedPath)
            if (!file.isFile) return@mapNotNull null
            val stateId = "$date-MANIFEST"
            val state = database.eventDao().getArchiveSyncState(stateId, backendType.name)
            if (!force && state?.status == ArchiveSyncStateEntity.Status.FAILED) return@mapNotNull null
            if (state?.status == ArchiveSyncStateEntity.Status.SYNCED) return@mapNotNull null
            ManifestUpload(stateId, normalizedPath, sha256(file))
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private data class PendingUploads(
        val uploads: List<PendingArchiveUpload>,
        val failures: List<ArchiveSyncResult>,
    )

    private data class ManifestUpload(
        val stateId: String,
        val relativePath: String,
        val sha256: String,
    )
}

object CloudSyncRuntime {
    @Volatile
    private var runner: ArchiveSyncRunner? = null

    fun configure(context: Context, backends: Collection<CloudSyncBackend> = defaultBackends(context)) {
        val configured = ArchiveSyncRunner(context, backends)
        runner = configured
        CloudSyncWorker.configure(configured)
    }

    fun ensureConfigured(context: Context): ArchiveSyncRunner = synchronized(this) {
        runner ?: ArchiveSyncRunner(context, defaultBackends(context)).also {
            runner = it
            CloudSyncWorker.configure(it)
        }
    }

    suspend fun syncNow(context: Context): SyncBatchResult = ensureConfigured(context).runSync(force = true)

    fun scheduler(context: Context): SyncScheduler = WorkManagerSyncScheduler(context)

    private fun defaultBackends(context: Context): List<CloudSyncBackend> {
        val appContext = context.applicationContext
        val secrets = SecureSecretStore(appContext)
        val settings = CloudSyncSettingsStore(appContext)
        val githubApi = GitHubArchiveClient(
            tokenProvider = GitHubAccessTokenProvider {
                secrets.get(CloudCredentialStore.GITHUB_ACCESS_TOKEN)
            }
        )
        val github = GitHubCloudSyncBackend(
            repositoryProvider = {
                val state = settings.state.first() as? CloudSyncSettingsState.Ready
                state?.settings?.let { current ->
                    current.githubUsername?.let { owner -> GitHubRepository(owner, current.githubRepository) }
                }
            },
            api = githubApi,
        )
        return listOf(github)
    }
}
