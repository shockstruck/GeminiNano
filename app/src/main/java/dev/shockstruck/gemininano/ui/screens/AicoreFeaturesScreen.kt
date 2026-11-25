package dev.shockstruck.gemininano.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.shockstruck.gemininano.AppGraph
import dev.shockstruck.gemininano.ui.components.Bullet
import dev.shockstruck.gemininano.ui.components.PromptCard
import dev.shockstruck.gemininano.ui.components.SectionCard
import dev.shockstruck.gemininano.ui.viewmodel.InvestigationViewModel

/**
 * AICore Features in Stage 3b: the independent four-config Prompt probe. The full
 * multi-feature matrix stays an honest Stage 4 placeholder.
 */
@Composable
fun AicoreFeaturesScreen(graph: AppGraph, modifier: Modifier = Modifier) {
    val viewModel: InvestigationViewModel = viewModel(
        factory = viewModelFactory { initializer { InvestigationViewModel(graph) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

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
            SectionCard(
                title = "Stage 4 — full multi-feature matrix",
                subtitle = "Not implemented in this iteration; no guessed data is shown.",
            ) {
                Bullet("Independent probes for Rewriting, Proofreading, Image Description, Embedding, Agent, Speech Recognition, Speech Translation and Text to Speech.")
                Bullet("Per-feature download eligibility and progress through the public ML Kit callbacks only.")
                Bullet("No fabricated feature ids, model names or provisioning states.")
                Text(
                    text = "This screen will grow in Stage 4. The Prompt card above is complete and real.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
