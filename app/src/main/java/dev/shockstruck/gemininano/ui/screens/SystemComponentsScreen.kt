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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import dev.shockstruck.gemininano.core.android.PackageCatalog
import dev.shockstruck.gemininano.model.ComponentFacts
import dev.shockstruck.gemininano.model.ComponentKind
import dev.shockstruck.gemininano.model.PackageFacts
import dev.shockstruck.gemininano.model.PackageFactsFormatting
import dev.shockstruck.gemininano.model.PackageVisibility
import dev.shockstruck.gemininano.model.PermissionMatrixFormatting
import dev.shockstruck.gemininano.ui.components.Bullet
import dev.shockstruck.gemininano.ui.components.KeyValueRow
import dev.shockstruck.gemininano.ui.components.MonoText
import dev.shockstruck.gemininano.ui.components.SectionCard
import dev.shockstruck.gemininano.ui.components.StatusBadge
import dev.shockstruck.gemininano.ui.viewmodel.SystemComponentsViewModel

private val DEFAULT_EXPANDED = setOf(
    PackageCatalog.AICORE_PACKAGE,
    PackageCatalog.AICORE_LEGACY_PACKAGE,
    PackageCatalog.PCS_PACKAGE,
    PackageCatalog.ASI_PACKAGE,
)

@Composable
fun SystemComponentsScreen(graph: AppGraph, modifier: Modifier = Modifier) {
    val viewModel: SystemComponentsViewModel = viewModel(
        factory = viewModelFactory { initializer { SystemComponentsViewModel(graph) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(
                title = "Package facts and visibility limits",
                subtitle = "Read through public PackageManager APIs under a scoped <queries> block.",
            ) {
                Bullet("QUERY_ALL_PACKAGES is not requested. If a package is absent or hidden by package visibility, the row says “not visible” instead of guessing.")
                Bullet("Permission grants are read with checkPermission; Android does not expose another app's runtime permission state in any other way.")
                Bullet("Android does not expose other packages' intent-filter XML. Component actions below come from querying a fixed set of common actions and recording the matching filters.")
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = viewModel::refresh, enabled = !state.loading) {
                    Text(if (state.loading) "Inspecting…" else "Re-inspect packages")
                }
            }
        }
        items(items = state.packages, key = { it.packageName }) { packageFacts ->
            PackageCard(packageFacts)
        }
    }
}

@Composable
private fun PackageCard(facts: PackageFacts) {
    var expanded by rememberSaveable(facts.packageName) {
        mutableStateOf(facts.packageName in DEFAULT_EXPANDED)
    }
    SectionCard(
        title = facts.packageName,
        subtitle = facts.packageName,
    ) {
        if (facts.visibility == PackageVisibility.READ_ERROR) {
            StatusBadge(
                text = "READ ERROR",
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Reading this package failed: " +
                    (facts.readError?.label ?: "class and message unavailable") +
                    ". This is a read error, not evidence that the package is absent.",
                style = MaterialTheme.typography.bodyMedium,
            )
            return@SectionCard
        }
        if (!facts.visible) {
            StatusBadge(
                text = "NOT VISIBLE",
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = PackageFactsFormatting.NOT_VISIBLE +
                    ". It may be absent, or Android package visibility may be hiding it even though it is declared in the app's <queries> block.",
                style = MaterialTheme.typography.bodyMedium,
            )
            return@SectionCard
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusBadge(
                text = "INSTALLED",
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            StatusBadge(
                text = if (facts.enabled == true) "ENABLED" else "DISABLED",
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(Modifier.height(8.dp))
        KeyValueRow("Version", PackageFactsFormatting.versionLabel(facts))
        KeyValueRow("Installer", PackageFactsFormatting.installerLabel(facts), monospace = true)
        KeyValueRow("SDK", PackageFactsFormatting.sdkLabel(facts))
        KeyValueRow("Requested permissions", PackageFactsFormatting.requestedPermissionsLabel(facts))
        KeyValueRow("Components", PackageFactsFormatting.componentsLabel(facts))
        KeyValueRow("Application metadata", PackageFactsFormatting.metadataLabel(facts))
        KeyValueRow("Install times", PackageFactsFormatting.installTimesLabel(facts))
        KeyValueRow(
            label = "Signing certificate SHA-256",
            value = PackageFactsFormatting.signingLabel(facts),
            monospace = true,
        )

        Spacer(Modifier.height(6.dp))
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Hide details" else "Show permissions, components and metadata")
        }

        if (expanded) {
            Spacer(Modifier.height(4.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text(
                "Permission verification — requested, checkPermission, requestedPermissionsFlags, declaration",
                fontWeight = FontWeight.SemiBold,
            )
            if (!facts.permissionsRead) {
                MonoText(
                    text = "permissions not read on this reduced package read; not requested cannot be concluded",
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            } else {
                facts.permissionGrants.forEach { grant ->
                    MonoText(
                        text = PermissionMatrixFormatting.renderPermissionFact(grant),
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("Requested-but-denied signature or privileged", fontWeight = FontWeight.SemiBold)
                val denied = facts.requestedButDeniedSignatureOrPrivileged
                if (denied.isEmpty()) {
                    MonoText("none", modifier = Modifier.padding(vertical = 1.dp))
                } else {
                    denied.forEach { grant ->
                        MonoText(
                            text = PermissionMatrixFormatting.renderPermissionFact(grant),
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Requested permissions (${facts.requestedPermissions.size})", fontWeight = FontWeight.SemiBold)
            facts.requestedPermissions.forEach { permission ->
                MonoText(permission, modifier = Modifier.padding(vertical = 1.dp))
            }
            if (facts.metadata.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Application metadata", fontWeight = FontWeight.SemiBold)
                facts.metadata.forEach { entry ->
                    MonoText(PackageFactsFormatting.renderMetadata(entry), modifier = Modifier.padding(vertical = 1.dp))
                }
            }
            ComponentKind.entries.forEach { kind ->
                val components = facts.components.filter { it.kind == kind }
                if (components.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("${kind.label.replaceFirstChar { it.uppercase() }}s (${components.size})", fontWeight = FontWeight.SemiBold)
                    components.forEach { component ->
                        ComponentLine(component)
                    }
                }
            }
        }
    }
}

@Composable
private fun ComponentLine(component: ComponentFacts) {
    Text(
        text = PackageFactsFormatting.renderComponent(component),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
}
