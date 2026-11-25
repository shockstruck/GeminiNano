package dev.shockstruck.gemininano.diagnostics

import dev.shockstruck.gemininano.model.DiagnosticEvent
import dev.shockstruck.gemininano.model.FeatureProbeResult
import dev.shockstruck.gemininano.model.GenAiFeature
import dev.shockstruck.gemininano.model.OfflineInferenceResult
import dev.shockstruck.gemininano.model.PackageFactsFormatting
import dev.shockstruck.gemininano.model.PcsBindResult
import dev.shockstruck.gemininano.model.PcsSurface
import dev.shockstruck.gemininano.model.PermissionMatrix
import dev.shockstruck.gemininano.model.PermissionMatrixFormatting
import dev.shockstruck.gemininano.model.PromptConfigStatus
import dev.shockstruck.gemininano.model.SummarizationProbeResult

/** Everything the full diagnostic report may contain, already collected by the app. */
data class FullDiagnosticsInput(
    val generatedAtEpochMillis: Long,
    val metadata: ExportMetadata,
    val matrix: PermissionMatrix?,
    val features: List<FeatureProbeResult>,
    val prompt: List<PromptConfigStatus>,
    val summarization: SummarizationProbeResult?,
    val surface: PcsSurface?,
    val bindResults: List<PcsBindResult>,
    val offline: List<OfflineInferenceResult>,
    val events: List<DiagnosticEvent>,
)

data class FullDiagnosticsReport(
    val generatedAtEpochMillis: Long,
    val json: String,
    val text: String,
)

/**
 * One comprehensive report of the safe probe evidence: permission matrix, every GenAI
 * feature status, Prompt configs, the retained summarization probe, the PCS surface,
 * any bind results the user already ran, the offline inference matrix and the
 * diagnostic snapshot. It never downloads, binds or provisions anything.
 */
class FullDiagnosticsReportBuilder {

    fun build(input: FullDiagnosticsInput): FullDiagnosticsReport = FullDiagnosticsReport(
        generatedAtEpochMillis = input.generatedAtEpochMillis,
        json = json(input),
        text = text(input),
    )

    private fun json(input: FullDiagnosticsInput): String {
        val out = StringBuilder()
        out.append("{\n")
        out.append("  \"schema\": ").append(quote(SCHEMA)).append(",\n")
        out.append("  \"generatedAt\": ")
            .append(quote(DiagnosticFormatting.formatIsoTimestamp(input.generatedAtEpochMillis)))
            .append(",\n")
        out.append("  \"app\": {\n")
        out.append("    \"applicationId\": ").append(quote(input.metadata.applicationId)).append(",\n")
        out.append("    \"versionName\": ").append(quote(input.metadata.versionName)).append(",\n")
        out.append("    \"versionCode\": ").append(input.metadata.versionCode).append('\n')
        out.append("  },\n")
        input.metadata.build?.let { build ->
            out.append("  \"device\": {\n")
            out.append("    \"manufacturer\": ").append(quote(build.manufacturer)).append(",\n")
            out.append("    \"model\": ").append(quote(build.model)).append(",\n")
            out.append("    \"device\": ").append(quote(build.device)).append(",\n")
            out.append("    \"fingerprint\": ").append(quote(build.fingerprint)).append(",\n")
            out.append("    \"androidRelease\": ").append(quote(build.androidRelease)).append(",\n")
            out.append("    \"sdkInt\": ").append(build.sdkInt).append(",\n")
            out.append("    \"securityPatch\": ").append(quote(build.securityPatch)).append('\n')
            out.append("  },\n")
        }
        out.append("  \"permissionMatrix\": ")
        out.append(permissionMatrixJson(input.matrix)).append(",\n")
        out.append("  \"features\": ")
        out.append(array(input.features) { featureJson(it) }).append(",\n")
        out.append("  \"prompt\": ")
        out.append(array(input.prompt) { promptJson(it) }).append(",\n")
        out.append("  \"summarization\": ")
        out.append(summarizationJson(input.summarization)).append(",\n")
        out.append("  \"pcs\": ")
        out.append(pcsJson(input.surface)).append(",\n")
        out.append("  \"pcsBind\": ")
        out.append(array(input.bindResults) { bindJson(it) }).append(",\n")
        out.append("  \"offline\": ")
        out.append(array(input.offline) { offlineJson(it) }).append(",\n")
        out.append("  \"diagnostics\": {\n")
        out.append("    \"eventCount\": ").append(input.events.size).append(",\n")
        out.append("    \"events\": ")
        out.append(array(input.events) { eventJson(it) }).append('\n')
        out.append("  }\n")
        out.append("}\n")
        return out.toString()
    }

    private fun text(input: FullDiagnosticsInput): String {
        val out = StringBuilder()
        out.append("Gemini Nano Manager — full diagnostics report\n")
        out.append("Generated: ").append(DiagnosticFormatting.formatTimestamp(input.generatedAtEpochMillis)).append('\n')
        out.append("App: ").append(input.metadata.applicationId)
            .append(' ').append(input.metadata.versionName)
            .append(" (versionCode ").append(input.metadata.versionCode).append(")\n")
        input.metadata.build?.let { build ->
            out.append("Device: ").append(build.manufacturer).append(' ').append(build.model)
                .append(" · Android ").append(build.androidRelease)
                .append(" (API ").append(build.sdkInt).append(")\n")
            out.append("Fingerprint: ").append(build.fingerprint).append('\n')
            out.append("Security patch: ").append(build.securityPatch ?: "unknown").append('\n')
        }

        out.appendLine()
        out.append("== Permission matrix ==\n")
        val matrix = input.matrix
        if (matrix == null) {
            out.append("not collected\n")
        } else {
            matrix.principals.forEach { principal ->
                out.append(principal.label).append(" (").append(principal.packageName).append(")\n")
                matrix.observationsFor(principal.packageName).forEach { observation ->
                    out.append("  ")
                        .append(PermissionMatrixFormatting.shortPermissionLabel(observation.permission))
                        .append(": ")
                        .append(PermissionMatrixFormatting.renderCell(observation))
                        .append('\n')
                }
            }
        }

        out.appendLine()
        out.append("== GenAI features ==\n")
        if (input.features.isEmpty()) {
            out.append("not probed\n")
        } else {
            GenAiFeature.MATRIX.forEach { feature ->
                val result = input.features.firstOrNull { it.feature == feature }
                out.append(feature.displayName).append(": ")
                if (result == null) {
                    out.append("not probed\n")
                } else {
                    out.append(result.status.label)
                    result.detail?.let { out.append(" | ").append(it) }
                    result.failure?.let {
                        out.append(" | error=").append(it.codeLabel)
                            .append(" class=").append(it.exceptionClass)
                            .append(" message=").append(it.message)
                    }
                    result.featureId?.let { out.append(" | featureId=").append(it) }
                    result.baseModelName?.let { out.append(" | baseModel=").append(it) }
                    result.modelSignature?.let { out.append(" | signature=").append(it) }
                    result.metadataDimension?.let { out.append(" | dimension=").append(it) }
                    result.tokenLimit?.let { out.append(" | tokenLimit=").append(it) }
                    out.append(" | latency=").append(result.latencyMillis).append("ms\n")
                }
            }
        }

        out.appendLine()
        out.append("== Prompt ==\n")
        if (input.prompt.isEmpty()) {
            out.append("not probed\n")
        } else {
            input.prompt.forEach { status ->
                out.append(status.config.label).append(": ").append(status.status.label)
                status.errorCode?.let { out.append(" | error=").append(it) }
                status.exceptionClass?.let { out.append(" | class=").append(it) }
                status.message?.let { out.append(" | message=").append(it) }
                status.featureId?.let { out.append(" | featureId=").append(it) }
                status.baseModelName?.let { out.append(" | baseModel=").append(it) }
                out.append(" | latency=").append(status.latencyMillis).append("ms\n")
            }
        }

        out.appendLine()
        out.append("== Summarization ==\n")
        input.summarization?.let { probe ->
            out.append(probe.status.label).append(" | ").append(probe.statusDetail)
            probe.baseModelName?.let { out.append(" | baseModel=").append(it) }
            probe.failure?.let {
                out.append(" | error=").append(it.codeLabel)
                    .append(" class=").append(it.exceptionClass)
                    .append(" message=").append(it.message)
            }
            out.append('\n')
        } ?: out.append("not probed\n")

        out.appendLine()
        out.append("== Private Compute Services surface ==\n")
        val surface = input.surface
        if (surface == null) {
            out.append("not inspected\n")
        } else if (!surface.packageVisible) {
            out.append("visibility limited: PCS is not visible to this app (not proof of absence)\n")
        } else {
            out.append("Exported components: ").append(surface.exportedComponents.size).append('\n')
            surface.exportedComponents.forEach { component ->
                out.append("  ").append(PackageFactsFormatting.renderComponent(component)).append('\n')
            }
            out.append("Settings: ")
                .append(if (surface.settings.resolved) surface.settings.activityName ?: "resolved" else "not resolved")
                .append(" | ").append(surface.settings.detail).append('\n')
        }

        out.appendLine()
        out.append("== PCS bind results (only if the user ran the separate bind probe) ==\n")
        if (input.bindResults.isEmpty()) {
            out.append("not run\n")
        } else {
            input.bindResults.forEach { result ->
                out.append(result.componentName).append(": ").append(result.outcome.label)
                result.interfaceDescriptor?.let { out.append(" | interface=").append(it) }
                result.exceptionClass?.let { out.append(" | class=").append(it) }
                result.exceptionMessage?.let { out.append(" | message=").append(it) }
                out.append(" | unbindCalls=").append(result.unbindCalls)
                out.append(" | elapsed=").append(result.elapsedMillis).append("ms\n")
            }
        }

        out.appendLine()
        out.append("== Offline inference (fixed inputs) ==\n")
        if (input.offline.isEmpty()) {
            out.append("not run\n")
        } else {
            input.offline.forEach { result ->
                out.append(result.feature.displayName).append(": ").append(result.outcome.label)
                result.output?.let { out.append(" | output=").append(it) }
                result.detail?.let { out.append(" | ").append(it) }
                result.failure?.let {
                    out.append(" | error=").append(it.codeLabel)
                        .append(" class=").append(it.exceptionClass)
                        .append(" message=").append(it.message)
                }
                out.append(" | latency=").append(result.latencyMillis).append("ms\n")
            }
        }
        out.append("Note: offline operation is only demonstrated if connectivity was disabled externally.\n")

        out.appendLine()
        out.append("== Diagnostic snapshot ==\n")
        out.append("Events: ").append(input.events.size).append('\n')
        if (input.events.isEmpty()) {
            out.append("(no events recorded)\n")
        } else {
            input.events.forEach { event ->
                out.append(DiagnosticFormatting.formatEvent(event)).append('\n')
            }
        }
        return out.toString()
    }

    private fun permissionMatrixJson(matrix: PermissionMatrix?): String {
        if (matrix == null) return "null"
        val out = StringBuilder()
        out.append("{\n")
        out.append("    \"principals\": ")
        out.append(array(matrix.principals) {
            "{" + "\"label\": " + quote(it.label) + ", \"packageName\": " + quote(it.packageName) + "}"
        }).append(",\n")
        out.append("    \"permissions\": ")
        out.append(array(matrix.permissions) { quote(it) }).append(",\n")
        out.append("    \"observations\": ")
        out.append(
            array(matrix.observations) { observation ->
                "{" +
                    "\"principal\": " + quote(observation.packageName) + ", " +
                    "\"permission\": " + quote(observation.permission) + ", " +
                    "\"requested\": " + observation.requested + ", " +
                    "\"grant\": " + quote(observation.grant.label) + ", " +
                    "\"status\": " + quote(observation.status.label) +
                    "}"
            },
        ).append('\n')
        out.append("  }")
        return out.toString()
    }

    private fun featureJson(result: FeatureProbeResult): String = "{" +
        "\"feature\": " + quote(result.feature.key) + ", " +
        "\"label\": " + quote(result.feature.displayName) + ", " +
        "\"artifact\": " + quote(result.feature.artifact) + ", " +
        "\"status\": " + quote(result.status.label) + ", " +
        "\"featureId\": " + (result.featureId?.toString() ?: "null") + ", " +
        "\"errorCode\": " + (result.errorCode?.toString() ?: "null") + ", " +
        "\"exceptionClass\": " + quote(result.exceptionClass) + ", " +
        "\"message\": " + quote(result.message) + ", " +
        "\"baseModelName\": " + quote(result.baseModelName) + ", " +
        "\"modelSignature\": " + quote(result.modelSignature) + ", " +
        "\"metadataDimension\": " + (result.metadataDimension?.toString() ?: "null") + ", " +
        "\"tokenLimit\": " + (result.tokenLimit?.toString() ?: "null") + ", " +
        "\"latencyMillis\": " + result.latencyMillis + ", " +
        "\"detail\": " + quote(result.detail) +
        "}"

    private fun promptJson(status: PromptConfigStatus): String = "{" +
        "\"config\": " + quote(status.config.label) + ", " +
        "\"status\": " + quote(status.status.label) + ", " +
        "\"featureId\": " + (status.featureId?.toString() ?: "null") + ", " +
        "\"errorCode\": " + (status.errorCode?.toString() ?: "null") + ", " +
        "\"exceptionClass\": " + quote(status.exceptionClass) + ", " +
        "\"message\": " + quote(status.message) + ", " +
        "\"baseModelName\": " + quote(status.baseModelName) + ", " +
        "\"generationSucceeded\": " + status.generationSucceeded + ", " +
        "\"latencyMillis\": " + status.latencyMillis +
        "}"

    private fun summarizationJson(probe: SummarizationProbeResult?): String {
        if (probe == null) return "null"
        return "{" +
            "\"status\": " + quote(probe.status.label) + ", " +
            "\"detail\": " + quote(probe.statusDetail) + ", " +
            "\"baseModelName\": " + quote(probe.baseModelName) + ", " +
            "\"errorCode\": " + (probe.failure?.errorCode?.toString() ?: "null") + ", " +
            "\"exceptionClass\": " + quote(probe.failure?.exceptionClass) + ", " +
            "\"message\": " + quote(probe.failure?.message) +
            "}"
    }

    private fun pcsJson(surface: PcsSurface?): String {
        if (surface == null) return "null"
        return "{" +
            "\"packageVisible\": " + surface.packageVisible + ", " +
            "\"exportedComponents\": " + array(surface.exportedComponents) {
                "{" +
                    "\"kind\": " + quote(it.kind.label) + ", " +
                    "\"className\": " + quote(it.className) + ", " +
                    "\"exported\": " + it.exported + ", " +
                    "\"enabled\": " + it.enabled + ", " +
                    "\"permission\": " + quote(it.requiredPermission) +
                    "}"
            } + ", " +
            "\"settings\": {" +
            "\"resolved\": " + surface.settings.resolved + ", " +
            "\"activityName\": " + quote(surface.settings.activityName) + ", " +
            "\"detail\": " + quote(surface.settings.detail) +
            "}}"
    }

    private fun bindJson(result: PcsBindResult): String = "{" +
        "\"componentName\": " + quote(result.componentName) + ", " +
        "\"outcome\": " + quote(result.outcome.label) + ", " +
        "\"exceptionClass\": " + quote(result.exceptionClass) + ", " +
        "\"exceptionMessage\": " + quote(result.exceptionMessage) + ", " +
        "\"interfaceDescriptor\": " + quote(result.interfaceDescriptor) + ", " +
        "\"elapsedMillis\": " + result.elapsedMillis + ", " +
        "\"unbindCalls\": " + result.unbindCalls +
        "}"

    private fun offlineJson(result: OfflineInferenceResult): String = "{" +
        "\"feature\": " + quote(result.feature.key) + ", " +
        "\"outcome\": " + quote(result.outcome.label) + ", " +
        "\"statusLabel\": " + quote(result.statusLabel) + ", " +
        "\"output\": " + quote(result.output) + ", " +
        "\"detail\": " + quote(result.detail) + ", " +
        "\"errorCode\": " + (result.failure?.errorCode?.toString() ?: "null") + ", " +
        "\"exceptionClass\": " + quote(result.failure?.exceptionClass) + ", " +
        "\"message\": " + quote(result.failure?.message) + ", " +
        "\"latencyMillis\": " + result.latencyMillis +
        "}"

    private fun eventJson(event: DiagnosticEvent): String = "{" +
        "\"timestamp\": " + quote(DiagnosticFormatting.formatIsoTimestamp(event.timestampEpochMillis)) + ", " +
        "\"component\": " + quote(event.component) + ", " +
        "\"operation\": " + quote(event.operation) + ", " +
        "\"outcome\": " + quote(event.outcome.label) + ", " +
        "\"severity\": " + quote(event.severity.label) + ", " +
        "\"exceptionClass\": " + quote(event.exceptionClass) + ", " +
        "\"errorCode\": " + (event.errorCode?.toString() ?: "null") + ", " +
        "\"message\": " + quote(event.message) + ", " +
        "\"featureId\": " + quote(event.featureId) + ", " +
        "\"bytes\": " + (event.bytes?.toString() ?: "null") + ", " +
        "\"durationMillis\": " + (event.durationMillis?.toString() ?: "null") +
        "}"

    private fun <T> array(items: List<T>, render: (T) -> String): String =
        items.joinToString(prefix = "[\n    ", postfix = "\n  ]", separator = ",\n    ") { render(it) }

    private fun quote(value: String?): String {
        if (value == null) return "null"
        val out = StringBuilder(value.length + 2)
        out.append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch.code < 0x20) {
                    out.append("\\u").append(String.format("%04x", ch.code))
                } else {
                    out.append(ch)
                }
            }
        }
        out.append('"')
        return out.toString()
    }

    companion object {
        const val SCHEMA = "dev.shockstruck.gemininano.full-diagnostics/1"
    }
}
