package io.github.tuzfucius.personalrecorder.collector

import io.github.tuzfucius.personalrecorder.data.PersonalEvent
import io.github.tuzfucius.personalrecorder.settings.FilterMode
import io.github.tuzfucius.personalrecorder.settings.FilterSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationCollectorPipelineTest {
    @Test
    fun whitelistRejectsContentButStillRegistersSourceThenCollectsAfterSelection() = runBlocking {
        val observed = mutableListOf<String>()
        val events = mutableListOf<PersonalEvent>()
        var parseCalls = 0
        val pipeline = NotificationCollectorPipeline(
            ownPackageName = "self.app",
            observeSource = { observed += it },
            persistEvent = { events += it },
        )
        val event = sampleEvent("com.example.hidden")

        pipeline.collect(
            packageName = "com.example.hidden",
            readSettings = { Result.success(FilterSettings(FilterMode.WHITELIST)) },
            parseEvent = { parseCalls++; event },
        )

        assertEquals(listOf("com.example.hidden"), observed)
        assertTrue(events.isEmpty())
        assertEquals(0, parseCalls)

        pipeline.collect(
            packageName = "com.example.hidden",
            readSettings = { Result.success(FilterSettings(FilterMode.WHITELIST, setOf("com.example.hidden"))) },
            parseEvent = { parseCalls++; event },
        )

        assertEquals(2, observed.size)
        assertEquals(1, events.size)
        assertEquals(1, parseCalls)
    }

    @Test
    fun allModePersistsUnknownPackageAndBlacklistOnlyRejectsSelectedPackage() = runBlocking {
        val events = mutableListOf<PersonalEvent>()
        val pipeline = NotificationCollectorPipeline(
            ownPackageName = "self.app",
            observeSource = {},
            persistEvent = { events += it },
        )

        pipeline.collect(
            packageName = "com.example.no_launcher",
            readSettings = { Result.success(FilterSettings(FilterMode.ALL)) },
            parseEvent = { sampleEvent("com.example.no_launcher") },
        )
        pipeline.collect(
            packageName = "com.example.blocked",
            readSettings = { Result.success(FilterSettings(FilterMode.BLACKLIST, setOf("com.example.blocked"))) },
            parseEvent = { sampleEvent("com.example.blocked") },
        )
        pipeline.collect(
            packageName = "com.example.allowed",
            readSettings = { Result.success(FilterSettings(FilterMode.BLACKLIST, setOf("com.example.blocked"))) },
            parseEvent = { sampleEvent("com.example.allowed") },
        )

        assertEquals(listOf("com.example.no_launcher", "com.example.allowed"), events.map { it.packageName })
        assertFalse(events.any { it.packageName == "com.example.blocked" })
    }

    private fun sampleEvent(packageName: String) = PersonalEvent(
        id = packageName,
        timestamp = 1_000L,
        source = "notification",
        packageName = packageName,
        title = "title",
        content = "content",
        bigText = "bigText",
        textLines = listOf("line"),
        notificationKey = packageName,
        notificationId = 1,
        category = null,
        channelId = null,
        groupKey = null,
        isOngoing = false,
        isGroupSummary = false,
        isClearable = true,
        createdAt = 1_000L,
    )
}
