package dev.shockstruck.gemininano.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.shockstruck.gemininano.AppGraph
import dev.shockstruck.gemininano.diagnostics.DiagnosticFormatting
import dev.shockstruck.gemininano.model.FeatureStatusKind
import dev.shockstruck.gemininano.model.Outcome
import dev.shockstruck.gemininano.model.Severity
import dev.shockstruck.gemininano.ui.components.KeyValueRow
import dev.shockstruck.gemininano.ui.components.PcsBindCard
import dev.shockstruck.gemininano.ui.components.PcsSurfaceCard
import dev.shockstruck.gemininano.ui.components.PermissionMatrixCard
import dev.shockstruck.gemininano.ui.components.PromptCard
import dev.shockstruck.gemininano.ui.components.ProvisioningDecisionCard
import dev.shockstruck.gemininano.ui.components.SectionCard
import dev.shockstruck.gemininano.ui.viewmodel.InvestigationViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The evidence blocks — permission matrix, Prompt, feature statuses, PCS surface/bind —
 * plus one explicit **Run Full Diagnostics** action that builds an exportable JSON and
 * text report. The PCS bind probe remains a separate explicit tap.
 */
@Composable
fun ProvisioningDiagnosticsScreen(graph: AppGraph, modifier: Modifier = Modifier) {
    val viewModel: InvestigationViewModel = viewModel(
        factory = viewModelFactory { initializer { InvestigationViewModel(graph) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exportManager = remember(viewModel) { viewModel.exportManager() }
    var exportMessage by remember { mutableStateOf<String?>(null) }

    val jsonPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri?.let { target ->
            exportMessage = exportManager.writePayloadToDocument(target, state.fullReport?.json.orEmpty())
                .fold(
                    onSuccess = { "Full report exported as JSON." },
                    onFailure = { "Export failed: ${it.message ?: "unknown error"}" },
                )
        }
    }
    val textPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        uri?.let { target ->
            exportMessage = exportManager.writePayloadToDocument(target, state.fullReport?.text.orEmpty())
                .fold(
                    onSuccess = { "Full report exported as text." },
                    onFailure = { "Export failed: ${it.message ?: "unknown error"}" },
                )
        }
    }

    fun startExport(json: Boolean) {
        val report = state.fullReport ?: return
        val payload = if (json) report.json else report.text
        scope.launch {
            val directory = settings.exportDirectoryUri
            if (directory != null) {
                val treeUri = Uri.parse(directory)
                if (graph.settings.hasWritePermission(treeUri)) {
                    val result = withContext(Dispatchers.IO) {
                        exportManager.writePayloadToDirectory(
                            treeUri = treeUri,
                            fileName = exportManager.fullReportFileName(json),
                            mimeType = exportManager.mimeType(json),
                            payload = payload,
                        )
                    }
                    exportMessage = result.fold(
                        onSuccess = { "Full report exported to the configured directory." },
                        onFailure = { "Export failed: ${it.message ?: "unknown error"}" },
                    )
                    return@launch
                }
            }
            if (json) {
                jsonPicker.launch(exportManager.fullReportFileName(true))
            } else {
                textPicker.launch(exportManager.fullReportFileName(false))
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(
                title = "Run full diagnostics",
                subtitle = "One explicit action; safe probes only, no download and no bind.",
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = viewModel::runFullDiagnostics,
                        enabled = !state.anyProbeBusy,
                    ) {
                        Text(if (state.fullReportBusy) "Running…" else "Run Full Diagnostics")
                    }
                    TextButton(
                        onClick = { startExport(json = true) },
                        enabled = state.fullReport != null,
                    ) { Text("Export JSON") }
                    TextButton(
                        onClick = { startExport(json = false) },
                        enabled = state.fullReport != null,
                    ) { Text("Export text") }
                }
                Text(
                    text = "The action re-inspects packages, rebuilds the permission matrix, probes " +
                        "every GenAI feature status and the four Prompt configs, records the PCS " +
                        "surface and includes the existing diagnostic snapshot. The PCS bindService " +
                        "probe stays a separate explicit tap below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.fullReport?.let { report ->
                    Spacer(Modifier.height(8.dp))
                    KeyValueRow(
                        "Report generated",
                        DiagnosticFormatting.formatTimestamp(report.generatedAtEpochMillis),
                    )
                    KeyValueRow("Features", state.features.size.toString())
                    KeyValueRow("Prompt configs", state.prompt.size.toString())
                    KeyValueRow("Permission cells", state.matrix?.observations?.size?.toString() ?: "0")
                    KeyValueRow("Diagnostic events", graph.diagnosticBus.snapshot().size.toString())
                    KeyValueRow(
                        "PCS bind results",
                        if (state.bindResults.isEmpty()) "not run" else state.bindResults.size.toString(),
                    )
                    KeyValueRow("Format", "JSON and text, through the existing SAF export path")
                }
                state.fullReportMessage?.let { text ->
                    Spacer(Modifier.height(6.dp))
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                }
                exportMessage?.let { text ->
                    Spacer(Modifier.height(6.dp))
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "No automatic large download: every probe is a status/metadata read. " +
                        "The report contains no note text or personal data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SectionCard(
                title = "Feature status summary",
                subtitle = "One row per public GenAI capability from the last probe.",
            ) {
                val results = state.features
                if (results.isEmpty()) {
                    Text(
                        if (state.featureBusy) "Probing…" else "Run Full Diagnostics to collect the feature statuses.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    results.forEach { result ->
                        KeyValueRow(
                            label = result.feature.displayName,
                            value = result.status.label,
                            isUnknown = result.status == FeatureStatusKind.PROBE_ERROR,
                        )
                    }
                }
            }
        }
        item { PermissionMatrixCard(state.matrix) }
        item {
            PromptCard(
                prompt = state.prompt,
                busy = state.promptBusy,
                downloading = state.promptDownloadConfig,
                downloadText = state.promptDownloadText,
                message = state.promptMessage,
                onProbe = viewModel::probePrompt,
                onDownload = viewModel::downloadPrompt,
            )
        }
        item {
            PcsSurfaceCard(
                surface = state.surface,
                onOpenSettings = {
                    runCatching { context.startActivity(graph.pcsSurfaceInspector.settingsIntent()) }
                        .onFailure { failure ->
                            graph.diagnosticBus.log(
                                component = "pcs",
                                operation = "openSettings",
                                outcome = Outcome.FAILURE,
                                severity = Severity.ERROR,
                                exceptionClass = failure.javaClass.name,
                                message = failure.message,
                            )
                        }
                },
            )
        }
        item {
            PcsBindCard(
                results = state.bindResults,
                busy = state.bindBusy,
                message = state.bindMessage,
                onRun = viewModel::runBindProbe,
            )
        }
        item { ProvisioningDecisionCard(state.decision?.lines) }
    }
}
