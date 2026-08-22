package io.github.tuzfucius.personalrecorder.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleDriveSyncTest {
    @Test
    fun driveBackendPrefixesManagedPersonalRecorderRoot() = runBlocking {
        val createdPaths = mutableListOf<String>()
        val cache = object : GoogleDriveFolderIdCache {
            private val values = mutableMapOf<String, String>()
            override suspend fun get(path: String) = values[path]
            override suspend fun put(path: String, folderId: String) { values[path] = folderId }
            override suspend fun remove(path: String) { values.remove(path) }
        }
        val rest = object : GoogleDriveRestClient {
            override suspend fun createFolder(name: String, parentId: String?): String {
                createdPaths += name
                return "id-${createdPaths.size}"
            }

            override suspend fun findManagedFile(parentId: String, name: String): GoogleDriveFile? = null

            override suspend fun uploadFile(parentId: String, name: String, content: ByteArray, sha256: String) =
                GoogleDriveUpload("file", sha256)
        }
        val backend = GoogleDriveCloudSyncBackend(rest, GoogleDriveFolderResolver(rest, cache))

        val result = backend.sync(
            CloudArchive(
                "archive/2026/08/2026-08-22/00-12.jsonl",
                "a".repeat(64),
                "{}\n".toByteArray()
            )
        )

        assertTrue(result is BackendSyncResult.Success)
        assertEquals(listOf("PersonalRecorder", "archive", "2026", "08", "2026-08-22"), createdPaths)
    }
}
