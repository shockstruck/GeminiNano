package dev.shockstruck.gemininano.core.genai

import android.os.SystemClock
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import dev.shockstruck.gemininano.model.DownloadResult
import dev.shockstruck.gemininano.model.FeatureStatusKind
import dev.shockstruck.gemininano.model.GenAiFailure
import dev.shockstruck.gemininano.model.PromptConfigStatus
import dev.shockstruck.gemininano.model.PromptFailureParsing
import dev.shockstruck.gemininano.model.PromptModelConfig
import dev.shockstruck.gemininano.model.PromptModelPreference
import dev.shockstruck.gemininano.model.PromptProbe
import dev.shockstruck.gemininano.model.PromptReleaseStage
import java.util.concurrent.ExecutionException

/**
 * The only Prompt implementation, pinned to `com.google.mlkit:genai-prompt:1.0.0-beta5`
 * and the official sample's API shape: `Generation.getClient(generationConfig)` ->
 * `GenerativeModel.checkStatus()`, `getBaseModelName()`, `download(): Flow<DownloadStatus>`
 * and `generateContent(String)`.
 *
 * A client is constructed per probe and always closed in `finally`. `download()` runs
 * only when the caller (the UI, on an explicit tap) asks for it; the probe performs no
 * automatic download. Prompt state is deliberately unrelated to feature 622.
 */
class MlKitPromptProbe : PromptProbe {

    override suspend fun probe(config: PromptModelConfig): PromptConfigStatus {
        val startedAt = SystemClock.elapsedRealtime()
        val probedAt = System.currentTimeMillis()
        var model: GenerativeModel? = null
        return try {
            model = client(config)
            val status = statusKind(model.checkStatus())
            var baseModelName: String? = null
            var generatedText: String? = null
            var failureCode: Int? = null
            var failureClass: String? = null
            var failureMessage: String? = null

            if (status == FeatureStatusKind.AVAILABLE) {
                baseModelName = runCatching { model.getBaseModelName() }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                try {
                    val response = model.generateContent(GENERATION_PROMPT)
                    generatedText = response.candidates
                        .firstOrNull()
                        ?.text
                        ?.takeIf { it.isNotBlank() }
                } catch (t: Throwable) {
                    val failure = t.toPromptFailure()
                    failureCode = failure.errorCode
                    failureClass = failure.exceptionClass
                    failureMessage = failure.message
                }
            }

            PromptConfigStatus(
                config = config,
                status = status,
                featureId = PromptFailureParsing.parseFeatureId(failureMessage),
                errorCode = failureCode,
                exceptionClass = failureClass,
                message = failureMessage,
                baseModelName = baseModelName,
                generatedText = generatedText,
                latencyMillis = SystemClock.elapsedRealtime() - startedAt,
                probedAtEpochMillis = probedAt,
            )
        } catch (t: Throwable) {
            val failure = t.toPromptFailure()
            PromptConfigStatus(
                config = config,
                status = FeatureStatusKind.PROBE_ERROR,
                featureId = PromptFailureParsing.parseFeatureId(failure.message),
                errorCode = failure.errorCode,
                exceptionClass = failure.exceptionClass,
                message = failure.message,
                baseModelName = null,
                generatedText = null,
                latencyMillis = SystemClock.elapsedRealtime() - startedAt,
                probedAtEpochMillis = probedAt,
            )
        } finally {
            runCatching { model?.close() }
        }
    }

    override suspend fun download(
        config: PromptModelConfig,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): DownloadResult {
        var downloadedBytes = 0L
        var totalBytes: Long? = null
        var model: GenerativeModel? = null
        return try {
            model = client(config)
            model.download().collect { status ->
                when (status) {
                    is DownloadStatus.DownloadStarted -> {
                        totalBytes = status.bytesToDownload
                        onProgress(0L, totalBytes)
                    }

                    is DownloadStatus.DownloadProgress -> {
                        downloadedBytes = status.totalBytesDownloaded
                        onProgress(downloadedBytes, totalBytes)
                    }

                    is DownloadStatus.DownloadFailed -> throw status.e

                    is DownloadStatus.DownloadCompleted -> Unit
                }
            }
            DownloadResult(
                success = true,
                bytesDownloaded = downloadedBytes,
                totalBytes = totalBytes,
                failure = null,
            )
        } catch (t: Throwable) {
            DownloadResult(
                success = false,
                bytesDownloaded = downloadedBytes,
                totalBytes = totalBytes,
                failure = t.toPromptFailure(),
            )
        } finally {
            runCatching { model?.close() }
        }
    }

    override fun close() = Unit

    private fun client(config: PromptModelConfig): GenerativeModel {
        val configuration = generationConfig {
            modelConfig = modelConfig {
                releaseStage = when (config.releaseStage) {
                    PromptReleaseStage.STABLE -> ModelReleaseStage.STABLE
                    PromptReleaseStage.PREVIEW -> ModelReleaseStage.PREVIEW
                }
                preference = when (config.preference) {
                    PromptModelPreference.FAST -> ModelPreference.FAST
                    PromptModelPreference.FULL -> ModelPreference.FULL
                }
            }
        }
        return Generation.getClient(configuration)
    }

    private fun statusKind(code: Int): FeatureStatusKind = when (code) {
        FeatureStatus.AVAILABLE -> FeatureStatusKind.AVAILABLE
        FeatureStatus.DOWNLOADABLE -> FeatureStatusKind.DOWNLOADABLE
        FeatureStatus.DOWNLOADING -> FeatureStatusKind.DOWNLOADING
        FeatureStatus.UNAVAILABLE -> FeatureStatusKind.UNAVAILABLE
        else -> FeatureStatusKind.UNKNOWN
    }

    private fun Throwable.toPromptFailure(): GenAiFailure {
        val unwrapped = if (this is ExecutionException) cause ?: this else this
        val genAi = generateSequence(unwrapped) { it.cause }
            .filterIsInstance<GenAiException>()
            .firstOrNull()
        return if (genAi != null) {
            GenAiFailure(
                errorCode = genAi.errorCode,
                exceptionClass = genAi.javaClass.name,
                message = genAi.message ?: "no detail",
            )
        } else {
            GenAiFailure(
                errorCode = null,
                exceptionClass = unwrapped.javaClass.name,
                message = unwrapped.message ?: unwrapped.javaClass.simpleName,
            )
        }
    }

    companion object {
        const val GENERATION_PROMPT = "Reply OK"
    }
}
