package dev.shockstruck.gemininano.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineMatrixTest {

    private fun result(feature: GenAiFeature, status: FeatureStatusKind) = FeatureProbeResult(
        feature = feature,
        status = status,
        probedAtEpochMillis = 1L,
    )

    @Test
    fun onlyAvailableFeaturesAreEligible() {
        val results = listOf(
            result(GenAiFeature.REWRITING, FeatureStatusKind.AVAILABLE),
            result(GenAiFeature.PROOFREADING, FeatureStatusKind.DOWNLOADABLE),
            result(GenAiFeature.EMBEDDING, FeatureStatusKind.UNAVAILABLE),
            result(GenAiFeature.AGENT, FeatureStatusKind.PROBE_ERROR),
        )
        assertEquals(1, OfflineInferencePlanning.eligibleCount(results))
        val rows = OfflineInferencePlanning.rows(results)
        assertEquals(8, rows.size)
        val rewriting = rows.first { it.feature == GenAiFeature.REWRITING }
        assertEquals("eligible — not run yet", rewriting.detail)
        assertTrue(rewriting.detail!!.contains("eligible"))
    }

    @Test
    fun eachSkipNamesTheObservedStatusInsteadOfClaimingFailure() {
        val results = listOf(
            result(GenAiFeature.PROOFREADING, FeatureStatusKind.DOWNLOADABLE),
            result(GenAiFeature.EMBEDDING, FeatureStatusKind.UNAVAILABLE),
            result(GenAiFeature.AGENT, FeatureStatusKind.PROBE_ERROR),
        )
        val rows = OfflineInferencePlanning.rows(results)
        assertTrue(
            rows.first { it.feature == GenAiFeature.PROOFREADING }.detail!!
                .contains("DOWNLOADABLE"),
        )
        assertTrue(
            rows.first { it.feature == GenAiFeature.EMBEDDING }.detail!!
                .contains("UNAVAILABLE"),
        )
        assertTrue(
            rows.first { it.feature == GenAiFeature.AGENT }.detail!!
                .contains("PROBE ERROR"),
        )
        assertTrue(
            rows.first { it.feature == GenAiFeature.TEXT_TO_SPEECH }.detail!!
                .contains("status not probed"),
        )
    }

    @Test
    fun mergeKeepsEveryFeatureRowAndItsOwnFailure() {
        val first = OfflineInferencePlanning.rows(
            listOf(
                result(GenAiFeature.REWRITING, FeatureStatusKind.AVAILABLE),
                result(GenAiFeature.PROOFREADING, FeatureStatusKind.AVAILABLE),
            ),
        )
        val fresh = listOf(
            OfflineInferenceResult(
                feature = GenAiFeature.REWRITING,
                outcome = OfflineInferenceOutcome.SUCCEEDED,
                output = "rewritten",
            ),
            OfflineInferenceResult(
                feature = GenAiFeature.PROOFREADING,
                outcome = OfflineInferenceOutcome.FAILED,
                failure = GenAiFailure(606, "com.google.mlkit.genai.common.GenAiException", "Feature 645 is not available."),
            ),
        )
        val merged = OfflineInferencePlanning.merge(first, fresh)
        assertEquals(8, merged.size)
        assertEquals(
            OfflineInferenceOutcome.SUCCEEDED,
            merged.first { it.feature == GenAiFeature.REWRITING }.outcome,
        )
        assertEquals(
            OfflineInferenceOutcome.FAILED,
            merged.first { it.feature == GenAiFeature.PROOFREADING }.outcome,
        )
        assertEquals(606, merged.first { it.feature == GenAiFeature.PROOFREADING }.errorCode)
        assertEquals(
            OfflineInferenceOutcome.SKIPPED,
            merged.first { it.feature == GenAiFeature.EMBEDDING }.outcome,
        )
    }

    @Test
    fun caveatNeverClaimsOfflineProof() {
        val caveat = OfflineInferenceFormatting.offlineCaveat()
        assertTrue(caveat.contains("does not prove offline operation"))
        assertTrue(caveat.contains("emulator or mocked results are not device evidence"))
        val header = OfflineInferenceFormatting.header(3)
        assertTrue(header.contains("3 eligible"))
        assertTrue(header.contains("cannot and will not toggle radios"))
    }

    @Test
    fun successRenderingUsesReturnedValuesAndLatency() {
        val rendered = OfflineInferenceFormatting.renderResult(
            OfflineInferenceResult(
                feature = GenAiFeature.TEXT_TO_SPEECH,
                outcome = OfflineInferenceOutcome.SUCCEEDED,
                output = "1024 audio bytes returned",
                latencyMillis = 42L,
            ),
        )
        assertTrue(rendered.contains("Text to Speech: inference returned"))
        assertTrue(rendered.contains("output=1024 audio bytes returned"))
        assertTrue(rendered.contains("latency=42ms"))
        assertFalse(rendered.contains("offline success"))
    }
}
