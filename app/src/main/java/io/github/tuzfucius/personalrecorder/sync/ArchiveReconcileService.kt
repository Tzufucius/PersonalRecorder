package io.github.tuzfucius.personalrecorder.sync

import android.util.Log
import io.github.tuzfucius.personalrecorder.archive.ArchiveManifest
import io.github.tuzfucius.personalrecorder.archive.ArchiveManifestSegment
import io.github.tuzfucius.personalrecorder.archive.ArchiveSegmentType
import io.github.tuzfucius.personalrecorder.archive.ArchiveWriter
import io.github.tuzfucius.personalrecorder.data.AppDatabase
import io.github.tuzfucius.personalrecorder.data.ArchiveConflictEntity
import io.github.tuzfucius.personalrecorder.data.ArchiveSyncStateEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

data class ReconcileReport(
    val mode: ReconcileMode,
    val discoveredRemote: Int,
    val downloaded: Int,
    val uploaded: Int,
    val skipped: Int,
    val conflicts: Int,
    val results: List<ArchiveSyncResult>,
    val restoreState: RestoreState = RestoreState.COMPLETED,
) {
    val needsRetry: Boolean
        get() = results.any { it.error?.retryable == true }

    val isSuccessful: Boolean
        get() = results.all { it.error == null && it.isSuccessful }
}

/** Coordinates pull-before-push reconciliation for the GitHub archive hub. */
class ArchiveReconcileService(
    private val filesDir: File,
    private val database: AppDatabase,
    private val api: GitHubArchiveApi,
    private val repositoryProvider: suspend () -> GitHubRepository?,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val deviceInstanceIdProvider: suspend () -> String? = { null },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val json: Json = ArchiveWriter.archiveJson,
    private val reconciler: ArchiveReconciler = ArchiveReconciler(json),
) {
    private val localScanner = LocalArchiveInventoryScanner(filesDir)
    private val importer = ArchiveImportService(database, reconciler)

    suspend fun discoverRemote(mode: ReconcileMode = ReconcileMode.FULL_RESTORE): RemoteArchiveInventory? {
        val repository = repositoryProvider() ?: return null
        val localDates = localScanner.scan().descriptors.map { it.date }.toSet()
        return RemoteArchiveInventoryScanner(
            api = api,
            repository = repository,
            zoneId = zoneId,
            nowMillis = nowMillis,
        ).discover(mode, localDates)
    }

    suspend fun reconcile(mode: ReconcileMode = ReconcileMode.INCREMENTAL): ReconcileReport {
        var attempt = 0
        while (true) {
            try {
                return reconcileOnce(mode)
            } catch (error: SyncHttpException) {
                if (error.statusCode == 409 && attempt++ == 0) {
                    log("Remote changed during PUT; retrying reconciliation once")
                    continue
                }
                val mapped = error.toSyncError()
                return ReconcileReport(
                    mode = mode,
                    discoveredRemote = 0,
                    downloaded = 0,
                    uploaded = 0,
                    skipped = 0,
                    conflicts = 0,
                    results = listOf(
                        ArchiveSyncResult(
                            archive = placeholderArchive(),
                            backend = CloudBackendType.GITHUB,
                            status = ArchiveSyncStatus.FAILED,
                            attempts = attempt.coerceAtLeast(1),
                            error = mapped,
                        )
                    ),
                    restoreState = RestoreState.FAILED,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                return ReconcileReport(
                    mode = mode,
                    discoveredRemote = 0,
                    downloaded = 0,
                    uploaded = 0,
                    skipped = 0,
                    conflicts = 0,
                    results = listOf(
                        ArchiveSyncResult(
                            archive = placeholderArchive(),
                            backend = CloudBackendType.GITHUB,
                            status = ArchiveSyncStatus.FAILED,
                            attempts = attempt.coerceAtLeast(1),
                            error = SyncError.Network("归档协调失败", error),
                        )
                    ),
                    restoreState = RestoreState.FAILED,
                )
            }
        }
    }

    private suspend fun reconcileOnce(mode: ReconcileMode): ReconcileReport {
        val repository = repositoryProvider()
        if (repository == null) {
            return ReconcileReport(
                mode = mode,
                discoveredRemote = 0,
                downloaded = 0,
                uploaded = 0,
                skipped = 0,
                conflicts = 0,
                results = emptyList(),
                restoreState = RestoreState.FAILED,
            )
        }

        val localInventory = localScanner.scan()
        val localDates = localInventory.descriptors.map { it.date }.toSet()
        val remoteRaw = RemoteArchiveInventoryScanner(
            api = api,
            repository = repository,
            zoneId = zoneId,
            nowMillis = nowMillis,
        ).discover(mode, localDates)
        val remoteContents = linkedMapOf<String, ByteArray>()
        val remoteDescriptors = mutableListOf<ArchiveDescriptor>()
        val results = mutableListOf<ArchiveSyncResult>()
        var downloaded = 0

        remoteRaw.descriptors.forEach { descriptor ->
            val content = api.downloadContent(repository, descriptor.relativePath)?.content
                ?: throw SyncHttpException(422, "GitHub 归档缺少内容: ${descriptor.relativePath}")
            val sha256 = ArchiveFileStore.sha256(content)
            remoteContents[descriptor.relativePath] = content
            remoteDescriptors += descriptor.copy(sha256 = sha256, size = content.size.toLong())
            downloaded++
        }

        val localByPath = localInventory.descriptors.associateBy { it.relativePath }
        val remoteByPath = remoteDescriptors.associateBy { it.relativePath }
        val segmentPaths = (localByPath.keys + remoteByPath.keys)
            .filterNot { it.endsWith("/manifest.json") }
            .sorted()
        var uploaded = 0
        var skipped = 0
        var conflicts = 0

        segmentPaths.forEach { path ->
            val local = localByPath[path]
            val remote = remoteByPath[path]
            val localBytes = local?.let { readLocal(it) }
            val remoteBytes = remote?.let { remoteContents[it.relativePath] }
            val pair = reconciler.pair(local, remote)
            when (pair.state) {
                ArchivePairState.LOCAL_ONLY -> {
                    val bytes = requireNotNull(localBytes)
                    val result = try {
                        reconciler.parseJsonl(bytes)
                        upload(repository, local, bytes, null)
                    } catch (error: SyncHttpException) {
                        if (error.statusCode == 409) throw error
                        failedResult(local, error.toSyncError())
                    } catch (error: IllegalArgumentException) {
                        failedResult(local, SyncError.InvalidArchive("本地 JSONL 校验失败: ${local.relativePath}"))
                    }
                    persistState(local.segmentId, result)
                    results += result
                    if (result.isSuccessful) uploaded++
                }
                ArchivePairState.REMOTE_ONLY -> {
                    val descriptor = requireNotNull(remote)
                    val bytes = requireNotNull(remoteBytes)
                    installRemoteSegment(descriptor, bytes)
                    val result = syncedResult(descriptor, descriptor.remoteSha, wasAlreadyPresent = false)
                    persistState(descriptor.segmentId, result)
                    results += result
                }
                ArchivePairState.BOTH_IDENTICAL -> {
                    val descriptor = requireNotNull(local)
                    val bytes = requireNotNull(localBytes)
                    importer.importAndRegisterSegment(descriptor, bytes, zoneId, nowMillis())
                    val result = syncedResult(descriptor, remote?.remoteSha, wasAlreadyPresent = true)
                    persistState(descriptor.segmentId, result)
                    results += result
                    skipped++
                }
                ArchivePairState.BOTH_DIFFERENT -> {
                    val localDescriptor = requireNotNull(local)
                    val remoteDescriptor = requireNotNull(remote)
                    val merge = reconciler.merge(
                        requireNotNull(localBytes),
                        requireNotNull(remoteBytes),
                    )
                    val mergedBytes = reconciler.encodeJsonl(merge.events)
                    ArchiveFileStore.atomicWrite(filesDir, path, mergedBytes, ArchiveFileStore.sha256(mergedBytes))
                    val mergedDescriptor = localDescriptor.copy(
                        sha256 = ArchiveFileStore.sha256(mergedBytes),
                        size = mergedBytes.size.toLong(),
                    )
                    val importResult = importer.importAndRegisterSegment(
                        mergedDescriptor,
                        mergedBytes,
                        zoneId,
                        nowMillis(),
                    )
                    if (merge.conflicts.isNotEmpty() || importResult.conflictingEventIds.isNotEmpty()) {
                        conflicts += merge.conflicts.size + importResult.conflictingEventIds.size
                        persistConflict(
                            localDescriptor,
                            requireNotNull(localBytes),
                            requireNotNull(remoteBytes),
                            merge.conflicts.size + importResult.conflictingEventIds.size,
                        )
                    }
                    val result = upload(repository, mergedDescriptor, mergedBytes, remoteDescriptor.remoteSha)
                    val finalResult = if (merge.conflicts.isNotEmpty() || importResult.conflictingEventIds.isNotEmpty()) {
                        result.copy(
                            status = ArchiveSyncStatus.CONFLICT,
                            error = SyncError.RemoteConflict("归档包含同 ID 内容冲突，已保留双方数据"),
                        )
                    } else result
                    persistState(mergedDescriptor.segmentId, finalResult)
                    results += finalResult
                    if (result.isSuccessful) uploaded++
                }
            }
        }

        val manifestResults = reconcileManifests(
            repository = repository,
            localInventory = localScanner.scan(),
            remoteDescriptors = remoteDescriptors.filter { it.isManifest }.associateBy { it.relativePath },
            remoteContents = remoteContents,
        )
        results += manifestResults.results
        uploaded += manifestResults.uploaded
        skipped += manifestResults.skipped
        return ReconcileReport(
            mode = mode,
            discoveredRemote = remoteDescriptors.size,
            downloaded = downloaded,
            uploaded = uploaded,
            skipped = skipped,
            conflicts = conflicts,
            results = results,
        )
    }

    private suspend fun reconcileManifests(
        repository: GitHubRepository,
        localInventory: LocalArchiveInventory,
        remoteDescriptors: Map<String, ArchiveDescriptor>,
        remoteContents: Map<String, ByteArray>,
    ): ManifestReport {
        val deviceId = deviceInstanceIdProvider()
        val localSegments = localInventory.descriptors.filterNot { it.isManifest }.groupBy { it.date }
        val results = mutableListOf<ArchiveSyncResult>()
        var uploaded = 0
        var skipped = 0
        localSegments.keys.sorted().forEach { date ->
            val segments = localSegments[date].orEmpty()
            if (segments.map { it.slot }.toSet() != setOf(
                    ArchiveSegmentType.FIRST_HALF.name,
                    ArchiveSegmentType.SECOND_HALF.name,
                )) return@forEach
            val manifestPath = "archive/${date.substring(0, 4)}/${date.substring(5, 7)}/$date/manifest.json"
            val manifestBytes = buildManifest(date, segments, deviceId)
            ArchiveFileStore.atomicWrite(filesDir, manifestPath, manifestBytes)
            val localDescriptor = ArchivePathDescriptor.fromPath(
                manifestPath,
                ArchiveFileStore.sha256(manifestBytes),
                manifestBytes.size.toLong(),
            ) ?: return@forEach
            val remoteDescriptor = remoteDescriptors[manifestPath]
            val remoteBytes = remoteDescriptor?.let { remoteContents[it.relativePath] }
            val result = if (remoteDescriptor != null && remoteBytes != null &&
                ArchiveFileStore.sha256(manifestBytes).equals(remoteDescriptor.sha256, ignoreCase = true)
            ) {
                skipped++
                syncedResult(localDescriptor, remoteDescriptor.remoteSha, wasAlreadyPresent = true)
            } else {
                val uploadedResult = upload(repository, localDescriptor, manifestBytes, remoteDescriptor?.remoteSha)
                if (uploadedResult.isSuccessful) uploaded++
                uploadedResult
            }
            persistState(localDescriptor.segmentId, result)
            results += result
        }
        return ManifestReport(results, uploaded, skipped)
    }

    private fun buildManifest(date: String, segments: List<ArchiveDescriptor>, deviceId: String?): ByteArray {
        val body = ArchiveManifest(
            schemaVersion = if (deviceId.isNullOrBlank()) 1 else 2,
            date = date,
            timeZone = zoneId.id,
            segments = segments.sortedBy { it.slot }.map { descriptor ->
                ArchiveManifestSegment(
                    fileName = descriptor.relativePath.substringAfterLast('/'),
                    eventCount = runCatching {
                        reconciler.parseJsonl(ArchiveFileStore.file(filesDir, descriptor.relativePath).readBytes()).size
                    }.getOrDefault(descriptor.size.toInt()),
                    sha256 = descriptor.sha256,
                )
            },
            totalEventCount = segments.sumOf { descriptor ->
                runCatching {
                    reconciler.parseJsonl(ArchiveFileStore.file(filesDir, descriptor.relativePath).readBytes()).size
                }.getOrDefault(descriptor.size.toInt())
            },
            sourceDeviceIds = deviceId?.let(::listOf).orEmpty(),
            lastWriterDeviceId = deviceId,
        )
        return (json.encodeToString(body) + "\n").toByteArray(Charsets.UTF_8)
    }

    private suspend fun installRemoteSegment(descriptor: ArchiveDescriptor, bytes: ByteArray) {
        reconciler.parseJsonl(bytes)
        ArchiveFileStore.atomicWrite(filesDir, descriptor.relativePath, bytes, descriptor.sha256)
        val importResult = importer.importAndRegisterSegment(descriptor, bytes, zoneId, nowMillis())
        if (importResult.conflictingEventIds.isNotEmpty()) {
            persistConflict(descriptor, bytes, bytes, importResult.conflictingEventIds.size)
        }
    }

    private suspend fun upload(
        repository: GitHubRepository,
        descriptor: ArchiveDescriptor,
        bytes: ByteArray,
        remoteSha: String?,
    ): ArchiveSyncResult {
        return try {
            val uploaded = api.putContent(
                repository = repository,
                path = descriptor.relativePath,
                content = bytes,
                message = "chore(archive): 更新 ${descriptor.relativePath.substringAfterLast('/').substringBeforeLast('.')}",
                sha = remoteSha,
            )
            syncedResult(descriptor, uploaded.sha, wasAlreadyPresent = false)
        } catch (error: SyncHttpException) {
            if (error.statusCode == 409) throw error
            failedResult(descriptor, error.toSyncError())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            failedResult(descriptor, SyncError.Network("GitHub 归档上传失败", error))
        }
    }

    private suspend fun persistState(segmentId: String, result: ArchiveSyncResult) {
        val status = when {
            result.status == ArchiveSyncStatus.CONFLICT -> ArchiveSyncStateEntity.Status.CONFLICT
            result.status == ArchiveSyncStatus.SYNCED -> ArchiveSyncStateEntity.Status.SYNCED
            result.error?.retryable == true -> ArchiveSyncStateEntity.Status.PENDING_UPLOAD
            else -> ArchiveSyncStateEntity.Status.FAILED
        }
        database.eventDao().upsertArchiveSyncState(
            ArchiveSyncStateEntity(
                segmentId = segmentId,
                backend = CloudBackendType.GITHUB.name,
                status = status,
                attempts = result.attempts,
                lastAttemptAt = nowMillis(),
                lastError = result.error?.message,
                remoteId = result.remoteReference,
                updatedAt = nowMillis(),
            )
        )
    }

    private suspend fun persistConflict(
        descriptor: ArchiveDescriptor,
        localBytes: ByteArray,
        remoteBytes: ByteArray,
        count: Int,
    ) {
        val conflictHash = ArchiveFileStore.sha256(localBytes + remoteBytes)
        val conflictId = "${descriptor.segmentId}-${conflictHash.take(16)}"
        val localPath = "archive_conflicts/${descriptor.segmentId}/local.jsonl"
        val remotePath = "archive_conflicts/${descriptor.segmentId}/remote.jsonl"
        ArchiveFileStore.atomicWrite(filesDir, localPath, localBytes)
        ArchiveFileStore.atomicWrite(filesDir, remotePath, remoteBytes)
        database.eventDao().upsertArchiveConflict(
            ArchiveConflictEntity(
                conflictId = conflictId,
                segmentId = descriptor.segmentId,
                relativePath = descriptor.relativePath,
                localFilePath = localPath,
                remoteFilePath = remotePath,
                summary = "发现 $count 个事件内容冲突，已保留本地与远端原始归档",
                createdAt = nowMillis(),
            )
        )
    }

    private fun readLocal(descriptor: ArchiveDescriptor): ByteArray {
        val bytes = ArchiveFileStore.file(filesDir, descriptor.relativePath).readBytes()
        require(ArchiveFileStore.sha256(bytes).equals(descriptor.sha256, ignoreCase = true)) {
            "本地归档 SHA-256 校验失败: ${descriptor.relativePath}"
        }
        return bytes
    }

    private fun syncedResult(
        descriptor: ArchiveDescriptor,
        remoteReference: String?,
        wasAlreadyPresent: Boolean,
    ) = ArchiveSyncResult(
        archive = CloudArchive(descriptor.relativePath, descriptor.sha256, ByteArray(descriptor.size.toInt())),
        backend = CloudBackendType.GITHUB,
        status = ArchiveSyncStatus.SYNCED,
        attempts = 1,
        remoteReference = remoteReference,
        wasAlreadyPresent = wasAlreadyPresent,
    )

    private fun failedResult(descriptor: ArchiveDescriptor, error: SyncError) = ArchiveSyncResult(
        archive = CloudArchive(descriptor.relativePath, descriptor.sha256.ifBlank { ArchiveFileStore.sha256(ByteArray(0)) }, ByteArray(0)),
        backend = CloudBackendType.GITHUB,
        status = ArchiveSyncStatus.FAILED,
        attempts = 1,
        error = error,
    )

    private fun placeholderArchive() = CloudArchive(
        relativePath = "archive/reconcile-error.jsonl",
        sha256 = ArchiveFileStore.sha256(ByteArray(0)),
        content = ByteArray(0),
    )

    private fun log(message: String) {
        runCatching { Log.i("PR-Sync", message) }
    }

    private data class ManifestReport(
        val results: List<ArchiveSyncResult>,
        val uploaded: Int,
        val skipped: Int,
    )
}
