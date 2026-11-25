package dev.shockstruck.gemininano.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.shockstruck.gemininano.AppGraph
import dev.shockstruck.gemininano.diagnostics.ProvisioningDecision
import dev.shockstruck.gemininano.diagnostics.ProvisioningEvidence
import dev.shockstruck.gemininano.model.DownloadResult
import dev.shockstruck.gemininano.model.FeatureStatusKind
import dev.shockstruck.gemininano.model.Outcome
import dev.shockstruck.gemininano.model.PcsBindOutcome
import dev.shockstruck.gemininano.model.PcsBindResult
import dev.shockstruck.gemininano.model.PcsSurface
import dev.shockstruck.gemininano.model.PermissionMatrix
import dev.shockstruck.gemininano.model.PermissionMatrixFormatting
import dev.shockstruck.gemininano.model.PlatformPrincipals
import dev.shockstruck.gemininano.model.PromptConfigStatus
import dev.shockstruck.gemininano.model.PromptModelConfig
import dev.shockstruck.gemininano.model.Severity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class InvestigationUiState(
    val loading: Boolean = false,
    val matrix: PermissionMatrix? = null,
    val surface: PcsSurface? = null,
    val prompt: List<PromptConfigStatus> = emptyList(),
    val promptBusy: Boolean = false,
    val promptDownloadConfig: PromptModelConfig? = null,
    val promptDownloadText: String? = null,
    val promptMessage: String? = null,
    val bindResults: List<PcsBindResult> = emptyList(),
    val bindBusy: Boolean = false,
    val bindMessage: String? = null,
    val decision: ProvisioningDecision? = null,
) {
    fun promptStatus(config: PromptModelConfig): PromptConfigStatus? =
        prompt.firstOrNull { it.config == config }

    val anyProbeBusy: Boolean
        get() = loading || promptBusy || promptDownloadConfig != null || bindBusy
}

/**
 * Shared state for the three provisioning-investigation screens (AICore Features,
 * Private Compute Services, Provisioning Diagnostics). It performs only safe, public
 * probes: the permission matrix, the four Prompt checks, PCS surface enumeration and
 * the one-bind-per-service probe on an explicit tap. No download happens without a tap.
 */
class InvestigationViewModel(
    private val graph: AppGraph,
) : ViewModel() {

    private val _state = MutableStateFlow(InvestigationUiState())
    val state: StateFlow<InvestigationUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** Re-inspects packages, rebuilds the matrix/surface and re-probes the four Prompt configs. */
    fun refresh() {
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val inspected = withContext(Dispatchers.IO) {
                graph.packageFactsProvider.inspectPackages()
            }
            val matrix = withContext(Dispatchers.IO) {
                graph.permissionMatrixProbe.build(inspected)
            }
            val pcs = inspected.firstOrNull { it.packageName == PlatformPrincipals.PCS }
            val surface = withContext(Dispatchers.IO) {
                graph.pcsSurfaceInspector.inspect(pcs)
            }
            _state.update { it.copy(loading = false, matrix = matrix, surface = surface) }
            logMatrix(matrix)
            logSurface(surface)
            probePromptInternal()
            recomputeDecision()
        }
    }

    fun probePrompt() {
        val current = _state.value
        if (current.loading || current.promptBusy || current.promptDownloadConfig != null) return
        viewModelScope.launch { probePromptInternal() }
    }

    fun downloadPrompt(config: PromptModelConfig) {
        val current = _state.value
        val status = current.promptStatus(config) ?: return
        if (!status.downloadOffered || current.promptBusy || current.promptDownloadConfig != null) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    promptDownloadConfig = config,
                    promptDownloadText = "Requesting ${config.label} from AICore…",
                    promptMessage = null,
                )
            }
            val startedAt = System.currentTimeMillis()
            val result = graph.promptProbe.download(config) { downloaded, total ->
                _state.update { it.copy(promptDownloadText = formatProgress(downloaded, total)) }
            }
            logDownload(config, result, System.currentTimeMillis() - startedAt)
            _state.update {
                it.copy(
                    promptDownloadConfig = null,
                    promptDownloadText = null,
                    promptMessage = if (result.success) {
                        "Download completed for ${config.label}; re-probing all four configs."
                    } else {
                        "Download failed for ${config.label}: " +
                            "${result.failure?.codeLabel ?: "unknown"} " +
                            "${result.failure?.message ?: "no detail"}"
                    },
                )
            }
            probePromptInternal()
        }
    }

    /** One bind per fixed service, only when the user asks. */
    fun runBindProbe() {
        if (_state.value.bindBusy) return
        viewModelScope.launch {
            _state.update { it.copy(bindBusy = true, bindMessage = null) }
            val exported = _state.value.surface?.exportedComponents.orEmpty()
            val results = graph.safeBindProbe.probeAll(exported)
            results.forEach { logBind(it) }
            _state.update {
                it.copy(
                    bindBusy = false,
                    bindResults = results,
                    bindMessage = "Bind probe finished: ${results.count { r -> r.outcome == PcsBindOutcome.BINDER_RETURNED }} " +
                        "of ${results.size} returned a binder; every accepted bind was unbound once.",
                )
            }
            recomputeDecision()
        }
    }

    private suspend fun probePromptInternal() {
        _state.update { it.copy(promptBusy = true, promptMessage = null) }
        val results = PromptModelConfig.ALL.map { config -> graph.promptProbe.probe(config) }
        results.forEach { logPrompt(it) }
        _state.update { it.copy(promptBusy = false, prompt = results) }
        recomputeDecision()
    }

    private fun recomputeDecision() {
        val current = _state.value
        val decision = graph.provisioningPresenter.present(
            ProvisioningEvidence(
                matrix = current.matrix,
                prompt = current.prompt,
                bindResults = current.bindResults,
            )
        )
        _state.update { it.copy(decision = decision) }
    }

    private fun logMatrix(matrix: PermissionMatrix) {
        matrix.principals.forEach { principal ->
            graph.diagnosticBus.log(
                component = COMPONENT_PERMISSIONS,
                operation = OPERATION_MATRIX,
                outcome = Outcome.SUCCESS,
                severity = Severity.INFO,
                message = "${principal.label}: " +
                    PermissionMatrixFormatting.renderPrincipalRow(matrix, principal.packageName),
            )
        }
    }

    private fun logSurface(surface: PcsSurface) {
        val message = if (!surface.packageVisible) {
            "PCS is not visible: exported surface cannot be enumerated (visibility limit, not absence)."
        } else {
            "Exported components: ${surface.exportedComponents.size}. " +
                "Settings: ${if (surface.settings.resolved) surface.settings.activityName else "not resolved"}. " +
                "Job-bound services (static only): ${surface.exportedComponents.count { it.requiredPermission == "android.permission.BIND_JOB_SERVICE" }}."
        }
        graph.diagnosticBus.log(
            component = COMPONENT_PCS,
            operation = OPERATION_SURFACE,
            outcome = if (surface.packageVisible) Outcome.SUCCESS else Outcome.SKIPPED,
            severity = Severity.INFO,
            message = message,
        )
    }

    private fun logPrompt(status: PromptConfigStatus) {
        val outcome = if (status.status == FeatureStatusKind.PROBE_ERROR) Outcome.FAILURE else Outcome.SUCCESS
        graph.diagnosticBus.log(
            component = COMPONENT_PROMPT,
            operation = OPERATION_PROMPT,
            outcome = outcome,
            severity = if (outcome == Outcome.FAILURE) Severity.ERROR else Severity.INFO,
            exceptionClass = status.exceptionClass,
            errorCode = status.errorCode,
            message = "config=${status.config.label} status=${status.status.label} " +
                "featureId=${status.featureIdLabel} " +
                "baseModel=${status.baseModelName ?: "unknown"} " +
                "generation=${if (status.generationSucceeded) "ok" else "not verified"} " +
                "latency=${status.latencyMillis}ms" +
                (status.message?.let { " message=$it" } ?: ""),
            featureId = "prompt:${status.config.label}",
            durationMillis = status.latencyMillis,
        )
    }

    private fun logDownload(
        config: PromptModelConfig,
        result: DownloadResult,
        durationMillis: Long,
    ) {
        graph.diagnosticBus.log(
            component = COMPONENT_PROMPT,
            operation = OPERATION_DOWNLOAD,
            outcome = if (result.success) Outcome.SUCCESS else Outcome.FAILURE,
            severity = if (result.success) Severity.INFO else Severity.ERROR,
            exceptionClass = result.failure?.exceptionClass,
            errorCode = result.failure?.errorCode,
            message = "config=${config.label} " +
                (if (result.success) "download completed" else "download failed") +
                (result.failure?.let { ": ${it.message}" } ?: ""),
            featureId = "prompt:${config.label}",
            bytes = result.bytesDownloaded,
            durationMillis = durationMillis,
        )
    }

    private fun logBind(result: PcsBindResult) {
        val outcome = when (result.outcome) {
            PcsBindOutcome.BINDER_RETURNED -> Outcome.SUCCESS
            PcsBindOutcome.NOT_EXPORTED -> Outcome.SKIPPED
            else -> Outcome.FAILURE
        }
        graph.diagnosticBus.log(
            component = COMPONENT_PCS,
            operation = OPERATION_BIND,
            outcome = outcome,
            severity = if (outcome == Outcome.FAILURE) Severity.WARN else Severity.INFO,
            exceptionClass = result.exceptionClass,
            message = "component=${result.componentName} outcome=${result.outcome.label} " +
                "interface=${result.interfaceDescriptor ?: "none"} " +
                "unbindCalls=${result.unbindCalls} " +
                (result.exceptionMessage?.let { "message=$it " } ?: ""),
            featureId = result.componentName,
            durationMillis = result.elapsedMillis,
        )
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
        const val COMPONENT_PERMISSIONS = "permissions"
        const val COMPONENT_PROMPT = "prompt"
        const val COMPONENT_PCS = "pcs"
        const val OPERATION_MATRIX = "permissionMatrix"
        const val OPERATION_PROMPT = "probePrompt"
        const val OPERATION_DOWNLOAD = "downloadPrompt"
        const val OPERATION_SURFACE = "inspectSurface"
        const val OPERATION_BIND = "bindService"
    }
}
