package io.github.tuzfucius.personalrecorder.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatisticsChartsTest {
    @Test
    fun packageColorMappingIsStable() {
        assertEquals(appColorIndex("com.example.chat"), appColorIndex("com.example.chat"))
    }

    @Test
    fun packageColorAssignmentsResolveCollisionsForCurrentResult() {
        val packages = listOf("com.example.app0", "com.example.app1", "com.example.chat")
        val first = packageColorIndices(packages)
        val second = packageColorIndices(packages.reversed())

        assertEquals(first, second)
        assertEquals(packages.size, first.values.distinct().size)
    }

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

    @Test
    fun plotMappingCoversTwentyFourHoursOnNarrowScreen() {
        assertEquals(0, mapPlotXToIndex(54f, 360f, 24))
        assertEquals(12, mapPlotXToIndex(200f, 360f, 24))
        assertEquals(23, mapPlotXToIndex(351f, 360f, 24))
    }
}
