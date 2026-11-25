package dev.shockstruck.gemininano.testing

import dev.shockstruck.gemininano.model.BuildFacts
import dev.shockstruck.gemininano.model.ComponentEnabledSetting
import dev.shockstruck.gemininano.model.ComponentFacts
import dev.shockstruck.gemininano.model.ComponentKind
import dev.shockstruck.gemininano.model.DiagnosticEvent
import dev.shockstruck.gemininano.model.DownloadResult
import dev.shockstruck.gemininano.model.FeatureStatusKind
import dev.shockstruck.gemininano.model.GenAiFailure
import dev.shockstruck.gemininano.model.GrantState
import dev.shockstruck.gemininano.model.ManifestApkKind
import dev.shockstruck.gemininano.model.ManifestAssociationPackageFacts
import dev.shockstruck.gemininano.model.ManifestBooleanValue
import dev.shockstruck.gemininano.model.ManifestComponentFacts
import dev.shockstruck.gemininano.model.ManifestElementFacts
import dev.shockstruck.gemininano.model.ManifestPackageFacts
import dev.shockstruck.gemininano.model.ManifestReadOutcome
import dev.shockstruck.gemininano.model.ManifestSourceFacts
import dev.shockstruck.gemininano.model.ManifestSourceId
import dev.shockstruck.gemininano.model.ManifestUsesPermissionFacts
import dev.shockstruck.gemininano.model.Outcome
import dev.shockstruck.gemininano.model.PackageFacts
import dev.shockstruck.gemininano.model.PackageFactsProvider
import dev.shockstruck.gemininano.model.PackageReadCoverage
import dev.shockstruck.gemininano.model.PackageVisibility
import dev.shockstruck.gemininano.model.PcsBindOutcome
import dev.shockstruck.gemininano.model.PcsBindResult
import dev.shockstruck.gemininano.model.PermissionGrantFacts
import dev.shockstruck.gemininano.model.PermissionMatrix
import dev.shockstruck.gemininano.model.PermissionMatrixBuilder
import dev.shockstruck.gemininano.model.PermissionPrincipal
import dev.shockstruck.gemininano.model.PlatformPermissions
import dev.shockstruck.gemininano.model.PlatformPrincipals
import dev.shockstruck.gemininano.model.PromptConfigStatus
import dev.shockstruck.gemininano.model.PromptFailureParsing
import dev.shockstruck.gemininano.model.PromptModelConfig
import dev.shockstruck.gemininano.model.PromptProbe
import dev.shockstruck.gemininano.model.Severity
import dev.shockstruck.gemininano.model.SummarizationProbe
import dev.shockstruck.gemininano.model.SummarizationProbeResult
import dev.shockstruck.gemininano.model.SummarizationRunResult

/** Deterministic fixtures shared by the unit tests. */
object TestFixtures {

    const val NOW = 1_800_000_000_000L

    fun buildFacts(
        manufacturer: String = "Google",
        model: String = "Pixel 10 Pro Fold",
        sdkInt: Int = 37,
        release: String = "17",
    ) = BuildFacts(
        manufacturer = manufacturer,
        brand = "google",
        model = model,
        device = "comet",
        product = "comet",
        hardware = "tensor",
        fingerprint = "google/comet/comet:17/AP4A/1:user/release-keys",
        androidRelease = release,
        sdkInt = sdkInt,
        securityPatch = "2026-11-05",
        supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
    )

    fun packageFacts(
        packageName: String,
        visible: Boolean = true,
        versionName: String? = "1.0.0",
        versionCode: Long? = 42L,
        installer: String? = "com.android.vending",
        signingSha256: List<String> = listOf("AA:BB:CC"),
        targetSdk: Int? = 36,
        minSdk: Int? = 24,
        enabled: Boolean? = true,
        requestedPermissions: List<String> = listOf("android.permission.INTERNET"),
        permissionGrants: List<PermissionGrantFacts> = emptyList(),
        components: List<ComponentFacts> = emptyList(),
    ) = PackageFacts(
        packageName = packageName,
        visibility = if (visible) PackageVisibility.VISIBLE else PackageVisibility.NOT_VISIBLE,
        versionName = if (visible) versionName else null,
        versionCode = if (visible) versionCode else null,
        installerPackage = if (visible) installer else null,
        signingSha256 = if (visible) signingSha256 else emptyList(),
        targetSdk = if (visible) targetSdk else null,
        minSdk = if (visible) minSdk else null,
        enabled = if (visible) enabled else null,
        requestedPermissions = if (visible) requestedPermissions else emptyList(),
        permissionGrants = if (visible) permissionGrants else emptyList(),
        components = if (visible) components else emptyList(),
        coverage = if (visible) PackageReadCoverage.FULL else PackageReadCoverage.NONE,
    )

    fun probeResult(
        status: FeatureStatusKind = FeatureStatusKind.AVAILABLE,
        baseModelName: String? = "gemini-nano-base",
        failure: GenAiFailure? = null,
        probedAt: Long = NOW,
    ) = SummarizationProbeResult(
        status = status,
        statusDetail = status.label,
        baseModelName = baseModelName,
        failure = failure,
        probedAtEpochMillis = probedAt,
    )

    fun failure(errorCode: Int?, message: String = "Feature 622 is not available.") =
        GenAiFailure(
            errorCode = errorCode,
            exceptionClass = "com.google.mlkit.genai.common.GenAiException",
            message = message,
        )

    fun event(
        timestamp: Long = NOW,
        component: String = "aicore",
        operation: String = "probeSummarization",
        outcome: Outcome = Outcome.FAILURE,
        severity: Severity = Severity.ERROR,
        errorCode: Int? = 606,
        message: String? = "Feature 622 is not available.",
        featureId: String? = "summarization",
    ) = DiagnosticEvent(
        timestampEpochMillis = timestamp,
        component = component,
        operation = operation,
        outcome = outcome,
        severity = severity,
        exceptionClass = "com.google.mlkit.genai.common.GenAiException",
        errorCode = errorCode,
        message = message,
        featureId = featureId,
        bytes = null,
        durationMillis = 12L,
    )

    fun component(
        kind: ComponentKind = ComponentKind.ACTIVITY,
        className: String = "com.example.ExportActivity",
        exported: Boolean = true,
        requiredPermission: String? = null,
        enabled: Boolean = true,
        runtimeEnabledSetting: Int? = ComponentEnabledSetting.DEFAULT,
        effectivelyEnabled: Boolean? = true,
    ) = ComponentFacts(
        kind = kind,
        className = className,
        exported = exported,
        requiredPermission = requiredPermission,
        enabled = enabled,
        manifestEffectiveEnabled = enabled,
        runtimeEnabledSetting = runtimeEnabledSetting,
        effectivelyEnabled = effectivelyEnabled,
    )

    private fun matrixFacts(
        packageName: String,
        visible: Boolean,
        requested: Set<String>,
        grants: Map<String, GrantState>,
    ) = PackageFacts(
        packageName = packageName,
        visibility = if (visible) PackageVisibility.VISIBLE else PackageVisibility.NOT_VISIBLE,
        requestedPermissions = PlatformPermissions.MATRIX.filter { it in requested },
        permissionGrants = PlatformPermissions.MATRIX.map { permission ->
            PermissionGrantFacts(
                permission = permission,
                state = grants[permission] ?: GrantState.DENIED,
                requested = permission in requested,
            )
        },
    )

    fun permissionMatrix(
        pcsVisible: Boolean = true,
        pcsPcc: GrantState = GrantState.DENIED,
        pcsPccRequested: Boolean = true,
        aicoreVisible: Boolean = true,
        aicorePcc: GrantState = GrantState.DENIED,
        aicorePccRequested: Boolean = false,
        aicoreBind: GrantState = GrantState.DENIED,
        pcsReadDeviceConfig: GrantState? = null,
        aicoreReadDeviceConfig: GrantState? = null,
        appPackageName: String = PlatformPrincipals.APP,
        appBind: GrantState = GrantState.GRANTED,
        appBindRequested: Boolean = true,
        appInternet: GrantState = GrantState.DENIED,
        appInternetRequested: Boolean = false,
    ): PermissionMatrix {
        val principals = listOf(
            PermissionPrincipal("PCS", PlatformPrincipals.PCS),
            PermissionPrincipal("ASI", PlatformPrincipals.ASI),
            PermissionPrincipal("AICore", PlatformPrincipals.AICORE),
            PermissionPrincipal("AICore legacy", PlatformPrincipals.AICORE_LEGACY),
            PermissionPrincipal("App", appPackageName),
        )
        val pcsRequested = buildSet {
            if (pcsPccRequested) add(PlatformPermissions.PCC)
            add(PlatformPermissions.AICORE_BIND)
            if (pcsReadDeviceConfig != null) add(PlatformPermissions.READ_DEVICE_CONFIG)
        }
        val aicoreRequested = buildSet {
            if (aicorePccRequested) add(PlatformPermissions.PCC)
            add(PlatformPermissions.INTERNET)
            if (aicoreReadDeviceConfig != null) add(PlatformPermissions.READ_DEVICE_CONFIG)
        }
        val appRequested = buildSet {
            if (appBindRequested) add(PlatformPermissions.AICORE_BIND)
            if (appInternetRequested) add(PlatformPermissions.INTERNET)
        }
        val facts = mapOf(
            PlatformPrincipals.PCS to matrixFacts(
                PlatformPrincipals.PCS,
                pcsVisible,
                pcsRequested,
                buildMap {
                    put(PlatformPermissions.PCC, pcsPcc)
                    put(PlatformPermissions.AICORE_BIND, GrantState.GRANTED)
                    pcsReadDeviceConfig?.let { put(PlatformPermissions.READ_DEVICE_CONFIG, it) }
                },
            ),
            PlatformPrincipals.ASI to matrixFacts(
                PlatformPrincipals.ASI,
                true,
                emptySet(),
                emptyMap(),
            ),
            PlatformPrincipals.AICORE to matrixFacts(
                PlatformPrincipals.AICORE,
                aicoreVisible,
                aicoreRequested,
                buildMap {
                    put(PlatformPermissions.PCC, aicorePcc)
                    put(PlatformPermissions.AICORE_BIND, aicoreBind)
                    aicoreReadDeviceConfig?.let {
                        put(PlatformPermissions.READ_DEVICE_CONFIG, it)
                    }
                },
            ),
            PlatformPrincipals.AICORE_LEGACY to matrixFacts(
                PlatformPrincipals.AICORE_LEGACY,
                false,
                emptySet(),
                emptyMap(),
            ),
            appPackageName to matrixFacts(
                appPackageName,
                true,
                appRequested,
                mapOf(
                    PlatformPermissions.AICORE_BIND to appBind,
                    PlatformPermissions.INTERNET to appInternet,
                ),
            ),
        )
        return PermissionMatrixBuilder.build(
            principals = principals,
            permissions = PlatformPermissions.MATRIX,
            factsByPackage = facts,
        )
    }

    fun promptStatus(
        config: PromptModelConfig = PromptModelConfig.ALL.first(),
        status: FeatureStatusKind = FeatureStatusKind.PROBE_ERROR,
        featureId: Int? = 636,
        errorCode: Int? = 606,
        message: String? = "Feature 636 is not available.",
        baseModelName: String? = null,
        generatedText: String? = null,
        latencyMillis: Long = 12L,
    ): PromptConfigStatus = PromptConfigStatus(
        config = config,
        status = status,
        featureId = featureId ?: PromptFailureParsing.parseFeatureId(message),
        errorCode = errorCode,
        exceptionClass = "com.google.mlkit.genai.common.GenAiException",
        message = message,
        baseModelName = baseModelName,
        generatedText = generatedText,
        latencyMillis = latencyMillis,
        probedAtEpochMillis = NOW,
    )

    fun manifestRead(
        packageName: String,
        declaredPackage: String = packageName,
        signerSha256: List<String> = listOf("11:22:33"),
        components: List<ManifestComponentFacts> = emptyList(),
        pccElements: List<ManifestElementFacts> = emptyList(),
        usesPermissions: List<ManifestUsesPermissionFacts> = emptyList(),
    ): ManifestReadOutcome.Read = ManifestReadOutcome.Read(
        packageName = packageName,
        facts = ManifestPackageFacts(
            packageName = packageName,
            signerCertificateSha256 = signerSha256,
            sources = listOf(
                ManifestSourceFacts(
                    source = ManifestSourceId("base.apk", ManifestApkKind.BASE),
                    declaredPackage = declaredPackage,
                    sharedUserId = null,
                    versionName = "1.0.0",
                    components = components,
                    pccElements = pccElements,
                    usesPermissions = usesPermissions,
                ),
            ),
        ),
    )

    fun pccComponent(
        name: String = "com.example.PccService",
        kind: String = "service",
    ): ManifestComponentFacts = ManifestComponentFacts(
        kind = kind,
        name = name,
        exported = true,
        permission = null,
        process = null,
        privateComputeCore = ManifestBooleanValue.TRUE,
    )

    fun usesPermission(name: String): ManifestUsesPermissionFacts =
        ManifestUsesPermissionFacts(name = name, maxSdkVersion = null)

    fun associationElement(vararg packageNames: String): ManifestElementFacts =
        ManifestElementFacts(
            name = "component-access",
            packages = packageNames.map { packageName ->
                ManifestAssociationPackageFacts(
                    name = packageName,
                    certificateDigests = listOf("AB:CD:EF"),
                )
            },
        )

    fun bindResult(
        componentName: String = "com.google.android.apps.miphone.pcs.grpc.PcsGrpcService",
        outcome: PcsBindOutcome = PcsBindOutcome.BINDER_RETURNED,
        exceptionClass: String? = null,
        exceptionMessage: String? = null,
        interfaceDescriptor: String? = "com.example.IPcs",
    ): PcsBindResult = PcsBindResult(
        componentName = componentName,
        outcome = outcome,
        exceptionClass = exceptionClass,
        exceptionMessage = exceptionMessage,
        interfaceDescriptor = interfaceDescriptor,
        elapsedMillis = 25L,
        unbindCalls = 1,
    )
}

class FakeSummarizationProbe(
    var probeResult: SummarizationProbeResult = TestFixtures.probeResult(),
    var downloadResult: DownloadResult = DownloadResult(true, 10L, 100L, null),
    var summarizeResult: SummarizationRunResult = SummarizationRunResult(true, "summary", 5L, null),
) : SummarizationProbe {

    var probeCalls: Int = 0
        private set
    var downloadCalls: Int = 0
        private set
    var summarizeCalls: Int = 0
        private set

    override suspend fun probe(): SummarizationProbeResult {
        probeCalls++
        return probeResult
    }

    override suspend fun downloadFeature(
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): DownloadResult {
        downloadCalls++
        onProgress(downloadResult.bytesDownloaded ?: 0L, downloadResult.totalBytes)
        return downloadResult
    }

    override suspend fun summarize(note: String): SummarizationRunResult {
        summarizeCalls++
        return summarizeResult
    }

    override fun close() = Unit
}

class FakePackageFactsProvider(
    var packages: List<PackageFacts> = emptyList(),
    var grants: Map<Pair<String, String>, GrantState> = emptyMap(),
    var build: BuildFacts = TestFixtures.buildFacts(),
) : PackageFactsProvider {

    var inspectCalls: Int = 0
        private set

    override fun inspectPackages(): List<PackageFacts> {
        inspectCalls++
        return packages
    }

    override fun inspectPackage(packageName: String): PackageFacts =
        packages.firstOrNull { it.packageName == packageName }
            ?: PackageFacts(
                packageName = packageName,
                visibility = PackageVisibility.NOT_VISIBLE,
            )

    override fun checkPermission(packageName: String, permission: String): GrantState =
        grants[packageName to permission] ?: GrantState.DENIED

    override fun buildFacts(): BuildFacts = build
}

class FakePromptProbe(
    var results: Map<PromptModelConfig, PromptConfigStatus> = emptyMap(),
    var downloadResult: DownloadResult = DownloadResult(true, 10L, 100L, null),
) : PromptProbe {

    var probeCalls: Int = 0
        private set
    var downloadCalls: Int = 0
        private set
    var closed: Int = 0
        private set

    override suspend fun probe(config: PromptModelConfig): PromptConfigStatus {
        probeCalls++
        return results[config] ?: TestFixtures.promptStatus(config = config)
    }

    override suspend fun download(
        config: PromptModelConfig,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): DownloadResult {
        downloadCalls++
        onProgress(downloadResult.bytesDownloaded ?: 0L, downloadResult.totalBytes)
        return downloadResult
    }

    override fun close() {
        closed++
    }
}
