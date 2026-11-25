package dev.shockstruck.gemininano.diagnostics

import dev.shockstruck.gemininano.model.DiagnosticEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Stable timestamp and event formatting shared by the UI and the text export. */
object DiagnosticFormatting {

    private const val ISO_PATTERN = "yyyy-MM-dd'T'HH:mm:ss'Z'"
    private const val DISPLAY_PATTERN = "yyyy-MM-dd HH:mm:ss 'UTC'"

    fun formatTimestamp(epochMillis: Long): String = format(epochMillis, DISPLAY_PATTERN)

    fun formatIsoTimestamp(epochMillis: Long): String = format(epochMillis, ISO_PATTERN)

    fun formatEvent(event: DiagnosticEvent): String {
        val parts = mutableListOf<String>()
        parts += formatTimestamp(event.timestampEpochMillis)
        parts += event.severity.label
        parts += "${event.component}/${event.operation}"
        parts += event.outcome.label
        event.errorCode?.let { parts += "error=$it" }
        event.featureId?.let { parts += "feature=$it" }
        event.exceptionClass?.let { parts += "exception=$it" }
        event.bytes?.let { parts += "bytes=$it" }
        event.durationMillis?.let { parts += "durationMs=$it" }
        event.message?.let { parts += it }
        return parts.joinToString(" | ")
    }

    private fun format(epochMillis: Long, pattern: String): String {
        val formatter = SimpleDateFormat(pattern, Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date(epochMillis))
    }
}
