package dev.shockstruck.gemininano.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.shockstruck.gemininano.AppGraph
import dev.shockstruck.gemininano.model.FeatureStatusKind
import dev.shockstruck.gemininano.model.SummarizationProbeResult
import dev.shockstruck.gemininano.ui.components.Bullet
import dev.shockstruck.gemininano.ui.components.FeatureMatrixCard
import dev.shockstruck.gemininano.ui.components.PromptCard
import dev.shockstruck.gemininano.ui.components.SectionCard
import dev.shockstruck.gemininano.ui.viewmodel.InvestigationViewModel

/**
 * AICore Features: the independent four-config Prompt card, the complete public GenAI
 * feature matrix and the retained summarization status. Every row is its own probe.
 */
@Composable
fun AicoreFeaturesScreen(graph: AppGraph, modifier: Modifier = Modifier) {
    val viewModel: InvestigationViewModel = viewModel(
        factory = viewModelFactory { initializer { InvestigationViewModel(graph) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val summarization by graph.probeCache.probe.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.ensureFeaturesProbed() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
            FeatureMatrixCard(
                results = state.features,
                busy = state.featureBusy,
                downloading = state.featureDownload,
                downloadText = state.featureDownloadText,
                message = state.featureMessage,
                onProbe = viewModel::probeFeatures,
                onDownload = viewModel::downloadFeature,
            )
        }
        item { SummarizationStatusCard(summarization) }
    }
}

@Composable
private fun SummarizationStatusCard(probe: SummarizationProbeResult?) {
    SectionCard(
        title = "Summarization — retained Private Notes path",
        subtitle = "The original probe from the accepted download-button patch; not part of the new matrix.",
    ) {
        if (probe == null) {
            Text("Probe on the Overview screen to record the summarization status.", style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(
                text = "${probe.status.label} — ${probe.statusDetail}",
                style = MaterialTheme.typography.bodyMedium,
            )
            probe.baseModelName?.let {
                Text("Base model name: $it", style = MaterialTheme.typography.bodySmall)
            }
            probe.failure?.let {
                Text(
                    text = "Error ${it.codeLabel}: ${it.exceptionClass} — ${it.message}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (probe.status == FeatureStatusKind.DOWNLOADABLE) {
                Bullet("A one-time download is offered on Overview, and only from that explicit tap.")
            }
        }
        Bullet("Feature 622 is never used to derive any other feature's state.")
        Text(
            text = "The model-card probe and one-time download remain on the Overview screen; " +
                "its inference test is the retained Private Notes test.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
