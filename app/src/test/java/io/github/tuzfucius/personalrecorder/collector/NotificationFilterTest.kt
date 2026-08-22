package io.github.tuzfucius.personalrecorder.collector

import io.github.tuzfucius.personalrecorder.settings.FilterMode
import io.github.tuzfucius.personalrecorder.settings.FilterSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationFilterTest {
    @Test
    fun allModeExcludesOwnPackage() {
        assertFalse(NotificationFilter.shouldCollectPackage("self.app", "self.app"))
        assertTrue(NotificationFilter.shouldCollectPackage("source.app", "self.app"))
    }

    @Test
    fun whitelistRequiresSelectedPackage() {
        val settings = FilterSettings(FilterMode.WHITELIST, setOf("allowed.app"))

        assertTrue(NotificationFilter.shouldCollectPackage("allowed.app", "self.app", settings))
        assertFalse(NotificationFilter.shouldCollectPackage("other.app", "self.app", settings))
    }

    @Test
    fun emptyWhitelistCollectsNothing() {
        assertFalse(
            NotificationFilter.shouldCollectPackage(
                "source.app", "self.app", FilterSettings(FilterMode.WHITELIST)
            )
        )
    }

    @Test
    fun blacklistRejectsSelectedPackage() {
        val settings = FilterSettings(FilterMode.BLACKLIST, setOf("blocked.app"))

        assertFalse(NotificationFilter.shouldCollectPackage("blocked.app", "self.app", settings))
        assertTrue(NotificationFilter.shouldCollectPackage("other.app", "self.app", settings))
    }
}
