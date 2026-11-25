package dev.shockstruck.gemininano.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.shockstruck.gemininano.AppGraph
import dev.shockstruck.gemininano.model.Outcome
import dev.shockstruck.gemininano.model.PackageFacts
import dev.shockstruck.gemininano.model.Severity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SystemComponentsUiState(
    val loading: Boolean = false,
    val packages: List<PackageFacts> = emptyList(),
)

class SystemComponentsViewModel(
    private val graph: AppGraph,
) : ViewModel() {

    private val _state = MutableStateFlow(SystemComponentsUiState())
    val state: StateFlow<SystemComponentsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val startedAt = System.currentTimeMillis()
            val packages = withContext(Dispatchers.IO) {
                graph.packageFactsProvider.inspectPackages()
            }
            graph.diagnosticBus.log(
                component = COMPONENT,
                operation = "inspectPackages",
                outcome = Outcome.SUCCESS,
                severity = Severity.DEBUG,
                message = "Inspected ${packages.size} packages " +
                    "(${packages.count { it.visible }} visible).",
                durationMillis = System.currentTimeMillis() - startedAt,
            )
            _state.value = SystemComponentsUiState(loading = false, packages = packages)
        }
    }

    private companion object {
        const val COMPONENT = "packages"
    }
}
