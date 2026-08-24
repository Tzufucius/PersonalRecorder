package io.github.tuzfucius.personalrecorder.sync

import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconcileScopeTest {
    @Test
    fun incrementalScopeIsSharedByLocalAndRemoteScanners() {
        val date = LocalDate.of(2026, 8, 23)
        val scope = ReconcileScope.dates(setOf(date))
        assertTrue(scope.includes(date))
        assertFalse(scope.includes(date.minusDays(1)))
        assertTrue(ReconcileScope.full().includes(date.minusYears(3)))
    }
}
