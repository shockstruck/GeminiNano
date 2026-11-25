package dev.shockstruck.gemininano.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcsActivityCatalogTest {

    private fun pcs(vararg components: ComponentFacts, coverage: PackageReadCoverage = PackageReadCoverage.FULL) =
        PackageFacts(
            packageName = PlatformPrincipals.PCS,
            visibility = PackageVisibility.VISIBLE,
            coverage = coverage,
            components = components.toList(),
        )

    private fun activity(
        name: String,
        exported: Boolean = true,
        effectivelyEnabled: Boolean? = true,
        requiredPermission: String? = null,
    ) = ComponentFacts(
        kind = ComponentKind.ACTIVITY,
        className = name,
        exported = exported,
        requiredPermission = requiredPermission,
        enabled = effectivelyEnabled != false,
        manifestEffectiveEnabled = effectivelyEnabled != false,
        runtimeEnabledSetting = ComponentEnabledSetting.DEFAULT,
        effectivelyEnabled = effectivelyEnabled,
    )

    @Test
    fun settingsAndNetworkLogAreLaunchableWhenTheRuntimeFactsAllowIt() {
        val targets = PcsActivityCatalog.targets(
            pcs(
                activity(PcsActivityCatalog.SETTINGS),
                activity(PcsActivityCatalog.NETWORK_USAGE_LOG),
                activity(PcsActivityCatalog.NETWORK_USAGE_ITEM_DETAILS),
            ),
        )
        val settings = targets.first { it.label == "PCS settings" }
        val log = targets.first { it.label == "Network usage log" }
        assertTrue(settings.launchable)
        assertTrue(log.launchable)
        assertTrue(settings.detail.contains("explicit tap only"))
    }

    @Test
    fun itemDetailsIsNeverLaunchedBecauseItsExtrasAreUnknown() {
        val target = PcsActivityCatalog.targets(
            pcs(activity(PcsActivityCatalog.NETWORK_USAGE_ITEM_DETAILS)),
        ).first { it.label == "Network usage item details" }
        assertTrue(target.declared)
        assertTrue(target.exported)
        assertFalse(target.launchable)
        assertTrue(target.detail.contains("extras"))
        assertTrue(target.detail.contains("not publicly known"))
    }

    @Test
    fun anActivityNotDeclaredGetsNoLaunchControl() {
        val target = PcsActivityCatalog.targets(pcs()).first { it.label == "PCS settings" }
        assertFalse(target.declared)
        assertFalse(target.launchable)
        assertTrue(target.detail.contains("not declared"))
    }

    @Test
    fun exportedEnabledAndPermissionFreeAreAllRequired() {
        val notExported = PcsActivityCatalog.targets(
            pcs(activity(PcsActivityCatalog.SETTINGS, exported = false)),
        ).first()
        val disabled = PcsActivityCatalog.targets(
            pcs(activity(PcsActivityCatalog.SETTINGS, effectivelyEnabled = false)),
        ).first()
        val unknown = PcsActivityCatalog.targets(
            pcs(activity(PcsActivityCatalog.SETTINGS, effectivelyEnabled = null)),
        ).first()
        val permission = PcsActivityCatalog.targets(
            pcs(activity(PcsActivityCatalog.SETTINGS, requiredPermission = "com.example.P")),
        ).first()
        assertFalse(notExported.launchable)
        assertFalse(disabled.launchable)
        assertFalse(unknown.launchable)
        assertFalse(permission.launchable)
        assertEquals("declared, not exported; no launch attempted", notExported.detail.substringBefore(" ("))
        assertTrue(disabled.detail.contains("not effectively enabled"))
        assertTrue(unknown.detail.contains("enabled state unknown"))
        assertTrue(permission.detail.contains("requires com.example.P"))
    }

    @Test
    fun componentsNotReadMeansNoLaunchAndNoAbsenceClaim() {
        val targets = PcsActivityCatalog.targets(
            pcs(coverage = PackageReadCoverage(components = false)),
        )
        assertEquals(PcsActivityCatalog.KNOWN.size, targets.size)
        targets.forEach { target ->
            assertFalse(target.launchable)
            assertFalse(target.declared)
            assertTrue(target.detail.contains("components not read"))
        }
    }
}
