package io.github.tuzfucius.personalrecorder.archive

import org.junit.Assert.assertEquals
import org.junit.Test

class ArchiveManifestTest {
    @Test
    fun sourceDeviceIdsAreMergedWithoutDroppingHistory() {
        assertEquals(
            listOf("A", "B", "C", "D"),
            mergeSourceDeviceIds(listOf("B", "C"), listOf("A", "B"), "D"),
        )
    }
}
