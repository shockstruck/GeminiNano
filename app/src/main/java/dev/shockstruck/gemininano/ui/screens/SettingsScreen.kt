package dev.shockstruck.gemininano.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.shockstruck.gemininano.AppGraph
import dev.shockstruck.gemininano.BuildConfig
import dev.shockstruck.gemininano.data.ThemeMode
import dev.shockstruck.gemininano.model.Severity
import dev.shockstruck.gemininano.ui.components.Bullet
import dev.shockstruck.gemininano.ui.components.KeyValueRow
import dev.shockstruck.gemininano.ui.components.SectionCard
import dev.shockstruck.gemininano.ui.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(graph: AppGraph, modifier: Modifier = Modifier) {
    val viewModel: SettingsViewModel = viewModel(
        factory = viewModelFactory { initializer { SettingsViewModel(graph) } },
    )
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val bindHistoryCount by viewModel.bindHistoryCount.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }

    val directoryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            viewModel.setExportDirectory(uri)
            message = "Export directory set. Exports will be written there when it is writable."
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(title = "Theme", subtitle = "Applies immediately and survives restarts.") {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.theme == mode,
                            onClick = { viewModel.setTheme(mode) },
                            label = { Text(mode.label) },
                        )
                    }
                }
            }
        }
        item {
            SectionCard(
                title = "Diagnostic log verbosity",
                subtitle = "Events below the chosen severity are not stored.",
            ) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Severity.entries.forEach { severity ->
                        FilterChip(
                            selected = settings.logVerbosity == severity,
                            onClick = { viewModel.setLogVerbosity(severity) },
                            label = { Text(severity.label) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Default is VERBOSE so every probe outcome is visible. The buffer is bounded at 500 events.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SectionCard(
                title = "Export directory",
                subtitle = "Chosen with the Storage Access Framework; the app holds only that grant.",
            ) {
                KeyValueRow(
                    label = "Current directory",
                    value = settings.exportDirectoryUri ?: "not set (exports use the file picker)",
                    isUnknown = settings.exportDirectoryUri == null,
                    monospace = true,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { directoryPicker.launch(null) }) {
                        Text("Choose directory")
                    }
                    OutlinedButton(
                        onClick = {
                            viewModel.setExportDirectory(null)
                            message = "Export directory cleared."
                        },
                        enabled = settings.exportDirectoryUri != null,
                    ) {
                        Text("Clear")
                    }
                }
            }
        }
        item {
            SectionCard(
                title = "Probe state",
                subtitle = "Nothing here can provision a model.",
            ) {
                Button(
                    onClick = {
                        viewModel.resetProbes()
                        message = "Stored probe result cleared. Use Re-check on Overview to probe again."
                    },
                ) {
                    Text("Reset probes")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "There is deliberately no toggle that claims to provision, activate or register a model. " +
                        "Only the public ML Kit download request is offered, and only when ML Kit reports DOWNLOADABLE.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        message?.let { text ->
            item {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SectionCard(
                title = "PCS bind history",
                subtitle = "Kept only in this app's private storage; the last five results per component.",
            ) {
                KeyValueRow(
                    label = "Stored results",
                    value = bindHistoryCount.toString(),
                    isUnknown = bindHistoryCount == 0,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        viewModel.clearBindHistory()
                        message = if (bindHistoryCount == 0) {
                            "No stored bind history to clear."
                        } else {
                            "Stored bind history cleared. The next diagnostics run starts a new history."
                        }
                    },
                ) {
                    Text("Clear bind history")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Clearing removes the stored observations only. Files you already exported " +
                        "are unchanged, and a later NOT RUN row never overwrites an earlier observation " +
                        "until you clear it here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SectionCard(
                title = "Version and provenance",
                subtitle = "No secrets, no proprietary artifacts.",
            ) {
                KeyValueRow("Application ID", BuildConfig.APPLICATION_ID, monospace = true)
                KeyValueRow("Version name", BuildConfig.VERSION_NAME, monospace = true)
                KeyValueRow("Version code", BuildConfig.VERSION_CODE.toString(), monospace = true)
                KeyValueRow("Build flavor", BuildConfig.FLAVOR, monospace = true)
                KeyValueRow("Build type", BuildConfig.BUILD_TYPE, monospace = true)
                Spacer(Modifier.height(8.dp))
                Bullet("Derived from the public anandgaur22/GeminiNano demo, which ships no licence file; this is an internal experimental derivative.")
                Bullet("No Google APKs, model binaries, keys or restricted artifacts are included in this app or its repository.")
                Bullet("Not affiliated with or endorsed by Google. Android AICore, Private Compute Services and Gemini Nano are Google components.")
                Bullet("0.4.0: all ten sections are implemented — Overview, System Components, AICore Features, Private Compute Services, Model Manager, Provisioning Diagnostics, Offline Inference, Privacy, Diagnostic Logs and Settings.")
            }
        }
    }
}
