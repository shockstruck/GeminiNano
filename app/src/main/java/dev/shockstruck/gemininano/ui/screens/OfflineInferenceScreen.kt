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
import dev.shockstruck.gemininano.model.OfflineInferenceFormatting
import dev.shockstruck.gemininano.ui.components.Bullet
import dev.shockstruck.gemininano.ui.components.OfflineInferenceCard
import dev.shockstruck.gemininano.ui.components.SectionCard
import dev.shockstruck.gemininano.ui.viewmodel.InvestigationViewModel

/**
 * Offline Inference: one fixed-input inference per AVAILABLE feature, with exact
 * failures and latency. Connectivity is disabled externally by the user, never here.
 */
@Composable
fun OfflineInferenceScreen(graph: AppGraph, modifier: Modifier = Modifier) {
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
            SectionCard(
                title = "Before you run",
                subtitle = "Offline operation is only demonstrated by your own network change.",
            ) {
                Text(
                    text = OfflineInferenceFormatting.header(state.eligibleOfflineCount),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Bullet("This app does not request INTERNET in the offline flavor and has no network code, " +
                    "but AICore/PCS are separate processes whose network use this app cannot control.")
                Bullet(OfflineInferenceFormatting.offlineCaveat())
            }
        }
        item {
            OfflineInferenceCard(
                results = state.offline,
                busy = state.offlineBusy,
                eligible = state.eligibleOfflineCount,
                message = state.offlineMessage,
                onRun = viewModel::runOfflineMatrix,
            )
        }
    }
}
