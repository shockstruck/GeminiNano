package dev.shockstruck.gemininano.diagnostics

import dev.shockstruck.gemininano.model.ComponentKind
import dev.shockstruck.gemininano.model.FeatureProbeResult
import dev.shockstruck.gemininano.model.FeatureStatusKind
import dev.shockstruck.gemininano.model.GenAiFailure
import dev.shockstruck.gemininano.model.GenAiFeature
import dev.shockstruck.gemininano.model.GrantState
import dev.shockstruck.gemininano.model.OfflineInferenceOutcome
import dev.shockstruck.gemininano.model.OfflineInferenceResult
import dev.shockstruck.gemininano.model.PackageFacts
import dev.shockstruck.gemininano.model.PackageReadError
import dev.shockstruck.gemininano.model.PackageVisibility
import dev.shockstruck.gemininano.model.PcsActivityLaunchResult
import dev.shockstruck.gemininano.model.PcsActivityTarget
import dev.shockstruck.gemininano.model.PcsBindOutcome
import dev.shockstruck.gemininano.model.PcsBindResult
import dev.shockstruck.gemininano.model.PcsLaunchOutcome
import dev.shockstruck.gemininano.model.PcsSettingsResolution
import dev.shockstruck.gemininano.model.PcsSurface
import dev.shockstruck.gemininano.model.PermissionDeclaration
import dev.shockstruck.gemininano.model.PermissionGrantFacts
import dev.shockstruck.gemininano.model.PlatformPermissions
import dev.shockstruck.gemininano.model.PlatformPrincipals
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
        packages: List<PackageFacts> = emptyList(),
        launchResults: List<PcsActivityLaunchResult> = emptyList(),
        systemFeatures: List<String> = emptyList(),
        summarization: dev.shockstruck.gemininano.model.SummarizationProbeResult? =
            TestFixtures.probeResult(failure = TestFixtures.failure(606), baseModelName = null),
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
        summarization = summarization,
        surface = surface,
        bindResults = bindResults,
        offline = offline,
        events = events,
        packages = packages,
        launchResults = launchResults,
        systemFeatures = systemFeatures,
    )

    @Test
    fun jsonCarriesEverySection() {
        val report = FullDiagnosticsReportBuilder().build(input())
        val json = JSONObject(report.json)
        assertEquals(FullDiagnosticsReportBuilder.SCHEMA, json.getString("schema"))
        assertEquals("dev.shockstruck.gemininano", json.getJSONObject("app").getString("applicationId"))
        assertEquals(4, json.getJSONObject("app").getInt("versionCode"))
        assertEquals(5, json.getJSONObject("permissionMatrix").getJSONArray("principals").length())
        assertEquals(GenAiFeature.MATRIX.size, json.getJSONArray("features").length())
        assertEquals(4, json.getJSONArray("prompt").length())
        assertTrue(json.has("permissionVerification"))
        assertTrue(json.has("packageIdentities"))
        assertTrue(json.has("systemFeatures"))
        assertTrue(json.has("pcsLaunches"))
        assertTrue(json.has("untestedOperations"))
        assertTrue(json.getJSONObject("summarization").has("status"))
        assertTrue(json.getJSONObject("pcs").getBoolean("packageVisible"))
        assertEquals(0, json.getJSONArray("pcsBind").length())
        assertEquals(1, json.getJSONArray("offline").length())
        assertEquals(1, json.getJSONObject("diagnostics").getInt("eventCount"))
    }

    @Test
    fun probeExecutionIsExportedSeparatelyFromFeatureAvailability() {
        val report = FullDiagnosticsReportBuilder().build(
            input(
                features = listOf(
                    feature(GenAiFeature.REWRITING, status = FeatureStatusKind.AVAILABLE),
                    feature(
                        GenAiFeature.PROOFREADING,
                        status = FeatureStatusKind.PROBE_ERROR,
                        failure = GenAiFailure(606, "com.google.mlkit.genai.common.GenAiException", "missing"),
                        featureId = 614,
                    ),
                ),
            ),
        )
        val json = JSONObject(report.json)
        val rows = (0 until json.getJSONArray("features").length())
            .map { json.getJSONArray("features").getJSONObject(it) }
        val rewriting = rows.first { it.getString("feature") == "rewriting" }
        val proofreading = rows.first { it.getString("feature") == "proofreading" }
        val agent = rows.first { it.getString("feature") == "agent" }
        assertTrue(rewriting.getBoolean("probeExecuted"))
        assertTrue(rewriting.getBoolean("featureAvailable"))
        assertTrue(proofreading.getBoolean("probeExecuted"))
        assertFalse(proofreading.getBoolean("featureAvailable"))
        assertFalse(agent.getBoolean("probeExecuted"))
        assertFalse(agent.getBoolean("featureAvailable"))
        assertTrue(report.text.contains("Rewriting: AVAILABLE | probeExecuted=true | featureAvailable=true"))
        assertTrue(report.text.contains("Agent: NOT PROBED | probeExecuted=false | featureAvailable=false"))
    }

    @Test
    fun untestedOperationsListEveryOperationNotAttempted() {
        val surface = PcsSurface(
            packageVisible = true,
            exportedComponents = emptyList(),
            settings = PcsSettingsResolution(false, null, "none"),
            launchTargets = listOf(
                PcsActivityTarget(
                    label = "PCS settings",
                    className = "com.example.PcsSettingsActivity",
                    declared = true,
                    exported = true,
                    effectivelyEnabled = true,
                    requiredPermission = null,
                    launchable = true,
                    detail = "explicit tap only",
                ),
                PcsActivityTarget(
                    label = "Network usage item details",
                    className = "com.example.DetailsActivity",
                    declared = true,
                    exported = true,
                    effectivelyEnabled = true,
                    requiredPermission = null,
                    launchable = false,
                    detail = "requires extras that are not publicly known; no launch is attempted.",
                ),
            ),
        )
        val report = FullDiagnosticsReportBuilder().build(
            input(
                features = emptyList(),
                bindResults = listOf(
                    PcsBindResult("com.example.Grpc", PcsBindOutcome.NOT_RUN),
                    PcsBindResult("com.example.Missing", PcsBindOutcome.NOT_DECLARED),
                ),
                surface = surface,
                offline = emptyList(),
                summarization = null,
            ),
        )
        val json = JSONObject(report.json)
        val untested = (0 until json.getJSONArray("untestedOperations").length())
            .map { json.getJSONArray("untestedOperations").getString(it) }
        assertTrue(untested.any { it == "Probe Rewriting" })
        assertTrue(untested.any { it == "Prompt STABLE/FULL" })
        assertTrue(untested.any { it == "Summarization probe" })
        assertTrue(untested.any { it.startsWith("Bind com.example.Grpc") })
        assertTrue(untested.any { it.startsWith("Bind com.example.Missing") })
        assertTrue(untested.any { it.contains("Launch PCS settings") })
        assertTrue(untested.any { it.contains("requires extras that are not publicly known") })
        assertTrue(untested.any { it == "Offline inference matrix" })
    }

    @Test
    fun aNotVisibleOrReadErrorPackageNeverClaimsAFullRead() {
        val notVisible = PackageFacts(
            packageName = "com.google.android.apps.aicore",
            visibility = PackageVisibility.NOT_VISIBLE,
        )
        val readError = PackageFacts(
            packageName = "com.google.android.as",
            visibility = PackageVisibility.READ_ERROR,
            readError = PackageReadError("java.lang.RuntimeException", "x", 4),
        )
        val report = FullDiagnosticsReportBuilder().build(
            input(packages = listOf(notVisible, readError)),
        )
        val json = JSONObject(report.json)
        val packages = (0 until json.getJSONArray("permissionVerification").length())
            .map { json.getJSONArray("permissionVerification").getJSONObject(it) }
        packages.forEach { packageJson ->
            assertFalse(packageJson.getBoolean("permissionsRead"))
            val coverage = packageJson.getJSONObject("coverage")
            assertFalse(coverage.getBoolean("permissions"))
            assertFalse(coverage.getBoolean("components"))
            assertFalse(coverage.getBoolean("signers"))
            assertFalse(coverage.getBoolean("metadata"))
        }
        val identities = (0 until json.getJSONArray("packageIdentities").length())
            .map { json.getJSONArray("packageIdentities").getJSONObject(it) }
        identities.forEach { identity ->
            assertFalse(identity.getBoolean("signersRead"))
            assertEquals("not read", identity.getString("signers"))
            assertEquals(0, identity.getJSONArray("signerSha256").length())
        }
        assertTrue(report.text.contains("signerSha256=not read"))
    }

    @Test
    fun otherPackagesKeepOnlyTheDeclaredPermissionSubset() {
        val allowed = PlatformPermissions.OTHER_PACKAGE_PERMISSIONS.toSet()
        val gms = PackageFacts(
            packageName = PlatformPrincipals.GMS,
            visibility = dev.shockstruck.gemininano.model.PackageVisibility.VISIBLE,
            versionCode = 250000000L,
            signingSha256 = listOf("AA:BB"),
            requestedPermissions = allowed.toList() + "android.permission.CAMERA",
            permissionGrants = (allowed.toList() + "android.permission.CAMERA").map { permission ->
                PermissionGrantFacts(permission, GrantState.DENIED, requested = true)
            },
            firstInstallTime = 100L,
            lastUpdateTime = 200L,
        )
        val pcs = PackageFacts(
            packageName = PlatformPrincipals.PCS,
            visibility = dev.shockstruck.gemininano.model.PackageVisibility.VISIBLE,
            versionCode = 152997L,
            signingSha256 = listOf("CC:DD"),
            requestedPermissions = listOf(
                PlatformPermissions.PCC,
                PlatformPermissions.AICORE_BIND,
                "com.example.OTHER",
            ),
            permissionGrants = listOf(
                PermissionGrantFacts(
                    PlatformPermissions.AICORE_BIND,
                    GrantState.GRANTED,
                    requested = true,
                    requestedFlagsGrant = true,
                    declaration = PermissionDeclaration(
                        defined = true,
                        declaringPackage = "android",
                        protectionBase = 0,
                    ),
                ),
                PermissionGrantFacts(
                    PlatformPermissions.PCC,
                    GrantState.DENIED,
                    requested = true,
                    requestedFlagsGrant = false,
                    declaration = PermissionDeclaration(
                        defined = true,
                        declaringPackage = "android",
                        protectionBase = 2,
                        protectionFlags = 0x10,
                    ),
                ),
                PermissionGrantFacts("com.example.OTHER", GrantState.DENIED, requested = true),
            ),
            firstInstallTime = 50L,
            lastUpdateTime = 60L,
        )
        val aicore = PackageFacts(
            packageName = PlatformPrincipals.AICORE,
            visibility = dev.shockstruck.gemininano.model.PackageVisibility.VISIBLE,
            versionCode = 545573L,
            signingSha256 = listOf("EE:FF"),
            requestedPermissions = listOf(PlatformPermissions.AICORE_BIND),
            permissionGrants = listOf(
                PermissionGrantFacts(
                    PlatformPermissions.AICORE_BIND,
                    GrantState.DENIED,
                    requested = true,
                    requestedFlagsGrant = false,
                ),
            ),
            firstInstallTime = 10L,
            lastUpdateTime = 20L,
        )
        val report = FullDiagnosticsReportBuilder().build(
            input(packages = listOf(gms, pcs, aicore)),
        )
        val json = JSONObject(report.json)
        val packages = (0 until json.getJSONArray("permissionVerification").length())
            .map { json.getJSONArray("permissionVerification").getJSONObject(it) }
            .associateBy { it.getString("packageName") }
        val gmsPermissions = packages.getValue(PlatformPrincipals.GMS)
            .getJSONArray("permissions")
            .let { array -> (0 until array.length()).map { array.getJSONObject(it).getString("permission") } }
        assertTrue(gmsPermissions.all { it in allowed })
        assertFalse(gmsPermissions.contains("android.permission.CAMERA"))
        val pcsPermissions = packages.getValue(PlatformPrincipals.PCS)
            .getJSONArray("permissions")
            .let { array -> (0 until array.length()).map { array.getJSONObject(it).getString("permission") } }
        assertTrue(pcsPermissions.contains("com.example.OTHER"))
        val bindFact = packages.getValue(PlatformPrincipals.AICORE)
            .getJSONArray("permissions").getJSONObject(0)
        assertEquals(10L, bindFact.getLong("firstInstallTime"))
        assertEquals(20L, bindFact.getLong("lastUpdateTime"))
        assertEquals(10L, bindFact.getLong("aicoreFirstInstallTime"))
        val identities = (0 until json.getJSONArray("packageIdentities").length())
            .map { json.getJSONArray("packageIdentities").getJSONObject(it) }
            .associateBy { it.getString("packageName") }
        assertEquals(545573L, identities.getValue(PlatformPrincipals.AICORE).getLong("versionCode"))
        assertEquals("EE:FF", identities.getValue(PlatformPrincipals.AICORE).getJSONArray("signerSha256").getString(0))
        assertEquals(250000000L, identities.getValue(PlatformPrincipals.GMS).getLong("versionCode"))
    }

    @Test
    fun bindAndLaunchResultsKeepTheirOwnFacts() {
        val report = FullDiagnosticsReportBuilder().build(
            input(
                bindResults = listOf(
                    PcsBindResult(
                        componentName = "com.google.android.apps.miphone.astrea.grpc.AstreaGrpcService",
                        outcome = PcsBindOutcome.BINDER_RETURNED,
                        role = "grpc",
                        declaredName = "com.google.android.apps.miphone.astrea.grpc.AstreaGrpcService",
                        interfaceDescriptor = "",
                        binderAlive = true,
                        descriptorReadMillis = 3L,
                    ),
                ),
                launchResults = listOf(
                    PcsActivityLaunchResult(
                        label = "PCS settings",
                        className = "com.example.PcsSettingsActivity",
                        resolved = true,
                        outcome = PcsLaunchOutcome.LAUNCHED,
                        elapsedMillis = 12L,
                    ),
                ),
                systemFeatures = listOf("android.hardware.npu", "android.software.virtualization"),
            ),
        )
        val json = JSONObject(report.json)
        val bind = json.getJSONArray("pcsBind").getJSONObject(0)
        assertEquals("", bind.getString("interfaceDescriptor"))
        assertEquals(true, bind.getBoolean("binderAlive"))
        assertEquals(3L, bind.getLong("descriptorReadMillis"))
        assertEquals("BIND_AUTO_CREATE", bind.getString("bindFlag"))
        assertEquals("grpc", bind.getString("role"))
        val launch = json.getJSONArray("pcsLaunches").getJSONObject(0)
        assertEquals("launched", launch.getString("outcome"))
        assertEquals(2, json.getJSONArray("systemFeatures").length())
        assertTrue(report.text.contains("== System features naming NPU, AI, virtualization or private compute (names only) =="))
        assertTrue(report.text.contains("android.hardware.npu"))
        assertTrue(report.text.contains("this app's own bind attempt"))
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
    fun featuresWithoutAResultAreExportedAsNotProbedNotOmitted() {
        val report = FullDiagnosticsReportBuilder().build(
            input(features = listOf(feature(GenAiFeature.REWRITING))),
        )
        val json = JSONObject(report.json)
        assertEquals(GenAiFeature.MATRIX.size, json.getJSONArray("features").length())
        val rows = (0 until json.getJSONArray("features").length())
            .map { json.getJSONArray("features").getJSONObject(it) }
        val unprobed = rows.filter { it.getString("status") == "NOT PROBED" }
        assertEquals(GenAiFeature.MATRIX.size - 1, unprobed.size)
        unprobed.forEach { row ->
            assertEquals("not probed yet", row.getString("detail"))
            assertTrue(row.isNull("errorCode"))
            assertTrue(row.isNull("exceptionClass"))
        }
        assertTrue(report.text.contains("Proofreading: NOT PROBED"))
        assertTrue(report.text.contains("not probed yet"))
    }

    @Test
    fun permissionVerificationExportsEveryRequestedPermissionAndItsDeclaration() {
        val pcs = TestFixtures.packageFacts(
            packageName = "com.google.android.as.oss",
            requestedPermissions = listOf(
                "android.permission.PROVIDE_PRIVATE_COMPUTE_SERVICES",
                "com.google.android.apps.aicore.service.BIND_SERVICE",
            ),
            permissionGrants = listOf(
                dev.shockstruck.gemininano.model.PermissionGrantFacts(
                    permission = "android.permission.PROVIDE_PRIVATE_COMPUTE_SERVICES",
                    state = dev.shockstruck.gemininano.model.GrantState.DENIED,
                    requested = true,
                    requestedFlagsGrant = false,
                    declaration = dev.shockstruck.gemininano.model.PermissionDeclaration(
                        defined = true,
                        declaringPackage = "android",
                        protectionBase = dev.shockstruck.gemininano.model.PermissionProtection.BASE_SIGNATURE,
                        protectionFlags = dev.shockstruck.gemininano.model.PermissionProtection.FLAG_PRIVILEGED,
                    ),
                ),
                dev.shockstruck.gemininano.model.PermissionGrantFacts(
                    permission = "com.google.android.apps.aicore.service.BIND_SERVICE",
                    state = dev.shockstruck.gemininano.model.GrantState.GRANTED,
                    requested = true,
                    requestedFlagsGrant = true,
                    declaration = dev.shockstruck.gemininano.model.PermissionDeclaration(defined = false),
                ),
            ),
        )
        val report = FullDiagnosticsReportBuilder().build(input(packages = listOf(pcs)))
        val json = JSONObject(report.json)
        val exported = json.getJSONArray("permissionVerification")
        assertEquals(1, exported.length())
        val packageJson = exported.getJSONObject(0)
        assertEquals("com.google.android.as.oss", packageJson.getString("packageName"))
        assertEquals(2, packageJson.getJSONArray("permissions").length())
        val pcc = packageJson.getJSONArray("permissions").getJSONObject(0)
        assertFalse(pcc.getBoolean("requestedPermissionsFlagsGrant"))
        assertTrue(pcc.getJSONObject("declaration").getBoolean("defined"))
        assertEquals(
            "signature|privileged",
            pcc.getJSONObject("declaration").getString("protection"),
        )
        val bind = packageJson.getJSONArray("permissions").getJSONObject(1)
        assertFalse(bind.getJSONObject("declaration").getBoolean("defined"))
        assertTrue(packageJson.getJSONArray("requestedButDeniedSignatureOrPrivileged").length() == 1)
        assertTrue(report.text.contains("== Permission verification"))
        assertTrue(report.text.contains("not defined on this OS"))
        assertTrue(report.text.contains("declared by android"))
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
        val agent = (0 until json.getJSONArray("features").length())
            .map { json.getJSONArray("features").getJSONObject(it) }
            .first { it.getString("feature") == "agent" }
        assertEquals("UNKNOWN", agent.getString("status"))
        assertTrue(agent.isNull("baseModelName"))
        assertTrue(agent.isNull("featureId"))
        assertTrue(agent.isNull("errorCode"))
        assertFalse(report.text.contains("provisioned"))
    }
}
