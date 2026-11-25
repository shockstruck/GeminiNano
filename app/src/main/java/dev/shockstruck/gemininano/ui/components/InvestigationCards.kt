package dev.shockstruck.gemininano.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import dev.shockstruck.gemininano.model.FeatureStatusKind
import dev.shockstruck.gemininano.model.PackageFactsFormatting
import dev.shockstruck.gemininano.model.PcsActivityLaunchResult
import dev.shockstruck.gemininano.model.PcsActivityTarget
import dev.shockstruck.gemininano.model.PcsBindOutcome
import dev.shockstruck.gemininano.model.PcsBindResult
import dev.shockstruck.gemininano.model.PcsSurface
import dev.shockstruck.gemininano.model.PermissionMatrix
import dev.shockstruck.gemininano.model.PermissionMatrixFormatting
import dev.shockstruck.gemininano.model.PermissionStatus
import dev.shockstruck.gemininano.model.PromptConfigStatus
import dev.shockstruck.gemininano.model.PromptModelConfig

@Composable
fun PermissionMatrixCard(matrix: PermissionMatrix?, modifier: Modifier = Modifier) {
    SectionCard(
        title = "Permission matrix — requested vs granted",
        modifier = modifier,
        subtitle = "PackageManager facts only; one principal's state is never derived from another's.",
    ) {
        if (matrix == null) {
            Text("Collecting package facts…", style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        Text(
            text = "requested comes from PackageInfo.requestedPermissions; granted comes from " +
                "checkPermission and the requestedPermissionsFlags bit; the declaration comes " +
                "from getPermissionInfo. A disagreement between the two grant sources is shown " +
                "as such, and an observed grant is never hidden by “not requested”.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        matrix.principals.forEach { principal ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = principal.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            matrix.permissions.forEach { permission ->
                val observation = matrix.cell(principal.packageName, permission)
                val status = observation?.status
                KeyValueRow(
                    label = PermissionMatrixFormatting.shortPermissionLabel(permission),
                    value = PermissionMatrixFormatting.renderCell(observation),
                    isUnknown = status == PermissionStatus.UNKNOWN ||
                        status == PermissionStatus.NOT_VISIBLE ||
                        status == PermissionStatus.READ_ERROR,
                )
                Text(
                    text = PermissionMatrixFormatting.verificationLabel(observation),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Requested-but-denied signature or privileged permissions",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        val denied = matrix.requestedButDeniedSignatureOrPrivileged()
        if (denied.isEmpty()) {
            Text(
                text = "None among the matrix columns.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            denied.forEach { observation ->
                Text(
                    text = "${observation.packageName} · " +
                        PermissionMatrixFormatting.shortPermissionLabel(observation.permission) +
                        " · " + PermissionMatrixFormatting.declarationLabel(observation.declaration),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
fun PromptCard(
    prompt: List<PromptConfigStatus>,
    busy: Boolean,
    downloading: PromptModelConfig?,
    downloadText: String?,
    message: String?,
    onProbe: () -> Unit,
    onDownload: (PromptModelConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Prompt — four ModelConfig combinations",
        modifier = modifier,
        subtitle = "Independent ML Kit Prompt probe (com.google.mlkit:genai-prompt:1.0.0-beta5).",
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onProbe, enabled = !busy) {
                Text(if (busy) "Probing…" else "Re-probe all four")
            }
            Text(
                text = "Status checks run here; a model download starts only on an explicit tap.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PromptModelConfig.ALL.forEach { config ->
            val status = prompt.firstOrNull { it.config == config }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text(
                text = config.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (status == null) {
                Spacer(Modifier.height(4.dp))
                Text("not probed yet", style = MaterialTheme.typography.bodyMedium)
                return@forEach
            }
            Spacer(Modifier.height(4.dp))
            PromptStatusRows(status)
            if (status.downloadOffered) {
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { onDownload(config) },
                        enabled = !busy && downloading == null,
                    ) {
                        Text("Download ${config.label}")
                    }
                    Text(
                        text = "Requires this tap; no automatic download.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (downloading != null) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                text = downloadText ?: "Downloading ${downloading.label}…",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        message?.let { text ->
            Spacer(Modifier.height(6.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Prompt state is independent of the summarization probe: feature 622 never " +
                "derives a Prompt row, and a Prompt failure never marks summarization.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PromptStatusRows(status: PromptConfigStatus) {
    KeyValueRow(label = "Status", value = status.status.label)
    KeyValueRow(
        label = "Feature id",
        value = status.featureIdLabel,
        monospace = true,
        isUnknown = status.featureId == null,
    )
    status.errorCode?.let { KeyValueRow("Error code", it.toString(), monospace = true) }
    status.exceptionClass?.let { KeyValueRow("Exception", it, monospace = true) }
    status.message?.let { KeyValueRow("Message", it, monospace = true) }
    status.baseModelName?.let {
        KeyValueRow("Base model name", it, monospace = true)
    }
    KeyValueRow("Latency", "${status.latencyMillis} ms")
    if (status.status == FeatureStatusKind.AVAILABLE) {
        KeyValueRow(
            label = "Generation (\"Reply OK\")",
            value = if (status.generationSucceeded) "succeeded" else "not verified",
            isUnknown = !status.generationSucceeded,
        )
    }
}

@Composable
fun PcsSurfaceCard(
    surface: PcsSurface?,
    launchResults: List<PcsActivityLaunchResult>,
    launchBusy: Boolean,
    onLaunch: (PcsActivityTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Exported surface",
        modifier = modifier,
        subtitle = "PackageManager GET_SERVICES | GET_ACTIVITIES | GET_RECEIVERS | GET_PROVIDERS.",
    ) {
        if (surface == null) {
            Text("Inspecting PCS…", style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        if (!surface.packageVisible) {
            StatusBadge(
                text = "VISIBILITY LIMITED",
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "PCS is not visible to this app, so its exported components cannot be " +
                    "enumerated. This is a package-visibility limit, not proof that the " +
                    "components are absent.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else if (!surface.componentsRead) {
            StatusBadge(
                text = "COMPONENTS NOT READ",
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = (surface.componentsReadDetail ?: "The reduced package read did not return components.") +
                    " An empty surface is not claimed.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            KeyValueRow("Exported components", surface.exportedComponents.size.toString())
            if (surface.exportedComponents.isEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "No exported components were returned by the read. Check the scoped " +
                        "<queries> block before concluding that PCS has no surface.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            surface.exportedComponents.forEach { component ->
                Text(
                    text = PackageFactsFormatting.renderComponent(component),
                    modifier = Modifier.padding(vertical = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Services with a required permission are static facts only and are never bound.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Launch controls (explicit tap only)",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Shown only for an activity the installed package reports as exported, " +
                "effectively enabled and permission-free. Nothing is read back from another app's screen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        surface.launchTargets.forEach { target ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${target.label} — ${target.className}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            )
            KeyValueRow(
                label = "Launchable",
                value = if (target.launchable) "yes" else "no",
                isUnknown = !target.launchable,
            )
            Text(
                text = target.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (target.launchable) {
                TextButton(
                    onClick = { onLaunch(target) },
                    enabled = !launchBusy,
                ) {
                    Text("Open ${target.label}")
                }
            }
            launchResults.firstOrNull { it.className == target.className }?.let { result ->
                KeyValueRow("Last launch", result.outcomeLabel)
                KeyValueRow("Resolved", if (result.resolved) "yes" else "no")
                result.exceptionClass?.let { KeyValueRow("Exception", it, monospace = true) }
                result.exceptionMessage?.let { KeyValueRow("Message", it, monospace = true) }
                result.elapsedMillis?.let { KeyValueRow("Launch elapsed", "$it ms") }
            }
        }
    }
}

@Composable
fun PcsBindCard(
    results: List<PcsBindResult>,
    eligibleCount: Int,
    busy: Boolean,
    message: String?,
    onRun: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Safe bind probe",
        modifier = modifier,
        subtitle = "bindService(BIND_AUTO_CREATE) once per eligible service, 3-second wait, one unbind.",
    ) {
        TextButton(onClick = onRun, enabled = !busy && eligibleCount > 0) {
            Text(
                when {
                    busy -> "Binding…"
                    eligibleCount == 0 -> "No eligible PCS service"
                    else -> "Run bind probe ($eligibleCount eligible)"
                },
            )
        }
        Text(
            text = "No binder transaction, AIDL stub, asInterface, transaction code, reflection or " +
                "service method call is made. Only the framework's ServiceConnection callbacks and " +
                "a guarded interfaceDescriptor read are recorded. A result belongs to this app's own " +
                "bind attempt and never proves AICore's access.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (results.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("No bind plan has been collected yet.", style = MaterialTheme.typography.bodyMedium)
        }
        results.forEach { result ->
            Spacer(Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(Modifier.height(6.dp))
            Text(
                text = (result.role?.let { "$it · " } ?: "") + result.componentName,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            )
            StatusBadge(
                text = result.outcome.label.uppercase(),
                containerColor = if (result.outcome == PcsBindOutcome.BINDER_RETURNED) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (result.outcome == PcsBindOutcome.BINDER_RETURNED) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            KeyValueRow("Attempted", if (result.outcome.attempted) "yes" else "no")
            KeyValueRow("Bind flag", result.bindFlag, monospace = true)
            result.declaredName?.let { KeyValueRow("Declared name", it, monospace = true) }
            result.requiredPermission?.let { KeyValueRow("Required permission", it, monospace = true) }
            KeyValueRow("Elapsed", "${result.elapsedMillis} ms")
            if (result.outcome.attempted) {
                val descriptor = result.interfaceDescriptor
                KeyValueRow(
                    label = "Binder descriptor",
                    value = when {
                        result.descriptorErrorClass != null -> "read failed"
                        descriptor == null -> "null"
                        descriptor.isEmpty() -> "empty"
                        else -> descriptor
                    },
                    monospace = true,
                )
                KeyValueRow(
                    label = "Binder",
                    value = result.binderLabel,
                    isUnknown = result.binderAlive == null && result.outcome != PcsBindOutcome.NULL_BINDING,
                )
                result.descriptorReadMillis?.let {
                    KeyValueRow("Descriptor read", "$it ms")
                }
                result.descriptorErrorClass?.let {
                    KeyValueRow("Descriptor error", it, monospace = true)
                }
                result.descriptorErrorMessage?.let {
                    KeyValueRow("Descriptor message", it, monospace = true)
                }
            }
            result.exceptionClass?.let {
                KeyValueRow("Exception", it, monospace = true)
            }
            result.exceptionMessage?.let {
                KeyValueRow("Message", it, monospace = true)
            }
            KeyValueRow("Unbind calls", result.unbindCalls.toString())
            if (result.serviceDisconnected) {
                KeyValueRow("onServiceDisconnected", "observed")
            }
            if (result.bindingDied) {
                KeyValueRow("onBindingDied", "observed")
            }
        }
        message?.let { text ->
            Spacer(Modifier.height(8.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun ProvisioningDecisionCard(
    lines: List<String>?,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Decision",
        modifier = modifier,
        subtitle = "Each line is tied to the evidence above; unknown stays unknown.",
    ) {
        if (lines == null) {
            Text("Collecting evidence…", style = MaterialTheme.typography.bodyMedium)
        } else {
            lines.forEach { line -> Bullet(line) }
        }
    }
}
