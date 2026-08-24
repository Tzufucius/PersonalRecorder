package io.github.tuzfucius.personalrecorder.sync

import android.util.Log
import io.github.tuzfucius.personalrecorder.archive.ArchiveManifest
import io.github.tuzfucius.personalrecorder.archive.ArchiveManifestSegment
import io.github.tuzfucius.personalrecorder.archive.ArchiveSegmentType
import io.github.tuzfucius.personalrecorder.archive.ArchiveWriter
import io.github.tuzfucius.personalrecorder.archive.mergeSourceDeviceIds
import io.github.tuzfucius.personalrecorder.data.AppDatabase
import io.github.tuzfucius.personalrecorder.data.ArchiveConflictEntity
import io.github.tuzfucius.personalrecorder.data.ArchiveSyncStateEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
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
        get() = restoreState != RestoreState.FAILED && results.all { it.error == null && it.isSuccessful }
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
        return RemoteArchiveInventoryScanner(
            api = api,
            repository = repository,
            zoneId = zoneId,
            nowMillis = nowMillis,
        ).discover(buildScope(mode))
    }

    suspend fun reconcile(
        mode: ReconcileMode = ReconcileMode.INCREMENTAL,
        onProgress: suspend (ReconcileProgress) -> Unit = {},
    ): ReconcileReport {
        var attempt = 0
        while (true) {
            try {
                return reconcileOnce(mode, onProgress)
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
            } catch (error: InvalidArchiveException) {
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
                            error = SyncError.InvalidArchive(error.message ?: "云端归档校验失败"),
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

    private suspend fun reconcileOnce(
        mode: ReconcileMode,
        onProgress: suspend (ReconcileProgress) -> Unit,
    ): ReconcileReport {
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

        val scope = buildScope(mode)
        val localInventory = localScanner.scan(scope)
        val remoteRaw = RemoteArchiveInventoryScanner(
            api = api,
            repository = repository,
            zoneId = zoneId,
            nowMillis = nowMillis,
        ).discover(scope)
        onProgress(ReconcileProgress("DISCOVERING", discovered = remoteRaw.descriptors.size))
        val remoteContents = linkedMapOf<String, ByteArray>()
        val remoteDescriptors = mutableListOf<ArchiveDescriptor>()
        val remoteVerification = linkedMapOf<String, ArchiveVerificationStatus>()
        val manifestShaByPath = linkedMapOf<String, String>()
        val invalidRemote = linkedMapOf<String, String>()
        val results = mutableListOf<ArchiveSyncResult>()
        var downloaded = 0

        remoteRaw.descriptors.filter { it.isManifest }.forEach { descriptor ->
            val content = api.downloadContent(repository, descriptor.relativePath)
                ?: throw SyncHttpException(422, "GitHub 归档缺少内容: ${descriptor.relativePath}")
            val sha256 = ArchiveFileStore.sha256(content)
            remoteContents[descriptor.relativePath] = content
            remoteDescriptors += descriptor.copy(sha256 = sha256, size = content.size.toLong())
            downloaded++
            parseManifestExpectations(descriptor.relativePath, content).forEach { (path, expectedSha) ->
                if (expectedSha.isNotBlank()) manifestShaByPath[path] = expectedSha
            }
        }
        remoteRaw.descriptors.filterNot { it.isManifest }.forEach { descriptor ->
            val content = api.downloadContent(repository, descriptor.relativePath)
                ?: throw SyncHttpException(422, "GitHub 归档缺少内容: ${descriptor.relativePath}")
            val sha256 = ArchiveFileStore.sha256(content)
            val expectedSha = manifestShaByPath[descriptor.relativePath]
            val verification = if (expectedSha.isNullOrBlank()) {
                ArchiveVerificationStatus.LEGACY_UNVERIFIED
            } else {
                if (!sha256.equals(expectedSha, ignoreCase = true)) {
                    invalidRemote[descriptor.relativePath] = "云端归档校验失败: ${descriptor.relativePath}"
                }
                ArchiveVerificationStatus.VERIFIED
            }
            remoteContents[descriptor.relativePath] = content
            remoteVerification[descriptor.relativePath] = verification
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

        segmentPaths.forEachIndexed { index, path ->
            val local = localByPath[path]
            val remote = remoteByPath[path]
            val invalidMessage = invalidRemote[path]
            val localBytes = if (invalidMessage == null) local?.let { readLocal(it) } else null
            val remoteBytes = remote?.let { remoteContents[it.relativePath] }
            val pair = reconciler.pair(local, remote)
            if (invalidMessage != null) {
                val descriptor = remote ?: local
                if (descriptor != null) {
                    val result = failedResult(descriptor, SyncError.InvalidArchive(invalidMessage))
                    persistState(descriptor.segmentId, result)
                    results += result
                }
            } else when (pair.state) {
                ArchivePairState.LOCAL_ONLY -> {
                    val localDescriptor = requireNotNull(local)
                    val bytes = requireNotNull(localBytes)
                    val result = try {
                        reconciler.parseJsonl(bytes)
                        upload(repository, localDescriptor, bytes, null)
                    } catch (error: SyncHttpException) {
                        if (error.statusCode == 409) throw error
                        failedResult(localDescriptor, error.toSyncError())
                    } catch (error: IllegalArgumentException) {
                        failedResult(localDescriptor, SyncError.InvalidArchive("本地 JSONL 校验失败: ${localDescriptor.relativePath}"))
                    }
                    persistState(localDescriptor.segmentId, result)
                    results += result
                    if (result.isSuccessful) uploaded++
                }
                ArchivePairState.REMOTE_ONLY -> {
                    val descriptor = requireNotNull(remote)
                    val bytes = requireNotNull(remoteBytes)
                    val result = try {
                        installRemoteSegment(
                            descriptor,
                            bytes,
                            remoteVerification[path] ?: ArchiveVerificationStatus.LEGACY_UNVERIFIED,
                        )
                        syncedResult(descriptor, descriptor.remoteSha, wasAlreadyPresent = false)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: SyncHttpException) {
                        if (error.statusCode == 409) throw error
                        failedResult(descriptor, error.toSyncError())
                    } catch (error: Throwable) {
                        failedResult(descriptor, SyncError.InvalidArchive(error.message ?: "云端归档无效"))
                    }
                    persistState(descriptor.segmentId, result)
                    results += result
                }
                ArchivePairState.BOTH_IDENTICAL -> {
                    val descriptor = requireNotNull(local)
                    val bytes = requireNotNull(localBytes)
                    val result = try {
                        importer.importAndRegisterSegment(
                            descriptor,
                            bytes,
                            zoneId,
                            nowMillis(),
                            remoteVerification[path] ?: ArchiveVerificationStatus.VERIFIED,
                        )
                        syncedResult(descriptor, remote?.remoteSha, wasAlreadyPresent = true)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        failedResult(descriptor, SyncError.InvalidArchive(error.message ?: "本地归档无效"))
                    }
                    persistState(descriptor.segmentId, result)
                    results += result
                    skipped++
                }
                ArchivePairState.BOTH_DIFFERENT -> {
                    val localDescriptor = requireNotNull(local)
                    val remoteDescriptor = requireNotNull(remote)
                    val finalResult = try {
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
                            remoteVerification[path] ?: ArchiveVerificationStatus.VERIFIED,
                        )
                        val hasConflict = merge.conflicts.isNotEmpty() || importResult.conflictingEventIds.isNotEmpty()
                        if (hasConflict) {
                            conflicts += merge.conflicts.size + importResult.conflictingEventIds.size
                            persistConflict(
                                localDescriptor,
                                requireNotNull(localBytes),
                                requireNotNull(remoteBytes),
                                merge.conflicts.size + importResult.conflictingEventIds.size,
                            )
                        }
                        val result = upload(repository, mergedDescriptor, mergedBytes, remoteDescriptor.remoteSha)
                        if (hasConflict) result.copy(
                            status = ArchiveSyncStatus.CONFLICT,
                            error = SyncError.RemoteConflict("归档包含同 ID 内容冲突，已保留本地与远端原始归档"),
                        ) else result
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: SyncHttpException) {
                        if (error.statusCode == 409) throw error
                        failedResult(localDescriptor, error.toSyncError())
                    } catch (error: Throwable) {
                        failedResult(localDescriptor, SyncError.InvalidArchive(error.message ?: "归档合并失败"))
                    }
                    persistState(localDescriptor.segmentId, finalResult)
                    results += finalResult
                    if (finalResult.isSuccessful) uploaded++
                }
            }
            onProgress(
                ReconcileProgress(
                    phase = "PROCESSING",
                    discovered = remoteRaw.descriptors.size,
                    processed = index + 1,
                    total = segmentPaths.size,
                    downloaded = downloaded,
                    uploaded = uploaded,
                    skipped = skipped,
                    conflicts = conflicts,
                    currentPath = path,
                )
            )
        }

        val manifestResults = reconcileManifests(
            repository = repository,
            localInventory = localScanner.scan(scope),
            remoteDescriptors = remoteDescriptors.filter { it.isManifest }.associateBy { it.relativePath },
            remoteContents = remoteContents,
        )
        results += manifestResults.results
        uploaded += manifestResults.uploaded
        skipped += manifestResults.skipped
        onProgress(
            ReconcileProgress(
                phase = "COMPLETED",
                discovered = remoteRaw.descriptors.size,
                processed = segmentPaths.size,
                total = segmentPaths.size,
                downloaded = downloaded,
                uploaded = uploaded,
                skipped = skipped,
                conflicts = conflicts,
            )
        )
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

    private suspend fun buildScope(mode: ReconcileMode): ReconcileScope {
        if (mode == ReconcileMode.FULL_RESTORE) return ReconcileScope.full()
        val today = java.time.Instant.ofEpochMilli(nowMillis()).atZone(zoneId).toLocalDate()
        val dates = buildSet {
            (0..7).forEach { add(today.minusDays(it.toLong())) }
            database.eventDao()
                .getReconcileScopeDates(CloudBackendType.GITHUB.name)
                .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
                .forEach(::add)
        }
        return ReconcileScope.dates(dates)
    }

    private fun parseManifestExpectations(
        manifestPath: String,
        bytes: ByteArray,
    ): Map<String, String> {
        val manifest = try {
            json.decodeFromString<ArchiveManifest>(bytes.toString(Charsets.UTF_8))
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            throw InvalidArchiveException("manifest.json 无法解析: $manifestPath", error)
        }
        val directory = manifestPath.substringBeforeLast('/')
        return manifest.segments.associate { segment ->
            "$directory/${segment.fileName}" to segment.sha256
        }
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
            val manifestBytes = buildManifest(
                date = date,
                segments = segments,
                deviceId = deviceId,
                localManifestBytes = File(filesDir, manifestPath).takeIf { it.isFile }?.readBytes(),
                remoteManifestBytes = remoteDescriptors[manifestPath]?.let { remoteContents[it.relativePath] },
            )
            ArchiveFileStore.atomicWrite(filesDir, manifestPath, manifestBytes)
            val localDescriptor = ArchivePathDescriptor.fromPath(
                manifestPath,
                ArchiveFileStore.sha256(manifestBytes),
                manifestBytes.size.toLong(),
            ) ?: return@forEach
            val remoteDescriptor = remoteDescriptors[manifestPath]
            val remoteManifestBytes = remoteDescriptor?.let { remoteContents[it.relativePath] }
            val result = if (remoteDescriptor != null && remoteManifestBytes != null &&
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
        // A full restore may have no local segment inventory at all. Persist remote
        // manifests after their JSON/SHA validation so a rerun remains idempotent.
        remoteDescriptors.keys.sorted().forEach { manifestPath ->
            val remoteDescriptor = remoteDescriptors[manifestPath] ?: return@forEach
            if (!remoteDescriptor.isManifest || localSegments.containsKey(remoteDescriptor.date)) return@forEach
            val bytes = remoteContents[manifestPath] ?: return@forEach
            ArchiveFileStore.atomicWrite(filesDir, manifestPath, bytes, remoteDescriptor.sha256)
            val result = syncedResult(remoteDescriptor, remoteDescriptor.remoteSha, wasAlreadyPresent = false)
            persistState(remoteDescriptor.segmentId, result)
            results += result
            skipped++
        }
        return ManifestReport(results, uploaded, skipped)
    }

    private fun buildManifest(
        date: String,
        segments: List<ArchiveDescriptor>,
        deviceId: String?,
        localManifestBytes: ByteArray?,
        remoteManifestBytes: ByteArray?,
    ): ByteArray {
        val localManifest = localManifestBytes?.let { runCatching { json.decodeFromString<ArchiveManifest>(it.toString(Charsets.UTF_8)) }.getOrNull() }
        val remoteManifest = remoteManifestBytes?.let { runCatching { json.decodeFromString<ArchiveManifest>(it.toString(Charsets.UTF_8)) }.getOrNull() }
        val sourceDeviceIds = mergeSourceDeviceIds(
            localManifest?.sourceDeviceIds.orEmpty(),
            remoteManifest?.sourceDeviceIds.orEmpty(),
            deviceId,
        )
        val body = ArchiveManifest(
            schemaVersion = if (sourceDeviceIds.isEmpty()) 1 else 2,
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
            sourceDeviceIds = sourceDeviceIds,
            lastWriterDeviceId = deviceId,
        )
        return (json.encodeToString(body) + "\n").toByteArray(Charsets.UTF_8)
    }

    private suspend fun installRemoteSegment(
        descriptor: ArchiveDescriptor,
        bytes: ByteArray,
        verificationStatus: ArchiveVerificationStatus,
    ) {
        reconciler.parseJsonl(bytes)
        ArchiveFileStore.atomicWrite(filesDir, descriptor.relativePath, bytes, descriptor.sha256)
        val importResult = importer.importAndRegisterSegment(
            descriptor,
            bytes,
            zoneId,
            nowMillis(),
            verificationStatus,
        )
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
            result.status == ArchiveSyncStatus.CONFLICT || result.status == ArchiveSyncStatus.SYNCED ->
                ArchiveSyncStateEntity.Status.SYNCED
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
