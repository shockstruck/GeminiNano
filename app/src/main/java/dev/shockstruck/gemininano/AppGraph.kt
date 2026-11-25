package dev.shockstruck.gemininano

import android.content.Context
import android.os.Build
import dev.shockstruck.gemininano.core.android.AndroidPackageInspector
import dev.shockstruck.gemininano.core.android.PackageCatalog
import dev.shockstruck.gemininano.core.genai.MlKitSummarizationProbe
import dev.shockstruck.gemininano.core.genai.UnavailableSummarizationProbe
import dev.shockstruck.gemininano.data.LogExportManager
import dev.shockstruck.gemininano.data.ProbeCache
import dev.shockstruck.gemininano.data.SettingsRepository
import dev.shockstruck.gemininano.diagnostics.DecisionTree
import dev.shockstruck.gemininano.diagnostics.DiagnosticBus
import dev.shockstruck.gemininano.diagnostics.ExportMetadata
import dev.shockstruck.gemininano.diagnostics.OverviewPresenter
import dev.shockstruck.gemininano.model.BuildFacts
import dev.shockstruck.gemininano.model.DiagnosticSnapshot
import dev.shockstruck.gemininano.model.PackageFacts
import dev.shockstruck.gemininano.model.PackageFactsProvider
import dev.shockstruck.gemininano.model.PackageVisibility
import dev.shockstruck.gemininano.model.SummarizationProbe
import dev.shockstruck.gemininano.model.SummarizationProbeResult

/**
 * Manual dependency wiring. One graph per process; everything is local and replaceable
 * in tests (the fakes live in `:core:testing`).
 */
class AppGraph(context: Context) {

    private val appContext = context.applicationContext

    val settings = SettingsRepository(appContext)

    val diagnosticBus = DiagnosticBus(
        minimumSeverity = { settings.state.value.logVerbosity },
    )

    val packageFactsProvider: PackageFactsProvider = AndroidPackageInspector(appContext)

    val summarizationProbe: SummarizationProbe =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            MlKitSummarizationProbe(appContext)
        } else {
            UnavailableSummarizationProbe()
        }

    val probeCache = ProbeCache()

    private val overviewPresenter = OverviewPresenter(DecisionTree())

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
            pccPermissionState = packageFactsProvider.checkPermission(
                PackageCatalog.PCS_PACKAGE,
                PackageCatalog.PCC_PERMISSION,
            ),
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
        PackageFacts(packageName = packageName, visibility = PackageVisibility.NOT_VISIBLE)
}
