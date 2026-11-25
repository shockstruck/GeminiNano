package dev.shockstruck.gemininano.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptFactsTest {

    @Test
    fun parsesEveryPublishedFeatureIdShape() {
        listOf(636, 645, 646, 647, 648).forEach { id ->
            assertEquals(
                id,
                PromptFailureParsing.parseFeatureId("Feature $id is not available."),
            )
        }
    }

    @Test
    fun parsesTheMessageEvenWithPrefixAndSuffix() {
        assertEquals(
            648,
            PromptFailureParsing.parseFeatureId(
                "3-PREPARATION_ERROR / 606-FEATURE_NOT_FOUND: Feature 648 is not available.",
            ),
        )
    }

    @Test
    fun noMessageMeansNoFeatureId() {
        assertNull(PromptFailureParsing.parseFeatureId(null))
        assertNull(PromptFailureParsing.parseFeatureId("AICore could not be bound."))
        assertNull(PromptFailureParsing.parseFeatureId("Feature is not available."))
    }

    @Test
    fun fourConfigsAreProbedIndependently() {
        val statuses = listOf(636, 645, 646, 647).mapIndexed { index, id ->
            PromptConfigStatus(
                config = PromptModelConfig.ALL[index],
                status = FeatureStatusKind.PROBE_ERROR,
                featureId = id,
                errorCode = 606,
                exceptionClass = "com.google.mlkit.genai.common.GenAiException",
                message = "Feature $id is not available.",
                baseModelName = null,
                generatedText = null,
                latencyMillis = 10L,
                probedAtEpochMillis = 1L,
            )
        }
        assertEquals(
            listOf(636, 645, 646, 647),
            statuses.map { it.featureId },
        )
        assertEquals(
            listOf("STABLE/FAST", "STABLE/FULL", "PREVIEW/FAST", "PREVIEW/FULL"),
            statuses.map { it.config.label },
        )
    }

    @Test
    fun feature622KeepsItsIdentityAndIsNotRemapped() {
        assertEquals(622, PromptFailureParsing.parseFeatureId("Feature 622 is not available."))
        val status = PromptConfigStatus(
            config = PromptModelConfig.ALL.first(),
            status = FeatureStatusKind.PROBE_ERROR,
            featureId = 648,
            errorCode = 606,
            exceptionClass = "com.google.mlkit.genai.common.GenAiException",
            message = "Feature 648 is not available.",
            baseModelName = null,
            generatedText = null,
            latencyMillis = 10L,
            probedAtEpochMillis = 1L,
        )
        assertEquals(648, status.featureId)
        assertFalse(status.statusLabel.contains("622"))
    }

    @Test
    fun downloadIsOfferedOnlyForDownloadable() {
        fun status(kind: FeatureStatusKind) = PromptConfigStatus(
            config = PromptModelConfig.ALL.first(),
            status = kind,
            featureId = null,
            errorCode = null,
            exceptionClass = null,
            message = null,
            baseModelName = null,
            generatedText = null,
            latencyMillis = 0L,
            probedAtEpochMillis = 0L,
        )
        assertTrue(status(FeatureStatusKind.DOWNLOADABLE).downloadOffered)
        assertFalse(status(FeatureStatusKind.AVAILABLE).downloadOffered)
        assertFalse(status(FeatureStatusKind.DOWNLOADING).downloadOffered)
        assertFalse(status(FeatureStatusKind.UNAVAILABLE).downloadOffered)
        assertFalse(status(FeatureStatusKind.PROBE_ERROR).downloadOffered)
        assertFalse(status(FeatureStatusKind.UNKNOWN).downloadOffered)
    }

    @Test
    fun prompt601KeepsItsCodeAndHasNoInventedFeatureId() {
        val status = PromptConfigStatus(
            config = PromptModelConfig.ALL.first(),
            status = FeatureStatusKind.PROBE_ERROR,
            featureId = PromptFailureParsing.parseFeatureId("AICore could not be bound."),
            errorCode = 601,
            exceptionClass = "com.google.mlkit.genai.common.GenAiException",
            message = "AICore could not be bound.",
            baseModelName = null,
            generatedText = null,
            latencyMillis = 8L,
            probedAtEpochMillis = 1L,
        )
        assertEquals(601, status.errorCode)
        assertNull(status.featureId)
        assertEquals("PROBE ERROR (code 601)", status.statusLabel)
    }

    @Test
    fun downloadAndGenerationStayInsideTheirStatuses() {
        fun status(kind: FeatureStatusKind) = PromptConfigStatus(
            config = PromptModelConfig.ALL.first(),
            status = kind,
            featureId = null,
            errorCode = null,
            exceptionClass = null,
            message = null,
            baseModelName = null,
            generatedText = null,
            latencyMillis = 0L,
            probedAtEpochMillis = 0L,
        )
        FeatureStatusKind.entries.forEach { kind ->
            val row = status(kind)
            assertEquals(kind == FeatureStatusKind.DOWNLOADABLE, row.downloadOffered)
            assertEquals(kind == FeatureStatusKind.AVAILABLE, row.generationOffered)
        }
    }

    @Test
    fun availableWithoutGenerationIsNotAClaimedSuccess() {
        val status = PromptConfigStatus(
            config = PromptModelConfig.ALL.first(),
            status = FeatureStatusKind.AVAILABLE,
            featureId = null,
            errorCode = 619,
            exceptionClass = "com.google.mlkit.genai.common.GenAiException",
            message = "generation failed",
            baseModelName = null,
            generatedText = null,
            latencyMillis = 5L,
            probedAtEpochMillis = 1L,
        )
        assertFalse(status.generationSucceeded)
        assertEquals("AVAILABLE · generation not verified", status.statusLabel)
    }
}
