package dev.shockstruck.gemininano.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.shockstruck.gemininano.AppGraph
import dev.shockstruck.gemininano.model.Outcome
import dev.shockstruck.gemininano.model.Severity
import dev.shockstruck.gemininano.ui.components.PcsBindCard
import dev.shockstruck.gemininano.ui.components.PcsSurfaceCard
import dev.shockstruck.gemininano.ui.components.PermissionMatrixCard
import dev.shockstruck.gemininano.ui.components.PromptCard
import dev.shockstruck.gemininano.ui.components.ProvisioningDecisionCard
import dev.shockstruck.gemininano.ui.components.SectionCard
import dev.shockstruck.gemininano.ui.viewmodel.InvestigationViewModel

/**
 * The four §14.5 blocks — permission matrix, Prompt, PCS surface/bind — plus the
 * evidence-grounded decision line and the exact feature-622 label.
 */
@Composable
fun ProvisioningDiagnosticsScreen(graph: AppGraph, modifier: Modifier = Modifier) {
    val viewModel: InvestigationViewModel = viewModel(
        factory = viewModelFactory { initializer { InvestigationViewModel(graph) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(
                title = "Evidence collection",
                subtitle = "Safe, public probes only; nothing is downloaded or provisioned automatically.",
            ) {
                TextButton(onClick = viewModel::refresh, enabled = !state.anyProbeBusy) {
                    Text(if (state.loading || state.promptBusy) "Probing…" else "Re-run safe probes")
                }
                Text(
                    text = "This re-inspects packages, rebuilds the permission matrix and the PCS " +
                        "surface, and re-checks the four Prompt configs. The bind probe stays a " +
                        "separate explicit tap on the Private Compute Services screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
