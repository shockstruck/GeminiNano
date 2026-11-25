package dev.shockstruck.gemininano.core.genai

import dev.shockstruck.gemininano.model.DownloadResult
import dev.shockstruck.gemininano.model.FeatureStatusKind
import dev.shockstruck.gemininano.model.SummarizationProbe
import dev.shockstruck.gemininano.model.SummarizationProbeResult
import dev.shockstruck.gemininano.model.SummarizationRunResult

/**
 * Used below API 26, where the ML Kit GenAI classes must not even be touched. Reports a
 * plain "not supported" state and never pretends to be an error from AICore.
 */
class UnavailableSummarizationProbe(
    private val reason: String = "On-device GenAI needs Android 8.0 (API 26) or newer.",
    private val clock: () -> Long = System::currentTimeMillis,
) : SummarizationProbe {

    override suspend fun probe(): SummarizationProbeResult = SummarizationProbeResult(
        status = FeatureStatusKind.UNKNOWN,
        statusDetail = reason,
        baseModelName = null,
        failure = null,
        probedAtEpochMillis = clock(),
    )

    override suspend fun downloadFeature(
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): DownloadResult = DownloadResult(
        success = false,
        bytesDownloaded = null,
        totalBytes = null,
        failure = null,
    )

    override suspend fun summarize(note: String): SummarizationRunResult = SummarizationRunResult(
        success = false,
        summary = null,
        latencyMillis = 0L,
        failure = null,
    )

    override fun close() = Unit
}
