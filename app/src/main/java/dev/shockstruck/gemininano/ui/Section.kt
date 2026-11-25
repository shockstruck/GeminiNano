package dev.shockstruck.gemininano.ui

/**
 * The ten sections the manager exposes. [implementedInStage] is null for every section
 * once Stage 4 completes; the field remains for any future honest placeholder.
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
        subtitle = "Independent Prompt probe over the four ModelConfig combinations",
        implementedInStage = null,
    ),
    PRIVATE_COMPUTE_SERVICES(
        title = "Private Compute Services",
        shortTitle = "PCS",
        subtitle = "Exported surface, permission matrix and safe bind outcomes",
        implementedInStage = null,
    ),
    MODEL_MANAGER(
        title = "Model Manager",
        shortTitle = "Models",
        subtitle = "Per-feature status, explicit downloads, progress and retry",
        implementedInStage = null,
    ),
    PROVISIONING_DIAGNOSTICS(
        title = "Provisioning Diagnostics",
        shortTitle = "Provisioning",
        subtitle = "Permission, Prompt, PCS surface and the evidence-grounded decision line",
        implementedInStage = null,
    ),
    OFFLINE_INFERENCE(
        title = "Offline Inference",
        shortTitle = "Offline",
        subtitle = "Fixed-input inference per AVAILABLE feature",
        implementedInStage = null,
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
