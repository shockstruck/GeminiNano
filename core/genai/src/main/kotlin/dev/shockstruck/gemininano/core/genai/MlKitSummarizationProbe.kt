package dev.shockstruck.gemininano.core.genai

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.google.common.util.concurrent.MoreExecutors
import com.google.mlkit.genai.common.DownloadCallback
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.summarization.Summarization
import com.google.mlkit.genai.summarization.SummarizationRequest
import com.google.mlkit.genai.summarization.Summarizer
import com.google.mlkit.genai.summarization.SummarizerOptions
import dev.shockstruck.gemininano.model.DownloadResult
import dev.shockstruck.gemininano.model.FeatureStatusKind
import dev.shockstruck.gemininano.model.GenAiFailure
import dev.shockstruck.gemininano.model.SummarizationProbe
import dev.shockstruck.gemininano.model.SummarizationProbeResult
import dev.shockstruck.gemininano.model.SummarizationRunResult
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The only module that touches `com.google.mlkit.genai.*`.
 *
 * This is the retained Private Notes summarization path plus its model-card probe and
 * the one-time download action from the accepted patch. It uses public ML Kit APIs
 * only: nothing here binds to AICore or Private Compute Services directly and nothing
 * here performs a network call itself — AICore/PCS own the download transport.
 */
class MlKitSummarizationProbe(
    private val context: Context,
) : SummarizationProbe {

    private val lock = Any()
    private var summarizer: Summarizer? = null

    private fun client(): Summarizer = synchronized(lock) {
        summarizer ?: Summarization.getClient(
            SummarizerOptions.builder(context)
                .setInputType(SummarizerOptions.InputType.ARTICLE)
                .setOutputType(SummarizerOptions.OutputType.THREE_BULLETS)
                .setLanguage(SummarizerOptions.Language.ENGLISH)
                .setLongInputAutoTruncationEnabled(true)
                .build()
        ).also { summarizer = it }
    }

    override suspend fun probe(): SummarizationProbeResult {
        val startedAt = System.currentTimeMillis()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return SummarizationProbeResult(
                status = FeatureStatusKind.UNKNOWN,
                statusDetail = "Android 8.0 (API 26) or newer is required for on-device GenAI.",
                baseModelName = null,
                failure = null,
                probedAtEpochMillis = startedAt,
            )
        }
        return try {
            val statusCode = client().checkFeatureStatus().await()
            val baseModelName = runCatching { client().getBaseModelName().await() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
            SummarizationProbeResult(
                status = statusKind(statusCode),
                statusDetail = statusDetail(statusCode),
                baseModelName = baseModelName,
                failure = null,
                probedAtEpochMillis = System.currentTimeMillis(),
            )
        } catch (t: Throwable) {
            val failure = t.toFailure()
            SummarizationProbeResult(
                status = FeatureStatusKind.PROBE_ERROR,
                statusDetail = failure.message,
                baseModelName = null,
                failure = failure,
                probedAtEpochMillis = System.currentTimeMillis(),
            )
        }
    }

    override suspend fun downloadFeature(
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): DownloadResult {
        var downloadedBytes = 0L
        var totalBytes: Long? = null
        return try {
            suspendCancellableCoroutine { continuation ->
                val callback = object : DownloadCallback {
                    override fun onDownloadStarted(bytesToDownload: Long) {
                        totalBytes = bytesToDownload
                        onProgress(0L, totalBytes)
                    }

                    override fun onDownloadProgress(bytesDownloaded: Long) {
                        downloadedBytes = bytesDownloaded
                        onProgress(bytesDownloaded, totalBytes)
                    }

                    override fun onDownloadCompleted() = Unit

                    override fun onDownloadFailed(exception: GenAiException) = Unit
                }
                val future = client().downloadFeature(callback)
                future.addListener(
                    {
                        try {
                            future.get()
                            if (continuation.isActive) continuation.resume(Unit)
                        } catch (t: Throwable) {
                            if (continuation.isActive) continuation.resumeWithException(unwrap(t))
                        }
                    },
                    MoreExecutors.directExecutor(),
                )
                continuation.invokeOnCancellation { future.cancel(true) }
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
                failure = t.toFailure(),
            )
        }
    }

    override suspend fun summarize(note: String): SummarizationRunResult {
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            val result = client()
                .runInference(SummarizationRequest.builder(note).build())
                .await()
            SummarizationRunResult(
                success = true,
                summary = result.summary,
                latencyMillis = SystemClock.elapsedRealtime() - startedAt,
                failure = null,
            )
        } catch (t: Throwable) {
            SummarizationRunResult(
                success = false,
                summary = null,
                latencyMillis = SystemClock.elapsedRealtime() - startedAt,
                failure = t.toFailure(),
            )
        }
    }

    override fun close() {
        synchronized(lock) {
            summarizer?.close()
            summarizer = null
        }
    }

    private fun statusKind(code: Int): FeatureStatusKind = when (code) {
        FeatureStatus.AVAILABLE -> FeatureStatusKind.AVAILABLE
        FeatureStatus.DOWNLOADABLE -> FeatureStatusKind.DOWNLOADABLE
        FeatureStatus.DOWNLOADING -> FeatureStatusKind.DOWNLOADING
        FeatureStatus.UNAVAILABLE -> FeatureStatusKind.UNAVAILABLE
        else -> FeatureStatusKind.UNKNOWN
    }

    private fun statusDetail(code: Int): String = when (code) {
        FeatureStatus.AVAILABLE -> "ML Kit reports the summarization feature as available."
        FeatureStatus.DOWNLOADABLE -> "ML Kit reports the feature as downloadable on this device."
        FeatureStatus.DOWNLOADING -> "ML Kit reports a download already in progress."
        FeatureStatus.UNAVAILABLE -> "ML Kit reports the feature as unavailable."
        else -> "ML Kit returned an unrecognised feature status ($code)."
    }

    private fun unwrap(t: Throwable): Throwable =
        if (t is ExecutionException) t.cause ?: t else t

    private fun Throwable.toFailure(): GenAiFailure {
        val genAi = generateSequence(this) { it.cause }.filterIsInstance<GenAiException>().firstOrNull()
        return if (genAi != null) {
            GenAiFailure(
                errorCode = genAi.errorCode,
                exceptionClass = genAi.javaClass.name,
                message = genAi.message ?: "no detail",
            )
        } else {
            GenAiFailure(
                errorCode = null,
                exceptionClass = javaClass.name,
                message = message ?: javaClass.simpleName,
            )
        }
    }
}
