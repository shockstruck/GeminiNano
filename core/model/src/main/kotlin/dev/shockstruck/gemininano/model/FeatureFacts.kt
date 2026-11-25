package dev.shockstruck.gemininano.model

/** Normalised ML Kit GenAI feature status. UNAVAILABLE and an exception are distinct. */
enum class FeatureStatusKind(val label: String) {
    AVAILABLE("AVAILABLE"),
    DOWNLOADABLE("DOWNLOADABLE"),
    DOWNLOADING("DOWNLOADING"),
    UNAVAILABLE("UNAVAILABLE"),
    UNKNOWN("UNKNOWN"),
    PROBE_ERROR("PROBE ERROR"),

    /** The published artifact has no usable public surface for this app. */
    NOT_IN_PUBLIC_API("NOT AVAILABLE IN THE PUBLIC API"),
}

/** A project-owned copy of an ML Kit GenAI failure; no SDK type crosses the module boundary. */
data class GenAiFailure(
    val errorCode: Int?,
    val exceptionClass: String,
    val message: String,
) {
    val codeLabel: String get() = errorCode?.toString() ?: "unknown"
}

data class SummarizationProbeResult(
    val status: FeatureStatusKind,
    val statusDetail: String,
    val baseModelName: String?,
    val failure: GenAiFailure?,
    val probedAtEpochMillis: Long,
) {
    val featureId: String get() = FEATURE_ID

    companion object {
        const val FEATURE_ID = "summarization"
    }
}

data class DownloadResult(
    val success: Boolean,
    val bytesDownloaded: Long?,
    val totalBytes: Long?,
    val failure: GenAiFailure?,
) {
    /**
     * A failed download that had started and reported fewer bytes than its own total.
     * This is the only interruption signal the public callbacks expose; no other
     * cause is inferred from it.
     */
    val interrupted: Boolean
        get() = !success &&
            bytesDownloaded != null &&
            totalBytes != null &&
            bytesDownloaded > 0 &&
            bytesDownloaded < totalBytes
}

data class SummarizationRunResult(
    val success: Boolean,
    val summary: String?,
    val latencyMillis: Long,
    val failure: GenAiFailure?,
)

/**
 * The one ML Kit GenAI surface the app owns: the retained Private Notes summarization.
 * Implemented only in `:core:genai`; every other module sees these plain types.
 */
interface SummarizationProbe {
    suspend fun probe(): SummarizationProbeResult

    suspend fun downloadFeature(
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): DownloadResult

    suspend fun summarize(note: String): SummarizationRunResult

    fun close()
}
