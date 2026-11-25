package dev.shockstruck.gemininano.diagnostics

import dev.shockstruck.gemininano.model.DiagnosticEvent
import dev.shockstruck.gemininano.model.Outcome
import dev.shockstruck.gemininano.model.Severity
import dev.shockstruck.gemininano.testing.TestFixtures
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportersTest {

    private val metadata = ExportMetadata(
        applicationId = "dev.shockstruck.gemininano",
        versionName = "0.2.0-offline",
        versionCode = 2,
        exportedAtEpochMillis = TestFixtures.NOW,
        build = TestFixtures.buildFacts(),
    )

    private val events = listOf(
        DiagnosticEvent(
            timestampEpochMillis = TestFixtures.NOW,
            component = "aicore",
            operation = "probeSummarization",
            outcome = Outcome.FAILURE,
            severity = Severity.ERROR,
            exceptionClass = "com.google.mlkit.genai.common.GenAiException",
            errorCode = 606,
            message = "Feature 622 is not available.",
            featureId = "summarization",
            bytes = 123L,
            durationMillis = 12L,
        ),
        DiagnosticEvent(
            timestampEpochMillis = TestFixtures.NOW + 1,
            component = "packages",
            operation = "inspectPackages",
            outcome = Outcome.SUCCESS,
            severity = Severity.DEBUG,
            message = "Inspected 11 packages.",
        ),
    )

    @Test
    fun jsonExportHasStableTopLevelShape() {
        val json = JSONObject(JsonExporter().export(events, metadata))
        assertEquals(JsonExporter.SCHEMA, json.getString("schema"))
        assertEquals("2027-01-15T08:00:00Z", json.getString("exportedAt"))
        assertEquals("dev.shockstruck.gemininano", json.getJSONObject("app").getString("applicationId"))
        assertEquals("0.2.0-offline", json.getJSONObject("app").getString("versionName"))
        assertEquals(2, json.getJSONObject("app").getInt("versionCode"))
        assertEquals("Google", json.getJSONObject("build").getString("manufacturer"))
        assertEquals(2, json.getJSONArray("events").length())
    }

    @Test
    fun jsonExportCarriesEveryEventField() {
        val json = JSONObject(JsonExporter().export(events, metadata))
        val event = json.getJSONArray("events").getJSONObject(0)
        assertEquals("aicore", event.getString("component"))
        assertEquals("probeSummarization", event.getString("operation"))
        assertEquals("failure", event.getString("outcome"))
        assertEquals("ERROR", event.getString("severity"))
        assertEquals(606, event.getInt("errorCode"))
        assertEquals("Feature 622 is not available.", event.getString("message"))
        assertEquals("summarization", event.getString("featureId"))
        assertEquals(123L, event.getLong("bytes"))
        assertEquals(12L, event.getLong("durationMillis"))
        val second = json.getJSONArray("events").getJSONObject(1)
        assertTrue(second.isNull("errorCode"))
        assertTrue(second.isNull("exceptionClass"))
    }

    @Test
    fun jsonExportEscapesHostileText() {
        val hostile = events[0].copy(message = "quote \" slash \\ newline \n tab \t")
        val json = JSONObject(JsonExporter().export(listOf(hostile), metadata))
        assertEquals("quote \" slash \\ newline \n tab \t", json.getJSONArray("events").getJSONObject(0).getString("message"))
    }

    @Test
    fun textExportIsReadableAndComplete() {
        val text = TextExporter().export(events, metadata)
        assertTrue(text.startsWith("Gemini Nano Manager — diagnostic log"))
        assertTrue(text.contains("Exported: 2027-01-15 08:00:00 UTC"))
        assertTrue(text.contains("App: dev.shockstruck.gemininano 0.2.0-offline (versionCode 2)"))
        assertTrue(text.contains("Events: 2"))
        assertTrue(text.contains("aicore/probeSummarization"))
        assertTrue(text.contains("error=606"))
    }

    @Test
    fun emptyExportIsStillWellFormed() {
        val json = JSONObject(JsonExporter().export(emptyList(), metadata))
        assertEquals(0, json.getJSONArray("events").length())
        val text = TextExporter().export(emptyList(), metadata)
        assertTrue(text.contains("Events: 0"))
        assertTrue(text.contains("(no events recorded)"))
    }
}
