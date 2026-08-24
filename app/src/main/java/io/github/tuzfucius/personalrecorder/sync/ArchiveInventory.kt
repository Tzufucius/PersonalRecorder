package io.github.tuzfucius.personalrecorder.sync

import io.github.tuzfucius.personalrecorder.archive.ArchivePartition
import io.github.tuzfucius.personalrecorder.archive.ArchiveSegmentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ARCHIVE_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

object ArchivePathDescriptor {
    fun fromPath(
        relativePath: String,
        sha256: String,
        size: Long,
        remoteSha: String? = null,
    ): ArchiveDescriptor? {
        val parts = relativePath.replace('\\', '/').split('/')
        if (parts.size != 5 || parts[0] != "archive") return null
        val date = runCatching { LocalDate.parse(parts[3], ARCHIVE_DATE_FORMAT) }.getOrNull() ?: return null
        val fileName = parts[4]
        return when {
            fileName == "manifest.json" -> ArchiveDescriptor(
                segmentId = "${date}-MANIFEST",
                relativePath = relativePath.replace('\\', '/'),
                sha256 = sha256,
                date = date.toString(),
                slot = "MANIFEST",
                size = size,
                isManifest = true,
                remoteSha = remoteSha,
            )
            fileName == ArchiveSegmentType.FIRST_HALF.fileName ||
                fileName == ArchiveSegmentType.SECOND_HALF.fileName -> {
                val slot = if (fileName == ArchiveSegmentType.FIRST_HALF.fileName) {
                    ArchiveSegmentType.FIRST_HALF.name
                } else {
                    ArchiveSegmentType.SECOND_HALF.name
                }
                ArchiveDescriptor(
                    segmentId = "$date-$slot",
                    relativePath = relativePath.replace('\\', '/'),
                    sha256 = sha256,
                    date = date.toString(),
                    slot = slot,
                    size = size,
                    remoteSha = remoteSha,
                )
            }
            else -> null
        }
    }

    fun datePath(date: LocalDate): String =
        "archive/${date.format(DateTimeFormatter.ofPattern("yyyy/MM"))}/$date"
}

class LocalArchiveInventoryScanner(private val filesDir: File) {
    suspend fun scan(scope: ReconcileScope = ReconcileScope.full()): LocalArchiveInventory = withContext(Dispatchers.IO) {
        val root = File(filesDir, "archive")
        if (!root.isDirectory) return@withContext LocalArchiveInventory(emptyList())
        val descriptors = root.walkTopDown()
            .filter { it.isFile && (it.name.endsWith(".jsonl") || it.name == "manifest.json") }
            .mapNotNull { file ->
                val relative = filesDir.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                ArchivePathDescriptor.fromPath(relative, sha256(file), file.length())
            }
            .filter { descriptor -> scope.includes(LocalDate.parse(descriptor.date, ARCHIVE_DATE_FORMAT)) }
            .toList()
        LocalArchiveInventory(descriptors.sortedBy { it.relativePath })
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
}

class RemoteArchiveInventoryScanner(
    private val api: GitHubArchiveApi,
    private val repository: GitHubRepository,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /** Compatibility overload for callers that still provide date strings. */
    suspend fun discover(
        mode: ReconcileMode,
        localDates: Set<String> = emptySet(),
    ): RemoteArchiveInventory {
        if (mode == ReconcileMode.FULL_RESTORE) return discover(ReconcileScope.full())
        val today = java.time.Instant.ofEpochMilli(nowMillis()).atZone(zoneId).toLocalDate()
        val dates = buildSet {
            (0..7).forEach { add(today.minusDays(it.toLong())) }
            localDates.mapNotNull { runCatching { LocalDate.parse(it, ARCHIVE_DATE_FORMAT) }.getOrNull() }
                .forEach(::add)
        }
        return discover(ReconcileScope.dates(dates))
    }

    suspend fun discover(scope: ReconcileScope): RemoteArchiveInventory {
        val paths = if (scope.full) {
            discoverRecursively("archive")
        } else {
            val datePaths = scope.dates.orEmpty().sorted()
                .map(ArchivePathDescriptor::datePath)
            datePaths.flatMap { api.listDirectory(repository, it) }
        }
        return RemoteArchiveInventory(
            paths.asSequence()
                .filter { it.type == "file" }
                .mapNotNull { entry ->
                    ArchivePathDescriptor.fromPath(
                        relativePath = entry.path,
                        sha256 = "",
                        size = entry.size,
                        remoteSha = entry.sha,
                    )
                }
                .distinctBy { it.relativePath }
                .sortedBy { it.relativePath }
                .toList()
        )
    }

    private suspend fun discoverRecursively(path: String): List<GitHubDirectoryEntry> {
        return api.listDirectory(repository, path).flatMap { entry ->
            if (entry.type == "dir") discoverRecursively(entry.path) else listOf(entry)
        }
    }

}

object ArchiveFileStore {
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    fun file(filesDir: File, relativePath: String): File = File(filesDir, relativePath)

    fun atomicWrite(filesDir: File, relativePath: String, bytes: ByteArray, expectedSha256: String? = null): File {
        expectedSha256?.let { expected ->
            require(sha256(bytes).equals(expected, ignoreCase = true)) { "归档 SHA-256 校验失败: $relativePath" }
        }
        val target = file(filesDir, relativePath)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.download")
        temp.writeBytes(bytes)
        try {
            expectedSha256?.let { expected ->
                require(sha256(temp.readBytes()).equals(expected, ignoreCase = true)) {
                    "临时归档 SHA-256 校验失败: $relativePath"
                }
            }
            runCatching {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
        return target
    }
}
