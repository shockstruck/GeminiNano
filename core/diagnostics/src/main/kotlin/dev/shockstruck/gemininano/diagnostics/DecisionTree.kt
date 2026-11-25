package dev.shockstruck.gemininano.diagnostics

import dev.shockstruck.gemininano.model.DiagnosticEvent
import dev.shockstruck.gemininano.model.DiagnosticSnapshot
import dev.shockstruck.gemininano.model.FeatureStatusKind
import dev.shockstruck.gemininano.model.GenAiFailure
import dev.shockstruck.gemininano.model.PermissionMatrixFormatting
import dev.shockstruck.gemininano.model.Recommendation
import dev.shockstruck.gemininano.model.RecommendationKind

/**
 * The single decision tree behind the Overview's "recommended next action".
 *
 * It never invents a cause: every branch either names an observed state, a documented
 * error code, or explicitly labels the state unknown.
 */
class DecisionTree {

    fun recommend(snapshot: DiagnosticSnapshot): Recommendation {
        val probe = snapshot.probe
            ?: return Recommendation(
                kind = RecommendationKind.UNKNOWN,
                headline = "Run the summarization probe",
                detail = "No probe has run yet. Use Re-check to probe Android AICore through the ML Kit summarization API.",
            )

        if (!snapshot.aicore.visible) {
            return Recommendation(
                kind = RecommendationKind.BLOCKED,
                headline = "Android AICore is not visible to this app",
                detail = "AICore is either not installed or hidden by Android package visibility. " +
                    "Install or update Android AICore from the Play Store, then re-check. " +
                    "This app cannot install or provision system components.",
            )
        }

        if (probe.status == FeatureStatusKind.PROBE_ERROR) {
            return recommendForFailure(probe.failure, snapshot)
        }

        return when (probe.status) {
            FeatureStatusKind.AVAILABLE -> Recommendation(
                kind = RecommendationKind.OK,
                headline = "Summarization reports AVAILABLE",
                detail = "ML Kit reports the summarization feature as AVAILABLE. " +
                    "Run the built-in summarization test below to verify a successful generation; " +
                    "no download is needed.",
            )

            FeatureStatusKind.DOWNLOADABLE -> Recommendation(
                kind = RecommendationKind.ACTION,
                headline = "The summarization model can be downloaded",
                detail = "Use “Download Gemini Nano” on this screen. Android AICore and Private " +
                    "Compute Services perform the download; this app only requests it and reports progress.",
            )

            FeatureStatusKind.DOWNLOADING -> Recommendation(
                kind = RecommendationKind.WAIT,
                headline = "A model download is already in progress",
                detail = "ML Kit reports DOWNLOADING. Wait for it to finish, then re-check. " +
                    "A failed download is reported with its exact error code in Diagnostic Logs.",
            )

            FeatureStatusKind.UNAVAILABLE -> Recommendation(
                kind = RecommendationKind.BLOCKED,
                headline = "ML Kit reports summarization as UNAVAILABLE",
                detail = "AICore is absent, a stub, or this device/OS build is not supported. " +
                    "There is no app-level action that can change this.",
            )

            FeatureStatusKind.UNKNOWN -> Recommendation(
                kind = RecommendationKind.UNKNOWN,
                headline = "The feature status could not be classified",
                detail = "The probe returned an unrecognised status. Check Diagnostic Logs for the raw value.",
            )

            FeatureStatusKind.PROBE_ERROR -> error("handled above")
        }
    }

    private fun recommendForFailure(
        failure: GenAiFailure?,
        snapshot: DiagnosticSnapshot,
    ): Recommendation {
        if (failure == null) {
            return Recommendation(
                kind = RecommendationKind.ACTION,
                headline = "The summarization probe failed",
                detail = "The probe reported an error without a code. Open Diagnostic Logs for the exact type and message.",
            )
        }
        return when (failure.errorCode) {
            606 -> Recommendation(
                kind = RecommendationKind.BLOCKED,
                headline = "Feature 622 is not provisioned (error 606)",
                detail = buildString {
                    append("AICore is present and bound, but it has no configuration entry for feature 622. ")
                    append("That configuration is delivered through Private Compute Services; on this build PCS ")
                    append("reports ")
                    append(
                        PermissionMatrixFormatting.pcsPcc(snapshot.permissionMatrix)?.status?.label
                            ?: "unknown",
                    )
                    append(" for android.permission.PROVIDE_PRIVATE_COMPUTE_SERVICES")
                    if (!snapshot.pcs.visible) append(" and the PCS package is not visible")
                    append(". No app-level action can add the entry; this is the platform restriction Stage 1 documented. ")
                    append("If AICore was just updated, an unmetered-Wi-Fi reboot and a later re-check is the only documented remedy.")
                },
            )

            601 -> Recommendation(
                kind = RecommendationKind.ACTION,
                headline = "AICore could not be bound (error 601)",
                detail = "ML Kit reported a bind failure. Update Android AICore and reinstall this app, then re-check.",
            )

            else -> Recommendation(
                kind = RecommendationKind.ACTION,
                headline = "The summarization probe failed (error ${failure.codeLabel})",
                detail = "Open Diagnostic Logs for the exception class and full message. " +
                    "This app does not retry automatically and does not hide the code.",
            )
        }
    }
}
