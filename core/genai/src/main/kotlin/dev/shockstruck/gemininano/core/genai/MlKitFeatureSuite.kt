package dev.shockstruck.gemininano.core.genai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.AudioAttributes
import android.os.Build
import android.os.SystemClock
import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.genai.agent.Agent
import com.google.mlkit.genai.agent.AgentEvent
import com.google.mlkit.genai.agent.AgentRunner
import com.google.mlkit.genai.common.DownloadCallback
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.embedding.Content as EmbeddingContent
import com.google.mlkit.genai.embedding.EmbeddingGeneration
import com.google.mlkit.genai.embedding.EmbeddingModel
import com.google.mlkit.genai.embedding.EmbeddingOptions
import com.google.mlkit.genai.embedding.EmbeddingRequest
import com.google.mlkit.genai.embedding.TextPart
import com.google.mlkit.genai.imagedescription.ImageDescription
import com.google.mlkit.genai.imagedescription.ImageDescriptionRequest
import com.google.mlkit.genai.imagedescription.ImageDescriberOptions
import com.google.mlkit.genai.prompt.Content
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.proofreading.ProofreaderOptions
import com.google.mlkit.genai.proofreading.ProofreaderOptions.builder
import com.google.mlkit.genai.proofreading.Proofreading
import com.google.mlkit.genai.proofreading.ProofreadingRequest
import com.google.mlkit.genai.rewriting.RewriterOptions
import com.google.mlkit.genai.rewriting.Rewriting
import com.google.mlkit.genai.rewriting.RewritingRequest
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerRequest
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import com.google.mlkit.genai.speechtranslation.SpeechTranslation
import com.google.mlkit.genai.speechtranslation.SpeechTranslatorOptions
import com.google.mlkit.genai.speechtranslation.SpeechTranslatorRequest
import com.google.mlkit.genai.speechtranslation.SpeechTranslatorResponse
import com.google.mlkit.genai.speechtranslation.TranslationAudio
import com.google.mlkit.genai.texttospeech.SpeechSynthesis
import com.google.mlkit.genai.texttospeech.SpeechSynthesizerRequest
import com.google.mlkit.genai.texttospeech.SpeechSynthesizerResponse
import com.google.mlkit.genai.texttospeech.speechSynthesizerOptions
import dev.shockstruck.gemininano.model.DownloadResult
import dev.shockstruck.gemininano.model.FeatureDownloadProgress
import dev.shockstruck.gemininano.model.FeatureProbeResult
import dev.shockstruck.gemininano.model.FeatureStatusKind
import dev.shockstruck.gemininano.model.GenAiFailure
import dev.shockstruck.gemininano.model.GenAiFeature
import dev.shockstruck.gemininano.model.GenAiFeatureSuite
import dev.shockstruck.gemininano.model.OfflineInferenceOutcome
import dev.shockstruck.gemininano.model.OfflineInferenceResult
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Real ML Kit GenAI probes for the Stage 4 feature matrix.
 *
 * Every feature uses only the public SDK surface demonstrated in
 * `googlesamples/mlkit` at the versions pinned in `libs.versions.toml`. Each probe
 * builds its own client, records only returned values and closes it in `finally`.
 * No probe calls `download()`; downloads happen only from an explicit user tap.
 */
class MlKitFeatureSuite(
    context: Context,
) : GenAiFeatureSuite {

    private val appContext = context.applicationContext

    override suspend fun probe(feature: GenAiFeature): FeatureProbeResult {
        if (!feature.implementedInPublicApi) {
            return FeatureProbeResult.omitted(feature, probedAtEpochMillis = System.currentTimeMillis())
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return unsupported(feature, "Android 8.0 (API 26) or newer is required for on-device GenAI.")
        }
        return try {
            when (feature) {
                GenAiFeature.REWRITING -> probeRewriting()
                GenAiFeature.PROOFREADING -> probeProofreading()
                GenAiFeature.IMAGE_DESCRIPTION -> probeImageDescription()
                GenAiFeature.EMBEDDING -> probeEmbedding()
                GenAiFeature.AGENT -> probeAgent()
                GenAiFeature.SPEECH_RECOGNITION -> probeSpeechRecognition()
                GenAiFeature.SPEECH_TRANSLATION -> probeSpeechTranslation()
                GenAiFeature.TEXT_TO_SPEECH -> probeTextToSpeech()
            }
        } catch (t: Throwable) {
            failureResult(feature, t, SystemClock.elapsedRealtime())
        }
    }

    override suspend fun download(
        feature: GenAiFeature,
        onProgress: (FeatureDownloadProgress) -> Unit,
    ): DownloadResult {
        if (!feature.implementedInPublicApi) {
            return DownloadResult(
                success = false,
                bytesDownloaded = null,
                totalBytes = null,
                failure = GenAiFailure(
                    errorCode = null,
                    exceptionClass = "dev.shockstruck.gemininano.NoPublicApi",
                    message = feature.omissionReason ?: "No usable public API.",
                ),
            )
        }
        return try {
            when (feature) {
                GenAiFeature.REWRITING -> downloadRewriting(onProgress)
                GenAiFeature.PROOFREADING -> downloadProofreading(onProgress)
                GenAiFeature.IMAGE_DESCRIPTION -> downloadImageDescription(onProgress)
                GenAiFeature.EMBEDDING -> downloadEmbedding(onProgress)
                GenAiFeature.AGENT -> downloadAgent(onProgress)
                GenAiFeature.SPEECH_RECOGNITION -> downloadSpeechRecognition(onProgress)
                GenAiFeature.SPEECH_TRANSLATION -> downloadSpeechTranslation(onProgress)
                GenAiFeature.TEXT_TO_SPEECH -> downloadTextToSpeech(onProgress)
            }
        } catch (t: Throwable) {
            DownloadResult(false, null, null, t.toFailure())
        }
    }

    override suspend fun runOfflineInference(feature: GenAiFeature): OfflineInferenceResult {
        if (!feature.implementedInPublicApi) {
            return OfflineInferenceResult.skipped(feature, FeatureProbeResult.omitted(feature))
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return OfflineInferenceResult(
                feature = feature,
                outcome = OfflineInferenceOutcome.SKIPPED,
                detail = "Android 8.0 (API 26) or newer is required.",
            )
        }
        return try {
            when (feature) {
                GenAiFeature.REWRITING -> inferRewriting()
                GenAiFeature.PROOFREADING -> inferProofreading()
                GenAiFeature.IMAGE_DESCRIPTION -> inferImageDescription()
                GenAiFeature.EMBEDDING -> inferEmbedding()
                GenAiFeature.AGENT -> inferAgent()
                GenAiFeature.SPEECH_RECOGNITION -> inferSpeechRecognition()
                GenAiFeature.SPEECH_TRANSLATION -> inferSpeechTranslation()
                GenAiFeature.TEXT_TO_SPEECH -> inferTextToSpeech()
            }
        } catch (t: Throwable) {
            OfflineInferenceResult(
                feature = feature,
                outcome = OfflineInferenceOutcome.FAILED,
                failure = t.toFailure(),
                detail = "inference threw before returning a result",
            )
        }
    }

    override fun close() = Unit

    // ---------------------------------------------------------------- probes

    private suspend fun probeRewriting(): FeatureProbeResult {
        val feature = GenAiFeature.REWRITING
        val started = SystemClock.elapsedRealtime()
        val client = Rewriting.getClient(
            RewriterOptions.builder(appContext)
                .setOutputType(RewriterOptions.OutputType.ELABORATE)
                .setLanguage(RewriterOptions.Language.ENGLISH)
                .build(),
        )
        return try {
            statusResult(feature, client.checkFeatureStatus().await()) { client.baseModelName.await() }
                .copy(latencyMillis = SystemClock.elapsedRealtime() - started)
        } finally {
            runCatching { client.close() }
        }
    }

    private suspend fun probeProofreading(): FeatureProbeResult {
        val feature = GenAiFeature.PROOFREADING
        val started = SystemClock.elapsedRealtime()
        val client = Proofreading.getClient(
            builder(appContext)
                .setInputType(ProofreaderOptions.InputType.KEYBOARD)
                .setLanguage(ProofreaderOptions.Language.ENGLISH)
                .build(),
        )
        return try {
            statusResult(feature, client.checkFeatureStatus().await()) { client.baseModelName.await() }
                .copy(latencyMillis = SystemClock.elapsedRealtime() - started)
        } finally {
            runCatching { client.close() }
        }
    }

    private suspend fun probeImageDescription(): FeatureProbeResult {
        val feature = GenAiFeature.IMAGE_DESCRIPTION
        val started = SystemClock.elapsedRealtime()
        val client = ImageDescription.getClient(ImageDescriberOptions.builder(appContext).build())
        return try {
            statusResult(feature, client.checkFeatureStatus().await()) { client.baseModelName.await() }
                .copy(latencyMillis = SystemClock.elapsedRealtime() - started)
        } finally {
            runCatching { client.close() }
        }
    }

    private suspend fun probeEmbedding(): FeatureProbeResult {
        val feature = GenAiFeature.EMBEDDING
        val started = SystemClock.elapsedRealtime()
        val model = firstEmbeddingModel()
            ?: return unsupported(feature, "EmbeddingGeneration.getEmbeddingModels returned no model.")
        return try {
            val status = model.checkStatus()
            var signature: String? = null
            var dimension: Int? = null
            var tokenLimit: Int? = null
            if (status == FeatureStatus.AVAILABLE) {
                signature = runCatching { model.getModelSignature() }.getOrNull()
                dimension = runCatching { model.getMetadata()?.dimension }.getOrNull()
                tokenLimit = runCatching { model.getTokenLimit() }.getOrNull()
            }
            FeatureProbeResult(
                feature = feature,
                status = statusKind(status),
                modelSignature = signature,
                metadataDimension = dimension,
                tokenLimit = tokenLimit,
                latencyMillis = SystemClock.elapsedRealtime() - started,
                probedAtEpochMillis = System.currentTimeMillis(),
                detail = embeddingDetail(signature, dimension),
            )
        } finally {
            runCatching { model.close() }
        }
    }

    private suspend fun probeAgent(): FeatureProbeResult {
        val feature = GenAiFeature.AGENT
        val started = SystemClock.elapsedRealtime()
        val model = Generation.getClient()
        return try {
            val status = model.checkStatus()
            var baseModelName: String? = null
            if (status == FeatureStatus.AVAILABLE) {
                baseModelName = runCatching { model.getBaseModelName() }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
            }
            FeatureProbeResult(
                feature = feature,
                status = statusKind(status),
                baseModelName = baseModelName,
                latencyMillis = SystemClock.elapsedRealtime() - started,
                probedAtEpochMillis = System.currentTimeMillis(),
                detail = "Agent uses the public Agent/AgentRunner API over the Prompt base model; " +
                    "app functions are disabled for this diagnostic.",
            )
        } finally {
            runCatching { model.close() }
        }
    }

    private suspend fun probeSpeechRecognition(): FeatureProbeResult {
        val feature = GenAiFeature.SPEECH_RECOGNITION
        val started = SystemClock.elapsedRealtime()
        val client = SpeechRecognition.getClient(recognizerOptions())
        return try {
            val status = client.checkStatus()
            FeatureProbeResult(
                feature = feature,
                status = statusKind(status),
                latencyMillis = SystemClock.elapsedRealtime() - started,
                probedAtEpochMillis = System.currentTimeMillis(),
                detail = "File input only (AudioSource.fromFile); RECORD_AUDIO is not requested.",
            )
        } finally {
            runCatching { client.close() }
        }
    }

    private suspend fun probeSpeechTranslation(): FeatureProbeResult {
        val feature = GenAiFeature.SPEECH_TRANSLATION
        val started = SystemClock.elapsedRealtime()
        val client = SpeechTranslation.getClient(SpeechTranslatorOptions.Builder(Locale.US, Locale.CHINA).build())
        return try {
            val status = client.checkStatus()
            FeatureProbeResult(
                feature = feature,
                status = statusKind(status),
                latencyMillis = SystemClock.elapsedRealtime() - started,
                probedAtEpochMillis = System.currentTimeMillis(),
                detail = "Fixed en-US to zh-CN pipeline fed from a fixed PCM buffer; no microphone permission.",
            )
        } finally {
            runCatching { client.close() }
        }
    }

    private suspend fun probeTextToSpeech(): FeatureProbeResult {
        val feature = GenAiFeature.TEXT_TO_SPEECH
        val started = SystemClock.elapsedRealtime()
        val client = SpeechSynthesis.getClient(
            speechSynthesizerOptions(Locale.US) { setVoiceFallbackEnabled(true) },
        )
        return try {
            val voices = client.getSupportedVoices(Locale.US)
            val voice = voices.firstOrNull()
            if (voice == null) {
                return FeatureProbeResult(
                    feature = feature,
                    status = FeatureStatusKind.UNAVAILABLE,
                    latencyMillis = SystemClock.elapsedRealtime() - started,
                    probedAtEpochMillis = System.currentTimeMillis(),
                    detail = "SpeechSynthesizer.getSupportedVoices(en-US) returned no voice.",
                )
            }
            FeatureProbeResult(
                feature = feature,
                status = statusKind(client.checkStatus(voice)),
                latencyMillis = SystemClock.elapsedRealtime() - started,
                probedAtEpochMillis = System.currentTimeMillis(),
                detail = "First supported en-US voice: ${voice.name}",
            )
        } finally {
            runCatching { client.close() }
        }
    }

    // -------------------------------------------------------------- downloads

    private suspend fun downloadRewriting(onProgress: (FeatureDownloadProgress) -> Unit): DownloadResult {
        val client = Rewriting.getClient(
            RewriterOptions.builder(appContext)
                .setOutputType(RewriterOptions.OutputType.ELABORATE)
                .setLanguage(RewriterOptions.Language.ENGLISH)
                .build(),
        )
        return try {
            downloadListenable({ callback -> client.downloadFeature(callback) }, onProgress)
        } finally {
            runCatching { client.close() }
        }
    }

    private suspend fun downloadProofreading(onProgress: (FeatureDownloadProgress) -> Unit): DownloadResult {
        val client = Proofreading.getClient(
            builder(appContext)
                .setInputType(ProofreaderOptions.InputType.KEYBOARD)
                .setLanguage(ProofreaderOptions.Language.ENGLISH)
                .build(),
        )
        return try {
            downloadListenable({ callback -> client.downloadFeature(callback) }, onProgress)
        } finally {
            runCatching { client.close() }
        }
    }

    private suspend fun downloadImageDescription(onProgress: (FeatureDownloadProgress) -> Unit): DownloadResult {
        val client = ImageDescription.getClient(ImageDescriberOptions.builder(appContext).build())
        return try {
            downloadListenable({ callback -> client.downloadFeature(callback) }, onProgress)
        } finally {
            runCatching { client.close() }
        }
    }

    private suspend fun downloadEmbedding(onProgress: (FeatureDownloadProgress) -> Unit): DownloadResult {
        val model = firstEmbeddingModel()
            ?: return DownloadResult(
                false,
                null,
                null,
                GenAiFailure(
                    errorCode = null,
                    exceptionClass = "dev.shockstruck.gemininano.NoEmbeddingModel",
                    message = "EmbeddingGeneration.getEmbeddingModels returned no model.",
                ),
            )
        return try {
            downloadFlow(model.download(), onProgress)
        } finally {
            runCatching { model.close() }
        }
    }

    private suspend fun downloadAgent(onProgress: (FeatureDownloadProgress) -> Unit): DownloadResult {
        val model = Generation.getClient()
        return try {
            downloadFlow(model.download(), onProgress)
        } finally {
            runCatching { model.close() }
        }
    }

    private suspend fun downloadSpeechRecognition(onProgress: (FeatureDownloadProgress) -> Unit): DownloadResult {
        val client = SpeechRecognition.getClient(recognizerOptions())
        return try {
            downloadFlow(client.download(), onProgress)
        } finally {
            runCatching { client.close() }
        }
    }

    private suspend fun downloadSpeechTranslation(onProgress: (FeatureDownloadProgress) -> Unit): DownloadResult {
        val client = SpeechTranslation.getClient(SpeechTranslatorOptions.Builder(Locale.US, Locale.CHINA).build())
        return try {
            downloadFlow(client.download(), onProgress)
        } finally {
            runCatching { client.close() }
        }
    }

    private suspend fun downloadTextToSpeech(onProgress: (FeatureDownloadProgress) -> Unit): DownloadResult {
        val client = SpeechSynthesis.getClient(
            speechSynthesizerOptions(Locale.US) { setVoiceFallbackEnabled(true) },
        )
        return try {
            val voice = client.getSupportedVoices(Locale.US).firstOrNull()
                ?: return DownloadResult(
                    false,
                    null,
                    null,
                    GenAiFailure(
                        errorCode = null,
                        exceptionClass = "dev.shockstruck.gemininano.NoVoice",
                        message = "No en-US voice was returned by the public API.",
                    ),
                )
            downloadFlow(client.download(voice), onProgress)
        } finally {
            runCatching { client.close() }
        }
    }

    // ------------------------------------------------------- offline inference

    private suspend fun inferRewriting(): OfflineInferenceResult =
        textListInference(GenAiFeature.REWRITING, REWRITING_INPUT) { text ->
            val client = Rewriting.getClient(
                RewriterOptions.builder(appContext)
                    .setOutputType(RewriterOptions.OutputType.ELABORATE)
                    .setLanguage(RewriterOptions.Language.ENGLISH)
                    .build(),
            )
            try {
                client.runInference(RewritingRequest.builder(text).build()).await()
                    .results.map { it.text }
            } finally {
                runCatching { client.close() }
            }
        }

    private suspend fun inferProofreading(): OfflineInferenceResult =
        textListInference(GenAiFeature.PROOFREADING, PROOFREADING_INPUT) { text ->
            val client = Proofreading.getClient(
                builder(appContext)
                    .setInputType(ProofreaderOptions.InputType.KEYBOARD)
                    .setLanguage(ProofreaderOptions.Language.ENGLISH)
                    .build(),
            )
            try {
                client.runInference(ProofreadingRequest.builder(text).build()).await()
                    .results.map { it.text }
            } finally {
                runCatching { client.close() }
            }
        }

    private suspend fun inferImageDescription(): OfflineInferenceResult {
        val started = SystemClock.elapsedRealtime()
        val client = ImageDescription.getClient(ImageDescriberOptions.builder(appContext).build())
        return try {
            val bitmap = diagnosticBitmap()
            val description = client.runInference(
                ImageDescriptionRequest.builder(bitmap).build(),
            ).await().description
            OfflineInferenceResult(
                feature = GenAiFeature.IMAGE_DESCRIPTION,
                outcome = OfflineInferenceOutcome.SUCCEEDED,
                output = description.trim().take(MAX_OUTPUT),
                latencyMillis = SystemClock.elapsedRealtime() - started,
                detail = "fixed 96×96 generated bitmap (white background, black circle)",
            )
        } finally {
            runCatching { client.close() }
        }
    }

    private suspend fun inferEmbedding(): OfflineInferenceResult {
        val started = SystemClock.elapsedRealtime()
        val model = firstEmbeddingModel()
            ?: return OfflineInferenceResult(
                feature = GenAiFeature.EMBEDDING,
                outcome = OfflineInferenceOutcome.FAILED,
                failure = GenAiFailure(
                    errorCode = null,
                    exceptionClass = "dev.shockstruck.gemininano.NoEmbeddingModel",
                    message = "EmbeddingGeneration.getEmbeddingModels returned no model.",
                ),
                latencyMillis = SystemClock.elapsedRealtime() - started,
            )
        return try {
            val result = model.generateEmbedding(
                EmbeddingRequest(
                    EmbeddingContent(listOf(TextPart(EMBEDDING_INPUT))),
                    taskConfig = null,
                ),
            )
            OfflineInferenceResult(
                feature = GenAiFeature.EMBEDDING,
                outcome = OfflineInferenceOutcome.SUCCEEDED,
                output = "vector dimension ${result.embedding.vector.size}",
                latencyMillis = SystemClock.elapsedRealtime() - started,
                detail = "fixed text input; the vector itself is not logged",
            )
        } finally {
            runCatching { model.close() }
        }
    }

    private suspend fun inferAgent(): OfflineInferenceResult {
        val started = SystemClock.elapsedRealtime()
        val model = Generation.getClient()
        var runner: AgentRunner? = null
        return try {
            val agent = Agent.builder()
                .setName("GeminiNanoDiagnostics")
                .setInstructions("Reply with a single short line. Do not call tools.")
                .setMaxTurns(1)
                .enableAppFunctions(false)
                .build()
            runner = AgentRunner(
                context = appContext,
                agent = agent,
                generativeModel = model,
            )
            val text = StringBuilder()
            val completed = withTimeoutOrNull(INFERENCE_TIMEOUT_MS) {
                runner.runSingleTurnAsync(Content.builder().text(AGENT_INPUT).build()).collect { event ->
                    if (event is AgentEvent.TextMessage) {
                        if (event.isPartial) {
                            text.append(event.text)
                        } else if (event.text.isNotBlank()) {
                            text.append(event.text)
                        }
                    }
                }
                true
            } ?: false
            if (!completed && text.isEmpty()) {
                OfflineInferenceResult(
                    feature = GenAiFeature.AGENT,
                    outcome = OfflineInferenceOutcome.FAILED,
                    failure = GenAiFailure(
                        errorCode = null,
                        exceptionClass = "kotlinx.coroutines.TimeoutCancellationException",
                        message = "no agent response within ${INFERENCE_TIMEOUT_MS / 1000} s",
                    ),
                    latencyMillis = SystemClock.elapsedRealtime() - started,
                )
            } else {
                OfflineInferenceResult(
                    feature = GenAiFeature.AGENT,
                    outcome = OfflineInferenceOutcome.SUCCEEDED,
                    output = text.toString().trim().take(MAX_OUTPUT),
                    latencyMillis = SystemClock.elapsedRealtime() - started,
                    detail = "single-turn AgentRunner call with app functions disabled",
                )
            }
        } finally {
            runCatching { runner?.close() }
            runCatching { model.close() }
        }
    }

    private suspend fun inferSpeechRecognition(): OfflineInferenceResult {
        val started = SystemClock.elapsedRealtime()
        val client = SpeechRecognition.getClient(recognizerOptions())
        return try {
            val wav = silenceWavFile()
            var text = ""
            var error: GenAiException? = null
            var completed = false
            runCatching {
                AudioSource.fromFile(wav).use { source ->
                    val request = speechRecognizerRequest {
                        audioSource = source
                        recognitionOptions { enableWordTiming = false }
                    }
                    withTimeoutOrNull(INFERENCE_TIMEOUT_MS) {
                        runCatching {
                            client.startRecognition(request).collect { response ->
                                when (response) {
                                    is SpeechRecognizerResponse.FinalTextResponse -> {
                                        text = listOf(text, response.text)
                                            .filter { it.isNotBlank() }
                                            .joinToString(" ")
                                    }

                                    is SpeechRecognizerResponse.CompletedResponse -> {
                                        completed = true
                                        throw StopCollection
                                    }

                                    is SpeechRecognizerResponse.ErrorResponse -> {
                                        error = response.e
                                        throw StopCollection
                                    }

                                    else -> Unit
                                }
                            }
                        }.onFailure { if (it !== StopCollection) throw it }
                    }
                }
            }.onFailure { if (it !== StopCollection) throw it }

            val failure = error
            when {
                failure != null -> OfflineInferenceResult(
                    feature = GenAiFeature.SPEECH_RECOGNITION,
                    outcome = OfflineInferenceOutcome.FAILED,
                    failure = failure.toFailure(),
                    latencyMillis = SystemClock.elapsedRealtime() - started,
                )

                else -> OfflineInferenceResult(
                    feature = GenAiFeature.SPEECH_RECOGNITION,
                    outcome = OfflineInferenceOutcome.SUCCEEDED,
                    output = if (text.isBlank()) "(no text recognised)" else text.take(MAX_OUTPUT),
                    latencyMillis = SystemClock.elapsedRealtime() - started,
                    detail = if (completed) {
                        "fixed 1.0 s 16 kHz mono PCM16 silence file via AudioSource.fromFile"
                    } else {
                        "fixed 1.0 s 16 kHz mono PCM16 silence file; stream ended without a completion event"
                    },
                )
            }
        } finally {
            runCatching { client.close() }
        }
    }

    private suspend fun inferSpeechTranslation(): OfflineInferenceResult {
        val started = SystemClock.elapsedRealtime()
        val client = SpeechTranslation.getClient(SpeechTranslatorOptions.Builder(Locale.US, Locale.CHINA).build())
        return try {
            val silence = ByteArray(PCM_CHUNK_BYTES)
            val audio = flowOf(*Array(10) { TranslationAudio(Locale.US, silence) })
            val request = SpeechTranslatorRequest.Builder(audio).build()
            var translated = ""
            var error: GenAiException? = null
            var completed = false
            withTimeoutOrNull(INFERENCE_TIMEOUT_MS) {
                runCatching {
                    client.startTranslation(request).collect { response ->
                        when (response) {
                            is SpeechTranslatorResponse.Text -> {
                                translated = listOf(translated, response.translatedText)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" ")
                            }

                            is SpeechTranslatorResponse.CompletedResponse -> {
                                completed = true
                                throw StopCollection
                            }

                            is SpeechTranslatorResponse.ErrorResponse -> {
                                error = response.e
                                throw StopCollection
                            }

                            else -> Unit
                        }
                    }
                }.onFailure { if (it !== StopCollection) throw it }
            }

            val failure = error
            when {
                failure != null -> OfflineInferenceResult(
                    feature = GenAiFeature.SPEECH_TRANSLATION,
                    outcome = OfflineInferenceOutcome.FAILED,
                    failure = failure.toFailure(),
                    latencyMillis = SystemClock.elapsedRealtime() - started,
                )

                else -> OfflineInferenceResult(
                    feature = GenAiFeature.SPEECH_TRANSLATION,
                    outcome = OfflineInferenceOutcome.SUCCEEDED,
                    output = if (translated.isBlank()) "(no translated text returned)" else translated.take(MAX_OUTPUT),
                    latencyMillis = SystemClock.elapsedRealtime() - started,
                    detail = if (completed) {
                        "fixed 1.0 s 16 kHz mono PCM16 silence buffer, en-US to zh-CN"
                    } else {
                        "fixed 1.0 s 16 kHz mono PCM16 silence buffer; stream ended without a completion event"
                    },
                )
            }
        } finally {
            runCatching { client.close() }
        }
    }

    private suspend fun inferTextToSpeech(): OfflineInferenceResult {
        val started = SystemClock.elapsedRealtime()
        val client = SpeechSynthesis.getClient(
            speechSynthesizerOptions(Locale.US) { setVoiceFallbackEnabled(true) },
        )
        return try {
            val voice = client.getSupportedVoices(Locale.US).firstOrNull()
                ?: return OfflineInferenceResult(
                    feature = GenAiFeature.TEXT_TO_SPEECH,
                    outcome = OfflineInferenceOutcome.FAILED,
                    failure = GenAiFailure(
                        errorCode = null,
                        exceptionClass = "dev.shockstruck.gemininano.NoVoice",
                        message = "No en-US voice was returned by the public API.",
                    ),
                    latencyMillis = SystemClock.elapsedRealtime() - started,
                )
            val request = SpeechSynthesizerRequest(
                input = SpeechSynthesizerRequest.Input.Text(TTS_INPUT, voice),
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                queueMode = SpeechSynthesizerRequest.QueueMode.QUEUE_FLUSH,
            )
            var audioBytes = 0L
            var errorCode: Int? = null
            withTimeoutOrNull(INFERENCE_TIMEOUT_MS) {
                runCatching {
                    client.synthesize(request).collect { response ->
                        when (response) {
                            is SpeechSynthesizerResponse.AudioAvailable -> {
                                audioBytes += response.audio.size
                            }

                            is SpeechSynthesizerResponse.Error -> {
                                errorCode = response.errorCode
                                throw StopCollection
                            }

                            else -> Unit
                        }
                    }
                }.onFailure { if (it !== StopCollection) throw it }
            }
            val code = errorCode
            if (code != null) {
                OfflineInferenceResult(
                    feature = GenAiFeature.TEXT_TO_SPEECH,
                    outcome = OfflineInferenceOutcome.FAILED,
                    failure = GenAiFailure(
                        errorCode = code,
                        exceptionClass = "SpeechSynthesizerResponse.Error",
                        message = "SpeechSynthesizer returned error code $code",
                    ),
                    latencyMillis = SystemClock.elapsedRealtime() - started,
                )
            } else if (audioBytes == 0L) {
                OfflineInferenceResult(
                    feature = GenAiFeature.TEXT_TO_SPEECH,
                    outcome = OfflineInferenceOutcome.FAILED,
                    failure = GenAiFailure(
                        errorCode = null,
                        exceptionClass = "kotlinx.coroutines.TimeoutCancellationException",
                        message = "no audio returned within ${INFERENCE_TIMEOUT_MS / 1000} s",
                    ),
                    latencyMillis = SystemClock.elapsedRealtime() - started,
                )
            } else {
                OfflineInferenceResult(
                    feature = GenAiFeature.TEXT_TO_SPEECH,
                    outcome = OfflineInferenceOutcome.SUCCEEDED,
                    output = "$audioBytes audio bytes returned",
                    latencyMillis = SystemClock.elapsedRealtime() - started,
                    detail = "fixed text \"$TTS_INPUT\" with voice ${voice.name}",
                )
            }
        } finally {
            runCatching { client.close() }
        }
    }

    // --------------------------------------------------------------- helpers

    private suspend fun textListInference(
        feature: GenAiFeature,
        input: String,
        run: suspend (String) -> List<String>,
    ): OfflineInferenceResult {
        val started = SystemClock.elapsedRealtime()
        return try {
            val results = run(input)
            OfflineInferenceResult(
                feature = feature,
                outcome = OfflineInferenceOutcome.SUCCEEDED,
                output = results.filter { it.isNotBlank() }.joinToString(" | ").take(MAX_OUTPUT),
                latencyMillis = SystemClock.elapsedRealtime() - started,
                detail = "fixed input: \"$input\"",
            )
        } catch (t: Throwable) {
            OfflineInferenceResult(
                feature = feature,
                outcome = OfflineInferenceOutcome.FAILED,
                failure = t.toFailure(),
                latencyMillis = SystemClock.elapsedRealtime() - started,
                detail = "fixed input: \"$input\"",
            )
        }
    }

    private suspend fun statusResult(
        feature: GenAiFeature,
        statusCode: Int,
        baseModel: suspend () -> String,
    ): FeatureProbeResult {
        val status = statusKind(statusCode)
        val name = if (status == FeatureStatusKind.AVAILABLE) {
            runCatchingSuspend { baseModel() }
        } else {
            null
        }
        return FeatureProbeResult(
            feature = feature,
            status = status,
            baseModelName = name?.takeIf { it.isNotBlank() },
            probedAtEpochMillis = System.currentTimeMillis(),
        )
    }

    private fun failureResult(feature: GenAiFeature, throwable: Throwable, started: Long): FeatureProbeResult {
        val failure = throwable.toFailure()
        return FeatureProbeResult(
            feature = feature,
            status = FeatureStatusKind.PROBE_ERROR,
            failure = failure,
            featureId = dev.shockstruck.gemininano.model.PromptFailureParsing.parseFeatureId(failure.message),
            latencyMillis = SystemClock.elapsedRealtime() - started,
            probedAtEpochMillis = System.currentTimeMillis(),
        )
    }

    private fun unsupported(feature: GenAiFeature, detail: String): FeatureProbeResult =
        FeatureProbeResult(
            feature = feature,
            status = FeatureStatusKind.UNKNOWN,
            detail = detail,
            probedAtEpochMillis = System.currentTimeMillis(),
        )

    private suspend fun firstEmbeddingModel(): EmbeddingModel? =
        EmbeddingGeneration.getEmbeddingModels(EmbeddingOptions()).firstOrNull()

    private fun embeddingDetail(signature: String?, dimension: Int?): String? {
        val parts = mutableListOf<String>()
        signature?.let { parts += "signature=$it" }
        dimension?.let { parts += "dimension=$it" }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    private suspend fun downloadListenable(
        call: (DownloadCallback) -> ListenableFuture<Void>,
        onProgress: (FeatureDownloadProgress) -> Unit,
    ): DownloadResult {
        var downloaded = 0L
        var total: Long? = null
        var callbackFailure: GenAiException? = null
        val callback = object : DownloadCallback {
            override fun onDownloadStarted(bytesToDownload: Long) {
                total = bytesToDownload
                onProgress(FeatureDownloadProgress(0L, total))
            }

            override fun onDownloadProgress(bytesDownloaded: Long) {
                downloaded = bytesDownloaded
                onProgress(FeatureDownloadProgress(downloaded, total))
            }

            override fun onDownloadCompleted() = Unit

            override fun onDownloadFailed(exception: GenAiException) {
                callbackFailure = exception
            }
        }
        return try {
            call(callback).await()
            DownloadResult(true, downloaded, total, null)
        } catch (t: Throwable) {
            DownloadResult(
                success = false,
                bytesDownloaded = downloaded,
                totalBytes = total,
                failure = callbackFailure?.toFailure() ?: t.toFailure(),
            )
        }
    }

    private suspend fun downloadFlow(
        flow: Flow<DownloadStatus>,
        onProgress: (FeatureDownloadProgress) -> Unit,
    ): DownloadResult {
        var downloaded = 0L
        var total: Long? = null
        return try {
            flow.collect { status ->
                when (status) {
                    is DownloadStatus.DownloadStarted -> {
                        total = status.bytesToDownload
                        onProgress(FeatureDownloadProgress(0L, total))
                    }

                    is DownloadStatus.DownloadProgress -> {
                        downloaded = status.totalBytesDownloaded
                        onProgress(FeatureDownloadProgress(downloaded, total))
                    }

                    is DownloadStatus.DownloadFailed -> throw status.e

                    is DownloadStatus.DownloadCompleted -> Unit
                }
            }
            DownloadResult(true, downloaded, total, null)
        } catch (t: Throwable) {
            DownloadResult(false, downloaded, total, t.toFailure())
        }
    }

    private fun statusKind(code: Int): FeatureStatusKind = when (code) {
        FeatureStatus.AVAILABLE -> FeatureStatusKind.AVAILABLE
        FeatureStatus.DOWNLOADABLE -> FeatureStatusKind.DOWNLOADABLE
        FeatureStatus.DOWNLOADING -> FeatureStatusKind.DOWNLOADING
        FeatureStatus.UNAVAILABLE -> FeatureStatusKind.UNAVAILABLE
        else -> FeatureStatusKind.UNKNOWN
    }

    private fun Throwable.toFailure(): GenAiFailure {
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

    private fun diagnosticBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(BITMAP_SIZE, BITMAP_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        canvas.drawCircle(BITMAP_SIZE / 2f, BITMAP_SIZE / 2f, BITMAP_SIZE / 3f, paint)
        return bitmap
    }

    private fun silenceWavFile(): File {
        val file = File(appContext.cacheDir, "gemininano-diagnostic-silence.wav")
        val dataSize = PCM_SAMPLE_RATE * PCM_SECONDS * 2
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
            out.writeBytes("RIFF")
            out.writeIntLe(36 + dataSize)
            out.writeBytes("WAVE")
            out.writeBytes("fmt ")
            out.writeIntLe(16)
            out.writeShortLe(1)
            out.writeShortLe(1)
            out.writeIntLe(PCM_SAMPLE_RATE)
            out.writeIntLe(PCM_SAMPLE_RATE * 2)
            out.writeShortLe(2)
            out.writeShortLe(16)
            out.writeBytes("data")
            out.writeIntLe(dataSize)
            out.write(ByteArray(dataSize))
        }
        return file
    }

    private fun DataOutputStream.writeIntLe(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun DataOutputStream.writeShortLe(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }

    private fun recognizerOptions(): SpeechRecognizerOptions =
        speechRecognizerOptions {
            locale = Locale.US
            preferredMode = SpeechRecognizerOptions.Mode.MODE_BASIC
        }

    private suspend fun runCatchingSuspend(block: suspend () -> String): String? =
        try {
            block()
        } catch (_: Throwable) {
            null
        }

    private object StopCollection : Throwable() {
        private fun readResolve(): Any = StopCollection
    }

    companion object {
        const val REWRITING_INPUT = "The diagnostic meeting is at 3 p.m."
        const val PROOFREADING_INPUT = "Their going to the store on there way home."
        const val EMBEDDING_INPUT = "Gemini Nano on-device diagnostic embedding."
        const val AGENT_INPUT = "Reply OK"
        const val TTS_INPUT = "Gemini Nano offline diagnostic."
        const val BITMAP_SIZE = 96
        const val PCM_SAMPLE_RATE = 16_000
        const val PCM_SECONDS = 1
        const val PCM_CHUNK_BYTES = 3_200
        const val INFERENCE_TIMEOUT_MS = 30_000L
        const val MAX_OUTPUT = 400
    }
}
