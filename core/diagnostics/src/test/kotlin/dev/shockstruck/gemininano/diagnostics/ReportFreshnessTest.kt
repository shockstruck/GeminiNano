package dev.shockstruck.gemininano.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportFreshnessTest {

    @Test
    fun noReportIsNotStale() {
        assertFalse(ReportFreshness.isStale(null, lastObservationAtEpochMillis = 100L))
        assertFalse(ReportFreshness.isStale(null, lastObservationAtEpochMillis = null))
    }

    @Test
    fun aReportWithNoLaterObservationIsFresh() {
        assertFalse(ReportFreshness.isStale(200L, lastObservationAtEpochMillis = null))
        assertFalse(ReportFreshness.isStale(200L, lastObservationAtEpochMillis = 100L))
    }

    @Test
    fun anObservationAtOrAfterTheReportMakesItStale() {
        assertTrue(ReportFreshness.isStale(200L, lastObservationAtEpochMillis = 200L))
        assertTrue(ReportFreshness.isStale(200L, lastObservationAtEpochMillis = 201L))
    }

    @Test
    fun aNewReportAfterTheObservationIsFreshAgain() {
        assertFalse(ReportFreshness.isStale(300L, lastObservationAtEpochMillis = 250L))
    }
}
