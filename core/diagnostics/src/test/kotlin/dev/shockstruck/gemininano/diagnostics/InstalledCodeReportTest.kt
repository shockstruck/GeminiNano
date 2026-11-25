package dev.shockstruck.gemininano.diagnostics

import dev.shockstruck.gemininano.model.AppUserFacts
import dev.shockstruck.gemininano.model.InstalledCodeApkDigest
import dev.shockstruck.gemininano.model.InstalledCodeApkFacts
import dev.shockstruck.gemininano.model.InstalledCodeApkId
import dev.shockstruck.gemininano.model.InstalledCodeApkKind
import dev.shockstruck.gemininano.model.InstalledCodeAssetEntryFacts
import dev.shockstruck.gemininano.model.InstalledCodeAssetFacts
import dev.shockstruck.gemininano.model.InstalledCodeDefaultValue
import dev.shockstruck.gemininano.model.InstalledCodeDeviceConfigFacts
import dev.shockstruck.gemininano.model.InstalledCodeMarkerFacts
import dev.shockstruck.gemininano.model.InstalledCodeMarkerKind
import dev.shockstruck.gemininano.model.InstalledCodeMarkerMatch
import dev.shockstruck.gemininano.model.InstalledCodeMarkerReference
import dev.shockstruck.gemininano.model.InstalledCodeMarkers
import dev.shockstruck.gemininano.model.InstalledCodeOutcome
import dev.shockstruck.gemininano.model.InstalledCodePackageEvidence
import dev.shockstruck.gemininano.model.InstalledCodePackageFacts
import dev.shockstruck.gemininano.model.InstalledCodePermissionCheckFacts
import dev.shockstruck.gemininano.model.InstalledCodePermissionCheckReference
import dev.shockstruck.gemininano.model.InstalledCodePresence
import dev.shockstruck.gemininano.model.InstalledCodeReadFailure
import dev.shockstruck.gemininano.model.PcsBindHistoryEntry
import dev.shockstruck.gemininano.model.PcsBindOutcome
import dev.shockstruck.gemininano.model.PcsBindResult
import dev.shockstruck.gemininano.model.PcsBindCatalog
import dev.shockstruck.gemininano.model.PlatformPrincipals
import dev.shockstruck.gemininano.testing.TestFixtures
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstalledCodeReportTest {

    private val baseApk = InstalledCodeApkId("base", InstalledCodeApkKind.BASE)

    private fun readEvidence(
        packageName: String = PlatformPrincipals.AICORE,
        facts: InstalledCodePackageFacts = facts(packageName),
        elapsedMillis: Long = 42L,
    ) = InstalledCodePackageEvidence(
        requestedPackage = packageName,
        outcome = InstalledCodeOutcome.Read(packageName, facts),
        elapsedMillis = elapsedMillis,
    )

    private fun facts(packageName: String = PlatformPrincipals.AICORE) = InstalledCodePackageFacts(
        packageName = packageName,
        versionCode = 545_573L,
        apkSha256 = listOf(InstalledCodeApkDigest(baseApk, "abc123")),
        apks = listOf(
            InstalledCodeApkFacts(baseApk, "abc123", listOf("classes.dex")),
        ),
        markers = listOf(
            InstalledCodeMarkerFacts(
                marker = "ProtectedDownload__enabled",
                match = InstalledCodeMarkerMatch.EXACT,
                kind = InstalledCodeMarkerKind.FLAG,
                presence = InstalledCodePresence.PRESENT,
                references = listOf(
                    InstalledCodeMarkerReference(
                        marker = "ProtectedDownload__enabled",
                        apk = baseApk,
                        dexEntry = "classes.dex",
                        matchedString = "ProtectedDownload__enabled",
                        methodCount = 1,
                        defaults = listOf(InstalledCodeDefaultValue.BooleanValue(false)),
                    ),
                ),
            ),
        ),
        readDeviceConfig = InstalledCodePermissionCheckFacts(
            marker = InstalledCodeMarkers.READ_DEVICE_CONFIG,
            references = listOf(
                InstalledCodePermissionCheckReference(
                    apk = baseApk,
                    dexEntry = "classes.dex",
                    referencingMethods = 1,
                    referencingMethodsWithPermissionCheck = 1,
                    permissionCheckMethodNames = listOf("checkSelfPermission"),
                ),
            ),
        ),
        deviceConfig = InstalledCodeDeviceConfigFacts(listOf("getBoolean")),
        assets = InstalledCodeAssetFacts(
            entries = listOf(
                InstalledCodeAssetEntryFacts(baseApk, "assets/manifest.binarypb", 123L),
            ),
            totalAssetCount = 1,
        ),
    )

    private fun bindResult(componentName: String = PcsBindCatalog.ASTREA_GRPC) = PcsBindResult(
        componentName = componentName,
        outcome = PcsBindOutcome.BINDER_RETURNED,
        interfaceDescriptor = "com.example.IPcs",
        elapsedMillis = 12L,
        unbindCalls = 1,
    )

    private fun bindHistoryEntry(
        componentName: String = PcsBindCatalog.ASTREA_GRPC,
        at: Long = TestFixtures.NOW,
        pcsVersionCode: Long? = 152_997L,
    ) = PcsBindHistoryEntry(
        result = bindResult(componentName),
        observedAtEpochMillis = at,
        appVersionName = "0.6.1",
        appVersionCode = 7L,
        pcsVersionCode = pcsVersionCode,
    )

    private fun build(
        installedCode: List<InstalledCodePackageEvidence> = emptyList(),
        dependencies: ProvisioningDependencyEvaluation? = null,
        appUser: AppUserFacts? = null,
        bindHistory: List<PcsBindHistoryEntry> = emptyList(),
    ) = FullDiagnosticsReportBuilder().build(
        FullDiagnosticsInput(
            generatedAtEpochMillis = TestFixtures.NOW,
            metadata = ExportMetadata(
                applicationId = PlatformPrincipals.APP,
                versionName = "0.7.0",
                versionCode = 8L,
                exportedAtEpochMillis = TestFixtures.NOW,
            ),
            matrix = null,
            features = emptyList(),
            prompt = emptyList(),
            summarization = null,
            surface = null,
            bindResults = emptyList(),
            offline = emptyList(),
            events = emptyList(),
            installedCode = installedCode,
            dependencies = dependencies,
            appUser = appUser,
            bindHistory = bindHistory,
        ),
    )

    @Test
    fun exportsInstalledCodeFactsWithTheDefaultsLabelAndTheAssetInterpretation() {
        val json = JSONObject(build(installedCode = listOf(readEvidence())).json)

        val installed = json.getJSONObject("installedCode")
        assertTrue(
            installed.getString("note")
                .contains("no reader message can embed a decoded DEX string"),
        )
        val packageJson = installed.getJSONArray("packages").getJSONObject(0)
        assertEquals(PlatformPrincipals.AICORE, packageJson.getString("requestedPackage"))
        assertEquals("read", packageJson.getString("outcome"))
        assertEquals(42L, packageJson.getLong("elapsedMillis"))
        assertEquals(545_573L, packageJson.getLong("versionCode"))
        assertEquals(true, packageJson.getBoolean("complete"))
        assertEquals("abc123", packageJson.getJSONArray("apks").getJSONObject(0).getString("sha256"))

        val marker = packageJson.getJSONArray("markers").getJSONObject(0)
        assertEquals("PRESENT", marker.getString("presence"))
        val reference = marker.getJSONArray("references").getJSONObject(0)
        assertEquals(
            ProvisioningDependencyEvaluator.DEFAULTS_LABEL,
            reference.getString("defaultsLabel"),
        )
        val default = reference.getJSONArray("defaults").getJSONObject(0)
        assertEquals("boolean", default.getString("type"))
        assertEquals(false, default.getBoolean("value"))
        assertEquals(
            true,
            packageJson.getJSONObject("readDeviceConfig")
                .getBoolean("anyReferencingMethodChecksPermission"),
        )
        assertEquals(
            "candidate non-DeviceConfig input only; presence is not use",
            packageJson.getJSONObject("assets").getString("interpretation"),
        )
        assertEquals(
            "assets/manifest.binarypb",
            packageJson.getJSONObject("assets").getJSONArray("entries").getJSONObject(0)
                .getString("name"),
        )
    }

    @Test
    fun exportsNoFollowingArgumentAsItsOwnValue() {
        val noArgumentFacts = facts().copy(
            markers = listOf(
                InstalledCodeMarkerFacts(
                    marker = "ProtectedDownload__enabled",
                    match = InstalledCodeMarkerMatch.EXACT,
                    kind = InstalledCodeMarkerKind.FLAG,
                    presence = InstalledCodePresence.PRESENT,
                    references = listOf(
                        InstalledCodeMarkerReference(
                            marker = "ProtectedDownload__enabled",
                            apk = baseApk,
                            dexEntry = "classes.dex",
                            matchedString = "ProtectedDownload__enabled",
                            methodCount = 1,
                            defaults = listOf(InstalledCodeDefaultValue.NoFollowingArgument),
                        ),
                    ),
                ),
            ),
        )
        val report = build(installedCode = listOf(readEvidence(facts = noArgumentFacts)))
        val json = JSONObject(report.json)

        assertEquals(
            "no-following-argument",
            json.getJSONObject("installedCode").getJSONArray("packages").getJSONObject(0)
                .getJSONArray("markers").getJSONObject(0)
                .getJSONArray("references").getJSONObject(0)
                .getJSONArray("defaults").getJSONObject(0)
                .getString("type"),
        )
        assertTrue(report.text.contains("no following argument"))
    }

    @Test
    fun exportsEveryNonReadOutcomeWithItsOwnDetail() {
        val tooLarge = InstalledCodePackageEvidence(
            requestedPackage = PlatformPrincipals.AICORE,
            outcome = InstalledCodeOutcome.TooLarge(
                PlatformPrincipals.AICORE,
                "classes.dex is 40000000 bytes, above the limit",
                545_573L,
                listOf(InstalledCodeApkDigest(baseApk, "abc123")),
            ),
            elapsedMillis = 3L,
        )
        val readError = InstalledCodePackageEvidence(
            requestedPackage = PlatformPrincipals.PCS,
            outcome = InstalledCodeOutcome.ReadError(
                PlatformPrincipals.PCS,
                "java.util.zip.ZipException",
                "invalid entry",
            ),
            elapsedMillis = 4L,
        )
        val notVisible = InstalledCodePackageEvidence(
            requestedPackage = PlatformPrincipals.AICORE_LEGACY,
            outcome = InstalledCodeOutcome.NotVisible(PlatformPrincipals.AICORE_LEGACY),
            elapsedMillis = 0L,
        )
        val partialFacts = facts(PlatformPrincipals.PCS).copy(
            failures = listOf(
                InstalledCodeReadFailure(
                    apk = baseApk,
                    dexEntry = "classes2.dex",
                    errorClass = "java.lang.StackOverflowError",
                    message = "stack",
                ),
            ),
        )
        val partial = InstalledCodePackageEvidence(
            requestedPackage = PlatformPrincipals.PCS,
            outcome = InstalledCodeOutcome.Partial(PlatformPrincipals.PCS, partialFacts),
            elapsedMillis = 5L,
        )

        val json = JSONObject(
            build(installedCode = listOf(tooLarge, readError, notVisible, partial)).json,
        )
        val packages = json.getJSONObject("installedCode").getJSONArray("packages")

        assertEquals("too large", packages.getJSONObject(0).getString("outcome"))
        assertTrue(packages.getJSONObject(0).getString("detail").contains("40000000 bytes"))
        assertEquals(545_573L, packages.getJSONObject(0).getLong("versionCode"))
        assertEquals("read error", packages.getJSONObject(1).getString("outcome"))
        assertEquals(
            "java.util.zip.ZipException",
            packages.getJSONObject(1).getString("errorClass"),
        )
        assertEquals("invalid entry", packages.getJSONObject(1).getString("message"))
        assertEquals("not visible", packages.getJSONObject(2).getString("outcome"))
        assertEquals("partial", packages.getJSONObject(3).getString("outcome"))
        assertEquals(false, packages.getJSONObject(3).getBoolean("complete"))
        assertEquals(
            "java.lang.StackOverflowError",
            packages.getJSONObject(3).getJSONArray("failures").getJSONObject(0)
                .getString("errorClass"),
        )
    }

    @Test
    fun exportsDependencyStepsWithTheirOutcomesAndStrength() {
        val evaluation = ProvisioningDependencyEvaluation(
            listOf(
                DependencyStep(
                    id = DependencyStepId.CLIENT_STATUS_CHECK,
                    outcome = DependencyStepOutcome.NOT_TESTED,
                    summary = "not traced",
                    evidence = listOf(
                        DependencyEvidence(
                            "the client status check was not traced",
                            DependencyEvidenceStrength.SOURCE_TRACE,
                            "public source trace",
                        ),
                    ),
                ),
                DependencyStep(
                    id = DependencyStepId.SYSTEM_USER,
                    outcome = DependencyStepOutcome.NOT_OBSERVABLE,
                    summary = "the user was not read",
                ),
                DependencyStep(
                    id = DependencyStepId.AICORE_BUILD_ID,
                    outcome = DependencyStepOutcome.FAILED,
                    summary = "the gate reads its default",
                    evidence = listOf(
                        DependencyEvidence(
                            "default (${ProvisioningDependencyEvaluator.DEFAULTS_LABEL}): int 0",
                            DependencyEvidenceStrength.OBSERVED,
                            "installed-code reader",
                        ),
                    ),
                ),
            ),
        )

        val json = JSONObject(build(dependencies = evaluation).json)
        val dependencies = json.getJSONObject("provisioningDependencies")
        assertEquals(
            "First step shown to fail: ${DependencyStepId.AICORE_BUILD_ID.label}",
            dependencies.getString("result"),
        )
        assertEquals(DependencyStepId.AICORE_BUILD_ID.label, dependencies.getString("firstFailingStep"))
        assertEquals(DependencyStepId.SYSTEM_USER.label, dependencies.getString("earliestUnsettledStep"))
        assertTrue(dependencies.getString("summary").contains("the gate reads its default"))
        assertTrue(dependencies.getString("summary").contains("Earlier steps not passed:"))
        val earlier = dependencies.getJSONArray("earlierStepsNotPassed")
        assertEquals(2, earlier.length())
        assertEquals(
            DependencyStepId.CLIENT_STATUS_CHECK.label,
            earlier.getJSONObject(0).getString("step"),
        )
        assertEquals("not tested", earlier.getJSONObject(0).getString("outcome"))
        assertEquals(
            DependencyStepId.SYSTEM_USER.label,
            earlier.getJSONObject(1).getString("step"),
        )
        val steps = dependencies.getJSONArray("steps")
        assertEquals("not tested", steps.getJSONObject(0).getString("outcome"))
        assertEquals(
            "source trace",
            steps.getJSONObject(0).getJSONArray("evidence").getJSONObject(0).getString("strength"),
        )
        assertEquals("not observable", steps.getJSONObject(1).getString("outcome"))
        assertEquals("failed", steps.getJSONObject(2).getString("outcome"))
    }

    @Test
    fun exportsTheAppUserAndTheStoredBindHistoryPerComponent() {
        val history = listOf(
            bindHistoryEntry(at = TestFixtures.NOW),
            bindHistoryEntry(at = TestFixtures.NOW + 1000),
            bindHistoryEntry(componentName = PcsBindCatalog.ASTREA_GENAI_INFERENCE),
        )
        val report = build(
            appUser = AppUserFacts(isSystemUser = true),
            bindHistory = history,
        )
        val json = JSONObject(report.json)

        assertEquals(true, json.getJSONObject("appUser").getBoolean("isSystemUser"))
        assertTrue(json.getJSONObject("appUser").getString("label").contains("system"))
        val bindHistory = json.getJSONObject("pcsBindHistory")
        assertTrue(bindHistory.getString("label").contains("at most five"))
        val components = bindHistory.getJSONArray("components")
        assertEquals(2, components.length())
        val grpc = (0 until components.length())
            .map { components.getJSONObject(it) }
            .first { it.getString("componentName") == PcsBindCatalog.ASTREA_GRPC }
        val entries = grpc.getJSONArray("entries")
        assertEquals(2, entries.length())
        assertEquals("0.6.1", entries.getJSONObject(0).getString("appVersionName"))
        assertEquals(7L, entries.getJSONObject(0).getLong("appVersionCode"))
        assertEquals(152_997L, entries.getJSONObject(0).getLong("pcsVersionCode"))
        assertTrue(entries.getJSONObject(0).getString("observedAt").isNotEmpty())
        assertTrue(report.text.contains("Stored PCS bind history"))
        assertTrue(report.text.contains("First step shown to fail"))
    }
}
