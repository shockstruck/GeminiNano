package dev.shockstruck.gemininano.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelManagerStateTest {

    private fun result(
        feature: GenAiFeature,
        status: FeatureStatusKind,
        lastDownload: DownloadResult? = null,
    ) = FeatureProbeResult(
        feature = feature,
        status = status,
        probedAtEpochMillis = 1L,
        lastDownload = lastDownload,
    )

    @Test
    fun everyMatrixFeatureGetsItsOwnRowEvenWhenUnprobed() {
        val rows = ModelManagerRules.rows(emptyList())
        assertEquals(GenAiFeature.MATRIX, rows.map { it.feature })
        rows.forEach { row ->
            assertNull(row.status)
            assertFalse(row.downloadOffered)
            assertEquals("unknown — probe this feature first", row.eligibility)
        }
    }

    @Test
    fun onlyDownloadableRowsOfferADownload() {
        val results = listOf(
            result(GenAiFeature.REWRITING, FeatureStatusKind.DOWNLOADABLE),
            result(GenAiFeature.PROOFREADING, FeatureStatusKind.AVAILABLE),
            result(GenAiFeature.EMBEDDING, FeatureStatusKind.UNAVAILABLE),
            result(GenAiFeature.AGENT, FeatureStatusKind.PROBE_ERROR),
        )
        val rows = ModelManagerRules.rows(results)
        assertTrue(rows.first { it.feature == GenAiFeature.REWRITING }.downloadOffered)
        assertFalse(rows.first { it.feature == GenAiFeature.PROOFREADING }.downloadOffered)
        assertFalse(rows.first { it.feature == GenAiFeature.EMBEDDING }.downloadOffered)
        assertFalse(rows.first { it.feature == GenAiFeature.AGENT }.downloadOffered)
    }

    @Test
    fun canStartDownloadRequiresAnOfferedRowAndNoBusyFeature() {
        val rows = ModelManagerRules.rows(
            listOf(
                result(GenAiFeature.SPEECH_RECOGNITION, FeatureStatusKind.DOWNLOADABLE),
                result(GenAiFeature.SPEECH_TRANSLATION, FeatureStatusKind.AVAILABLE),
            ),
        )
        val downloadable = rows.first { it.feature == GenAiFeature.SPEECH_RECOGNITION }
        val available = rows.first { it.feature == GenAiFeature.SPEECH_TRANSLATION }
        assertTrue(ModelManagerRules.canStartDownload(downloadable, busyFeature = null))
        assertFalse(
            ModelManagerRules.canStartDownload(downloadable, busyFeature = GenAiFeature.TEXT_TO_SPEECH),
        )
        assertFalse(ModelManagerRules.canStartDownload(available, busyFeature = null))
        assertFalse(ModelManagerRules.canStartDownload(null, busyFeature = null))
    }

    @Test
    fun failedDownloadOffersRetryAndCompletedDownloadDoesNot() {
        val failed = DownloadResult(
            success = false,
            bytesDownloaded = 200L,
            totalBytes = 1_000L,
            failure = GenAiFailure(606, "com.google.mlkit.genai.common.GenAiException", "download interrupted"),
        )
        val completed = DownloadResult(true, 1_000L, 1_000L, null)
        val rows = ModelManagerRules.rows(
            listOf(
                result(GenAiFeature.REWRITING, FeatureStatusKind.DOWNLOADABLE, failed),
                result(GenAiFeature.PROOFREADING, FeatureStatusKind.DOWNLOADABLE, completed),
            ),
        )
        assertTrue(rows.first { it.feature == GenAiFeature.REWRITING }.retryOffered)
        assertFalse(rows.first { it.feature == GenAiFeature.PROOFREADING }.retryOffered)
        assertEquals(200L, rows.first { it.feature == GenAiFeature.REWRITING }.downloadedBytes)
        assertEquals(1_000L, rows.first { it.feature == GenAiFeature.REWRITING }.totalBytes)
    }

    @Test
    fun progressIsOnlyWhatTheCallbackReported() {
        val progress = FeatureDownloadProgress(downloadedBytes = 512L, totalBytes = 4_096L)
        val rows = ModelManagerRules.rows(
            listOf(result(GenAiFeature.IMAGE_DESCRIPTION, FeatureStatusKind.DOWNLOADABLE)),
            progress = mapOf(GenAiFeature.IMAGE_DESCRIPTION to progress),
        )
        val row = rows.first { it.feature == GenAiFeature.IMAGE_DESCRIPTION }
        assertEquals(512L, row.progress?.downloadedBytes)
        assertEquals(4_096L, row.progress?.totalBytes)
        assertEquals("512 / 4096 bytes", row.progress?.label)
    }

    @Test
    fun noDownloadIsEverAutomatic() {
        assertFalse(ModelManagerRules.automaticDownloadsAllowed())
    }

    @Test
    fun noRowInventsACatalogSizeBeforeTheApiReportsOne() {
        val row = ModelManagerRules.rows(
            listOf(result(GenAiFeature.REWRITING, FeatureStatusKind.DOWNLOADABLE)),
        ).first { it.feature == GenAiFeature.REWRITING }
        assertNull(row.totalBytes)
        assertNull(row.downloadedBytes)
        assertNull(row.progress)
    }
}
