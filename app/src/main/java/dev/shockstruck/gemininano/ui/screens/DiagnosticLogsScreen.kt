package dev.shockstruck.gemininano.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.shockstruck.gemininano.AppGraph
import dev.shockstruck.gemininano.data.LogExportManager
import dev.shockstruck.gemininano.diagnostics.DiagnosticFormatting
import dev.shockstruck.gemininano.model.DiagnosticEvent
import dev.shockstruck.gemininano.model.Severity
import dev.shockstruck.gemininano.ui.components.SectionCard
import dev.shockstruck.gemininano.ui.components.StatusBadge
import dev.shockstruck.gemininano.ui.viewmodel.LogsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DiagnosticLogsScreen(graph: AppGraph, modifier: Modifier = Modifier) {
    val viewModel: LogsViewModel = viewModel(
        factory = viewModelFactory { initializer { LogsViewModel(graph) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val exportManager = remember(viewModel) { viewModel.exportManager() }
    var message by remember { mutableStateOf<String?>(null) }

    val jsonPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri?.let { target ->
            message = writeDocument(exportManager, target, json = true)
        }
    }
    val textPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        uri?.let { target ->
            message = writeDocument(exportManager, target, json = false)
        }
    }

    fun startExport(json: Boolean) {
        scope.launch {
            val directory = settings.exportDirectoryUri
            if (directory != null) {
                val treeUri = Uri.parse(directory)
                if (graph.settings.hasWritePermission(treeUri)) {
                    val result = withContext(Dispatchers.IO) {
                        exportManager.writeToDirectory(treeUri, json)
                    }
                    if (result.isSuccess) {
                        message = "Exported ${if (json) "JSON" else "text"} to the chosen directory."
                        return@launch
                    }
                    message = "Directory export failed: " +
                        (result.exceptionOrNull()?.message ?: "unknown error") +
                        ". Choose a file instead."
                }
            }
            if (json) {
                jsonPicker.launch(exportManager.suggestedFileName(true))
            } else {
                textPicker.launch(exportManager.suggestedFileName(false))
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
                title = "Filters",
                subtitle = "The buffer is bounded and lives only in process memory. Nothing is uploaded.",
            ) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search component, operation, message or code") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                Text("Minimum severity", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = state.minimumSeverity == null,
                        onClick = { viewModel.setMinimumSeverity(null) },
                        label = { Text("All") },
                    )
                    listOf(Severity.INFO, Severity.WARN, Severity.ERROR).forEach { severity ->
                        FilterChip(
                            selected = state.minimumSeverity == severity,
                            onClick = { viewModel.setMinimumSeverity(severity) },
                            label = { Text(severity.label) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("Component", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = state.component == null,
                        onClick = { viewModel.setComponent(null) },
                        label = { Text("All components") },
                    )
                    state.components.forEach { component ->
                        FilterChip(
                            selected = state.component == component,
                            onClick = { viewModel.setComponent(component) },
                            label = { Text(component) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${state.visibleEvents.size} of ${state.events.size} events",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(state.visibleEvents.joinToString("\n") {
                                DiagnosticFormatting.formatEvent(it)
                            }))
                            message = "Copied ${state.visibleEvents.size} events to the clipboard."
                        },
                    ) { Text("Copy all") }
                    TextButton(onClick = { startExport(json = true) }) { Text("Export JSON") }
                    TextButton(onClick = { startExport(json = false) }) { Text("Export text") }
                    TextButton(onClick = viewModel::clear) { Text("Clear") }
                }
                message?.let { text ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (state.isEmpty) {
            item {
                SectionCard(
                    title = "No events",
                    subtitle = "Run a probe, inspect packages or use the summarization test to produce events.",
                ) {
                    Text(
                        text = "The filter may also be hiding events; widen it above.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else {
            itemsIndexed(
                items = state.visibleEvents,
                key = { index, event -> "${event.timestampEpochMillis}-$index" },
            ) { _, event ->
                EventCard(
                    event = event,
                    onCopy = {
                        clipboard.setText(AnnotatedString(DiagnosticFormatting.formatEvent(event)))
                        message = "Copied one event."
                    },
                )
            }
        }
    }
}

@Composable
private fun EventCard(event: DiagnosticEvent, onCopy: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val container = when (event.severity) {
        Severity.ERROR -> colors.errorContainer
        Severity.WARN -> colors.secondaryContainer
        Severity.INFO -> colors.primaryContainer
        Severity.DEBUG, Severity.VERBOSE -> colors.surfaceVariant
    }
    val content = when (event.severity) {
        Severity.ERROR -> colors.onErrorContainer
        Severity.WARN -> colors.onSecondaryContainer
        Severity.INFO -> colors.onPrimaryContainer
        Severity.DEBUG, Severity.VERBOSE -> colors.onSurfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusBadge(event.severity.label, container, content)
                Text(
                    text = DiagnosticFormatting.formatTimestamp(event.timestampEpochMillis),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCopy) { Text("Copy") }
            }
            Text(
                text = "${event.component} / ${event.operation} — ${event.outcome.label}",
                style = MaterialTheme.typography.bodyMedium,
            )
            val meta = buildList {
                event.errorCode?.let { add("error=$it") }
                event.featureId?.let { add("feature=$it") }
                event.durationMillis?.let { add("durationMs=$it") }
                event.bytes?.let { add("bytes=$it") }
            }
            if (meta.isNotEmpty()) {
                Text(
                    text = meta.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            event.exceptionClass?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            event.message?.let {
                Spacer(Modifier.height(2.dp))
                Text(text = it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun writeDocument(
    exportManager: LogExportManager,
    uri: Uri,
    json: Boolean,
): String = exportManager.writeToDocument(uri, json).fold(
    onSuccess = { "Exported ${if (json) "JSON" else "text"} to the chosen file." },
    onFailure = { "Export failed: ${it.message ?: "unknown error"}" },
)
