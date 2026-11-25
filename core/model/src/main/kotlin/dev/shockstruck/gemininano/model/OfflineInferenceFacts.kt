package dev.shockstruck.gemininano.model

enum class OfflineInferenceOutcome(val label: String) {
    SUCCEEDED("inference returned"),
    FAILED("inference failed"),
    SKIPPED("not run"),
}

/**
 * One fixed-input inference result. [output] holds only what the public API returned
 * (trimmed for display); [detail] explains a skip and never claims offline proof.
 */
data class OfflineInferenceResult(
    val feature: GenAiFeature,
    val outcome: OfflineInferenceOutcome,
    val output: String? = null,
    val latencyMillis: Long = 0L,
    val failure: GenAiFailure? = null,
    val detail: String? = null,
) {
    val errorCode: Int? get() = failure?.errorCode

    val statusLabel: String
        get() = when (outcome) {
            OfflineInferenceOutcome.SUCCEEDED -> "inference returned"
            OfflineInferenceOutcome.FAILED ->
                if (errorCode != null) "inference failed (code $errorCode)" else "inference failed"

            OfflineInferenceOutcome.SKIPPED -> "not run"
        }

    companion object {
        fun skipped(feature: GenAiFeature, result: FeatureProbeResult?): OfflineInferenceResult =
            OfflineInferenceResult(
                feature = feature,
                outcome = OfflineInferenceOutcome.SKIPPED,
                detail = when {
                    !feature.implementedInPublicApi ->
                        "not available in the public API: ${feature.omissionReason}"

                    result == null ->
                        "status not probed yet; run the AICore feature probe first"

                    result.status == FeatureStatusKind.DOWNLOADABLE ->
                        "ML Kit reports DOWNLOADABLE; download it from Model Manager first"

                    result.status == FeatureStatusKind.DOWNLOADING ->
                        "a download is in progress; wait for it to finish"

                    result.status == FeatureStatusKind.UNAVAILABLE ->
                        "ML Kit reports UNAVAILABLE on this device; nothing to run"

                    result.status == FeatureStatusKind.AVAILABLE ->
                        "no inference was returned"

                    else ->
                        "status is ${result.status.label}; nothing to run"
                },
            )
    }
}

/**
 * Builds the per-feature plan the Offline Inference screen shows before anything runs:
 * every feature is listed, and only AVAILABLE ones are eligible.
 */
object OfflineInferencePlanning {

    fun rows(results: List<FeatureProbeResult>): List<OfflineInferenceResult> {
        val byFeature = results.associateBy { it.feature }
        return GenAiFeature.MATRIX.map { feature ->
            val result = byFeature[feature]
            if (result?.status == FeatureStatusKind.AVAILABLE) {
                OfflineInferenceResult(
                    feature = feature,
                    outcome = OfflineInferenceOutcome.SKIPPED,
                    detail = "eligible — not run yet",
                )
            } else {
                OfflineInferenceResult.skipped(feature, result)
            }
        }
    }

    fun eligibleCount(results: List<FeatureProbeResult>): Int =
        results.count { it.status == FeatureStatusKind.AVAILABLE }

    /**
     * Merges fresh results into the existing list so each row keeps its own feature
     * identity even when one inference failed.
     */
    fun merge(
        existing: List<OfflineInferenceResult>,
        fresh: List<OfflineInferenceResult>,
    ): List<OfflineInferenceResult> {
        val byFeature = (existing + fresh).associateBy { it.feature }
        return GenAiFeature.MATRIX.mapNotNull { byFeature[it] }
    }
}

object OfflineInferenceFormatting {

    fun header(eligible: Int): String =
        "Fixed-input inference for each AVAILABLE feature ($eligible eligible). " +
            "Disable Wi-Fi and mobile data in system settings first; this app cannot and " +
            "will not toggle radios."

    fun offlineCaveat(): String =
        "A successful inference here does not prove offline operation. Only you can disable " +
            "connectivity; emulator or mocked results are not device evidence."

    fun renderResult(result: OfflineInferenceResult): String {
        val parts = mutableListOf<String>()
        parts += "${result.feature.displayName}: ${result.outcome.label}"
        result.output?.let { parts += "output=$it" }
        result.detail?.let { parts += "detail=$it" }
        result.failure?.let {
            parts += "error=${it.codeLabel}"
            parts += "class=${it.exceptionClass}"
            parts += "message=${it.message}"
        }
        parts += "latency=${result.latencyMillis}ms"
        return parts.joinToString(" ")
    }
}
