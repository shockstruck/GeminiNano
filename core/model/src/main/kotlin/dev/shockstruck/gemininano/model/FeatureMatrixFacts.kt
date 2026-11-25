package dev.shockstruck.gemininano.model

/**
 * The public ML Kit GenAI capabilities the app probes independently.
 *
 * [omissionReason] is non-null only when the published artifact has no usable public
 * surface for this app. An omitted feature is rendered as
 * "NOT AVAILABLE IN THE PUBLIC API" with that exact reason; it is never marked
 * unavailable, downloadable or successful by guesswork.
 */
enum class GenAiFeature(
    val key: String,
    val displayName: String,
    val artifact: String,
    val omissionReason: String? = null,
) {
    REWRITING(
        key = "rewriting",
        displayName = "Rewriting",
        artifact = "com.google.mlkit:genai-rewriting:1.0.0-beta1",
    ),
    PROOFREADING(
        key = "proofreading",
        displayName = "Proofreading",
        artifact = "com.google.mlkit:genai-proofreading:1.0.0-beta1",
    ),
    IMAGE_DESCRIPTION(
        key = "image-description",
        displayName = "Image Description",
        artifact = "com.google.mlkit:genai-image-description:1.0.0-beta1",
    ),
    EMBEDDING(
        key = "embedding",
        displayName = "Embedding",
        artifact = "com.google.mlkit:genai-embedding:1.0.0-alpha1",
    ),
    AGENT(
        key = "agent",
        displayName = "Agent",
        artifact = "com.google.mlkit:genai-agent:1.0.0-alpha1",
    ),
    SPEECH_RECOGNITION(
        key = "speech-recognition",
        displayName = "Speech Recognition",
        artifact = "com.google.mlkit:genai-speech-recognition:1.0.0-alpha2",
    ),
    SPEECH_TRANSLATION(
        key = "speech-translation",
        displayName = "Speech Translation",
        artifact = "com.google.mlkit:genai-speech-translation:1.0.0-alpha1",
    ),
    TEXT_TO_SPEECH(
        key = "text-to-speech",
        displayName = "Text to Speech",
        artifact = "com.google.mlkit:genai-text-to-speech:1.0.0-alpha1",
    ),
    ;

    val implementedInPublicApi: Boolean get() = omissionReason == null

    companion object {
        /** The eight Stage 4 matrix entries, verified against the official sample. */
        val MATRIX: List<GenAiFeature> = entries.toList()
    }
}

/**
 * One independent feature observation. Every field is a fact the public API returned;
 * `null` always means "the API did not return it", never a guessed value.
 */
data class FeatureProbeResult(
    val feature: GenAiFeature,
    val status: FeatureStatusKind,
    val featureId: Int? = null,
    val failure: GenAiFailure? = null,
    val baseModelName: String? = null,
    val modelSignature: String? = null,
    val metadataDimension: Int? = null,
    val tokenLimit: Int? = null,
    val latencyMillis: Long = 0L,
    val probedAtEpochMillis: Long = 0L,
    val detail: String? = null,
    val lastDownload: DownloadResult? = null,
) {
    val errorCode: Int? get() = failure?.errorCode
    val exceptionClass: String? get() = failure?.exceptionClass
    val message: String? get() = failure?.message

    /** A download is offered only when ML Kit itself reports DOWNLOADABLE. */
    val downloadOffered: Boolean get() = status == FeatureStatusKind.DOWNLOADABLE

    /** An inference is attempted only when ML Kit itself reports AVAILABLE. */
    val inferenceOffered: Boolean get() = status == FeatureStatusKind.AVAILABLE

    val featureIdLabel: String get() = featureId?.toString() ?: "none parsed"

    val statusLabel: String
        get() = when {
            status == FeatureStatusKind.AVAILABLE -> "AVAILABLE (status only)"
            status == FeatureStatusKind.PROBE_ERROR && errorCode != null ->
                "PROBE ERROR (code $errorCode)"

            else -> status.label
        }

    companion object {
        /** Renders an omitted feature without pretending the SDK answered. */
        fun omitted(
            feature: GenAiFeature,
            reason: String = feature.omissionReason ?: "No usable public API.",
            probedAtEpochMillis: Long = 0L,
        ): FeatureProbeResult =
            FeatureProbeResult(
                feature = feature,
                status = FeatureStatusKind.NOT_IN_PUBLIC_API,
                detail = reason,
                probedAtEpochMillis = probedAtEpochMillis,
            )
    }
}

/** One observed download-progress callback; no estimated sizes are invented. */
data class FeatureDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long?,
) {
    val label: String
        get() = if (totalBytes != null && totalBytes > 0) {
            "$downloadedBytes / $totalBytes bytes"
        } else {
            "$downloadedBytes bytes downloaded (total not reported)"
        }
}

/** Rendering helpers kept in `:core:model` so the exact strings are unit-tested. */
object FeatureMatrixFormatting {

    fun eligibilityLabel(result: FeatureProbeResult): String = when {
        result.status == FeatureStatusKind.NOT_IN_PUBLIC_API || !result.feature.implementedInPublicApi ->
            "no download — not available in the public API"

        result.status == FeatureStatusKind.DOWNLOADABLE ->
            "download available (ML Kit reports DOWNLOADABLE)"

        result.status == FeatureStatusKind.DOWNLOADING ->
            "download in progress (ML Kit reports DOWNLOADING)"

        result.status == FeatureStatusKind.AVAILABLE ->
            "already available — no download offered"

        result.status == FeatureStatusKind.UNAVAILABLE ->
            "not downloadable — ML Kit reports UNAVAILABLE"

        result.status == FeatureStatusKind.PROBE_ERROR ->
            "unknown — the status probe failed"

        else -> "unknown — ML Kit returned no recognised status"
    }

    fun renderResult(result: FeatureProbeResult): String {
        val parts = mutableListOf<String>()
        parts += "${result.feature.displayName}: ${result.status.label}"
        result.detail?.let { parts += "detail=$it" }
        result.failure?.let {
            parts += "error=${it.codeLabel}"
            parts += "class=${it.exceptionClass}"
            parts += "message=${it.message}"
        }
        result.featureId?.let { parts += "featureId=$it" }
        result.baseModelName?.let { parts += "baseModel=$it" }
        result.modelSignature?.let { parts += "signature=$it" }
        result.metadataDimension?.let { parts += "dimension=$it" }
        result.tokenLimit?.let { parts += "tokenLimit=$it" }
        parts += "latency=${result.latencyMillis}ms"
        return parts.joinToString(" ")
    }
}
