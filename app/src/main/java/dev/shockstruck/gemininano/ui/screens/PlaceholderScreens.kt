package dev.shockstruck.gemininano.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.shockstruck.gemininano.ui.Section
import dev.shockstruck.gemininano.ui.components.Bullet
import dev.shockstruck.gemininano.ui.components.SectionCard
import dev.shockstruck.gemininano.ui.components.StatusBadge

/**
 * Sections the Stage 2 foundation deliberately leaves empty. They state the stage and the
 * planned scope, and never show estimated or fabricated probe data.
 */
@Composable
fun PlaceholderScreens(section: Section, modifier: Modifier = Modifier) {
    val stage = section.implementedInStage ?: return
    val planned = plannedScope(section)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(title = section.title, subtitle = section.subtitle) {
                StatusBadge(
                    text = "$stage — not implemented yet",
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "This section is intentionally empty in the Stage 2 foundation build. " +
                        "It shows no device data, no estimated values and no placeholder success states.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        item {
            SectionCard(title = "Planned scope", subtitle = "What the later stage will add here.") {
                planned.forEach { line -> Bullet(line) }
            }
        }
        item {
            SectionCard(
                title = "Available now",
                subtitle = "The implemented sections are fully functional.",
            ) {
                Text(
                    text = "Overview, System Components, AICore Features, Private Compute Services, " +
                        "Provisioning Diagnostics, Privacy, Diagnostic Logs and Settings are implemented. " +
                        "The retained summarization probe and its one-time download action are on the Overview screen.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun plannedScope(section: Section): List<String> = when (section) {
    Section.MODEL_MANAGER -> listOf(
        "Per-feature download, retry and progress controls through the public ML Kit downloadFeature() callback only.",
        "No model catalog claims: model names, sizes and versions are shown only when a public API returns them.",
        "No fabricated provisioning percentages and no toggle that pretends to provision a model.",
        "Download state persists in the diagnostic log with byte counts and exact failures.",
    )

    Section.OFFLINE_INFERENCE -> listOf(
        "The full offline inference matrix for every public GenAI feature that is AVAILABLE on the device.",
        "Latency, success and exact failure reporting per feature after connectivity is disabled externally.",
        "No programmatic radio toggling and no claim of offline success from emulator or mocked results.",
        "The retained summarization test remains available on the Overview screen in the meantime.",
    )

    else -> emptyList()
}
