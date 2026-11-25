package dev.shockstruck.gemininano.diagnostics

import dev.shockstruck.gemininano.model.Outcome
import dev.shockstruck.gemininano.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticBusTest {

    private var now = 1_800_000_000_000L

    private fun bus(
        capacity: Int = 500,
        minimumSeverity: Severity = Severity.VERBOSE,
    ) = DiagnosticBus(
        capacity = capacity,
        clock = { now++ },
        minimumSeverity = { minimumSeverity },
    )

    @Test
    fun storesEventsInOrder() {
        val bus = bus()
        bus.log("aicore", "probe", Outcome.SUCCESS, Severity.INFO, message = "first")
        bus.log("packages", "inspect", Outcome.SUCCESS, Severity.DEBUG, message = "second")
        val snapshot = bus.snapshot()
        assertEquals(2, snapshot.size)
        assertEquals("first", snapshot[0].message)
        assertEquals("second", snapshot[1].message)
    }

    @Test
    fun bufferIsBoundedAndDropsOldest() {
        val bus = bus(capacity = 3)
        repeat(5) { index ->
            bus.log("logs", "append", Outcome.INFO, Severity.INFO, message = "event $index")
        }
        val snapshot = bus.snapshot()
        assertEquals(3, snapshot.size)
        assertEquals(listOf("event 2", "event 3", "event 4"), snapshot.map { it.message })
    }

    @Test
    fun eventsBelowMinimumSeverityAreDropped() {
        val bus = bus(minimumSeverity = Severity.WARN)
        assertNull(bus.log("logs", "debug", Outcome.INFO, Severity.DEBUG, message = "hidden"))
        assertNotNull(bus.log("logs", "warn", Outcome.INFO, Severity.WARN, message = "kept"))
        assertEquals(1, bus.snapshot().size)
    }

    @Test
    fun messageRedactionUsesRegisteredValues() {
        val bus = bus()
        bus.registerSensitiveValue("my secret note text")
        val stored = bus.log(
            "aicore",
            "summarize",
            Outcome.FAILURE,
            Severity.ERROR,
            message = "failed to summarize: my secret note text",
        )
        assertEquals("failed to summarize: [redacted]", stored?.message)
        assertEquals("failed to summarize: [redacted]", bus.snapshot().single().message)
    }

    @Test
    fun clearEmptiesTheBuffer() {
        val bus = bus()
        bus.log("logs", "append", Outcome.INFO, Severity.INFO, message = "event")
        bus.clear()
        assertTrue(bus.snapshot().isEmpty())
    }

    @Test
    fun lastFailureFindsTheMostRecentError() {
        val bus = bus()
        bus.log("aicore", "probe", Outcome.SUCCESS, Severity.INFO)
        bus.log("aicore", "probe", Outcome.FAILURE, Severity.ERROR, errorCode = 606)
        bus.log("packages", "inspect", Outcome.SUCCESS, Severity.DEBUG)
        assertEquals(606, bus.lastFailure()?.errorCode)
    }

    @Test
    fun searchAndFiltersWorkTogether() {
        val bus = bus()
        bus.log("aicore", "probe", Outcome.SUCCESS, Severity.INFO, message = "status AVAILABLE")
        bus.log("aicore", "probe", Outcome.FAILURE, Severity.ERROR, errorCode = 606, message = "Feature 622")
        bus.log("packages", "inspect", Outcome.SUCCESS, Severity.DEBUG, message = "11 packages")

        assertEquals(2, DiagnosticQuery.apply(bus.snapshot(), component = "aicore").size)
        assertEquals(1, DiagnosticQuery.apply(bus.snapshot(), query = "606").size)
        assertEquals(1, DiagnosticQuery.apply(bus.snapshot(), minimumSeverity = Severity.ERROR).size)
        assertEquals(
            1,
            DiagnosticQuery.apply(
                bus.snapshot(),
                query = "feature",
                component = "aicore",
                minimumSeverity = Severity.ERROR,
            ).size,
        )
    }

    @Test
    fun queryIsCaseInsensitiveAndMatchesSeveralFields() {
        val bus = bus()
        bus.log("aicore", "probeSummarization", Outcome.FAILURE, Severity.ERROR, errorCode = 601, message = "bind failed")
        assertEquals(1, DiagnosticQuery.apply(bus.snapshot(), query = "AICORE").size)
        assertEquals(1, DiagnosticQuery.apply(bus.snapshot(), query = "probesummarization").size)
        assertEquals(1, DiagnosticQuery.apply(bus.snapshot(), query = "601").size)
        assertEquals(1, DiagnosticQuery.apply(bus.snapshot(), query = "error").size)
    }
}
