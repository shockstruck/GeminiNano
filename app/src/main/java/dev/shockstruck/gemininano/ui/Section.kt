package dev.shockstruck.gemininano.ui

/**
 * The ten sections the manager exposes. [implementedInStage] is null for sections that
 * are fully implemented in this Stage 2 build.
 */
enum class Section(
    val title: String,
    val shortTitle: String,
    val subtitle: String,
    val implementedInStage: String?,
) {
    OVERVIEW(
        title = "Overview",
        shortTitle = "Overview",
        subtitle = "Runtime state and the recommended next action",
        implementedInStage = null,
    ),
    SYSTEM_COMPONENTS(
        title = "System Components",
        shortTitle = "Components",
        subtitle = "Packages, permissions, components and visibility limits",
        implementedInStage = null,
    ),
    AICORE_FEATURES(
        title = "AICore Features",
        shortTitle = "Features",
        subtitle = "Independent probes for every public ML Kit GenAI feature",
        implementedInStage = "Stage 3",
    ),
    PRIVATE_COMPUTE_SERVICES(
        title = "Private Compute Services",
        shortTitle = "PCS",
        subtitle = "Component discovery and supported binding probes",
        implementedInStage = "Stage 4",
    ),
    MODEL_MANAGER(
        title = "Model Manager",
        shortTitle = "Models",
        subtitle = "Per-feature download and provisioning controls",
        implementedInStage = "Stage 3/4",
    ),
    PROVISIONING_DIAGNOSTICS(
        title = "Provisioning Diagnostics",
        shortTitle = "Provisioning",
        subtitle = "The full diagnostic report and decision tree",
        implementedInStage = "Stage 3/4",
    ),
    OFFLINE_INFERENCE(
        title = "Offline Inference",
        shortTitle = "Offline",
        subtitle = "The full offline inference matrix",
        implementedInStage = "Stage 3",
    ),
    PRIVACY(
        title = "Privacy",
        shortTitle = "Privacy",
        subtitle = "What runs locally and what this app cannot control",
        implementedInStage = null,
    ),
    DIAGNOSTIC_LOGS(
        title = "Diagnostic Logs",
        shortTitle = "Logs",
        subtitle = "Structured, local, exportable diagnostic events",
        implementedInStage = null,
    ),
    SETTINGS(
        title = "Settings",
        shortTitle = "Settings",
        subtitle = "Theme, export directory, log verbosity and provenance",
        implementedInStage = null,
    ),
}

object SectionCatalog {
    val sections: List<Section> = Section.entries

    val implemented: List<Section> = sections.filter { it.implementedInStage == null }

    val staged: List<Section> = sections.filter { it.implementedInStage != null }
}
