@file:OptIn(ExperimentalMaterial3Api::class)

package dev.shockstruck.gemininano.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.shockstruck.gemininano.AppGraph
import dev.shockstruck.gemininano.ui.screens.DiagnosticLogsScreen
import dev.shockstruck.gemininano.ui.screens.OverviewScreen
import dev.shockstruck.gemininano.ui.screens.PlaceholderScreens
import dev.shockstruck.gemininano.ui.screens.PrivacyScreen
import dev.shockstruck.gemininano.ui.screens.SettingsScreen
import dev.shockstruck.gemininano.ui.screens.SystemComponentsScreen
import kotlinx.coroutines.launch

/**
 * Single-activity Material 3 shell. Wide layouts get a permanent navigation rail;
 * compact layouts (including the Pixel 10 Pro Fold outer display) get a modal drawer.
 * The selected section is saved across rotation and process death.
 */
@Composable
fun ManagerApp(graph: AppGraph) {
    var selectedName by rememberSaveable { mutableStateOf(Section.OVERVIEW.name) }
    val section = Section.entries.firstOrNull { it.name == selectedName } ?: Section.OVERVIEW

    Box(modifier = Modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            if (maxWidth >= 840.dp) {
                Row(modifier = Modifier.fillMaxSize()) {
                    NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "GNM",
                            modifier = Modifier.padding(bottom = 12.dp),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Section.entries.forEach { entry ->
                            NavigationRailItem(
                                selected = entry == section,
                                onClick = { selectedName = entry.name },
                                icon = { SectionGlyph(entry, entry == section) },
                                label = { Text(entry.shortTitle, maxLines = 1) },
                            )
                        }
                    }
                    VerticalDivider()
                    SectionHost(
                        section = section,
                        graph = graph,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize(),
                    )
                }
            } else {
                val drawerState = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        ModalDrawerSheet {
                            Text(
                                text = "Gemini Nano Manager",
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Section.entries.forEach { entry ->
                                NavigationDrawerItem(
                                    label = { Text(entry.title) },
                                    selected = entry == section,
                                    onClick = {
                                        selectedName = entry.name
                                        scope.launch { drawerState.close() }
                                    },
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                                )
                            }
                        }
                    },
                ) {
                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = { Text(section.title) },
                                navigationIcon = {
                                    TextButton(
                                        onClick = { scope.launch { drawerState.open() } },
                                    ) {
                                        Text("Sections")
                                    }
                                },
                            )
                        },
                    ) { innerPadding ->
                        SectionHost(
                            section = section,
                            graph = graph,
                            modifier = Modifier
                                .padding(innerPadding)
                                .fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionGlyph(section: Section, selected: Boolean) {
    Surface(
        shape = CircleShape,
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            Text(
                text = section.title.take(1),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun SectionHost(section: Section, graph: AppGraph, modifier: Modifier) {
    when (section) {
        Section.OVERVIEW -> OverviewScreen(graph, modifier)
        Section.SYSTEM_COMPONENTS -> SystemComponentsScreen(graph, modifier)
        Section.AICORE_FEATURES,
        Section.PRIVATE_COMPUTE_SERVICES,
        Section.MODEL_MANAGER,
        Section.PROVISIONING_DIAGNOSTICS,
        Section.OFFLINE_INFERENCE,
        -> PlaceholderScreens(section = section, modifier = modifier)

        Section.PRIVACY -> PrivacyScreen(graph, modifier)
        Section.DIAGNOSTIC_LOGS -> DiagnosticLogsScreen(graph, modifier)
        Section.SETTINGS -> SettingsScreen(graph, modifier)
    }
}
