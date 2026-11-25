package dev.shockstruck.gemininano.model

enum class Severity(val rank: Int, val label: String) {
    VERBOSE(0, "VERBOSE"),
    DEBUG(1, "DEBUG"),
    INFO(2, "INFO"),
    WARN(3, "WARN"),
    ERROR(4, "ERROR"),
    ;

    companion object {
        fun fromLabel(label: String): Severity? = entries.firstOrNull { it.label == label }
    }
}

enum class Outcome(val label: String) {
    SUCCESS("success"),
    FAILURE("failure"),
    INFO("info"),
    SKIPPED("skipped"),
    CANCELLED("cancelled"),
}

/**
 * One structured diagnostic record. Everything is local; nothing in this type or its
 * consumers performs network or telemetry work.
 */
data class DiagnosticEvent(
    val timestampEpochMillis: Long,
    val component: String,
    val operation: String,
    val outcome: Outcome,
    val severity: Severity,
    val exceptionClass: String? = null,
    val errorCode: Int? = null,
    val message: String? = null,
    val featureId: String? = null,
    val bytes: Long? = null,
    val durationMillis: Long? = null,
)

/** What the app knows about the host build; all values come from android.os.Build. */
data class BuildFacts(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val product: String,
    val hardware: String,
    val fingerprint: String,
    val androidRelease: String,
    val sdkInt: Int,
    val securityPatch: String?,
    val supportedAbis: List<String>,
)

data class LabelledValue(
    val label: String,
    val value: String,
    val isUnknown: Boolean = false,
)

enum class RecommendationKind {
    OK,
    ACTION,
    WAIT,
    BLOCKED,
    UNKNOWN,
}

data class Recommendation(
    val kind: RecommendationKind,
    val headline: String,
    val detail: String,
)

/**
 * Inputs the decision tree needs, already collected from the platform. The single
 * PCS permission flag is replaced by the full requested-vs-granted matrix so no
 * principal's state is inferred from another's.
 */
data class DiagnosticSnapshot(
    val aicore: PackageFacts,
    val pcs: PackageFacts,
    val permissionMatrix: PermissionMatrix,
    val probe: SummarizationProbeResult?,
    val lastFailure: DiagnosticEvent?,
    val build: BuildFacts,
)

/** The Overview screen's data model; identical values render as rows on screen and in tests. */
data class OverviewModel(
    val rows: List<LabelledValue>,
    val recommendation: Recommendation,
    val lastFailureText: String,
)
