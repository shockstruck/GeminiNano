package dev.shockstruck.gemininano.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import dev.shockstruck.gemininano.AppGraph
import dev.shockstruck.gemininano.data.AppSettings
import dev.shockstruck.gemininano.data.ThemeMode
import dev.shockstruck.gemininano.model.Outcome
import dev.shockstruck.gemininano.model.Severity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsViewModel(
    private val graph: AppGraph,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = graph.settings.state

    private val _bindHistoryCount = MutableStateFlow(graph.pcsBindHistoryStore.load().history.entries.size)
    val bindHistoryCount: StateFlow<Int> = _bindHistoryCount.asStateFlow()

    fun setTheme(mode: ThemeMode) {
        graph.settings.setTheme(mode)
        log("setTheme", "Theme set to ${mode.label}.")
    }

    fun setLogVerbosity(severity: Severity) {
        graph.settings.setLogVerbosity(severity)
        log("setLogVerbosity", "Log verbosity set to ${severity.label}.")
    }

    fun setExportDirectory(uri: Uri?) {
        graph.settings.setExportDirectory(uri)
        log(
            "setExportDirectory",
            if (uri == null) "Export directory cleared." else "Export directory set through SAF.",
        )
    }

    fun resetProbes() {
        graph.probeCache.clear()
        log("resetProbes", "Stored probe result cleared; the next Re-check re-probes AICore.")
    }

    /** Removes the locally stored bind observations; the next report shows no history. */
    fun clearBindHistory() {
        val stored = _bindHistoryCount.value
        graph.pcsBindHistoryStore.clear()
        _bindHistoryCount.value = 0
        log(
            "clearBindHistory",
            if (stored == 0) {
                "Clear bind history tapped; nothing was stored."
            } else {
                "Stored bind history cleared ($stored observation(s))."
            },
        )
    }

    private fun log(operation: String, message: String) {
        graph.diagnosticBus.log(
            component = COMPONENT,
            operation = operation,
            outcome = Outcome.SUCCESS,
            severity = Severity.INFO,
            message = message,
        )
    }

    private companion object {
        const val COMPONENT = "settings"
    }
}
