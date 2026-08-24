package io.github.tuzfucius.personalrecorder.ui

import io.github.tuzfucius.personalrecorder.data.NotificationSourceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterAppCatalogTest {
    @Test
    fun mergesObservedLauncherAndSelectedPackagesWithoutDuplicates() {
        val catalog = buildFilterAppCatalog(
            observedSources = listOf(
                NotificationSourceEntity("A", "Observed A", 1L, 10L, 3L, false),
                NotificationSourceEntity("B", "Observed B", 1L, 20L, 2L, null),
            ),
            launcherApps = listOf(
                FilterLauncherApp("B", "Launcher B"),
                FilterLauncherApp("C", "Launcher C"),
            ),
            selectedPackages = setOf("D"),
            ownPackageName = "self",
        )

        assertEquals(setOf("A", "B", "C", "D"), catalog.map { it.packageName }.toSet())
        assertEquals(listOf("B", "A"), catalog.take(2).map { it.packageName })
        assertTrue(catalog.single { it.packageName == "B" }.observed)
        assertEquals("Observed B", catalog.single { it.packageName == "B" }.label)
        assertEquals("D", catalog.single { it.packageName == "D" }.label)
    }

    @Test
    fun searchMatchesLabelAndPackageName() {
        val apps = listOf(
            FilterAppItem("com.tencent.mm", "微信", true, 1L, 1L, false, false),
            FilterAppItem("com.android.chrome", "Chrome", false, 0L, null, true, false),
        )

        assertEquals(listOf("com.tencent.mm"), filterAppItems(apps, "微信").map { it.packageName })
        assertEquals(listOf("com.tencent.mm"), filterAppItems(apps, "TENCENT").map { it.packageName })
    }
}
