package dev.shockstruck.gemininano.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureMatrixTest {

    private fun result(
        feature: GenAiFeature,
        status: FeatureStatusKind,
        failure: GenAiFailure? = null,
        featureId: Int? = null,
        baseModelName: String? = null,
        signature: String? = null,
        dimension: Int? = null,
        latency: Long = 5L,
    ) = FeatureProbeResult(
        feature = feature,
        status = status,
        failure = failure,
        featureId = featureId,
        baseModelName = baseModelName,
        modelSignature = signature,
        metadataDimension = dimension,
        latencyMillis = latency,
        probedAtEpochMillis = 1_800_000_000_000L,
    )

    @Test
    fun matrixHasTheEightVerifiedArtifacts() {
        assertEquals(8, GenAiFeature.MATRIX.size)
        assertEquals(
            listOf(
                "com.google.mlkit:genai-rewriting:1.0.0-beta1",
                "com.google.mlkit:genai-proofreading:1.0.0-beta1",
                "com.google.mlkit:genai-image-description:1.0.0-beta1",
                "com.google.mlkit:genai-embedding:1.0.0-alpha1",
                "com.google.mlkit:genai-agent:1.0.0-alpha1",
                "com.google.mlkit:genai-speech-recognition:1.0.0-alpha2",
                "com.google.mlkit:genai-speech-translation:1.0.0-alpha1",
                "com.google.mlkit:genai-text-to-speech:1.0.0-alpha1",
            ),
            GenAiFeature.MATRIX.map { it.artifact },
        )
        assertTrue(GenAiFeature.MATRIX.all { it.implementedInPublicApi })
    }

    @Test
    fun everyImplementedFeatureMapsStatusDownloadAndErrorIndependently() {
        GenAiFeature.MATRIX.forEach { feature ->
            val available = result(feature, FeatureStatusKind.AVAILABLE)
            assertTrue("$feature AVAILABLE must offer inference", available.inferenceOffered)
            assertFalse("$feature AVAILABLE must not offer a download", available.downloadOffered)

            val downloadable = result(feature, FeatureStatusKind.DOWNLOADABLE)
            assertTrue("$feature DOWNLOADABLE must offer a download", downloadable.downloadOffered)
            assertFalse("$feature DOWNLOADABLE must not offer inference", downloadable.inferenceOffered)

            val unavailable = result(feature, FeatureStatusKind.UNAVAILABLE)
            assertFalse("$feature UNAVAILABLE must not offer a download", unavailable.downloadOffered)
            assertFalse("$feature UNAVAILABLE must not offer inference", unavailable.inferenceOffered)

            val failed = result(
                feature = feature,
                status = FeatureStatusKind.PROBE_ERROR,
                failure = GenAiFailure(
                    errorCode = 606,
                    exceptionClass = "com.google.mlkit.genai.common.GenAiException",
                    message = "Feature 700 is not available.",
                ),
                featureId = PromptFailureParsing.parseFeatureId("Feature 700 is not available."),
            )
            assertEquals(feature, failed.feature)
            assertEquals(606, failed.errorCode)
            assertEquals(700, failed.featureId)
            assertEquals(
                GenAiFailureCategory.FEATURE_NOT_AVAILABLE,
                GenAiFailureClassification.classify(failed.failure),
            )
        }
    }

    @Test
    fun availableStatusOffersInferenceButNeverADownload() {
        val available = result(GenAiFeature.REWRITING, FeatureStatusKind.AVAILABLE)
        assertTrue(available.inferenceOffered)
        assertFalse(available.downloadOffered)
        assertEquals("AVAILABLE (status only)", available.statusLabel)
        assertEquals(
            "already available — no download offered",
            FeatureMatrixFormatting.eligibilityLabel(available),
        )
    }

    @Test
    fun downloadableStatusOffersOnlyADownload() {
        val downloadable = result(GenAiFeature.PROOFREADING, FeatureStatusKind.DOWNLOADABLE)
        assertTrue(downloadable.downloadOffered)
        assertFalse(downloadable.inferenceOffered)
        assertEquals(
            "download available (ML Kit reports DOWNLOADABLE)",
            FeatureMatrixFormatting.eligibilityLabel(downloadable),
        )
    }

    @Test
    fun unavailableNeverOffersDownloadOrInference() {
        val unavailable = result(GenAiFeature.IMAGE_DESCRIPTION, FeatureStatusKind.UNAVAILABLE)
        assertFalse(unavailable.downloadOffered)
        assertFalse(unavailable.inferenceOffered)
        assertEquals(
            "not downloadable — ML Kit reports UNAVAILABLE",
            FeatureMatrixFormatting.eligibilityLabel(unavailable),
        )
    }

    @Test
    fun errorKeepsExactCodeClassAndMessage() {
        val failure = GenAiFailure(
            errorCode = 606,
            exceptionClass = "com.google.mlkit.genai.common.GenAiException",
            message = "Feature 636 is not available.",
        )
        val result = result(GenAiFeature.AGENT, FeatureStatusKind.PROBE_ERROR, failure, featureId = 636)
        assertEquals(606, result.errorCode)
        assertEquals("com.google.mlkit.genai.common.GenAiException", result.exceptionClass)
        assertEquals("Feature 636 is not available.", result.message)
        assertEquals("PROBE ERROR (code 606)", result.statusLabel)
        assertEquals(
            GenAiFailureCategory.FEATURE_NOT_AVAILABLE,
            GenAiFailureClassification.classify(failure),
        )
        val rendered = FeatureMatrixFormatting.renderResult(result)
        assertTrue(rendered.contains("error=606"))
        assertTrue(rendered.contains("class=com.google.mlkit.genai.common.GenAiException"))
        assertTrue(rendered.contains("message=Feature 636 is not available."))
        assertTrue(rendered.contains("featureId=636"))
    }

    @Test
    fun a606OnOneFeatureLeavesEveryOtherFeatureRowUntouched() {
        val ids = listOf(636, 645, 646, 647, 648)
        val features = listOf(
            GenAiFeature.REWRITING to 636,
            GenAiFeature.PROOFREADING to 645,
            GenAiFeature.IMAGE_DESCRIPTION to 646,
            GenAiFeature.EMBEDDING to 647,
            GenAiFeature.AGENT to 648,
        )
        val failures = features.map { (feature, id) ->
            result(
                feature = feature,
                status = FeatureStatusKind.PROBE_ERROR,
                failure = GenAiFailure(606, "com.google.mlkit.genai.common.GenAiException", "Feature $id is not available."),
                featureId = PromptFailureParsing.parseFeatureId("Feature $id is not available."),
            )
        }
        failures.forEachIndexed { index, failure ->
            assertEquals(ids[index], failure.featureId)
            assertEquals(606, failure.errorCode)
        }
        assertEquals(ids, failures.map { it.featureId })
        assertEquals(features.map { it.first }, failures.map { it.feature })
    }

    @Test
    fun a601IsReportedAsAicoreNotBoundNotAsAFeatureFailure() {
        val failure = GenAiFailure(601, "com.google.mlkit.genai.common.GenAiException", "AICore is not bound")
        assertEquals(GenAiFailureCategory.NOT_BOUND, GenAiFailureClassification.classify(failure))
        assertEquals("AICore not bound (601)", GenAiFailureClassification.label(failure))
    }

    @Test
    fun downloadInterruptionIsDerivedOnlyFromReportedBytes() {
        val partial = DownloadResult(
            success = false,
            bytesDownloaded = 4_000_000L,
            totalBytes = 10_000_000L,
            failure = GenAiFailure(null, "java.io.IOException", "connection lost"),
        )
        assertTrue(partial.interrupted)

        val neverStarted = DownloadResult(
            success = false,
            bytesDownloaded = 0L,
            totalBytes = 10_000_000L,
            failure = GenAiFailure(null, "java.io.IOException", "connection lost"),
        )
        assertFalse(neverStarted.interrupted)

        val completed = DownloadResult(true, 10_000_000L, 10_000_000L, null)
        assertFalse(completed.interrupted)
    }

    @Test
    fun networkInvalidMetadataAndIntegrityFailuresAreCategorisedFromReturnedText() {
        assertEquals(
            GenAiFailureCategory.NETWORK_UNAVAILABLE,
            GenAiFailureClassification.classify(
                GenAiFailure(null, "java.io.IOException", "network unavailable"),
            ),
        )
        assertEquals(
            GenAiFailureCategory.INVALID_METADATA,
            GenAiFailureClassification.classify(
                GenAiFailure(null, "com.google.mlkit.genai.common.GenAiException", "invalid model metadata"),
            ),
        )
        assertEquals(
            GenAiFailureCategory.INTEGRITY_FAILURE,
            GenAiFailureClassification.classify(
                GenAiFailure(null, "com.google.mlkit.genai.common.GenAiException", "integrity check failed"),
            ),
        )
        assertEquals(
            GenAiFailureCategory.UNKNOWN,
            GenAiFailureClassification.classify(null),
        )
    }

    @Test
    fun baseModelIsRenderedOnlyWhenTheApiReturnedOne() {
        val without = result(GenAiFeature.SPEECH_RECOGNITION, FeatureStatusKind.AVAILABLE)
        assertNull(without.baseModelName)
        assertFalse(FeatureMatrixFormatting.renderResult(without).contains("baseModel="))

        val with = result(GenAiFeature.REWRITING, FeatureStatusKind.AVAILABLE, baseModelName = "base-model")
        assertTrue(FeatureMatrixFormatting.renderResult(with).contains("baseModel=base-model"))
    }

    @Test
    fun embeddingUsesTheReturnedSignatureAndDimension() {
        val embedding = result(
            GenAiFeature.EMBEDDING,
            FeatureStatusKind.AVAILABLE,
            signature = "embedding_gemma_v1",
            dimension = 768,
        )
        assertNull(embedding.baseModelName)
        assertEquals("embedding_gemma_v1", embedding.modelSignature)
        assertEquals(768, embedding.metadataDimension)
        val rendered = FeatureMatrixFormatting.renderResult(embedding)
        assertTrue(rendered.contains("signature=embedding_gemma_v1"))
        assertTrue(rendered.contains("dimension=768"))
    }

    @Test
    fun aMissingResultIsNotProbedAndNeverAnError() {
        val notProbed = FeatureProbeResult.notProbed(GenAiFeature.PROOFREADING)
        assertEquals(FeatureStatusKind.NOT_PROBED, notProbed.status)
        assertEquals("NOT PROBED", notProbed.statusLabel)
        assertEquals(FeatureStatusKind.NOT_PROBED_DETAIL, notProbed.detail)
        assertNull(notProbed.failure)
        assertFalse(notProbed.downloadOffered)
        assertFalse(notProbed.inferenceOffered)
        assertEquals(
            "unknown — not probed yet",
            FeatureMatrixFormatting.eligibilityLabel(notProbed),
        )
        assertFalse(
            FeatureMatrixFormatting.renderResult(notProbed).contains("error="),
        )
    }

    @Test
    fun omittedFeatureRendersTheExactPublicApiReason() {
        val result = FeatureProbeResult.omitted(
            GenAiFeature.REWRITING,
            reason = "No public client exists in the published artifact.",
        )
        assertEquals(FeatureStatusKind.NOT_IN_PUBLIC_API, result.status)
        assertEquals("No public client exists in the published artifact.", result.detail)
        assertEquals(
            "no download — not available in the public API",
            FeatureMatrixFormatting.eligibilityLabel(result),
        )
        assertFalse(result.downloadOffered)
        assertEquals("NOT AVAILABLE IN THE PUBLIC API", result.status.label)
    }
}
