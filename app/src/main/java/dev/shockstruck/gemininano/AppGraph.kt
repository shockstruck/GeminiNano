package dev.shockstruck.gemininano

import android.content.Context
import android.os.Build
import dev.shockstruck.gemininano.core.android.AndroidPackageInspector
import dev.shockstruck.gemininano.core.android.AppUserFactsSource
import dev.shockstruck.gemininano.core.android.InstalledCodePackageReader
import dev.shockstruck.gemininano.core.android.InstalledManifestReader
import dev.shockstruck.gemininano.core.android.PackageCatalog
import dev.shockstruck.gemininano.core.android.PcsActivityLauncher
import dev.shockstruck.gemininano.core.android.PcsSurfaceInspector
import dev.shockstruck.gemininano.core.android.PermissionMatrixProbe
import dev.shockstruck.gemininano.core.android.SystemFeatureFacts
import dev.shockstruck.gemininano.core.android.SafeBindProbe
import dev.shockstruck.gemininano.core.genai.MlKitFeatureSuite
import dev.shockstruck.gemininano.core.genai.MlKitPromptProbe
import dev.shockstruck.gemininano.core.genai.MlKitSummarizationProbe
import dev.shockstruck.gemininano.core.genai.UnavailableFeatureSuite
import dev.shockstruck.gemininano.core.genai.UnavailablePromptProbe
import dev.shockstruck.gemininano.core.genai.UnavailableSummarizationProbe
import dev.shockstruck.gemininano.data.LogExportManager
import dev.shockstruck.gemininano.data.PcsBindHistoryStore
import dev.shockstruck.gemininano.data.ProbeCache
import dev.shockstruck.gemininano.data.SettingsRepository
import dev.shockstruck.gemininano.diagnostics.BoundedProbeRunner
import dev.shockstruck.gemininano.diagnostics.DecisionTree
import dev.shockstruck.gemininano.diagnostics.DiagnosticBus
import dev.shockstruck.gemininano.diagnostics.ExportMetadata
import dev.shockstruck.gemininano.diagnostics.InstalledCodeEvidenceCollector
import dev.shockstruck.gemininano.diagnostics.OverviewPresenter
import dev.shockstruck.gemininano.diagnostics.ProvisioningDependencyEvaluator
import dev.shockstruck.gemininano.diagnostics.ProvisioningDiagnosticsPresenter
import dev.shockstruck.gemininano.model.BuildFacts
import dev.shockstruck.gemininano.model.DiagnosticSnapshot
import dev.shockstruck.gemininano.model.PackageFacts
import dev.shockstruck.gemininano.model.PackageFactsProvider
import dev.shockstruck.gemininano.model.PackageReadCoverage
import dev.shockstruck.gemininano.model.PackageVisibility
import dev.shockstruck.gemininano.model.PromptProbe
import dev.shockstruck.gemininano.model.SummarizationProbe
import dev.shockstruck.gemininano.model.SummarizationProbeResult

/**
 * Manual dependency wiring. One graph per process; everything is local and replaceable
 * in tests (the fakes live in `:core:testing`).
 */
class AppGraph(context: Context) {

    private val appContext = context.applicationContext

    val settings = SettingsRepository(appContext)

    /** Bind outcomes kept in app-private storage, with the last five per component. */
    val pcsBindHistoryStore = PcsBindHistoryStore(appContext)

    val diagnosticBus = DiagnosticBus(
        minimumSeverity = { settings.state.value.logVerbosity },
    )

    val packageFactsProvider: PackageFactsProvider = AndroidPackageInspector(appContext)

    /** Reads the installed APK manifests of AICore, PCS and ASI; blocking, off the main thread. */
    val manifestReader = InstalledManifestReader(appContext)

    /** Reads the installed AICore and PCS dex code as narrow facts; blocking, off the main thread. */
    val installedCodeReader = InstalledCodePackageReader(appContext)

    /**
     * Collects one installed-code read per package, turning any failure into that
     * package's own outcome, and records how long each package took.
     */
    val installedCodeCollector = InstalledCodeEvidenceCollector(
        read = installedCodeReader::read,
        elapsedClock = { android.os.SystemClock.elapsedRealtime() },
    )

    /** Whether this app runs in the system (owner) user; public UserManager call only. */
    val appUserFacts = AppUserFactsSource(appContext)

    /** Pure ordered evaluation of the provisioning chain the public source trace laid out. */
    val provisioningDependencies = ProvisioningDependencyEvaluator()

    val permissionMatrixProbe = PermissionMatrixProbe(
        provider = packageFactsProvider,
        appPackageName = BuildConfig.APPLICATION_ID,
    )

    val pcsSurfaceInspector = PcsSurfaceInspector(appContext)

    val safeBindProbe = SafeBindProbe(appContext)

    val pcsActivityLauncher = PcsActivityLauncher(appContext)

    val systemFeatureFacts = SystemFeatureFacts(appContext)

    val summarizationProbe: SummarizationProbe =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            MlKitSummarizationProbe(appContext)
        } else {
            UnavailableSummarizationProbe()
        }

    val promptProbe: PromptProbe =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            MlKitPromptProbe()
        } else {
            UnavailablePromptProbe()
        }

    val featureSuite: dev.shockstruck.gemininano.model.GenAiFeatureSuite =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            MlKitFeatureSuite(appContext)
        } else {
            UnavailableFeatureSuite()
        }

    val probeCache = ProbeCache()

    /** Every probe runs isolated behind its own timeout. */
    val probeRunner = BoundedProbeRunner(
        elapsedClock = { android.os.SystemClock.elapsedRealtime() },
    )

    private val overviewPresenter = OverviewPresenter(DecisionTree())

    val provisioningPresenter = ProvisioningDiagnosticsPresenter()

    private val buildFacts: BuildFacts get() = packageFactsProvider.buildFacts()

    fun snapshot(probe: SummarizationProbeResult?): DiagnosticSnapshot {
        val packages = packageFactsProvider.inspectPackages()
        return buildSnapshot(packages, probe)
    }

    fun buildSnapshot(
        packages: List<PackageFacts>,
        probe: SummarizationProbeResult?,
    ): DiagnosticSnapshot {
        val aicore = packages.firstOrNull {
            it.packageName in PackageCatalog.AICORE_PACKAGES && it.visible
        } ?: packages.firstOrNull { it.packageName in PackageCatalog.AICORE_PACKAGES }
            ?: invisible(PackageCatalog.AICORE_PACKAGE)
        val pcs = packages.firstOrNull { it.packageName == PackageCatalog.PCS_PACKAGE }
            ?: invisible(PackageCatalog.PCS_PACKAGE)
        return DiagnosticSnapshot(
            aicore = aicore,
            pcs = pcs,
            permissionMatrix = permissionMatrixProbe.build(packages),
            probe = probe,
            lastFailure = diagnosticBus.lastFailure(),
            build = buildFacts,
        )
    }

    fun presentOverview(packages: List<PackageFacts>, probe: SummarizationProbeResult?) =
        overviewPresenter.present(buildSnapshot(packages, probe))

    fun exportMetadata(nowMillis: Long = System.currentTimeMillis()): ExportMetadata =
        ExportMetadata(
            applicationId = BuildConfig.APPLICATION_ID,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE.toLong(),
            exportedAtEpochMillis = nowMillis,
            build = buildFacts,
        )

    fun logExportManager(): LogExportManager = LogExportManager(
        appContext,
        eventsProvider = { diagnosticBus.snapshot() },
        metadataProvider = { exportMetadata() },
    )

    private fun invisible(packageName: String) =
        PackageFacts(
            packageName = packageName,
            visibility = PackageVisibility.NOT_VISIBLE,
            coverage = PackageReadCoverage.NONE,
        )
}
