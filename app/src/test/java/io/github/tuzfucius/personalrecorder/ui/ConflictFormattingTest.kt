package io.github.tuzfucius.personalrecorder.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConflictFormattingTest {
    @Test
    fun extractsFirstIntegerWithoutDependingOnDisplayLanguage() {
        assertEquals(3, extractConflictCount("发现 3 个事件内容冲突"))
        assertEquals(12, extractConflictCount("12 conflicting events"))
        assertNull(extractConflictCount("archive conflict details unavailable"))
    }
}
