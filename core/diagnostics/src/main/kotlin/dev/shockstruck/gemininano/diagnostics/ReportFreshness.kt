package dev.shockstruck.gemininano.diagnostics

/**
 * A full-diagnostics report is a snapshot. Once a bind or launch produces a newer
 * observation, a report generated before it no longer contains that observation.
 */
object ReportFreshness {

    fun isStale(
        reportGeneratedAtEpochMillis: Long?,
        lastObservationAtEpochMillis: Long?,
    ): Boolean = reportGeneratedAtEpochMillis != null &&
        lastObservationAtEpochMillis != null &&
        lastObservationAtEpochMillis >= reportGeneratedAtEpochMillis
}
