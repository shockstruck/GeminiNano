package com.anandgaur.gemininano.data

import android.content.Context
import android.os.Build
import com.google.mlkit.genai.common.DownloadCallback
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.summarization.Summarization
import com.google.mlkit.genai.summarization.SummarizationRequest
import com.google.mlkit.genai.summarization.Summarizer
import com.google.mlkit.genai.summarization.SummarizerOptions
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * NotesRepository — the ONLY place that talks to the AI.
 *
 * WHAT IS HAPPENING HERE (the whole teaching point of the demo):
 *
 *  - We use ML Kit's GenAI Summarization API (com.google.mlkit:genai-summarization).
 *  - That API is backed by **Gemini Nano**, a small version of Gemini that runs
 *    directly on the phone's hardware through a system service called **Android AICore**.
 *  - AICore hosts the model and the ML Kit library just talks to it locally.
 *
 *  Because everything runs on the device:
 *    • There is NO API key anywhere in this project.
 *    • There are NO network calls for the AI — the note never leaves the phone.
 *    • There is NO per-request cost — inference is free.
 *    • It works fully OFFLINE (turn off wifi/data and it still summarizes).
 *
 *  The trade-off: Gemini Nano only exists on supported devices (e.g. Pixel 8+ and a
 *  growing list of flagships) running Android 8.0 / API 26+. On anything else we
 *  detect that up front and show a friendly message — we NEVER fall back to the cloud,
 *  because the point of this demo is that it is 100% on-device.
 */
class NotesRepository(private val appContext: Context) {

    /** Simple, UI-friendly description of whether on-device AI can run right now. */
    sealed interface NanoStatus {
        /** Ready to summarize immediately — the model is already on the device. */
        data object Available : NanoStatus

        /** Supported, but the model must be downloaded once (one-time, over wifi). */
        data object Downloadable : NanoStatus

        /** The model is currently downloading in the background. */
        data object Downloading : NanoStatus

        /** This device can't run Gemini Nano (unsupported hardware or Android < 8.0). */
        data class Unsupported(val reason: String) : NanoStatus
    }

    // We create the Summarizer lazily and keep a single instance for the app's lifetime.
    // It is closed in close() to release the native resources.
    private var summarizer: Summarizer? = null

    private fun getOrCreateSummarizer(): Summarizer {
        return summarizer ?: Summarization.getClient(
            // Configure WHAT kind of summary we want. All on-device.
            SummarizerOptions.builder(appContext)
                // Treat the note as prose/article (vs. a chat CONVERSATION).
                .setInputType(SummarizerOptions.InputType.ARTICLE)
                // Ask for a short bullet-point summary — perfect for notes.
                .setOutputType(SummarizerOptions.OutputType.THREE_BULLETS)
                // The on-device model supports English, Japanese and Korean.
                .setLanguage(SummarizerOptions.Language.ENGLISH)
                // If a note is very long (>~4000 tokens), auto-trim instead of failing.
                .setLongInputAutoTruncationEnabled(true)
                .build()
        ).also { summarizer = it }
    }

    /**
     * Ask ML Kit whether on-device summarization is available on THIS device, right now.
     * This is the safe way to drive the UI — it never triggers a network/cloud call.
     */
    suspend fun checkStatus(): NanoStatus {
        // The GenAI library needs API 26+. Guard here so we never touch it on older phones.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return NanoStatus.Unsupported(
                "On-device AI needs Android 8.0 or newer on a supported device (e.g. Pixel 8+)."
            )
        }

        return try {
            // checkFeatureStatus() returns a Guava ListenableFuture<Int>; await() suspends
            // until it resolves. The Int matches the FeatureStatus.* constants below.
            when (getOrCreateSummarizer().checkFeatureStatus().await()) {
                FeatureStatus.AVAILABLE -> NanoStatus.Available
                FeatureStatus.DOWNLOADABLE -> NanoStatus.Downloadable
                FeatureStatus.DOWNLOADING -> NanoStatus.Downloading
                else -> NanoStatus.Unsupported(
                    "On-device AI needs a supported device (e.g. Pixel 8+)."
                )
            }
        } catch (e: Exception) {
            // Any failure here means we can't run on-device — report it, don't crash.
            NanoStatus.Unsupported(
                "On-device AI isn't available on this device: ${e.message ?: "unknown reason"}"
            )
        }
    }

    /**
     * Download the on-device model (one-time). Suspends until the download finishes.
     *
     * [onProgress] is called repeatedly with (bytesDownloadedSoFar, totalBytes) so the UI
     * can show a live percentage / MB counter. ML Kit gives us:
     *   - onDownloadStarted(total)      → the full size to download (we remember it)
     *   - onDownloadProgress(soFar)     → cumulative bytes downloaded (fires many times)
     *   - onDownloadCompleted()         → finished
     *   - onDownloadFailed(e)           → error
     * totalBytes can be 0 if the size is unknown; the UI then shows an indeterminate bar.
     *
     * We wrap this callback-based ML Kit API in a coroutine.
     */
    suspend fun downloadModel(onProgress: (downloaded: Long, total: Long) -> Unit): Result<Unit> =
        suspendCancellableCoroutine { cont ->
            // Remember the total announced in onDownloadStarted so we can compute a %.
            var totalBytes = 0L
            getOrCreateSummarizer().downloadFeature(object : DownloadCallback {
                override fun onDownloadStarted(bytesToDownload: Long) {
                    totalBytes = bytesToDownload
                    onProgress(0L, totalBytes)
                }

                override fun onDownloadProgress(totalBytesDownloaded: Long) {
                    onProgress(totalBytesDownloaded, totalBytes)
                }

                override fun onDownloadCompleted() {
                    if (cont.isActive) cont.resume(Result.success(Unit))
                }

                override fun onDownloadFailed(e: GenAiException) {
                    if (cont.isActive) cont.resume(Result.failure(e))
                }
            })
        }

    /**
     * Run the actual summarization ON THE DEVICE and return the bullet-point summary.
     *
     * We use the non-streaming form for simplicity: runInference(request) returns a
     * ListenableFuture<SummarizationResult>; await() gives us the final result and
     * .summary is the text. (ML Kit also offers a streaming overload with a callback.)
     */
    suspend fun summarize(note: String): String {
        val request = SummarizationRequest.builder(note).build()
        return getOrCreateSummarizer().runInference(request).await().summary
    }

    /** Release native resources. Called from the ViewModel's onCleared(). */
    fun close() {
        summarizer?.close()
        summarizer = null
    }
}
