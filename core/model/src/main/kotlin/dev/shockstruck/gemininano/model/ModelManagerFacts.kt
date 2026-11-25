package dev.shockstruck.gemininano.model

/**
 * One Model Manager row. It contains only values the public API returned: a status
 * and, after a download starts, the byte counts from the SDK's own callbacks.
 *
 * A feature with no probe result gets [FeatureStatusKind.NOT_PROBED]; [statusDetail]
 * is null in that case and also when a probe returned no detail, so the text
 * "not probed yet" can never appear next to a real probe result. A [PROBE_ERROR] row
 * keeps the exact failure and the probe time so the cause stays visible.
 */
data class ModelManagerRow(
    val feature: GenAiFeature,
    val status: FeatureStatusKind,
    val statusDetail: String?,
    val downloadOffered: Boolean,
    val eligibility: String,
    val lastDownload: DownloadResult? = null,
    val progress: FeatureDownloadProgress? = null,
    val failure: GenAiFailure? = null,
    val probedAtEpochMillis: Long? = null,
) {
    val retryOffered: Boolean
        get() = downloadOffered && lastDownload?.success == false

    val downloadedBytes: Long? get() = lastDownload?.bytesDownloaded
    val totalBytes: Long? get() = lastDownload?.totalBytes

    val probeTimeLabel: String? get() = FeatureMatrixFormatting.probeTimeLabel(probedAtEpochMillis)
}

/**
 * Pure Model Manager rules, shared by the UI and its tests.
 *
 * Only an explicit tap on a DOWNLOADABLE row may start a download. The app keeps no
 * catalog, no model size and no provisioning percentage: [ModelManagerRow.totalBytes]
 * is only what `onDownloadStarted`/`DownloadStarted` reported.
 */
object ModelManagerRules {

    fun rows(
        results: List<FeatureProbeResult>,
        progress: Map<GenAiFeature, FeatureDownloadProgress> = emptyMap(),
    ): List<ModelManagerRow> {
        val byFeature = results.associateBy { it.feature }
        return GenAiFeature.MATRIX.map { feature ->
            val result = byFeature[feature]
            ModelManagerRow(
                feature = feature,
                status = result?.status ?: FeatureStatusKind.NOT_PROBED,
                statusDetail = result?.detail
                    ?: FeatureStatusKind.NOT_PROBED_DETAIL.takeIf { result == null },
                downloadOffered = result?.downloadOffered == true,
                eligibility = result?.let { FeatureMatrixFormatting.eligibilityLabel(it) }
                    ?: "unknown — probe this feature first",
                lastDownload = result?.lastDownload,
                progress = progress[feature],
                failure = result?.failure,
                probedAtEpochMillis = result?.probedAtEpochMillis,
            )
        }
    }

    fun canStartDownload(row: ModelManagerRow?, busyFeature: GenAiFeature?): Boolean =
        row != null && row.downloadOffered && busyFeature == null

    /** No call to the SDK may begin before the user's tap. */
    fun automaticDownloadsAllowed(): Boolean = false
}
