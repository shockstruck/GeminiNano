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
import dev.shockstruck.gemininano.ui.components.Bullet
import dev.shockstruck.gemininano.ui.components.ModelManagerCard
import dev.shockstruck.gemininano.ui.components.SectionCard
import dev.shockstruck.gemininano.ui.viewmodel.InvestigationViewModel

/**
 * Model Manager: per-feature observed status, explicit downloads, progress and retry.
 * No catalog, no model sizes beyond what ML Kit reports, no automatic downloads.
 */
@Composable
fun ModelManagerScreen(graph: AppGraph, modifier: Modifier = Modifier) {
    val viewModel: InvestigationViewModel = viewModel(
        factory = viewModelFactory { initializer { InvestigationViewModel(graph) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.ensureFeaturesProbed() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ModelManagerCard(
                rows = state.modelManagerRows,
                busyFeature = state.featureDownload,
                downloadText = state.featureDownloadText,
                message = state.featureMessage,
                onDownload = viewModel::downloadFeature,
            )
        }
        item {
            SectionCard(
                title = "What this screen does not do",
                subtitle = "Honest limits of the public API and this rootless app.",
            ) {
                Bullet("It does not download a model on its own, on entry or on a schedule.")
                Bullet("It does not show a model catalog, version list or size until ML Kit reports one. " +
                    "The only size shown is the byte count from onDownloadStarted/DownloadStarted and its progress.")
                Bullet("It does not offer cancel: the public download callbacks in these SDKs expose no " +
                    "documented cancel operation, so none is invented.")
                Bullet("It cannot provision Gemini Nano. A 606 stays unprovisioned; an UNAVAILABLE stays unavailable.")
                Text(
                    text = "Prompt's four configs keep their download buttons on AICore Features. " +
                        "The retained summarization one-time download keeps its button on Overview.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
