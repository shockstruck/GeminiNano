package dev.shockstruck.gemininano.model

/** A coarse failure family for display and tests. It never replaces the raw error. */
enum class GenAiFailureCategory(val label: String) {
    NOT_BOUND("AICore not bound (601)"),
    FEATURE_NOT_AVAILABLE("feature not available (606)"),
    DOWNLOAD_INTERRUPTED("download interrupted before completion"),
    NETWORK_UNAVAILABLE("network unavailable"),
    INVALID_METADATA("invalid model metadata"),
    INTEGRITY_FAILURE("model integrity failure"),
    UNKNOWN("uncategorised failure"),
}

/**
 * Classifies a [GenAiFailure] without inventing a code mapping.
 *
 * Only 601 and 606 have a documented meaning from the Stage 3a investigation. The
 * remaining families are recognised from the exception message that the SDK itself
 * returned; when no phrase matches, the failure stays [GenAiFailureCategory.UNKNOWN].
 * `null` is not a failure and classifies as [GenAiFailureCategory.UNKNOWN] for callers
 * that must render a label anyway.
 */
object GenAiFailureClassification {

    private val NETWORK = listOf("network", "offline", "connectivity", "no internet")
    private val INTERRUPTED = listOf("interrupt", "cancel")
    private val INVALID_METADATA = listOf("metadata", "invalid model", "malformed")
    private val INTEGRITY = listOf("integrity", "checksum", "digest", "signature mismatch")

    fun classify(failure: GenAiFailure?): GenAiFailureCategory {
        if (failure == null) return GenAiFailureCategory.UNKNOWN
        when (failure.errorCode) {
            601 -> return GenAiFailureCategory.NOT_BOUND
            606 -> return GenAiFailureCategory.FEATURE_NOT_AVAILABLE
        }
        val haystack = (failure.message + " " + failure.exceptionClass).lowercase()
        return when {
            INTERRUPTED.any { it in haystack } -> GenAiFailureCategory.DOWNLOAD_INTERRUPTED
            NETWORK.any { it in haystack } -> GenAiFailureCategory.NETWORK_UNAVAILABLE
            INVALID_METADATA.any { it in haystack } -> GenAiFailureCategory.INVALID_METADATA
            INTEGRITY.any { it in haystack } -> GenAiFailureCategory.INTEGRITY_FAILURE
            else -> GenAiFailureCategory.UNKNOWN
        }
    }

    fun label(failure: GenAiFailure?): String = classify(failure).label
}
