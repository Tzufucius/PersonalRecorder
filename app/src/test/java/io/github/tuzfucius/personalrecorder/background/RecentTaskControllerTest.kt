package io.github.tuzfucius.personalrecorder.background

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentTaskControllerTest {
    @Test
    fun fakeTracksExcludeFromRecents() {
        val controller = RecentTaskController.Fake()

        assertFalse(controller.excluded)
        controller.setExcludeFromRecents(true)
        assertTrue(controller.excluded)
        assertTrue(controller.updateCount == 1)
        controller.setExcludeFromRecents(false)
        assertFalse(controller.excluded)
    }
}
