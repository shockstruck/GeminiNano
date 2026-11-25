package dev.shockstruck.gemininano.diagnostics

import dev.shockstruck.gemininano.model.BuildFacts
import dev.shockstruck.gemininano.model.DiagnosticEvent

data class ExportMetadata(
    val applicationId: String,
    val versionName: String,
    val versionCode: Long,
    val exportedAtEpochMillis: Long,
    val build: BuildFacts? = null,
)

/**
 * Writes the diagnostic log as a single JSON object. The shape is stable and covered by
 * a unit test; it is the format coding agents and humans are expected to read.
 */
class JsonExporter {

    fun export(events: List<DiagnosticEvent>, metadata: ExportMetadata): String {
        val out = StringBuilder()
        out.append("{\n")
        out.append("  \"schema\": ").append(quote(SCHEMA)).append(",\n")
        out.append("  \"exportedAt\": ").append(quote(DiagnosticFormatting.formatIsoTimestamp(metadata.exportedAtEpochMillis))).append(",\n")
        out.append("  \"app\": {\n")
        out.append("    \"applicationId\": ").append(quote(metadata.applicationId)).append(",\n")
        out.append("    \"versionName\": ").append(quote(metadata.versionName)).append(",\n")
        out.append("    \"versionCode\": ").append(metadata.versionCode).append('\n')
        out.append("  },\n")
        metadata.build?.let { build ->
            out.append("  \"build\": {\n")
            out.append("    \"manufacturer\": ").append(quote(build.manufacturer)).append(",\n")
            out.append("    \"brand\": ").append(quote(build.brand)).append(",\n")
            out.append("    \"model\": ").append(quote(build.model)).append(",\n")
            out.append("    \"device\": ").append(quote(build.device)).append(",\n")
            out.append("    \"product\": ").append(quote(build.product)).append(",\n")
            out.append("    \"hardware\": ").append(quote(build.hardware)).append(",\n")
            out.append("    \"fingerprint\": ").append(quote(build.fingerprint)).append(",\n")
            out.append("    \"androidRelease\": ").append(quote(build.androidRelease)).append(",\n")
            out.append("    \"sdkInt\": ").append(build.sdkInt).append(",\n")
            out.append("    \"securityPatch\": ").append(quote(build.securityPatch)).append(",\n")
            out.append("    \"supportedAbis\": [")
            out.append(build.supportedAbis.joinToString(", ") { quote(it) })
            out.append("]\n")
            out.append("  },\n")
        }
        out.append("  \"events\": [")
        if (events.isEmpty()) {
            out.append("]\n")
        } else {
            out.append('\n')
            events.forEachIndexed { index, event ->
                out.append("    {")
                out.append("\"timestamp\": ").append(quote(DiagnosticFormatting.formatIsoTimestamp(event.timestampEpochMillis))).append(", ")
                out.append("\"component\": ").append(quote(event.component)).append(", ")
                out.append("\"operation\": ").append(quote(event.operation)).append(", ")
                out.append("\"outcome\": ").append(quote(event.outcome.label)).append(", ")
                out.append("\"severity\": ").append(quote(event.severity.label)).append(", ")
                out.append("\"exceptionClass\": ").append(quote(event.exceptionClass)).append(", ")
                out.append("\"errorCode\": ").append(event.errorCode?.toString() ?: "null").append(", ")
                out.append("\"message\": ").append(quote(event.message)).append(", ")
                out.append("\"featureId\": ").append(quote(event.featureId)).append(", ")
                out.append("\"bytes\": ").append(event.bytes?.toString() ?: "null").append(", ")
                out.append("\"durationMillis\": ").append(event.durationMillis?.toString() ?: "null")
                out.append('}')
                if (index != events.lastIndex) out.append(',')
                out.append('\n')
            }
            out.append("  ]\n")
        }
        out.append("}\n")
        return out.toString()
    }

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
        const val SCHEMA = "dev.shockstruck.gemininano.diagnostic-log/1"
    }
}

/** Human-readable export of the same events. */
class TextExporter {

    fun export(events: List<DiagnosticEvent>, metadata: ExportMetadata): String {
        val out = StringBuilder()
        out.append("Gemini Nano Manager — diagnostic log\n")
        out.append("Exported: ").append(DiagnosticFormatting.formatTimestamp(metadata.exportedAtEpochMillis)).append('\n')
        out.append("App: ").append(metadata.applicationId)
            .append(' ').append(metadata.versionName)
            .append(" (versionCode ").append(metadata.versionCode).append(")\n")
        metadata.build?.let { build ->
            out.append("Device: ").append(build.manufacturer).append(' ').append(build.model)
                .append(" · Android ").append(build.androidRelease)
                .append(" (API ").append(build.sdkInt).append(")\n")
            out.append("Fingerprint: ").append(build.fingerprint).append('\n')
            out.append("Security patch: ").append(build.securityPatch ?: "unknown").append('\n')
        }
        out.append("Events: ").append(events.size).append('\n')
        out.append("-".repeat(72)).append('\n')
        events.forEach { event ->
            out.append(DiagnosticFormatting.formatEvent(event)).append('\n')
        }
        if (events.isEmpty()) {
            out.append("(no events recorded)\n")
        }
        return out.toString()
    }
}
