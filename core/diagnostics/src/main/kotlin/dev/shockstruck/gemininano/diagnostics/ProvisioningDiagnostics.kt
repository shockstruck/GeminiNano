package dev.shockstruck.gemininano.diagnostics

import dev.shockstruck.gemininano.model.AicoreFeatureLabels
import dev.shockstruck.gemininano.model.FeatureStatusKind
import dev.shockstruck.gemininano.model.PcsBindOutcome
import dev.shockstruck.gemininano.model.PcsBindResult
import dev.shockstruck.gemininano.model.PermissionMatrix
import dev.shockstruck.gemininano.model.PermissionMatrixFormatting
import dev.shockstruck.gemininano.model.PermissionStatus
import dev.shockstruck.gemininano.model.PromptConfigStatus

/** The evidence the Provisioning Diagnostics decision line is allowed to use. */
data class ProvisioningEvidence(
    val matrix: PermissionMatrix?,
    val prompt: List<PromptConfigStatus>,
    val bindResults: List<PcsBindResult>,
)

data class ProvisioningDecision(
    val lines: List<String>,
)

/**
 * Builds the §14.5 decision lines strictly from observed evidence. It never infers one
 * principal from another, never invents a cause and never says "provisioned" unless a
 * Prompt config reported AVAILABLE and its generation succeeded.
 */
class ProvisioningDiagnosticsPresenter {

    fun present(evidence: ProvisioningEvidence): ProvisioningDecision {
        val lines = mutableListOf<String>()

        val pcc = evidence.matrix?.let { PermissionMatrixFormatting.pcsPcc(it) }
        lines += pccLine(pcc?.status)

        lines += promptLines(evidence.prompt)
        lines += bindLine(evidence.bindResults)
        lines += AicoreFeatureLabels.FEATURE_622

        return ProvisioningDecision(lines.filter { it.isNotBlank() })
    }

    private fun pccLine(status: PermissionStatus?): String = when (status) {
        null -> "Permission matrix not collected yet."
        PermissionStatus.DENIED ->
            "PCS PCC = requested · denied: PCS is not a PCS UID on this OS; this is the " +
                "sandboxed-install gap. It does not by itself prove AICore cannot bind PCS."

        PermissionStatus.GRANTED ->
            "PCS PCC = requested · granted: the framework classifies PCS as a PCS UID on this OS."

        PermissionStatus.NOT_REQUESTED ->
            "PCS PCC = not requested: PCS does not ask for the flag, so the framework cannot " +
                "classify it as a PCS UID."

        PermissionStatus.NOT_VISIBLE ->
            "PCS PCC = unknown: the PCS package is not visible, so its permissions cannot be read."

        PermissionStatus.UNKNOWN ->
            "PCS PCC = requested · unknown: checkPermission returned no definitive grant state."
    }

    private fun promptLines(prompt: List<PromptConfigStatus>): List<String> {
        if (prompt.isEmpty()) return listOf("Prompt has not been probed yet.")

        val lines = mutableListOf<String>()
        if (prompt.all { it.errorCode == 606 }) {
            lines += "Prompt all 606: AICore has no ML Kit feature entry for any Prompt config; " +
                "summarization 622 is not a base-model absence."
        }
        prompt.firstOrNull { it.status == FeatureStatusKind.AVAILABLE && it.generationSucceeded }
            ?.let { status ->
                lines += "Prompt ${status.config.label} is AVAILABLE and generation succeeded" +
                    (status.baseModelName?.let { " (base model: $it)" } ?: "") + "."
            }
        prompt.firstOrNull {
            it.status == FeatureStatusKind.AVAILABLE && !it.baseModelName.isNullOrBlank()
        }?.let { status ->
            if (!status.generationSucceeded) {
                lines += "Prompt ${status.config.label} is AVAILABLE and returned base model " +
                    "${status.baseModelName}: base model present; the summarization slot is missing."
            } else {
                lines += "Prompt ${status.config.label} returned base model ${status.baseModelName}: " +
                    "a base model is present; the summarization slot may still be missing."
            }
        }
        return lines
    }

    private fun bindLine(results: List<PcsBindResult>): String {
        if (results.isEmpty()) return "PCS bind probe has not run yet."
        val grpc = results.firstOrNull {
            it.componentName.endsWith("PcsGrpcService")
        }
        return when (grpc?.outcome) {
            PcsBindOutcome.SECURITY_EXCEPTION ->
                "PCS bind SecurityException: the OS association policy blocks the transport; " +
                    "the next fix is OS-side." +
                    (grpc.exceptionMessage?.let { " ($it)" } ?: "")

            PcsBindOutcome.BINDER_RETURNED ->
                "PCS bind binder returned: the OS association permits; the next suspect is the " +
                    "gRPC policy/API key, not the OS."

            PcsBindOutcome.NULL_BINDING ->
                "PCS bind null binding: the component is disabled or returned no binder."

            PcsBindOutcome.TIMEOUT ->
                "PCS bind timed out: the system accepted the bind but no callback arrived within " +
                    "the 3-second window."

            PcsBindOutcome.BIND_REFUSED ->
                "PCS bind was refused by the system (bindService returned false)."

            PcsBindOutcome.BINDING_DIED ->
                "PCS binding died during the probe; the transport is not stable for this app."

            PcsBindOutcome.NOT_EXPORTED ->
                "PCS gRPC service was not returned as exported at runtime; no bind was attempted."

            null -> "PCS bind results do not include the gRPC service."
        }
    }
}
