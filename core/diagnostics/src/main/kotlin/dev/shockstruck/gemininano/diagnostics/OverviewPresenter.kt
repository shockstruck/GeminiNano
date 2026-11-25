package dev.shockstruck.gemininano.diagnostics

import dev.shockstruck.gemininano.model.DiagnosticSnapshot
import dev.shockstruck.gemininano.model.FeatureStatusKind
import dev.shockstruck.gemininano.model.LabelledValue
import dev.shockstruck.gemininano.model.OverviewModel
import dev.shockstruck.gemininano.model.PackageFacts
import dev.shockstruck.gemininano.model.PackageFactsFormatting
import dev.shockstruck.gemininano.model.PackageVisibility
import dev.shockstruck.gemininano.model.PermissionMatrixFormatting
import dev.shockstruck.gemininano.model.PermissionStatus

/**
 * Builds the Overview rows from observed facts only. Anything the app could not read
 * is rendered as "unknown" (and flagged) rather than guessed.
 */
class OverviewPresenter(
    private val decisionTree: DecisionTree = DecisionTree(),
) {

    fun present(snapshot: DiagnosticSnapshot): OverviewModel {
        val pcsPcc = PermissionMatrixFormatting.pcsPcc(snapshot.permissionMatrix)
        val rows = listOf(
            row("Android AICore", aicoreValue(snapshot.aicore)),
            row("Private Compute Services", aicoreValue(snapshot.pcs)),
            row(
                "PROVIDE_PRIVATE_COMPUTE_SERVICES (PCS)",
                PermissionMatrixFormatting.renderCell(pcsPcc),
                unknown = pcsPcc?.status != PermissionStatus.GRANTED &&
                    pcsPcc?.status != PermissionStatus.DENIED,
            ),
            row(
                "Summarization feature status",
                featureValue(snapshot),
                unknown = snapshot.probe == null,
            ),
            row(
                "Base model name",
                baseModelValue(snapshot),
                unknown = snapshot.probe?.baseModelName.isNullOrBlank(),
            ),
            row(
                "Last probe",
                snapshot.probe?.let { DiagnosticFormatting.formatTimestamp(it.probedAtEpochMillis) } ?: "not probed yet",
                unknown = snapshot.probe == null,
            ),
            row(
                "Device",
                "${snapshot.build.manufacturer} ${snapshot.build.model} · " +
                    "Android ${snapshot.build.androidRelease} (API ${snapshot.build.sdkInt})",
            ),
            row(
                "Build fingerprint",
                snapshot.build.fingerprint,
            ),
        )
        return OverviewModel(
            rows = rows,
            recommendation = decisionTree.recommend(snapshot),
            lastFailureText = snapshot.lastFailure
                ?.let { DiagnosticFormatting.formatEvent(it) }
                ?: "none recorded",
        )
    }

    private fun featureValue(snapshot: DiagnosticSnapshot): String {
        val probe = snapshot.probe ?: return "not probed yet"
        val failure = probe.failure
        return if (probe.status == FeatureStatusKind.PROBE_ERROR && failure != null) {
            "PROBE ERROR (code ${failure.codeLabel}): ${failure.message}"
        } else {
            probe.status.label
        }
    }

    private fun baseModelValue(snapshot: DiagnosticSnapshot): String =
        snapshot.probe?.baseModelName?.takeIf { it.isNotBlank() } ?: PackageFactsFormatting.UNKNOWN

    private fun aicoreValue(facts: PackageFacts): String = when {
        facts.visibility != PackageVisibility.VISIBLE ->
            PackageFactsFormatting.visibilityLabel(facts)
        else -> "${facts.versionName ?: PackageFactsFormatting.UNKNOWN} " +
            "(versionCode ${facts.versionCode?.toString() ?: PackageFactsFormatting.UNKNOWN})"
    }

    private fun row(label: String, value: String, unknown: Boolean = false): LabelledValue =
        LabelledValue(label = label, value = value, isUnknown = unknown)
}
