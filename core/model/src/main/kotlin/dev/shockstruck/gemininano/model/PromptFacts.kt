package dev.shockstruck.gemininano.model

/** The two release stages the public Prompt `ModelConfig` exposes. */
enum class PromptReleaseStage(val label: String) {
    STABLE("STABLE"),
    PREVIEW("PREVIEW"),
}

/** The two model preferences the public Prompt `ModelConfig` exposes. */
enum class PromptModelPreference(val label: String) {
    FAST("FAST"),
    FULL("FULL"),
}

data class PromptModelConfig(
    val releaseStage: PromptReleaseStage,
    val preference: PromptModelPreference,
) {
    val label: String get() = "${releaseStage.label}/${preference.label}"

    companion object {
        val ALL: List<PromptModelConfig> = listOf(
            PromptModelConfig(PromptReleaseStage.STABLE, PromptModelPreference.FAST),
            PromptModelConfig(PromptReleaseStage.STABLE, PromptModelPreference.FULL),
            PromptModelConfig(PromptReleaseStage.PREVIEW, PromptModelPreference.FAST),
            PromptModelConfig(PromptReleaseStage.PREVIEW, PromptModelPreference.FULL),
        )
    }
}

/**
 * One Prompt probe result. It is deliberately independent of
 * [SummarizationProbeResult]: feature 622 never derives Prompt state and a Prompt
 * failure never marks summarization.
 */
data class PromptConfigStatus(
    val config: PromptModelConfig,
    val status: FeatureStatusKind,
    val featureId: Int?,
    val errorCode: Int?,
    val exceptionClass: String?,
    val message: String?,
    val baseModelName: String?,
    val generatedText: String?,
    val latencyMillis: Long,
    val probedAtEpochMillis: Long,
) {
    val generationSucceeded: Boolean get() = !generatedText.isNullOrBlank()

    /** A download is offered only on an explicit user tap when ML Kit says DOWNLOADABLE. */
    val downloadOffered: Boolean get() = status == FeatureStatusKind.DOWNLOADABLE

    /** Generation is attempted only when ML Kit says AVAILABLE. */
    val generationOffered: Boolean get() = status == FeatureStatusKind.AVAILABLE

    val featureIdLabel: String get() = featureId?.toString() ?: "none parsed"

    val statusLabel: String
        get() = when {
            status == FeatureStatusKind.AVAILABLE && generationSucceeded -> "AVAILABLE · generation succeeded"
            status == FeatureStatusKind.AVAILABLE -> "AVAILABLE · generation not verified"
            status == FeatureStatusKind.PROBE_ERROR && errorCode != null -> "PROBE ERROR (code $errorCode)"
            else -> status.label
        }
}

/**
 * Parses the AICore/ML Kit 606 message shape `Feature <id> is not available` into the
 * numeric feature id. No other inference is made from the text.
 */
object PromptFailureParsing {
    private val FEATURE_ID = Regex("""Feature\s+(\d+)\s+is\s+not\s+available""", RegexOption.IGNORE_CASE)

    fun parseFeatureId(message: String?): Int? =
        message?.let { FEATURE_ID.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
}

/**
 * The Prompt surface owned by `:core:genai`. Every call returns project-owned types so
 * no ML Kit class crosses the module boundary.
 */
interface PromptProbe {
    /** `checkStatus()`, plus base model name and one short generation only when AVAILABLE. */
    suspend fun probe(config: PromptModelConfig): PromptConfigStatus

    /** Only called from an explicit user tap when [PromptConfigStatus.downloadOffered]. */
    suspend fun download(
        config: PromptModelConfig,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): DownloadResult

    fun close()
}
