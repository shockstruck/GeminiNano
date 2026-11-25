package dev.shockstruck.gemininano.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.shockstruck.gemininano.AppGraph
import dev.shockstruck.gemininano.diagnostics.FullDiagnosticsInput
import dev.shockstruck.gemininano.diagnostics.FullDiagnosticsReport
import dev.shockstruck.gemininano.diagnostics.FullDiagnosticsReportBuilder
import dev.shockstruck.gemininano.diagnostics.ProvisioningDecision
import dev.shockstruck.gemininano.diagnostics.ProvisioningEvidence
import dev.shockstruck.gemininano.model.DownloadResult
import dev.shockstruck.gemininano.model.FeatureDownloadProgress
import dev.shockstruck.gemininano.model.FeatureMatrixFormatting
import dev.shockstruck.gemininano.model.FeatureProbeResult
import dev.shockstruck.gemininano.model.FeatureStatusKind
import dev.shockstruck.gemininano.model.GenAiFailureClassification
import dev.shockstruck.gemininano.model.GenAiFeature
import dev.shockstruck.gemininano.model.ModelManagerRow
import dev.shockstruck.gemininano.model.ModelManagerRules
import dev.shockstruck.gemininano.model.OfflineInferenceFormatting
import dev.shockstruck.gemininano.model.OfflineInferencePlanning
import dev.shockstruck.gemininano.model.OfflineInferenceResult
import dev.shockstruck.gemininano.model.Outcome
import dev.shockstruck.gemininano.model.PcsActivityLaunchResult
import dev.shockstruck.gemininano.model.PcsActivityTarget
import dev.shockstruck.gemininano.model.PcsBindOutcome
import dev.shockstruck.gemininano.model.PcsBindCatalog
import dev.shockstruck.gemininano.model.PcsBindPlan
import dev.shockstruck.gemininano.model.PcsBindResult
import dev.shockstruck.gemininano.model.PcsLaunchOutcome
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
    val bindPlan: PcsBindPlan? = null,
    val bindResults: List<PcsBindResult> = emptyList(),
    val bindBusy: Boolean = false,
    val bindMessage: String? = null,
    val launchResults: List<PcsActivityLaunchResult> = emptyList(),
    val launchBusy: Boolean = false,
    val launchMessage: String? = null,
    val decision: ProvisioningDecision? = null,
    val features: List<FeatureProbeResult> = emptyList(),
    val featureBusy: Boolean = false,
    val featureDownload: GenAiFeature? = null,
    val featureDownloadText: String? = null,
    val featureMessage: String? = null,
    val featureProgress: Map<GenAiFeature, FeatureDownloadProgress> = emptyMap(),
    val offline: List<OfflineInferenceResult> = emptyList(),
    val offlineBusy: Boolean = false,
    val offlineMessage: String? = null,
    val fullReport: FullDiagnosticsReport? = null,
    val fullReportBusy: Boolean = false,
    val fullReportMessage: String? = null,
) {
    fun promptStatus(config: PromptModelConfig): PromptConfigStatus? =
        prompt.firstOrNull { it.config == config }

    fun featureResult(feature: GenAiFeature): FeatureProbeResult? =
        features.firstOrNull { it.feature == feature }

    val modelManagerRows: List<ModelManagerRow>
        get() = ModelManagerRules.rows(features, featureProgress)

    val eligibleOfflineCount: Int
        get() = OfflineInferencePlanning.eligibleCount(features)

    val anyProbeBusy: Boolean
        get() = loading || promptBusy || promptDownloadConfig != null || bindBusy || launchBusy ||
            featureBusy || featureDownload != null || offlineBusy || fullReportBusy

    val eligibleBindCount: Int
        get() = bindPlan?.eligibleCount ?: 0
}

/**
 * Shared state for the investigation and Stage 4 screens (AICore Features, Private
 * Compute Services, Model Manager, Provisioning Diagnostics, Offline Inference).
 *
 * The view model performs only safe, public probes: permission matrix, Prompt and
 * feature status checks, PCS surface enumeration, downloads the user explicitly taps,
 * fixed-input inference the user explicitly runs, and the one-bind-per-service probe
 * on its own explicit tap. Nothing is downloaded or provisioned automatically.
 */
class InvestigationViewModel(
    private val graph: AppGraph,
) : ViewModel() {

    private val _state = MutableStateFlow(InvestigationUiState())
    val state: StateFlow<InvestigationUiState> = _state.asStateFlow()

    private val reportBuilder = FullDiagnosticsReportBuilder()

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
            val plan = PcsBindCatalog.plan(pcs)
            _state.update {
                it.copy(
                    loading = false,
                    matrix = matrix,
                    surface = surface,
                    bindPlan = plan,
                    bindResults = plan.projectedResults(),
                    bindMessage = bindPlanMessage(plan),
                )
            }
            logMatrix(matrix)
            logSurface(surface)
            probePromptInternal()
            recomputeDecision()
        }
    }

    /** Probes the eight Stage 4 features once; safe status checks only. */
    fun ensureFeaturesProbed() {
        val current = _state.value
        if (current.featureBusy || current.features.isNotEmpty()) return
        viewModelScope.launch { probeFeaturesInternal() }
    }

    fun probeFeatures() {
        val current = _state.value
        if (current.featureBusy || current.featureDownload != null) return
        viewModelScope.launch { probeFeaturesInternal() }
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

    /**
     * A feature download starts only from an explicit tap and only when ML Kit reported
     * DOWNLOADABLE. Progress is whatever the public callback reported.
     */
    fun downloadFeature(feature: GenAiFeature) {
        val current = _state.value
        val result = current.featureResult(feature) ?: return
        if (!result.downloadOffered || current.featureBusy || current.featureDownload != null) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    featureDownload = feature,
                    featureDownloadText = "Requesting ${feature.displayName} from AICore…",
                    featureMessage = null,
                )
            }
            val startedAt = System.currentTimeMillis()
            val download = graph.featureSuite.download(feature) { progress ->
                _state.update {
                    it.copy(
                        featureProgress = it.featureProgress + (feature to progress),
                        featureDownloadText = progress.label,
                    )
                }
            }
            val duration = System.currentTimeMillis() - startedAt
            logFeatureDownload(feature, download, duration)
            val refreshed = graph.probeRunner.probeFeature(feature) { graph.featureSuite.probe(it) }
            logFeatureProbe(refreshed)
            _state.update {
                it.copy(
                    featureDownload = null,
                    featureDownloadText = null,
                    featureProgress = it.featureProgress - feature,
                    features = it.features.map { existing ->
                        if (existing.feature == feature) refreshed.copy(lastDownload = download) else existing
                    },
                    featureMessage = downloadMessage(feature, download),
                )
            }
            recomputeDecision()
        }
    }

    /**
     * One short fixed-input inference per AVAILABLE feature. The user must disable
     * connectivity externally; this method never touches a radio or claims a result is
     * proof of offline operation.
     */
    fun runOfflineMatrix() {
        val current = _state.value
        if (current.offlineBusy || current.featureDownload != null) return
        viewModelScope.launch {
            _state.update { it.copy(offlineBusy = true, offlineMessage = null) }
            if (current.features.isEmpty()) {
                probeFeaturesInternal()
            }
            val probed = _state.value.features
            val plan = OfflineInferencePlanning.rows(probed)
            _state.update { it.copy(offline = plan) }
            val eligible = probed.filter { it.status == FeatureStatusKind.AVAILABLE }
            if (eligible.isEmpty()) {
                _state.update {
                    it.copy(
                        offlineBusy = false,
                        offlineMessage = "No feature reports AVAILABLE; nothing was run. " +
                            "Download a DOWNLOADABLE feature first, then re-probe.",
                    )
                }
                return@launch
            }
            var results = plan
            eligible.forEach { feature ->
                val result = runCatching {
                    graph.featureSuite.runOfflineInference(feature.feature)
                }.getOrElse { throwable ->
                    OfflineInferenceResult(
                        feature = feature.feature,
                        outcome = dev.shockstruck.gemininano.model.OfflineInferenceOutcome.FAILED,
                        failure = dev.shockstruck.gemininano.model.GenAiFailure(
                            errorCode = null,
                            exceptionClass = throwable.javaClass.name,
                            message = throwable.message ?: throwable.javaClass.simpleName,
                        ),
                        detail = "inference threw before returning a result",
                    )
                }
                logOffline(result)
                results = OfflineInferencePlanning.merge(results, listOf(result))
                _state.update { it.copy(offline = results) }
            }
            _state.update {
                it.copy(
                    offlineBusy = false,
                    offline = results,
                    offlineMessage = OfflineInferenceFormatting.offlineCaveat(),
                )
            }
        }
    }

    /** One explicit action running every safe probe and building the exportable report. */
    fun runFullDiagnostics() {
        val current = _state.value
        if (current.fullReportBusy) return
        viewModelScope.launch {
            _state.update { it.copy(fullReportBusy = true, fullReportMessage = null) }
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
            val plan = PcsBindCatalog.plan(pcs)
            _state.update { current ->
                val attempted = current.bindResults
                    .filter { it.outcome.attempted }
                    .associateBy { it.componentName }
                current.copy(
                    matrix = matrix,
                    surface = surface,
                    bindPlan = plan,
                    bindResults = plan.projectedResults().map { projected ->
                        attempted[projected.componentName] ?: projected
                    },
                )
            }
            logMatrix(matrix)
            logSurface(surface)

            probeFeaturesInternal()
            probePromptInternal()
            val summarization = graph.probeRunner.probeSummarization {
                graph.summarizationProbe.probe()
            }
            graph.probeCache.store(summarization)

            val systemFeatures = withContext(Dispatchers.IO) {
                graph.systemFeatureFacts.read()
            }
            val now = System.currentTimeMillis()
            val snapshot = _state.value
            val report = reportBuilder.build(
                FullDiagnosticsInput(
                    generatedAtEpochMillis = now,
                    metadata = graph.exportMetadata(now),
                    matrix = snapshot.matrix,
                    features = snapshot.features,
                    prompt = snapshot.prompt,
                    summarization = summarization,
                    surface = snapshot.surface,
                    bindResults = snapshot.bindResults,
                    offline = snapshot.offline,
                    events = graph.diagnosticBus.snapshot(),
                    packages = inspected,
                    launchResults = snapshot.launchResults,
                    systemFeatures = systemFeatures.names,
                    systemFeatureReadError = systemFeatures.readErrorLabel,
                ),
            )
            graph.diagnosticBus.log(
                component = COMPONENT_DIAGNOSTICS,
                operation = OPERATION_FULL,
                outcome = Outcome.SUCCESS,
                severity = Severity.INFO,
                message = "Full diagnostics report generated: ${snapshot.features.size} features, " +
                    "${snapshot.prompt.size} Prompt configs, ${snapshot.matrix?.observations?.size ?: 0} matrix cells.",
                featureId = "full-diagnostics",
            )
            _state.update {
                it.copy(
                    fullReportBusy = false,
                    fullReport = report,
                    fullReportMessage = "Report ready. Export it as JSON or text; no download or bind was performed.",
                )
            }
            recomputeDecision()
        }
    }

    /** One bind per eligible service, only when the user asks. */
    fun runBindProbe() {
        val plan = _state.value.bindPlan ?: return
        if (_state.value.bindBusy || plan.eligibleCount == 0) return
        viewModelScope.launch {
            _state.update { it.copy(bindBusy = true, bindMessage = null) }
            val results = graph.safeBindProbe.probeAll(plan)
            results.forEach { logBind(it) }
            _state.update {
                it.copy(
                    bindBusy = false,
                    bindResults = results,
                    bindMessage = "Bind probe finished: ${results.count { r -> r.outcome == PcsBindOutcome.BINDER_RETURNED }} " +
                        "of ${plan.eligibleCount} eligible services returned a binder; every accepted bind " +
                        "was unbound once. These are this app's own results, not AICore's access.",
                )
            }
            recomputeDecision()
        }
    }

    /** Launches one PCS activity from an explicit user tap and records the outcome. */
    fun launchActivity(target: PcsActivityTarget) {
        if (_state.value.launchBusy) return
        viewModelScope.launch {
            _state.update { it.copy(launchBusy = true, launchMessage = null) }
            val result = graph.pcsActivityLauncher.launch(target)
            logLaunch(result)
            _state.update { current ->
                current.copy(
                    launchBusy = false,
                    launchResults = current.launchResults.filterNot {
                        it.className == result.className
                    } + result,
                    launchMessage = when (result.outcome) {
                        PcsLaunchOutcome.LAUNCHED ->
                            "${result.label} launched from your tap; nothing is read back from its screen."

                        PcsLaunchOutcome.NOT_ATTEMPTED ->
                            "${result.label}: not launched (${result.exceptionMessage ?: "not launchable"})."

                        else -> "${result.label}: ${result.outcome.label}" +
                            (result.exceptionClass?.let { " ($it)" } ?: "") +
                            (result.exceptionMessage?.let { ": $it" } ?: "")
                    },
                )
            }
        }
    }

    private fun bindPlanMessage(plan: PcsBindPlan): String = when {
        !plan.componentsRead ->
            "PCS components were not read; each bind row is NOT READ and none was attempted."

        plan.eligibleCount == 0 ->
            "No PCS service is eligible; every row is NOT RUN or reports why no bind was attempted."

        else ->
            "${plan.eligibleCount} PCS service(s) are eligible. Each row is NOT RUN until you tap the bind button; " +
                "a result would be this app's own bind, not AICore's access."
    }

    fun exportManager(): dev.shockstruck.gemininano.data.LogExportManager = graph.logExportManager()

    private suspend fun probeFeaturesInternal() {
        _state.update { it.copy(featureBusy = true, featureMessage = null) }
        val results = graph.probeRunner.probeFeatures(GenAiFeature.MATRIX) {
            graph.featureSuite.probe(it)
        }
        results.forEach { logFeatureProbe(it) }
        _state.update { it.copy(featureBusy = false, features = results) }
        recomputeDecision()
    }

    private suspend fun probePromptInternal() {
        _state.update { it.copy(promptBusy = true, promptMessage = null) }
        val results = graph.probeRunner.probePrompts(PromptModelConfig.ALL) {
            graph.promptProbe.probe(it)
        }
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

    private fun downloadMessage(feature: GenAiFeature, result: DownloadResult): String {
        if (result.success) {
            val size = result.totalBytes?.let { " ($it bytes reported)" } ?: ""
            return "${feature.displayName} download completed$size."
        }
        val category = GenAiFailureClassification.classify(result.failure).label
        val interrupted = if (result.interrupted) " The download stopped after ${result.bytesDownloaded} bytes." else ""
        return "${feature.displayName} download failed — $category." +
            " ${result.failure?.codeLabel ?: "unknown"}: ${result.failure?.message ?: "no detail"}.$interrupted " +
            "Tap Retry to try again."
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
        val message = when {
            !surface.packageVisible ->
                "PCS is not visible: exported surface cannot be enumerated (visibility limit, not absence)."

            !surface.componentsRead ->
                "PCS components were not read: ${surface.componentsReadDetail ?: "reduced package read"}."

            else ->
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

    private fun logFeatureProbe(result: FeatureProbeResult) {
        val failed = result.status == FeatureStatusKind.PROBE_ERROR
        graph.diagnosticBus.log(
            component = COMPONENT_FEATURE,
            operation = OPERATION_FEATURE_PROBE,
            outcome = if (failed) Outcome.FAILURE else Outcome.SUCCESS,
            severity = if (failed) Severity.ERROR else Severity.INFO,
            exceptionClass = result.exceptionClass,
            errorCode = result.errorCode,
            message = FeatureMatrixFormatting.renderResult(result),
            featureId = result.feature.key,
            durationMillis = result.latencyMillis,
        )
    }

    private fun logFeatureDownload(feature: GenAiFeature, result: DownloadResult, durationMillis: Long) {
        graph.diagnosticBus.log(
            component = COMPONENT_FEATURE,
            operation = OPERATION_FEATURE_DOWNLOAD,
            outcome = if (result.success) Outcome.SUCCESS else Outcome.FAILURE,
            severity = if (result.success) Severity.INFO else Severity.ERROR,
            exceptionClass = result.failure?.exceptionClass,
            errorCode = result.failure?.errorCode,
            message = "${feature.displayName} " +
                (if (result.success) "download completed" else "download failed") +
                (result.failure?.let { ": ${it.message}" } ?: "") +
                (if (result.interrupted) " (interrupted after ${result.bytesDownloaded} bytes)" else ""),
            featureId = feature.key,
            bytes = result.bytesDownloaded,
            durationMillis = durationMillis,
        )
    }

    private fun logOffline(result: OfflineInferenceResult) {
        val outcome = when (result.outcome) {
            dev.shockstruck.gemininano.model.OfflineInferenceOutcome.SUCCEEDED -> Outcome.SUCCESS
            dev.shockstruck.gemininano.model.OfflineInferenceOutcome.FAILED -> Outcome.FAILURE
            dev.shockstruck.gemininano.model.OfflineInferenceOutcome.SKIPPED -> Outcome.SKIPPED
        }
        graph.diagnosticBus.log(
            component = COMPONENT_OFFLINE,
            operation = OPERATION_INFERENCE,
            outcome = outcome,
            severity = if (outcome == Outcome.FAILURE) Severity.ERROR else Severity.INFO,
            exceptionClass = result.failure?.exceptionClass,
            errorCode = result.failure?.errorCode,
            message = OfflineInferenceFormatting.renderResult(result),
            featureId = result.feature.key,
            durationMillis = result.latencyMillis,
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
        val outcome = when {
            result.outcome == PcsBindOutcome.BINDER_RETURNED -> Outcome.SUCCESS
            !result.outcome.attempted -> Outcome.SKIPPED
            else -> Outcome.FAILURE
        }
        graph.diagnosticBus.log(
            component = COMPONENT_PCS,
            operation = OPERATION_BIND,
            outcome = outcome,
            severity = if (outcome == Outcome.FAILURE) Severity.WARN else Severity.INFO,
            exceptionClass = result.exceptionClass ?: result.descriptorErrorClass,
            message = "component=${result.componentName} role=${result.role ?: "extra"} " +
                "outcome=${result.outcome.label} attempted=${result.outcome.attempted} " +
                "bindFlag=${result.bindFlag} " +
                "interface=${result.interfaceDescriptor ?: "none"} " +
                "binderAlive=${result.binderAlive ?: "unknown"} " +
                "unbindCalls=${result.unbindCalls} " +
                (result.exceptionMessage?.let { "message=$it " } ?: ""),
            featureId = result.componentName,
            durationMillis = result.elapsedMillis,
        )
    }

    private fun logLaunch(result: PcsActivityLaunchResult) {
        val outcome = when (result.outcome) {
            PcsLaunchOutcome.LAUNCHED -> Outcome.SUCCESS
            PcsLaunchOutcome.NOT_ATTEMPTED -> Outcome.SKIPPED
            else -> Outcome.FAILURE
        }
        graph.diagnosticBus.log(
            component = COMPONENT_PCS,
            operation = OPERATION_LAUNCH,
            outcome = outcome,
            severity = if (outcome == Outcome.FAILURE) Severity.WARN else Severity.INFO,
            exceptionClass = result.exceptionClass,
            message = "activity=${result.className} outcome=${result.outcomeLabel} " +
                "resolved=${result.resolved} " +
                (result.exceptionMessage?.let { "message=$it " } ?: ""),
            featureId = result.className,
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
        const val COMPONENT_FEATURE = "feature"
        const val COMPONENT_OFFLINE = "offline"
        const val COMPONENT_DIAGNOSTICS = "diagnostics"
        const val OPERATION_MATRIX = "permissionMatrix"
        const val OPERATION_PROMPT = "probePrompt"
        const val OPERATION_DOWNLOAD = "downloadPrompt"
        const val OPERATION_SURFACE = "inspectSurface"
        const val OPERATION_BIND = "bindService"
        const val OPERATION_LAUNCH = "launchActivity"
        const val OPERATION_FEATURE_PROBE = "probeFeature"
        const val OPERATION_FEATURE_DOWNLOAD = "downloadFeature"
        const val OPERATION_INFERENCE = "runInference"
        const val OPERATION_FULL = "fullDiagnostics"
    }
}
