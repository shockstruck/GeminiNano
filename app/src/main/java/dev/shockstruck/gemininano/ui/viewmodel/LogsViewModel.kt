package dev.shockstruck.gemininano.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.shockstruck.gemininano.AppGraph
import dev.shockstruck.gemininano.data.LogExportManager
import dev.shockstruck.gemininano.diagnostics.LogsScreenState
import dev.shockstruck.gemininano.model.Outcome
import dev.shockstruck.gemininano.model.Severity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class LogsViewModel(
    private val graph: AppGraph,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val component = MutableStateFlow<String?>(null)
    private val minimumSeverity = MutableStateFlow<Severity?>(null)

    val state: StateFlow<LogsScreenState> = combine(
        graph.diagnosticBus.events,
        query,
        component,
        minimumSeverity,
    ) { events, currentQuery, currentComponent, currentSeverity ->
        LogsScreenState(
            events = events,
            query = currentQuery,
            component = currentComponent,
            minimumSeverity = currentSeverity,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = LogsScreenState(events = graph.diagnosticBus.snapshot()),
    )

    private val exportManager: LogExportManager = graph.logExportManager()

    fun exportManager(): LogExportManager = exportManager

    fun setQuery(value: String) {
        query.value = value
    }

    fun setComponent(value: String?) {
        component.value = value
    }

    fun setMinimumSeverity(value: Severity?) {
        minimumSeverity.value = value
    }

    fun clear() {
        graph.diagnosticBus.clear()
        graph.diagnosticBus.log(
            component = COMPONENT,
            operation = "clearLog",
            outcome = Outcome.SUCCESS,
            severity = Severity.INFO,
            message = "Diagnostic log cleared by the user.",
        )
    }

    private companion object {
        const val COMPONENT = "logs"
    }
}
