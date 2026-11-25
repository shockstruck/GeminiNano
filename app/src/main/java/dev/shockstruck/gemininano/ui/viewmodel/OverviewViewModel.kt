package dev.shockstruck.gemininano.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.shockstruck.gemininano.AppGraph
import dev.shockstruck.gemininano.model.FeatureStatusKind
import dev.shockstruck.gemininano.model.Outcome
import dev.shockstruck.gemininano.model.OverviewModel
import dev.shockstruck.gemininano.model.PackageFacts
import dev.shockstruck.gemininano.model.Severity
import dev.shockstruck.gemininano.model.SummarizationProbeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class OverviewUiState(
    val model: OverviewModel? = null,
    val probeStatus: FeatureStatusKind? = null,
    val probeFailure: dev.shockstruck.gemininano.model.GenAiFailure? = null,
    val probeStatusDetail: String? = null,
    val probeBusy: Boolean = false,
    val downloadBusy: Boolean = false,
    val downloadProgressText: String? = null,
    val actionMessage: String? = null,
    val noteText: String = "",
    val inferenceBusy: Boolean = false,
    val inferenceSummary: String? = null,
    val inferenceError: String? = null,
) {
    /** The patch's one-time download action is only usable when ML Kit says DOWNLOADABLE. */
    val canDownload: Boolean
        get() = !probeBusy && !downloadBusy && probeStatus == FeatureStatusKind.DOWNLOADABLE

    val canSummarize: Boolean
        get() = !inferenceBusy && noteText.isNotBlank()
}

class OverviewViewModel(
    private val graph: AppGraph,
) : ViewModel() {

    private val _state = MutableStateFlow(OverviewUiState())
    val state: StateFlow<OverviewUiState> = _state.asStateFlow()

    private var packages: List<PackageFacts> = emptyList()
    private var probe: SummarizationProbeResult? = null

    init {
        viewModelScope.launch {
            graph.probeCache.probe.collect { cached ->
                probe = cached
                recompute()
            }
        }
        refresh()
    }

    fun refresh() {
        if (_state.value.probeBusy) return
        viewModelScope.launch {
            _state.update { it.copy(probeBusy = true) }
            val startedAt = System.currentTimeMillis()
            packages = withContext(Dispatchers.IO) {
                graph.packageFactsProvider.inspectPackages()
            }
            val result = graph.summarizationProbe.probe()
            graph.probeCache.store(result)
            probe = result
            val duration = System.currentTimeMillis() - startedAt
            val failure = result.failure
            if (failure != null) {
                graph.diagnosticBus.log(
                    component = COMPONENT,
                    operation = OPERATION_PROBE,
                    outcome = Outcome.FAILURE,
                    severity = Severity.ERROR,
                    exceptionClass = failure.exceptionClass,
                    errorCode = failure.errorCode,
                    message = failure.message,
                    featureId = SummarizationProbeResult.FEATURE_ID,
                    durationMillis = duration,
                )
            } else {
                graph.diagnosticBus.log(
                    component = COMPONENT,
                    operation = OPERATION_PROBE,
                    outcome = Outcome.SUCCESS,
                    severity = Severity.INFO,
                    message = "Feature status: ${result.status.label}. ${result.statusDetail}",
                    featureId = SummarizationProbeResult.FEATURE_ID,
                    durationMillis = duration,
                )
            }
            _state.update { it.copy(probeBusy = false) }
            recompute()
        }
    }

    private fun recompute() {
        if (packages.isEmpty()) return
        _state.update {
            it.copy(
                model = graph.presentOverview(packages, probe),
                probeStatus = probe?.status,
                probeFailure = probe?.failure,
                probeStatusDetail = probe?.statusDetail,
            )
        }
    }

    fun downloadModel() {
        if (!_state.value.canDownload) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    downloadBusy = true,
                    downloadProgressText = "Requesting the one-time download…",
                    actionMessage = null,
                )
            }
            val startedAt = System.currentTimeMillis()
            val result = graph.summarizationProbe.downloadFeature { downloaded, total ->
                _state.update { it.copy(downloadProgressText = formatProgress(downloaded, total)) }
            }
            val duration = System.currentTimeMillis() - startedAt
            val failure = result.failure
            if (result.success) {
                graph.diagnosticBus.log(
                    component = COMPONENT,
                    operation = OPERATION_DOWNLOAD,
                    outcome = Outcome.SUCCESS,
                    severity = Severity.INFO,
                    message = "Model download completed.",
                    featureId = SummarizationProbeResult.FEATURE_ID,
                    bytes = result.bytesDownloaded,
                    durationMillis = duration,
                )
                _state.update {
                    it.copy(
                        downloadBusy = false,
                        actionMessage = "Download completed — re-checking feature status…",
                    )
                }
                refresh()
            } else {
                graph.diagnosticBus.log(
                    component = COMPONENT,
                    operation = OPERATION_DOWNLOAD,
                    outcome = Outcome.FAILURE,
                    severity = Severity.ERROR,
                    exceptionClass = failure?.exceptionClass,
                    errorCode = failure?.errorCode,
                    message = failure?.message ?: "Download failed without an error detail.",
                    featureId = SummarizationProbeResult.FEATURE_ID,
                    bytes = result.bytesDownloaded,
                    durationMillis = duration,
                )
                _state.update {
                    it.copy(
                        downloadBusy = false,
                        actionMessage = failureDescription(failure, "Download failed"),
                    )
                }
            }
        }
    }

    fun summarize() {
        val note = _state.value.noteText
        if (note.isBlank() || _state.value.inferenceBusy) return
        graph.diagnosticBus.registerSensitiveValue(note)
        viewModelScope.launch {
            _state.update {
                it.copy(inferenceBusy = true, inferenceSummary = null, inferenceError = null)
            }
            val result = graph.summarizationProbe.summarize(note)
            val failure = result.failure
            if (result.success) {
                graph.diagnosticBus.log(
                    component = COMPONENT,
                    operation = OPERATION_SUMMARIZE,
                    outcome = Outcome.SUCCESS,
                    severity = Severity.INFO,
                    message = "Summarization completed.",
                    featureId = SummarizationProbeResult.FEATURE_ID,
                    durationMillis = result.latencyMillis,
                )
                _state.update {
                    it.copy(inferenceBusy = false, inferenceSummary = result.summary)
                }
            } else {
                graph.diagnosticBus.log(
                    component = COMPONENT,
                    operation = OPERATION_SUMMARIZE,
                    outcome = Outcome.FAILURE,
                    severity = Severity.ERROR,
                    exceptionClass = failure?.exceptionClass,
                    errorCode = failure?.errorCode,
                    message = failure?.message ?: "Inference failed without an error detail.",
                    featureId = SummarizationProbeResult.FEATURE_ID,
                    durationMillis = result.latencyMillis,
                )
                _state.update {
                    it.copy(
                        inferenceBusy = false,
                        inferenceError = failureDescription(failure, "Inference failed"),
                    )
                }
            }
        }
    }

    fun onNoteChange(text: String) {
        _state.update { it.copy(noteText = text) }
    }

    fun clearNote() {
        _state.update { it.copy(noteText = "", inferenceSummary = null, inferenceError = null) }
    }

    private fun failureDescription(
        failure: dev.shockstruck.gemininano.model.GenAiFailure?,
        fallback: String,
    ): String = if (failure == null) {
        "$fallback — no error detail was returned."
    } else {
        "$fallback — error ${failure.codeLabel}: ${failure.message}"
    }

    private fun formatProgress(downloaded: Long, total: Long?): String =
        if (total != null && total > 0) {
            val percent = (downloaded.coerceAtMost(total) * 100) / total
            "$percent% (${formatBytes(downloaded)} / ${formatBytes(total)})"
        } else {
            "${formatBytes(downloaded)} downloaded (total unknown)"
        }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format(Locale.US, "%.1f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }

    private companion object {
        const val COMPONENT = "aicore"
        const val OPERATION_PROBE = "probeSummarization"
        const val OPERATION_DOWNLOAD = "downloadSummarization"
        const val OPERATION_SUMMARIZE = "summarize"
    }
}
