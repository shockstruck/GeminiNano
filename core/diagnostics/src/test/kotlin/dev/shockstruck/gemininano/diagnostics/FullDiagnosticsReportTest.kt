package dev.shockstruck.gemininano.diagnostics

import dev.shockstruck.gemininano.model.ComponentKind
import dev.shockstruck.gemininano.model.FeatureProbeResult
import dev.shockstruck.gemininano.model.FeatureStatusKind
import dev.shockstruck.gemininano.model.GenAiFailure
import dev.shockstruck.gemininano.model.GenAiFeature
import dev.shockstruck.gemininano.model.OfflineInferenceOutcome
import dev.shockstruck.gemininano.model.OfflineInferenceResult
import dev.shockstruck.gemininano.model.PcsBindOutcome
import dev.shockstruck.gemininano.model.PcsBindResult
import dev.shockstruck.gemininano.model.PcsSettingsResolution
import dev.shockstruck.gemininano.model.PcsSurface
import dev.shockstruck.gemininano.testing.TestFixtures
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullDiagnosticsReportTest {

    private val fixedNow = 1_800_000_000_000L

    private fun feature(
        feature: GenAiFeature,
        status: FeatureStatusKind = FeatureStatusKind.AVAILABLE,
        failure: GenAiFailure? = null,
        featureId: Int? = null,
        baseModelName: String? = null,
    ) = FeatureProbeResult(
        feature = feature,
        status = status,
        featureId = featureId,
        failure = failure,
        baseModelName = baseModelName,
        latencyMillis = 7L,
        probedAtEpochMillis = fixedNow,
        detail = "detail for ${feature.key}",
    )

    private fun input(
        events: List<dev.shockstruck.gemininano.model.DiagnosticEvent> = listOf(
            TestFixtures.event(),
        ),
        features: List<FeatureProbeResult> = listOf(
            feature(GenAiFeature.REWRITING, baseModelName = "base-model"),
            feature(
                GenAiFeature.PROOFREADING,
                status = FeatureStatusKind.PROBE_ERROR,
                failure = GenAiFailure(606, "com.google.mlkit.genai.common.GenAiException", "Feature 645 is not available."),
                featureId = 645,
            ),
            feature(GenAiFeature.IMAGE_DESCRIPTION, status = FeatureStatusKind.DOWNLOADABLE),
            feature(GenAiFeature.EMBEDDING, status = FeatureStatusKind.UNAVAILABLE),
        ),
        bindResults: List<PcsBindResult> = emptyList(),
        offline: List<OfflineInferenceResult> = listOf(
            OfflineInferenceResult(
                feature = GenAiFeature.REWRITING,
                outcome = OfflineInferenceOutcome.SUCCEEDED,
                output = "rewritten text",
                latencyMillis = 11L,
            ),
        ),
        surface: PcsSurface = PcsSurface(
            packageVisible = true,
            exportedComponents = listOf(
                TestFixtures.component(
                    kind = ComponentKind.SERVICE,
                    className = "com.google.android.as.oss.grpc.PcsGrpcService",
                    requiredPermission = "android.permission.BIND_JOB_SERVICE",
                ),
            ),
            settings = PcsSettingsResolution(true, "PcsSettingsActivity", "resolved through ACTION_APPLICATION_PREFERENCES"),
        ),
    ) = FullDiagnosticsInput(
        generatedAtEpochMillis = fixedNow,
        metadata = ExportMetadata(
            applicationId = "dev.shockstruck.gemininano",
            versionName = "0.4.0-offline",
            versionCode = 4L,
            exportedAtEpochMillis = fixedNow,
            build = TestFixtures.buildFacts(),
        ),
        matrix = TestFixtures.permissionMatrix(),
        features = features,
        prompt = listOf(TestFixtures.promptStatus()),
        summarization = TestFixtures.probeResult(failure = TestFixtures.failure(606), baseModelName = null),
        surface = surface,
        bindResults = bindResults,
        offline = offline,
        events = events,
    )

    @Test
    fun jsonCarriesEverySection() {
        val report = FullDiagnosticsReportBuilder().build(input())
        val json = JSONObject(report.json)
        assertEquals(FullDiagnosticsReportBuilder.SCHEMA, json.getString("schema"))
        assertEquals("dev.shockstruck.gemininano", json.getJSONObject("app").getString("applicationId"))
        assertEquals(4, json.getJSONObject("app").getInt("versionCode"))
        assertEquals(5, json.getJSONObject("permissionMatrix").getJSONArray("principals").length())
        assertEquals(4, json.getJSONArray("features").length())
        assertEquals(1, json.getJSONArray("prompt").length())
        assertTrue(json.getJSONObject("summarization").has("status"))
        assertTrue(json.getJSONObject("pcs").getBoolean("packageVisible"))
        assertEquals(0, json.getJSONArray("pcsBind").length())
        assertEquals(1, json.getJSONArray("offline").length())
        assertEquals(1, json.getJSONObject("diagnostics").getInt("eventCount"))
    }

    @Test
    fun jsonKeepsExactFeatureFailuresAndNulls() {
        val report = FullDiagnosticsReportBuilder().build(input())
        val json = JSONObject(report.json)
        val proofreading = (0 until json.getJSONArray("features").length())
            .map { json.getJSONArray("features").getJSONObject(it) }
            .first { it.getString("feature") == "proofreading" }
        assertEquals(606, proofreading.getInt("errorCode"))
        assertEquals(645, proofreading.getInt("featureId"))
        assertEquals("Feature 645 is not available.", proofreading.getString("message"))
        assertEquals("PROBE ERROR", proofreading.getString("status"))
        assertEquals(606, proofreading.getInt("errorCode"))

        val embedding = (0 until json.getJSONArray("features").length())
            .map { json.getJSONArray("features").getJSONObject(it) }
            .first { it.getString("feature") == "embedding" }
        assertTrue(embedding.isNull("baseModelName"))
        assertTrue(embedding.isNull("modelSignature"))
        assertEquals("UNAVAILABLE", embedding.getString("status"))
    }

    @Test
    fun textContainsEverySectionAndTheOfflineCaveat() {
        val report = FullDiagnosticsReportBuilder().build(input())
        val text = report.text
        assertTrue(text.contains("== Permission matrix =="))
        assertTrue(text.contains("== GenAI features =="))
        assertTrue(text.contains("== Prompt =="))
        assertTrue(text.contains("== Summarization =="))
        assertTrue(text.contains("== Private Compute Services surface =="))
        assertTrue(text.contains("== PCS bind results"))
        assertTrue(text.contains("== Offline inference"))
        assertTrue(text.contains("== Diagnostic snapshot =="))
        assertTrue(text.contains("offline operation is only demonstrated if connectivity was disabled externally"))
        assertTrue(text.contains("Rewriting: AVAILABLE"))
        assertTrue(text.contains("Proofreading: PROBE ERROR"))
        assertTrue(text.contains("error=606"))
        assertTrue(text.contains("Feature 645 is not available."))
    }

    @Test
    fun noBindResultMeansTheReportSaysNotRun() {
        val report = FullDiagnosticsReportBuilder().build(input())
        assertTrue(report.text.contains("not run"))
    }

    @Test
    fun existingBindResultsAreIncludedWithoutRunningTheProbe() {
        val report = FullDiagnosticsReportBuilder().build(
            input(
                bindResults = listOf(
                    PcsBindResult(
                        componentName = "com.google.android.as.oss.grpc.PcsGrpcService",
                        outcome = PcsBindOutcome.SECURITY_EXCEPTION,
                        exceptionClass = "java.lang.SecurityException",
                        exceptionMessage = "not allowed",
                        elapsedMillis = 15L,
                        unbindCalls = 1,
                    ),
                ),
            ),
        )
        val json = JSONObject(report.json)
        assertEquals(1, json.getJSONArray("pcsBind").length())
        assertEquals("SecurityException", json.getJSONArray("pcsBind").getJSONObject(0).getString("outcome"))
        assertTrue(report.text.contains("java.lang.SecurityException"))
    }

    @Test
    fun sensitiveNoteTextIsRedactedFromTheReport() {
        val bus = DiagnosticBus(clock = { fixedNow })
        bus.registerSensitiveValue("My private dentist appointment")
        bus.log(
            component = "aicore",
            operation = "summarize",
            outcome = dev.shockstruck.gemininano.model.Outcome.SUCCESS,
            message = "Summarized: My private dentist appointment",
        )
        val report = FullDiagnosticsReportBuilder().build(input(events = bus.snapshot()))
        assertFalse(report.json.contains("My private dentist appointment"))
        assertFalse(report.text.contains("My private dentist appointment"))
        assertTrue(report.json.contains("Summarized: [redacted]"))
    }

    @Test
    fun unknownValuesStayUnknownInsteadOfBeingFilledIn() {
        val report = FullDiagnosticsReportBuilder().build(
            input(
                features = listOf(feature(GenAiFeature.AGENT, status = FeatureStatusKind.UNKNOWN)),
            ),
        )
        val json = JSONObject(report.json)
        val agent = json.getJSONArray("features").getJSONObject(0)
        assertEquals("UNKNOWN", agent.getString("status"))
        assertTrue(agent.isNull("baseModelName"))
        assertTrue(agent.isNull("featureId"))
        assertTrue(agent.isNull("errorCode"))
        assertFalse(report.text.contains("provisioned"))
    }
}
