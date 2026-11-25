@file:OptIn(ExperimentalMaterial3Api::class)

package dev.shockstruck.gemininano.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.shockstruck.gemininano.AppGraph
import dev.shockstruck.gemininano.model.FeatureStatusKind
import dev.shockstruck.gemininano.model.LabelledValue
import dev.shockstruck.gemininano.model.OverviewModel
import dev.shockstruck.gemininano.model.Recommendation
import dev.shockstruck.gemininano.model.RecommendationKind
import dev.shockstruck.gemininano.ui.components.Bullet
import dev.shockstruck.gemininano.ui.components.KeyValueRow
import dev.shockstruck.gemininano.ui.components.LabelledValueRow
import dev.shockstruck.gemininano.ui.components.SectionCard
import dev.shockstruck.gemininano.ui.components.StatusBadge
import dev.shockstruck.gemininano.ui.viewmodel.OverviewUiState
import dev.shockstruck.gemininano.ui.viewmodel.OverviewViewModel

@Composable
fun OverviewScreen(graph: AppGraph, modifier: Modifier = Modifier) {
    val viewModel: OverviewViewModel = viewModel(
        factory = viewModelFactory { initializer { OverviewViewModel(graph) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val model = state.model

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            RecommendationCard(
                recommendation = model?.recommendation,
                probeBusy = state.probeBusy,
            )
        }
        item {
            RuntimeCard(
                model = model,
                state = state,
                onRefresh = viewModel::refresh,
            )
        }
        item {
            SummarizationCard(
                state = state,
                onDownload = viewModel::downloadModel,
                onRecheck = viewModel::refresh,
            )
        }
        item {
            SectionCard(
                title = "Most recent diagnostic failure",
                subtitle = "Taken from the local diagnostic log; nothing is uploaded.",
            ) {
                Text(
                    text = model?.lastFailureText ?: "not collected yet",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        item {
            SummarizationTestCard(
                state = state,
                onNoteChange = viewModel::onNoteChange,
                onSummarize = viewModel::summarize,
                onClear = viewModel::clearNote,
            )
        }
    }
}

@Composable
private fun RecommendationCard(recommendation: Recommendation?, probeBusy: Boolean) {
    val colors = MaterialTheme.colorScheme
    val container = when (recommendation?.kind) {
        RecommendationKind.OK -> colors.tertiaryContainer
        RecommendationKind.ACTION -> colors.primaryContainer
        RecommendationKind.WAIT -> colors.secondaryContainer
        RecommendationKind.BLOCKED -> colors.errorContainer
        RecommendationKind.UNKNOWN, null -> colors.surfaceVariant
    }
    val content = when (recommendation?.kind) {
        RecommendationKind.BLOCKED -> colors.onErrorContainer
        RecommendationKind.ACTION -> colors.onPrimaryContainer
        RecommendationKind.OK -> colors.onTertiaryContainer
        else -> colors.onSurfaceVariant
    }
    SectionCard(
        title = "Recommended next action",
        subtitle = if (probeBusy) "Probing Android AICore…" else null,
    ) {
        StatusBadge(
            text = when (recommendation?.kind) {
                RecommendationKind.OK -> "OK"
                RecommendationKind.ACTION -> "ACTION"
                RecommendationKind.WAIT -> "WAIT"
                RecommendationKind.BLOCKED -> "BLOCKED"
                RecommendationKind.UNKNOWN, null -> "UNKNOWN"
            },
            containerColor = container,
            contentColor = content,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = recommendation?.headline ?: "Waiting for the first probe",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = recommendation?.detail
                ?: "The decision tree has no data yet. Use Re-check below.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun RuntimeCard(
    model: OverviewModel?,
    state: OverviewUiState,
    onRefresh: () -> Unit,
) {
    SectionCard(
        title = "Runtime state",
        subtitle = "Values are read from the platform; anything unreadable is labelled unknown.",
    ) {
        if (model == null) {
            Text("Collecting package facts…", style = MaterialTheme.typography.bodyMedium)
        } else {
            model.rows.forEach { row -> LabelledValueRow(row) }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onRefresh, enabled = !state.probeBusy) {
                Text("Re-check")
            }
            if (state.probeBusy) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Probing…", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SummarizationCard(
    state: OverviewUiState,
    onDownload: () -> Unit,
    onRecheck: () -> Unit,
) {
    val statusRow = state.model.row("Summarization feature status")
    val baseModelRow = state.model.row("Base model name")

    SectionCard(
        title = "Summarization feature",
        subtitle = "Real ML Kit probe through Android AICore (com.google.mlkit:genai-summarization:1.0.0-beta1).",
    ) {
        KeyValueRow(
            label = "Feature status",
            value = statusRow?.value ?: FeatureStatusKind.UNKNOWN.label,
            isUnknown = statusRow?.isUnknown ?: true,
        )
        KeyValueRow(
            label = "Base model name",
            value = baseModelRow?.value ?: "unknown",
            isUnknown = baseModelRow?.isUnknown ?: true,
            monospace = true,
        )
        state.probeStatusDetail?.let { detail ->
            KeyValueRow(label = "Status detail", value = detail)
        }
        state.probeFailure?.let { failure ->
            KeyValueRow(label = "Error code", value = failure.codeLabel, monospace = true)
            KeyValueRow(label = "Exception", value = failure.exceptionClass, monospace = true)
            KeyValueRow(label = "Message", value = failure.message, monospace = true)
        }
        Spacer(Modifier.height(10.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onDownload, enabled = state.canDownload) {
                Text("Download Gemini Nano")
            }
            TextButton(onClick = onRecheck, enabled = !state.probeBusy && !state.downloadBusy) {
                Text("Re-check")
            }
        }
        if (state.downloadBusy) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        state.downloadProgressText?.let { progress ->
            Spacer(Modifier.height(4.dp))
            Text(progress, style = MaterialTheme.typography.bodySmall)
        }
        state.actionMessage?.let { message ->
            Spacer(Modifier.height(6.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
        if (!state.canDownload && state.probeStatus != FeatureStatusKind.DOWNLOADABLE) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "The download action is only offered when ML Kit reports DOWNLOADABLE. " +
                    "This app never fabricates a download or a provisioning success.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SummarizationTestCard(
    state: OverviewUiState,
    onNoteChange: (String) -> Unit,
    onSummarize: () -> Unit,
    onClear: () -> Unit,
) {
    SectionCard(
        title = "Summarization test (retained Private Notes probe)",
        subtitle = "The original on-device summarization, kept as one probe through :core:genai. " +
            "Note text is redacted from the diagnostic log and is never sent anywhere by this app.",
    ) {
        OutlinedTextField(
            value = state.noteText,
            onValueChange = onNoteChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Note") },
            minLines = 3,
            maxLines = 8,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onSummarize, enabled = state.canSummarize) {
                Text("Summarize on device")
            }
            TextButton(onClick = onClear, enabled = !state.inferenceBusy) {
                Text("Clear")
            }
            if (state.inferenceBusy) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }
        state.inferenceSummary?.let { summary ->
            Spacer(Modifier.height(10.dp))
            Text("Summary", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(summary, style = MaterialTheme.typography.bodyMedium)
        }
        state.inferenceError?.let { error ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "The error code and message above are shown exactly as returned.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.probeStatus == FeatureStatusKind.PROBE_ERROR) {
            Spacer(Modifier.height(10.dp))
            Bullet("A 606 result means AICore has no configuration entry for feature 622; it is reported as unprovisioned, never as success.")
        }
    }
}

private fun OverviewModel?.row(label: String): LabelledValue? =
    this?.rows?.firstOrNull { it.label == label }
