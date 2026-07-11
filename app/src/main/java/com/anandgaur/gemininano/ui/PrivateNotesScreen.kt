package com.anandgaur.gemininano.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anandgaur.gemininano.NotesUiState
import com.anandgaur.gemininano.SummarizerViewModel

/**
 * The whole app is one screen. It reads immutable [NotesUiState] from the ViewModel and
 * renders the matching UI. Notice there's no AI code here at all — the UI only reacts to
 * state, which keeps the on-device inference cleanly separated (MVVM).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivateNotesScreen(viewModel: SummarizerViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showInfo by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Private Notes", fontWeight = FontWeight.Bold) },
                actions = {
                    // "About" opens a screen contrasting on-device vs cloud.
                    TextButton(onClick = { showInfo = true }) { Text("About") }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // ── The teaching badges: make "on-device" impossible to miss. ──
            OnDeviceBadges()

            Spacer(Modifier.height(16.dp))

            Text(
                "Write or paste a note, then summarize it — the model runs on your phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))

            // ── The note editor. ──
            OutlinedTextField(
                value = state.noteText,
                onValueChange = viewModel::onNoteChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp),
                label = { Text("Your note") },
                placeholder = { Text("Paste long meeting notes or a journal entry…") },
                enabled = !state.isBusy,
            )

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = viewModel::summarize,
                enabled = state.canSummarize,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.isBusy) "Working…" else "Summarize")
            }

            Spacer(Modifier.height(16.dp))

            // ── Render whatever phase we're in. ──
            when (state.phase) {
                NotesUiState.Phase.PreparingModel ->
                    DownloadCard(fraction = state.downloadFraction, label = state.downloadLabel)

                NotesUiState.Phase.Summarizing ->
                    StatusCard("Summarizing on your phone…", showSpinner = true)

                NotesUiState.Phase.Success -> SummaryCard(state.summary)

                NotesUiState.Phase.Error ->
                    MessageCard(title = "Something went wrong", body = state.message)

                NotesUiState.Phase.NotSupported ->
                    MessageCard(title = "On-device AI unavailable", body = state.message)

                NotesUiState.Phase.Idle -> { /* nothing to show yet */ }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showInfo) {
        OnDeviceVsCloudDialog(onDismiss = { showInfo = false })
    }
}

/** Horizontal strip of badges: [On-device] [Offline] [Private] [Free]. */
@Composable
private fun OnDeviceBadges() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Badge("On-device")
        Badge("Offline")
        Badge("Private · No data leaves phone")
        Badge("Free")
    }
}

@Composable
private fun Badge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** A loading card with a spinner and a status line ("Preparing…" / "Summarizing…"). */
@Composable
private fun StatusCard(text: String, showSpinner: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showSpinner) CircularProgressIndicator(modifier = Modifier.height(24.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/**
 * One-time model download card. Shows a real progress bar + "42%  (128 MB / 305 MB)".
 * If the total size isn't known yet, the bar is indeterminate and shows "Starting download…".
 */
@Composable
private fun DownloadCard(fraction: Float?, label: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Preparing on-device model…",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "One-time download over wifi. After this it runs fully offline.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            if (fraction == null) {
                // Size unknown → indeterminate animated bar.
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                // Known size → determinate bar that fills up as bytes arrive.
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Shows the bullet-point summary plus the "never left this phone" reassurance line. */
@Composable
private fun SummaryCard(summary: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Summary",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                summary.ifBlank { "(The model returned an empty summary — try a longer note.)" },
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Summarized on-device by Gemini Nano — your note never left this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/** Neutral message card for errors and the "not supported" state. */
@Composable
private fun MessageCard(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The "i" info screen: on-device (Nano) vs cloud (Gemini API). */
@Composable
private fun OnDeviceVsCloudDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
        title = { Text("On-device vs Cloud") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                CompareRow("", "On-device (Nano)", "Cloud (Gemini API)", header = true)
                CompareRow("Privacy", "Note stays on phone", "Note sent to servers")
                CompareRow("Offline", "Works with no internet", "Needs a connection")
                CompareRow("Cost", "Free", "Pay per request")
                CompareRow("Setup", "No API key", "API key required")
                CompareRow("Capability", "Small, fast, focused", "Largest, most capable")
                Spacer(Modifier.height(12.dp))
                Text(
                    "This app is on-device only. It never falls back to the cloud — that's the point.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
    )
}

@Composable
private fun CompareRow(label: String, onDevice: String, cloud: String, header: Boolean = false) {
    val weight = if (header) FontWeight.Bold else FontWeight.Normal
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, modifier = Modifier.weight(0.9f), fontSize = 12.sp, fontWeight = weight)
        Text(onDevice, modifier = Modifier.weight(1.2f), fontSize = 12.sp, fontWeight = weight)
        Text(cloud, modifier = Modifier.weight(1.2f), fontSize = 12.sp, fontWeight = weight)
    }
}
