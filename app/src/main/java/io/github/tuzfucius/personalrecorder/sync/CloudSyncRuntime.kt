package io.github.tuzfucius.personalrecorder.sync

import android.content.Context
import io.github.tuzfucius.personalrecorder.archive.ArchiveService
import io.github.tuzfucius.personalrecorder.archive.ArchiveWriter
import io.github.tuzfucius.personalrecorder.data.AppDatabase
import io.github.tuzfucius.personalrecorder.data.ArchiveSegmentEntity
import io.github.tuzfucius.personalrecorder.data.ArchiveSyncStateEntity
import io.github.tuzfucius.personalrecorder.settings.CloudSyncSettingsStore
import kotlinx.coroutines.flow.first
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Bridges Room/archive generation and the backend coordinator. Backends are injected by
 * the host so credentials never become a concern of the Worker or Compose UI.
 */
class ArchiveSyncRunner(
    context: Context,
    private val backends: Collection<CloudSyncBackend> = emptyList(),
) : CloudSyncWorkRunner {
    private val appContext = context.applicationContext
    private val database = AppDatabase.getInstance(appContext)
    private val writer = ArchiveWriter(appContext.filesDir)
    private val archiveService = ArchiveService(database, writer)
    private val settings = CloudSyncSettingsStore(appContext)

    override suspend fun runSync(): SyncBatchResult {
        finalizeClosedArchives()
        val enabled = settings.state.first().let { state ->
            val value = (state as? io.github.tuzfucius.personalrecorder.settings.CloudSyncSettingsState.Ready)
                ?.settings ?: return SyncBatchResult(emptyList())
            buildSet {
                if (value.githubEnabled) add(CloudBackendType.GITHUB)
                if (value.googleDriveEnabled) add(CloudBackendType.GOOGLE_DRIVE)
            }
        }
        if (enabled.isEmpty()) return SyncBatchResult(emptyList())

        val coordinator = SyncCoordinator(backends)
        val results = mutableListOf<ArchiveSyncResult>()
        for (backendType in enabled) {
            val backendName = backendType.name
            val pending = database.eventDao().getPendingArchiveSegments(backendName).first()
            for (segment in pending) {
                val result = syncFile(coordinator, segment.segmentId, backendType, segment.relativePath, segment.sha256)
                results += result
            }
            for (manifest in pendingManifests(backendType)) {
                val result = syncFile(coordinator, manifest.segmentId, backendType, manifest.relativePath, manifest.sha256)
                results += result
            }
        }
        return SyncBatchResult(results)
    }

    private suspend fun syncFile(
        coordinator: SyncCoordinator,
        stateId: String,
        backendType: CloudBackendType,
        relativePath: String,
        sha256: String,
    ): ArchiveSyncResult {
        val file = File(appContext.filesDir, relativePath)
        if (!file.isFile) {
            val archive = CloudArchive(relativePath, sha256, ByteArray(0))
            val result = ArchiveSyncResult(
                archive,
                backendType,
                ArchiveSyncStatus.FAILED,
                attempts = 0,
                error = SyncError.InvalidArchive("本地归档文件不存在: $relativePath"),
            )
            persistState(stateId, backendType, result)
            return result
        }
        val bytes = file.readBytes()
        if (!sha256(bytes).equals(sha256, ignoreCase = true)) {
            val invalidArchive = CloudArchive(relativePath, sha256, bytes)
            val result = ArchiveSyncResult(
                invalidArchive,
                backendType,
                ArchiveSyncStatus.FAILED,
                attempts = 0,
                error = SyncError.InvalidArchive("本地归档 SHA-256 校验失败: $relativePath"),
            )
            persistState(stateId, backendType, result)
            return result
        }
        val archive = CloudArchive(relativePath, sha256, bytes)
        val result = coordinator.syncBatch(listOf(archive), setOf(backendType)).results.first()
        persistState(stateId, backendType, result)
        return result
    }

    private suspend fun persistState(
        stateId: String,
        backendType: CloudBackendType,
        result: ArchiveSyncResult,
    ) {
        database.eventDao().upsertArchiveSyncState(
            ArchiveSyncStateEntity(
                segmentId = stateId,
                backend = backendType.name,
                status = result.status.name,
                attempts = result.attempts,
                lastAttemptAt = System.currentTimeMillis(),
                lastError = result.error?.message,
                remoteId = result.remoteReference,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    private suspend fun finalizeClosedArchives() {
        val bounds = database.eventDao().getEventTimestampBounds()
        val minTimestamp = bounds.minTimestamp ?: return
        val zone = ZoneId.systemDefault()
        var date = Instant.ofEpochMilli(minTimestamp).atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)
        while (!date.isAfter(today)) {
            archiveService.archiveDay(date)
            date = date.plusDays(1)
        }
    }

    private suspend fun pendingManifests(backendType: CloudBackendType): List<ManifestUpload> {
        val segments = database.eventDao().getArchiveSegments().first()
        return segments.groupBy { it.date }.mapNotNull { (date, daySegments) ->
            if (daySegments.map { it.slot }.toSet().size < 2) return@mapNotNull null
            val normalizedPath = "archive/${date.substring(0, 4)}/${date.substring(5, 7)}/$date/manifest.json"
            val file = File(appContext.filesDir, normalizedPath)
            if (!file.isFile) return@mapNotNull null
            val segmentId = "$date-MANIFEST"
            val sha256 = sha256(file)
            val state = database.eventDao().getArchiveSyncState(segmentId, backendType.name)
            if (state?.status == ArchiveSyncStateEntity.Status.SYNCED) return@mapNotNull null
            ManifestUpload(segmentId, normalizedPath, sha256)
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

    private data class ManifestUpload(val segmentId: String, val relativePath: String, val sha256: String)
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

    suspend fun syncNow(context: Context): SyncBatchResult {
        return ensureConfigured(context).runSync()
    }

    fun scheduler(context: Context): SyncScheduler = WorkManagerSyncScheduler(context)

    private fun defaultBackends(context: Context): List<CloudSyncBackend> {
        val secrets = SecureSecretStore(context)
        val driveClient = OkHttpGoogleDriveRestClient(
            tokenProvider = AccessTokenProvider {
                secrets.get(CloudCredentialStore.GOOGLE_ACCESS_TOKEN)
            }
        )
        return listOf(
            GoogleDriveCloudSyncBackend(
                restClient = driveClient,
                folderResolver = GoogleDriveFolderResolver(
                    restClient = driveClient,
                    cache = SharedPreferencesGoogleDriveFolderIdCache(context)
                )
            )
        )
    }
}
