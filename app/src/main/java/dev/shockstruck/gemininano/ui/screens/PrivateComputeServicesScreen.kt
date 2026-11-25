package dev.shockstruck.gemininano.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.shockstruck.gemininano.AppGraph
import dev.shockstruck.gemininano.ui.components.PcsBindCard
import dev.shockstruck.gemininano.ui.components.PcsSurfaceCard
import dev.shockstruck.gemininano.ui.components.PermissionMatrixCard
import dev.shockstruck.gemininano.ui.viewmodel.InvestigationViewModel

/**
 * Private Compute Services: the permission matrix, the exported surface and the safe
 * bind outcomes. No binder transaction or service call is ever made from this screen.
 */
@Composable
fun PrivateComputeServicesScreen(graph: AppGraph, modifier: Modifier = Modifier) {
    val viewModel: InvestigationViewModel = viewModel(
        factory = viewModelFactory { initializer { InvestigationViewModel(graph) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { PermissionMatrixCard(state.matrix) }
        item {
            PcsSurfaceCard(
                surface = state.surface,
                launchResults = state.launchResults,
                launchBusy = state.launchBusy,
                onLaunch = viewModel::launchActivity,
            )
        }
        item {
            PcsBindCard(
                results = state.bindResults,
                eligibleCount = state.eligibleBindCount,
                busy = state.bindBusy,
                message = state.bindMessage,
                onRun = viewModel::runBindProbe,
            )
        }
    }
}
