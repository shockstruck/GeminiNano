package dev.shockstruck.gemininano.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.shockstruck.gemininano.model.FeatureDownloadProgress
import dev.shockstruck.gemininano.model.FeatureMatrixFormatting
import dev.shockstruck.gemininano.model.FeatureProbeResult
import dev.shockstruck.gemininano.model.FeatureStatusKind
import dev.shockstruck.gemininano.model.GenAiFailureClassification
import dev.shockstruck.gemininano.model.GenAiFeature
import dev.shockstruck.gemininano.model.ModelManagerRow
import dev.shockstruck.gemininano.model.OfflineInferenceOutcome
import dev.shockstruck.gemininano.model.OfflineInferenceResult

/**
 * Full feature matrix: one isolated row per public ML Kit GenAI capability. Each row
 * shows only what that feature's own client returned.
 */
@Composable
fun FeatureMatrixCard(
    results: List<FeatureProbeResult>,
    busy: Boolean,
    downloading: GenAiFeature?,
    downloadText: String?,
    message: String?,
    onProbe: () -> Unit,
    onDownload: (GenAiFeature) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "GenAI feature matrix",
        modifier = modifier,
        subtitle = "Independent public-API probes; one failure never marks another feature.",
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onProbe, enabled = !busy && downloading == null) {
                Text(if (busy) "Probing…" else "Re-probe all features")
            }
            Text(
                text = "Status checks only. A download starts only from an explicit tap.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        GenAiFeature.MATRIX.forEach { feature ->
            val result = results.firstOrNull { it.feature == feature }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text(
                text = feature.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = feature.artifact,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!feature.implementedInPublicApi) {
                Spacer(Modifier.height(4.dp))
                StatusBadge(
                    text = "NOT AVAILABLE IN THE PUBLIC API",
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = feature.omissionReason.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@forEach
            }
            if (result == null) {
                Spacer(Modifier.height(4.dp))
                StatusBadge(
                    text = FeatureStatusKind.NOT_PROBED.label,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                KeyValueRow("Detail", FeatureStatusKind.NOT_PROBED_DETAIL)
                return@forEach
            }
            Spacer(Modifier.height(4.dp))
            StatusBadge(
                text = result.status.label,
                containerColor = if (result.status == FeatureStatusKind.AVAILABLE) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (result.status == FeatureStatusKind.AVAILABLE) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            KeyValueRow("Download", FeatureMatrixFormatting.eligibilityLabel(result))
            result.featureId?.let { KeyValueRow("Feature id", it.toString(), monospace = true) }
            result.errorCode?.let { KeyValueRow("Error code", it.toString(), monospace = true) }
            result.exceptionClass?.let { KeyValueRow("Exception", it, monospace = true) }
            result.message?.let { KeyValueRow("Message", it, monospace = true) }
            result.failure?.let {
                KeyValueRow("Failure family", GenAiFailureClassification.label(it))
            }
            result.baseModelName?.let { KeyValueRow("Base model name", it, monospace = true) }
            result.modelSignature?.let { KeyValueRow("Model signature", it, monospace = true) }
            result.metadataDimension?.let { KeyValueRow("Vector dimension", it.toString(), monospace = true) }
            result.tokenLimit?.let { KeyValueRow("Token limit", it.toString(), monospace = true) }
            result.detail?.let { KeyValueRow("Detail", it) }
            KeyValueRow("Probe latency", "${result.latencyMillis} ms")
            result.lastDownload?.let { download ->
                KeyValueRow(
                    label = "Last download",
                    value = if (download.success) {
                        "completed" + (download.totalBytes?.let { " ($it bytes reported)" } ?: "")
                    } else {
                        "failed: ${download.failure?.codeLabel ?: "unknown"}"
                    },
                )
                if (download.interrupted) {
                    KeyValueRow(
                        label = "Interrupted after",
                        value = "${download.bytesDownloaded} bytes",
                    )
                }
            }
            if (result.downloadOffered) {
                Spacer(Modifier.height(6.dp))
                TextButton(
                    onClick = { onDownload(feature) },
                    enabled = !busy && downloading == null,
                ) {
                    Text("Download ${feature.displayName}")
                }
            }
        }
        if (downloading != null) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                text = downloadText ?: "Downloading ${downloading.displayName}…",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        message?.let { text ->
            Spacer(Modifier.height(6.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Per-feature Model Manager: observed status plus explicit, retryable downloads. */
@Composable
fun ModelManagerCard(
    rows: List<ModelManagerRow>,
    busyFeature: GenAiFeature?,
    downloadText: String?,
    message: String?,
    onDownload: (GenAiFeature) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Per-feature models",
        modifier = modifier,
        subtitle = "No catalog and no size claims: only the status and byte counts ML Kit returns.",
    ) {
        Text(
            text = "Download buttons appear only when ML Kit reports DOWNLOADABLE. No download " +
                "is started automatically, and a failed download can be retried with the same " +
                "button. Prompt downloads stay on AICore Features; the summarization one-time " +
                "download stays on Overview.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        rows.forEach { row ->
            Spacer(Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text(
                text = row.feature.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            KeyValueRow(
                label = "Status",
                value = row.status.label,
                isUnknown = row.status == FeatureStatusKind.NOT_PROBED,
            )
            KeyValueRow("Eligibility", row.eligibility)
            row.statusDetail?.takeIf { it.isNotBlank() }?.let {
                KeyValueRow("Detail", it)
            }
            if (row.status == FeatureStatusKind.PROBE_ERROR) {
                row.failure?.let { failure ->
                    KeyValueRow("Error code", failure.codeLabel, monospace = true)
                    KeyValueRow("Exception", failure.exceptionClass, monospace = true)
                    KeyValueRow("Message", failure.message, monospace = true)
                }
                row.probeTimeLabel?.let { KeyValueRow("Probe time", it) }
            }
            row.progress?.let { progress -> DownloadProgressRows(progress) }
            row.lastDownload?.let { download ->
                KeyValueRow(
                    label = "Last download",
                    value = if (download.success) "completed" else "failed",
                )
                download.bytesDownloaded?.let { KeyValueRow("Downloaded", "$it bytes") }
                download.totalBytes?.let { KeyValueRow("Reported total", "$it bytes") }
                download.failure?.let {
                    KeyValueRow("Error", it.codeLabel, monospace = true)
                    KeyValueRow("Exception", it.exceptionClass, monospace = true)
                    KeyValueRow("Message", it.message, monospace = true)
                    KeyValueRow("Failure family", GenAiFailureClassification.label(it))
                }
                if (download.interrupted) {
                    KeyValueRow("Interrupted", "yes — bytes reported stopped before the total")
                }
            }
            if (row.downloadOffered) {
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { onDownload(row.feature) },
                        enabled = busyFeature == null,
                    ) {
                        Text(if (row.retryOffered) "Retry download" else "Download")
                    }
                    if (busyFeature == row.feature) {
                        Text(
                            text = downloadText ?: "Downloading…",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        message?.let { text ->
            Spacer(Modifier.height(8.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun DownloadProgressRows(progress: FeatureDownloadProgress) {
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    KeyValueRow("Downloaded", "${progress.downloadedBytes} bytes")
    progress.totalBytes?.let { KeyValueRow("Reported total", "$it bytes") }
}

/** Fixed-input offline inference matrix; results never claim offline proof by themselves. */
@Composable
fun OfflineInferenceCard(
    results: List<OfflineInferenceResult>,
    busy: Boolean,
    eligible: Int,
    message: String?,
    onRun: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Offline inference matrix",
        modifier = modifier,
        subtitle = "One short fixed input per AVAILABLE feature; exact errors and latency.",
    ) {
        TextButton(onClick = onRun, enabled = !busy) {
            Text(if (busy) "Running…" else "Run offline matrix ($eligible eligible)")
        }
        Text(
            text = "This app never toggles Wi-Fi or mobile data. Disable connectivity externally " +
                "before running if you want the result to be offline evidence.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        results.forEach { result ->
            Spacer(Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text(
                text = result.feature.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            StatusBadge(
                text = result.outcome.label.uppercase(),
                containerColor = if (result.outcome == OfflineInferenceOutcome.SUCCEEDED) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (result.outcome == OfflineInferenceOutcome.SUCCEEDED) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            result.output?.let { KeyValueRow("Output", it) }
            result.detail?.let { KeyValueRow("Input / detail", it) }
            result.errorCode?.let { KeyValueRow("Error code", it.toString(), monospace = true) }
            result.failure?.let {
                KeyValueRow("Exception", it.exceptionClass, monospace = true)
                KeyValueRow("Message", it.message, monospace = true)
                KeyValueRow("Failure family", GenAiFailureClassification.label(it))
            }
            KeyValueRow("Latency", "${result.latencyMillis} ms")
        }
        if (results.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("The matrix has not run in this session.", style = MaterialTheme.typography.bodyMedium)
        }
        message?.let { text ->
            Spacer(Modifier.height(8.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "The retained Private Notes summarization test stays on Overview; Prompt " +
                "generation stays on AICore Features. Neither is duplicated here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
