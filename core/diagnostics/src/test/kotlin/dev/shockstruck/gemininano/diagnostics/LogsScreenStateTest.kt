package dev.shockstruck.gemininano.diagnostics

import dev.shockstruck.gemininano.model.Outcome
import dev.shockstruck.gemininano.model.Severity
import dev.shockstruck.gemininano.testing.TestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogsScreenStateTest {

    private val events = listOf(
        TestFixtures.event(component = "aicore", severity = Severity.ERROR, outcome = Outcome.FAILURE),
        TestFixtures.event(
            component = "packages",
            severity = Severity.DEBUG,
            outcome = Outcome.SUCCESS,
            errorCode = null,
            message = "Inspected 11 packages.",
        ),
        TestFixtures.event(
            component = "aicore",
            severity = Severity.INFO,
            outcome = Outcome.SUCCESS,
            errorCode = null,
            message = "Feature status: AVAILABLE",
        ),
    )

    @Test
    fun componentsAreDerivedAndSorted() {
        assertEquals(listOf("aicore", "packages"), LogsScreenState(events).components)
    }

    @Test
    fun filtersNarrowVisibleEvents() {
        val byComponent = LogsScreenState(events = events, component = "aicore")
        assertEquals(2, byComponent.visibleEvents.size)

        val bySeverity = LogsScreenState(events = events, minimumSeverity = Severity.ERROR)
        assertEquals(1, bySeverity.visibleEvents.size)

        val byQuery = LogsScreenState(events = events, query = "packages")
        assertEquals(1, byQuery.visibleEvents.size)
    }

    @Test
    fun emptyStateReflectsFilters() {
        val state = LogsScreenState(events = events, query = "no-such-text")
        assertTrue(state.isEmpty)
        assertFalse(LogsScreenState(events = events).isEmpty)
    }

    @Test
    fun filtersCombine() {
        val state = LogsScreenState(
            events = events,
            query = "error",
            component = "aicore",
            minimumSeverity = Severity.WARN,
        )
        assertEquals(1, state.visibleEvents.size)
    }
}
