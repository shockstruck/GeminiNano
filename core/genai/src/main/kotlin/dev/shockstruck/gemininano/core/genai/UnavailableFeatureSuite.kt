package dev.shockstruck.gemininano.core.genai

import dev.shockstruck.gemininano.model.DownloadResult
import dev.shockstruck.gemininano.model.FeatureDownloadProgress
import dev.shockstruck.gemininano.model.FeatureProbeResult
import dev.shockstruck.gemininano.model.FeatureStatusKind
import dev.shockstruck.gemininano.model.GenAiFeature
import dev.shockstruck.gemininano.model.GenAiFeatureSuite
import dev.shockstruck.gemininano.model.OfflineInferenceOutcome
import dev.shockstruck.gemininano.model.OfflineInferenceResult

/**
 * Used below API 26, where touching the ML Kit GenAI classes is not allowed. It reports
 * a plain "not supported" state instead of pretending AICore returned an error.
 */
class UnavailableFeatureSuite(
    private val reason: String = "On-device GenAI needs Android 8.0 (API 26) or newer.",
    private val clock: () -> Long = System::currentTimeMillis,
) : GenAiFeatureSuite {

    override suspend fun probe(feature: GenAiFeature): FeatureProbeResult = FeatureProbeResult(
        feature = feature,
        status = FeatureStatusKind.UNKNOWN,
        detail = reason,
        probedAtEpochMillis = clock(),
    )

    override suspend fun download(
        feature: GenAiFeature,
        onProgress: (FeatureDownloadProgress) -> Unit,
    ): DownloadResult = DownloadResult(
        success = false,
        bytesDownloaded = null,
        totalBytes = null,
        failure = null,
    )

    override suspend fun runOfflineInference(feature: GenAiFeature): OfflineInferenceResult =
        OfflineInferenceResult(
            feature = feature,
            outcome = OfflineInferenceOutcome.SKIPPED,
            detail = reason,
        )

    override fun close() = Unit
}
