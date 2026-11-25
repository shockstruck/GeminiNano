package dev.shockstruck.gemininano.diagnostics

import dev.shockstruck.gemininano.model.FeatureProbeResult
import dev.shockstruck.gemininano.model.FeatureStatusKind
import dev.shockstruck.gemininano.model.GenAiFeature
import dev.shockstruck.gemininano.model.PromptConfigStatus
import dev.shockstruck.gemininano.model.PromptModelConfig
import dev.shockstruck.gemininano.model.SummarizationProbeResult
import dev.shockstruck.gemininano.testing.TestFixtures
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedProbeRunnerTest {

    private fun available(feature: GenAiFeature) = FeatureProbeResult(
        feature = feature,
        status = FeatureStatusKind.AVAILABLE,
        latencyMillis = 5L,
        probedAtEpochMillis = 1_800_000_000_000L,
    )

    @Test
    fun oneFeatureThrowingDoesNotPreventTheRest() = runBlocking {
        val runner = BoundedProbeRunner(timeoutMillis = 5_000L)
        val results = runner.probeFeatures(GenAiFeature.MATRIX) { feature ->
            if (feature == GenAiFeature.PROOFREADING) {
                throw IllegalStateException("probe blew up")
            }
            available(feature)
        }
        assertEquals(GenAiFeature.MATRIX.size, results.size)
        assertEquals(GenAiFeature.MATRIX, results.map { it.feature })
        val failed = results.first { it.feature == GenAiFeature.PROOFREADING }
        assertEquals(FeatureStatusKind.PROBE_ERROR, failed.status)
        assertEquals("java.lang.IllegalStateException", failed.exceptionClass)
        assertEquals("probe blew up", failed.message)
        assertEquals(
            GenAiFeature.MATRIX.size - 1,
            results.count { it.status == FeatureStatusKind.AVAILABLE },
        )
    }

    @Test
    fun aHungFeatureTimesOutAndTheRestStillRun() = runBlocking {
        val runner = BoundedProbeRunner(timeoutMillis = 100L)
        val results = runner.probeFeatures(GenAiFeature.MATRIX) { feature ->
            if (feature == GenAiFeature.AGENT) {
                delay(30_000L)
            }
            available(feature)
        }
        val hung = results.first { it.feature == GenAiFeature.AGENT }
        assertEquals(FeatureStatusKind.PROBE_ERROR, hung.status)
        assertEquals("kotlinx.coroutines.TimeoutCancellationException", hung.exceptionClass)
        assertTrue(hung.message.orEmpty().contains("100 ms"))
        assertEquals(
            GenAiFeature.MATRIX.size - 1,
            results.count { it.status == FeatureStatusKind.AVAILABLE },
        )
    }

    @Test
    fun aReturnedFailureKeepsItsOwnCodeAndProbeTime() = runBlocking {
        val runner = BoundedProbeRunner(timeoutMillis = 5_000L)
        val returned = FeatureProbeResult(
            feature = GenAiFeature.REWRITING,
            status = FeatureStatusKind.PROBE_ERROR,
            failure = TestFixtures.failure(606),
            latencyMillis = 123L,
            probedAtEpochMillis = 1_800_000_000_000L,
        )
        val result = runner.probeFeature(GenAiFeature.REWRITING) { returned }
        assertEquals(returned, result)
        assertEquals(606, result.errorCode)
        assertEquals(123L, result.latencyMillis)
    }

    @Test
    fun synthesisedFailureLatencyIsMeasuredFromTheProbesRealStart() = runBlocking {
        var now = 1_000L
        val runner = BoundedProbeRunner(
            timeoutMillis = 5_000L,
            elapsedClock = { now },
        )
        val result = runner.probeFeature(GenAiFeature.REWRITING) {
            now += 250L
            throw IllegalStateException("late failure")
        }
        assertEquals(FeatureStatusKind.PROBE_ERROR, result.status)
        assertEquals(250L, result.latencyMillis)
    }

    @Test
    fun allFourPromptConfigsRunEvenWhenOneThrows() = runBlocking {
        val runner = BoundedProbeRunner(timeoutMillis = 5_000L)
        val results = runner.probePrompts(PromptModelConfig.ALL) { config ->
            if (config == PromptModelConfig.ALL[1]) throw IllegalStateException("no binding")
            PromptConfigStatus(
                config = config,
                status = FeatureStatusKind.UNAVAILABLE,
                featureId = null,
                errorCode = null,
                exceptionClass = null,
                message = null,
                baseModelName = null,
                generatedText = null,
                latencyMillis = 12L,
                probedAtEpochMillis = 1_800_000_000_000L,
            )
        }
        assertEquals(PromptModelConfig.ALL, results.map { it.config })
        val failed = results.first { it.config == PromptModelConfig.ALL[1] }
        assertEquals(FeatureStatusKind.PROBE_ERROR, failed.status)
        assertEquals("java.lang.IllegalStateException", failed.exceptionClass)
        assertEquals(
            PromptModelConfig.ALL.size - 1,
            results.count { it.status == FeatureStatusKind.UNAVAILABLE },
        )
    }

    @Test
    fun aHungPromptTimesOutWithoutStoppingTheOtherConfigs() = runBlocking {
        val runner = BoundedProbeRunner(timeoutMillis = 100L)
        val results = runner.probePrompts(PromptModelConfig.ALL) { config ->
            if (config == PromptModelConfig.ALL.last()) delay(30_000L)
            PromptConfigStatus(
                config = config,
                status = FeatureStatusKind.UNAVAILABLE,
                featureId = null,
                errorCode = null,
                exceptionClass = null,
                message = null,
                baseModelName = null,
                generatedText = null,
                latencyMillis = 12L,
                probedAtEpochMillis = 1_800_000_000_000L,
            )
        }
        val timedOut = results.last()
        assertEquals(FeatureStatusKind.PROBE_ERROR, timedOut.status)
        assertEquals("kotlinx.coroutines.TimeoutCancellationException", timedOut.exceptionClass)
        assertEquals(
            PromptModelConfig.ALL.size - 1,
            results.count { it.status == FeatureStatusKind.UNAVAILABLE },
        )
    }

    @Test
    fun aHungSummarizationProbeIsAProbeErrorNotASilentStop() = runBlocking {
        val runner = BoundedProbeRunner(timeoutMillis = 100L)
        val result = runner.probeSummarization {
            delay(30_000L)
            TestFixtures.probeResult()
        }
        assertEquals(FeatureStatusKind.PROBE_ERROR, result.status)
        assertEquals("kotlinx.coroutines.TimeoutCancellationException", result.failure?.exceptionClass)
        assertTrue(result.statusDetail.orEmpty().contains("100 ms"))
        assertEquals(1_800_000_000_000L, TestFixtures.NOW)
        assertTrue(result.probedAtEpochMillis > 0L)
    }

    @Test
    fun aSummarizationFailureKeepsItsClassAndMessage() = runBlocking {
        val runner = BoundedProbeRunner(timeoutMillis = 5_000L)
        val result = runner.probeSummarization { throw SecurityException("aicore denied") }
        assertEquals(FeatureStatusKind.PROBE_ERROR, result.status)
        assertEquals("java.lang.SecurityException", result.failure?.exceptionClass)
        assertEquals("aicore denied", result.failure?.message)
        assertEquals("aicore denied", result.statusDetail)
    }

    @Test
    fun defaultResultForNoResultIsUnchanged() {
        val result = SummarizationProbeResult(
            status = FeatureStatusKind.PROBE_ERROR,
            statusDetail = "x",
            baseModelName = null,
            failure = null,
            probedAtEpochMillis = 0L,
        )
        assertEquals(FeatureStatusKind.PROBE_ERROR, result.status)
    }
}
