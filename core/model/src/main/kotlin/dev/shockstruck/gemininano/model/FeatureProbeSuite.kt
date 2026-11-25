package dev.shockstruck.gemininano.model

/**
 * The public GenAI feature surface owned by `:core:genai`. Every method returns
 * project-owned types, so no ML Kit class crosses the module boundary and tests use
 * fakes instead of SDK types.
 */
interface GenAiFeatureSuite {

    /** Safe status probe. Never downloads and never runs inference. */
    suspend fun probe(feature: GenAiFeature): FeatureProbeResult

    /** Explicit user-triggered download; only called after the probe said DOWNLOADABLE. */
    suspend fun download(
        feature: GenAiFeature,
        onProgress: (FeatureDownloadProgress) -> Unit,
    ): DownloadResult

    /** One short fixed-input inference; only called for an AVAILABLE feature. */
    suspend fun runOfflineInference(feature: GenAiFeature): OfflineInferenceResult

    fun close()
}
