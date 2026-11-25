package dev.shockstruck.gemininano.diagnostics

import dev.shockstruck.gemininano.model.AicoreFeatureLabels
import dev.shockstruck.gemininano.model.FeatureStatusKind
import dev.shockstruck.gemininano.model.PcsBindOutcome
import dev.shockstruck.gemininano.model.PcsBindResult
import dev.shockstruck.gemininano.model.PermissionMatrix
import dev.shockstruck.gemininano.model.PermissionMatrixFormatting
import dev.shockstruck.gemininano.model.PermissionStatus
import dev.shockstruck.gemininano.model.PromptConfigStatus
import dev.shockstruck.gemininano.model.PromptModelConfig

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
            "PCS PCC = requested · denied: PackageInfo reports the permission requested and " +
                "checkPermission returned denied. That is the observed grant state only; it does " +
                "not say why, prove PCS is not a PCC UID, or prove AICore cannot bind PCS."

        PermissionStatus.GRANTED ->
            "PCS PCC = requested · granted: both the request and the observed grant are shown; " +
                "no further meaning is inferred from the grant alone."

        PermissionStatus.GRANT_DISAGREEMENT ->
            "PCS PCC = requested · grant sources disagree: checkPermission and the " +
                "requestedPermissionsFlags bit returned different results. Both are shown and " +
                "neither is treated as authoritative."

        PermissionStatus.GRANTED_NOT_REQUESTED ->
            "PCS PCC = grant observed · not requested: a grant was observed although " +
                "PackageInfo does not list the permission as requested; the grant is shown " +
                "rather than hidden."

        PermissionStatus.NOT_REQUESTED ->
            "PCS PCC = not requested: PackageInfo does not list the permission and no grant " +
                "was observed."

        PermissionStatus.NOT_VISIBLE ->
            "PCS PCC = unknown: the PCS package is not visible, so its permissions cannot be read."

        PermissionStatus.READ_ERROR ->
            "PCS PCC = unknown: reading the PCS package failed; see the recorded error class " +
                "and message."

        PermissionStatus.NOT_READ ->
            "PCS PCC = unknown: the reduced package read did not return permissions, so " +
                "requested state cannot be concluded."

        PermissionStatus.UNKNOWN ->
            "PCS PCC = requested · unknown: neither grant source returned a definitive state."
    }

    private fun promptLines(prompt: List<PromptConfigStatus>): List<String> {
        if (prompt.isEmpty()) return listOf("Prompt has not been probed yet.")

        val lines = mutableListOf<String>()
        if (prompt.size == PromptModelConfig.ALL.size &&
            prompt.all { it.status == FeatureStatusKind.UNAVAILABLE }
        ) {
            lines += "Prompt all four configs UNAVAILABLE: ML Kit reported UNAVAILABLE for " +
                "every ModelConfig. That is the observed status only; it says nothing about " +
                "the base model, the model registry or whether a download would change it."
        }
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
            it.role == "grpc" || it.componentName.endsWith("GrpcService")
        }
        return when (grpc?.outcome) {
            PcsBindOutcome.SECURITY_EXCEPTION -> {
                val message = grpc.exceptionMessage.orEmpty()
                if (ASSOCIATION_HINTS.any { it in message.lowercase() }) {
                    "PCS bind SecurityException: the message names the OS association policy " +
                        "($message); the next fix is OS-side."
                } else {
                    "PCS bind SecurityException: the cause is not identified. The exception " +
                        "message does not name the OS association policy, so nothing is " +
                        "attributed to it." +
                        grpc.exceptionMessage?.let { " ($it)" }.orEmpty()
                }
            }

            PcsBindOutcome.BIND_ERROR ->
                "PCS bind threw ${grpc.exceptionClass ?: "a non-SecurityException"}: " +
                    "${grpc.exceptionMessage ?: "no message"}. It is not a SecurityException, " +
                    "so the cause is not identified."

            PcsBindOutcome.BINDER_RETURNED ->
                "PCS bind binder returned: this app's own bind attempt received a binder" +
                    (grpc.interfaceDescriptor?.let { " (descriptor \"$it\")" } ?: "") +
                    ". It does not prove or describe AICore's access to PCS."

            PcsBindOutcome.NULL_BINDING ->
                "PCS bind delivered no binder (null binding); the cause is not identified."

            PcsBindOutcome.NOT_RUN ->
                "PCS bind is planned for the gRPC service but has not run; it runs only on " +
                    "your explicit tap."

            PcsBindOutcome.NOT_DECLARED ->
                "The installed PCS package does not declare the gRPC service this catalogue " +
                    "knows about; no bind was attempted."

            PcsBindOutcome.NOT_READ ->
                "PCS components were not read on this reduced package read; no bind was attempted."

            PcsBindOutcome.NOT_ENABLED ->
                "The gRPC service is declared but not effectively enabled; no bind was attempted."

            PcsBindOutcome.ENABLED_UNKNOWN ->
                "The gRPC service is declared but its effective enabled state could not be read; " +
                    "no bind was attempted."

            PcsBindOutcome.REQUIRES_PERMISSION ->
                "The gRPC service is declared but requires " +
                    (grpc.requiredPermission ?: "a permission") + "; no bind was attempted."

            PcsBindOutcome.TIMEOUT ->
                "PCS bind timed out: the system accepted the bind but no callback arrived within " +
                    "the 3-second window."

            PcsBindOutcome.BIND_REFUSED ->
                "PCS bind was refused by the system (bindService returned false)."

            PcsBindOutcome.BINDING_DIED ->
                "PCS binding died during the probe; the transport is not stable for this app."

            PcsBindOutcome.NOT_EXPORTED ->
                "The gRPC service is declared but not exported; no bind was attempted."

            null -> "PCS bind results do not include the gRPC service."
        }
    }

    private companion object {
        /** Only these message fragments tie a SecurityException to the OS association policy. */
        val ASSOCIATION_HINTS = listOf("association", "allow-component-access", "component-access")
    }
}
