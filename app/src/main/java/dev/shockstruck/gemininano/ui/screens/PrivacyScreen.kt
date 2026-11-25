package dev.shockstruck.gemininano.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.shockstruck.gemininano.AppGraph
import dev.shockstruck.gemininano.ui.components.Bullet
import dev.shockstruck.gemininano.ui.components.KeyValueRow
import dev.shockstruck.gemininano.ui.components.SectionCard
import dev.shockstruck.gemininano.ui.components.StatusBadge

@Composable
fun PrivacyScreen(graph: AppGraph, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val requestsInternet = remember(context) {
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_PERMISSIONS,
            ).requestedPermissions?.contains(Manifest.permission.INTERNET) == true
        }.getOrDefault(true)
    }
    val flavor = dev.shockstruck.gemininano.BuildConfig.FLAVOR

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(
                title = "This build's network capability",
                subtitle = "Read from the merged manifest of the installed APK.",
            ) {
                StatusBadge(
                    text = if (requestsInternet) "INTERNET REQUESTED" else "NO INTERNET PERMISSION",
                    containerColor = if (requestsInternet) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.tertiaryContainer
                    },
                    contentColor = if (requestsInternet) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    },
                )
                KeyValueRow("Build flavor", flavor)
                KeyValueRow(
                    "android.permission.INTERNET",
                    if (requestsInternet) "requested" else "not requested",
                    isUnknown = false,
                )
                if (!requestsInternet) {
                    Text(
                        text = "The default offline build removes INTERNET (and ACCESS_NETWORK_STATE) " +
                            "from the merged manifest with tools:node=\"remove\" in the offline flavor " +
                            "manifest. This app therefore cannot open a socket of its own.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        text = "This is the optional network flavor, not the delivered default build. " +
                            "It exists only for a future user-driven repository browser.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        item {
            SectionCard(
                title = "Operations that execute entirely on this device",
                subtitle = "No network, no telemetry, no account.",
            ) {
                Bullet("Reading package facts, permissions, signatures and components through PackageManager.")
                Bullet("Storing the diagnostic event buffer in process memory only; it is lost when the app is killed.")
                Bullet("Redacting note text, e-mail addresses and account-like digit strings before anything is stored in the log.")
                Bullet("Writing exports only where you point Storage Access Framework; the app has no other storage or network path.")
                Bullet("Theme, log verbosity and export-directory choice in this app's private SharedPreferences.")
            }
        }
        item {
            SectionCard(
                title = "Operations that invoke other applications",
                subtitle = "These are separate processes with their own permissions.",
            ) {
                Bullet("checkFeatureStatus(), downloadFeature() and runInference() go through the ML Kit GenAI SDK inside Google Play Services to Android AICore.")
                Bullet("AICore has no direct internet access; Android's documentation says model and configuration traffic is routed through the open-source Private Compute Services companion APK.")
                Bullet("The Play Store can update AICore, Private Compute Services and Play Services independently of this app.")
                Bullet("This app never binds to, injects into, or sends transactions to AICore or Private Compute Services.")
            }
        }
        item {
            SectionCard(
                title = "What this app's permission does not control",
                subtitle = "The important limit.",
            ) {
                Bullet("The absence of INTERNET here says nothing about AICore, Private Compute Services, Google Play Services, the Play Store or any other installed app. Each of those processes has its own network permissions.")
                Bullet("AICore/PCS may contact Google for manifests, model blobs or configuration independently of anything this app does.")
                Bullet("Disabling this app's network capability is not a guarantee that on-device AI performs no network activity; it is only a guarantee about this process.")
            }
        }
        item {
            SectionCard(
                title = "Guarantees that cannot be verified from here",
                subtitle = "Labelled rather than assumed.",
            ) {
                Bullet("Whether AICore or Private Compute Services made a network request at any given time.")
                Bullet("Which model version, adapter or configuration AICore holds internally.")
                Bullet("Whether the AICore↔PCS association, the privileged PROVIDE_PRIVATE_COMPUTE_SERVICES grant, or the certificate allow-list is satisfied on this OS build.")
                Bullet("Verified-boot or attestation state: Android does not expose it to ordinary apps.")
                Bullet("Whether a successful download was followed by model registration; no public API reports that step.")
            }
        }
        item {
            SectionCard(
                title = "Diagnostic exports",
                subtitle = "You decide where they go.",
            ) {
                Text(
                    text = "Exports contain package facts, component names, permission grants, error codes and messages. " +
                        "Note text is redacted, and e-mail addresses and account-like digit strings are replaced. " +
                        "The export is written only to the location you choose through SAF; nothing is uploaded automatically.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
