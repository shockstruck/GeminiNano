package dev.shockstruck.gemininano.diagnostics

import dev.shockstruck.gemininano.model.FeatureProbeResult
import dev.shockstruck.gemininano.model.FeatureStatusKind
import dev.shockstruck.gemininano.model.GenAiFailure
import dev.shockstruck.gemininano.model.GenAiFeature
import dev.shockstruck.gemininano.model.PromptConfigStatus
import dev.shockstruck.gemininano.model.PromptFailureParsing
import dev.shockstruck.gemininano.model.PromptModelConfig
import dev.shockstruck.gemininano.model.SummarizationProbeResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs each probe in isolation behind its own timeout.
 *
 * - One probe that throws never prevents the next from running.
 * - One probe that hangs until the timeout yields a timeout result with the
 *   `TimeoutCancellationException` class, not a silent stop.
 * - A result the probe itself returned (including a 601/606 PROBE ERROR) is kept as it
 *   is; this runner never rewrites the cause.
 * - A failure this runner synthesises measures its latency from the moment the probe was
 *   started, not from the moment the exception was handled.
 */
class BoundedProbeRunner(
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val elapsedClock: () -> Long = { System.nanoTime() / 1_000_000L },
) {

    suspend fun probeFeatures(
        features: List<GenAiFeature>,
        probe: suspend (GenAiFeature) -> FeatureProbeResult,
    ): List<FeatureProbeResult> = features.map { feature -> probeFeature(feature, probe) }

    suspend fun probeFeature(
        feature: GenAiFeature,
        probe: suspend (GenAiFeature) -> FeatureProbeResult,
    ): FeatureProbeResult {
        val startedAt = elapsedClock()
        val probedAt = wallClock()
        return when (val outcome = run("${feature.key} probe") { probe(feature) }) {
            is ProbeOutcome.Value -> outcome.value
            is ProbeOutcome.Error -> FeatureProbeResult(
                feature = feature,
                status = FeatureStatusKind.PROBE_ERROR,
                failure = outcome.failure(),
                latencyMillis = elapsedClock() - startedAt,
                probedAtEpochMillis = probedAt,
            )

            is ProbeOutcome.TimedOut -> FeatureProbeResult(
                feature = feature,
                status = FeatureStatusKind.PROBE_ERROR,
                failure = timeoutFailure(outcome),
                latencyMillis = elapsedClock() - startedAt,
                probedAtEpochMillis = probedAt,
            )
        }
    }

    suspend fun probePrompts(
        configs: List<PromptModelConfig>,
        probe: suspend (PromptModelConfig) -> PromptConfigStatus,
    ): List<PromptConfigStatus> = configs.map { config -> probePrompt(config, probe) }

    suspend fun probePrompt(
        config: PromptModelConfig,
        probe: suspend (PromptModelConfig) -> PromptConfigStatus,
    ): PromptConfigStatus {
        val startedAt = elapsedClock()
        val probedAt = wallClock()
        return when (val outcome = run("${config.label} probe") { probe(config) }) {
            is ProbeOutcome.Value -> outcome.value
            is ProbeOutcome.Error -> {
                val failure = outcome.failure()
                PromptConfigStatus(
                    config = config,
                    status = FeatureStatusKind.PROBE_ERROR,
                    featureId = PromptFailureParsing.parseFeatureId(failure.message),
                    errorCode = failure.errorCode,
                    exceptionClass = failure.exceptionClass,
                    message = failure.message,
                    baseModelName = null,
                    generatedText = null,
                    latencyMillis = elapsedClock() - startedAt,
                    probedAtEpochMillis = probedAt,
                )
            }

            is ProbeOutcome.TimedOut -> {
                val failure = timeoutFailure(outcome)
                PromptConfigStatus(
                    config = config,
                    status = FeatureStatusKind.PROBE_ERROR,
                    featureId = PromptFailureParsing.parseFeatureId(failure.message),
                    errorCode = null,
                    exceptionClass = failure.exceptionClass,
                    message = failure.message,
                    baseModelName = null,
                    generatedText = null,
                    latencyMillis = elapsedClock() - startedAt,
                    probedAtEpochMillis = probedAt,
                )
            }
        }
    }

    suspend fun probeSummarization(
        probe: suspend () -> SummarizationProbeResult,
    ): SummarizationProbeResult {
        val probedAt = wallClock()
        return when (val outcome = run("summarization probe") { probe() }) {
            is ProbeOutcome.Value -> outcome.value
            is ProbeOutcome.Error -> {
                val failure = outcome.failure()
                SummarizationProbeResult(
                    status = FeatureStatusKind.PROBE_ERROR,
                    statusDetail = failure.message,
                    baseModelName = null,
                    failure = failure,
                    probedAtEpochMillis = probedAt,
                )
            }

            is ProbeOutcome.TimedOut -> {
                val failure = timeoutFailure(outcome)
                SummarizationProbeResult(
                    status = FeatureStatusKind.PROBE_ERROR,
                    statusDetail = failure.message,
                    baseModelName = null,
                    failure = failure,
                    probedAtEpochMillis = probedAt,
                )
            }
        }
    }

    private suspend fun <T> run(label: String, block: suspend () -> T): ProbeOutcome<T> {
        val outcome = withTimeoutOrNull(timeoutMillis) {
            try {
                ProbeOutcome.Value(block())
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                ProbeOutcome.Error(t)
            }
        }
        return outcome ?: ProbeOutcome.TimedOut("$label produced no result within $timeoutMillis ms")
    }

    private fun ProbeOutcome.Error.failure(): GenAiFailure = GenAiFailure(
        errorCode = null,
        exceptionClass = throwable.javaClass.name,
        message = throwable.message ?: throwable.javaClass.simpleName,
    )

    private fun timeoutFailure(outcome: ProbeOutcome.TimedOut): GenAiFailure = GenAiFailure(
        errorCode = null,
        exceptionClass = "kotlinx.coroutines.TimeoutCancellationException",
        message = outcome.message,
    )

    private sealed interface ProbeOutcome<out T> {
        data class Value<T>(val value: T) : ProbeOutcome<T>
        data class Error(val throwable: Throwable) : ProbeOutcome<Nothing>
        data class TimedOut(val message: String) : ProbeOutcome<Nothing>
    }

    companion object {
        /** Long enough for a cold AICore status check, short enough to bound the action. */
        const val DEFAULT_TIMEOUT_MILLIS = 30_000L
    }
}
