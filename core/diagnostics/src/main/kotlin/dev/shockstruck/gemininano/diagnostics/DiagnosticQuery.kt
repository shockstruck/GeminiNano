package dev.shockstruck.gemininano.diagnostics

import dev.shockstruck.gemininano.model.DiagnosticEvent
import dev.shockstruck.gemininano.model.Severity

/** Search and filter logic shared by the Logs screen and its unit tests. */
object DiagnosticQuery {

    fun matches(
        event: DiagnosticEvent,
        query: String,
        component: String?,
        minimumSeverity: Severity?,
    ): Boolean {
        if (component != null && event.component != component) return false
        if (minimumSeverity != null && event.severity.rank < minimumSeverity.rank) return false
        if (query.isBlank()) return true
        val needle = query.trim().lowercase()
        return listOfNotNull(
            event.component,
            event.operation,
            event.outcome.label,
            event.severity.label,
            event.message,
            event.exceptionClass,
            event.errorCode?.toString(),
            event.featureId,
        ).any { it.lowercase().contains(needle) }
    }

    fun apply(
        events: List<DiagnosticEvent>,
        query: String = "",
        component: String? = null,
        minimumSeverity: Severity? = null,
    ): List<DiagnosticEvent> = events.filter { matches(it, query, component, minimumSeverity) }
}

/** Pure state for the Diagnostic Logs screen. */
data class LogsScreenState(
    val events: List<DiagnosticEvent> = emptyList(),
    val query: String = "",
    val component: String? = null,
    val minimumSeverity: Severity? = null,
) {
    val components: List<String> get() = events.map { it.component }.distinct().sorted()

    val visibleEvents: List<DiagnosticEvent>
        get() = DiagnosticQuery.apply(events, query, component, minimumSeverity)

    val isEmpty: Boolean get() = visibleEvents.isEmpty()

    companion object {
        const val ALL_COMPONENTS = "All components"
    }
}
