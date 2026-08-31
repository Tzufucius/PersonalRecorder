package io.github.tuzfucius.personalrecorder.archive

import io.github.tuzfucius.personalrecorder.data.ArchiveSyncStateEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArchiveServiceManifestStateTest {
    @Test
    fun generatedManifestCreatesPendingUploadState() {
        assertEquals(
            ArchiveSyncStateEntity.Status.PENDING_UPLOAD,
            ManifestSyncStatePolicy.nextStatus(null),
        )
        assertEquals(
            ArchiveSyncStateEntity.Status.PENDING_UPLOAD,
            ManifestSyncStatePolicy.nextStatus(ArchiveSyncStateEntity.Status.FAILED),
        )
    }

    @Test
    fun retryableAndSyncedManifestStatesAreNotReset() {
        assertEquals(
            ArchiveSyncStateEntity.Status.PENDING_UPLOAD,
            ManifestSyncStatePolicy.nextStatus(ArchiveSyncStateEntity.Status.PENDING_UPLOAD),
        )
        assertNull(ManifestSyncStatePolicy.nextStatus(ArchiveSyncStateEntity.Status.SYNCED))
    }
}
