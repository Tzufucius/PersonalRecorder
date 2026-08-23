package io.github.tuzfucius.personalrecorder.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatisticsChartsTest {
    @Test
    fun donutHitTestUsesAngleAndRejectsCenter() {
        assertEquals(0, donutHitTest(50f, 5f, 100f, 100f, listOf(1, 1), 20f))
        assertEquals(1, donutHitTest(50f, 95f, 100f, 100f, listOf(1, 1), 20f))
        assertNull(donutHitTest(50f, 50f, 100f, 100f, listOf(1, 1), 20f))
    }

    @Test
    fun plotMappingUsesAxisBounds() {
        assertNull(mapPlotXToIndex(20f, 300f, 3))
        assertEquals(0, mapPlotXToIndex(50f, 300f, 3))
        assertEquals(2, mapPlotXToIndex(280f, 300f, 3))
    }
}
