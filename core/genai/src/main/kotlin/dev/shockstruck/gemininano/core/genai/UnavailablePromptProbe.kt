package dev.shockstruck.gemininano.core.genai

import dev.shockstruck.gemininano.model.DownloadResult
import dev.shockstruck.gemininano.model.FeatureStatusKind
import dev.shockstruck.gemininano.model.PromptConfigStatus
import dev.shockstruck.gemininano.model.PromptModelConfig
import dev.shockstruck.gemininano.model.PromptProbe

/**
 * Used below API 26, where the ML Kit GenAI classes must not be touched. It reports a
 * plain "not supported" state instead of pretending AICore returned an error.
 */
class UnavailablePromptProbe(
    private val reason: String = "On-device GenAI needs Android 8.0 (API 26) or newer.",
    private val clock: () -> Long = System::currentTimeMillis,
) : PromptProbe {

    override suspend fun probe(config: PromptModelConfig): PromptConfigStatus = PromptConfigStatus(
        config = config,
        status = FeatureStatusKind.UNKNOWN,
        featureId = null,
        errorCode = null,
        exceptionClass = null,
        message = reason,
        baseModelName = null,
        generatedText = null,
        latencyMillis = 0L,
        probedAtEpochMillis = clock(),
    )

    override suspend fun download(
        config: PromptModelConfig,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): DownloadResult = DownloadResult(
        success = false,
        bytesDownloaded = null,
        totalBytes = null,
        failure = null,
    )

    override fun close() = Unit
}
