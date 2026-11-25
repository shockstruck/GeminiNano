package dev.shockstruck.gemininano.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageReadCoverageTest {

    private val principals = listOf(
        PermissionPrincipal("PCS", PlatformPrincipals.PCS),
    )

    @Test
    fun aReducedReadRendersPermissionsAsNotReadNeverNotRequested() {
        val facts = PackageFacts(
            packageName = PlatformPrincipals.PCS,
            visibility = PackageVisibility.VISIBLE,
            coverage = PackageReadCoverage(
                permissions = false,
                components = true,
                signers = false,
                metadata = true,
            ),
            requestedPermissions = emptyList(),
        )
        val matrix = PermissionMatrixBuilder.build(
            principals = principals,
            permissions = listOf(PlatformPermissions.PCC),
            factsByPackage = mapOf(PlatformPrincipals.PCS to facts),
        )
        val pcc = matrix.cell(PlatformPrincipals.PCS, PlatformPermissions.PCC)!!
        assertFalse(pcc.permissionsRead)
        assertEquals(PermissionStatus.NOT_READ, pcc.status)
        assertEquals("unknown (permissions not read)", PermissionMatrixFormatting.renderCell(pcc))
        assertFalse(pcc.status == PermissionStatus.NOT_REQUESTED)
    }

    @Test
    fun formattingNeverTurnsAnUnreadFactGroupIntoNone() {
        val facts = PackageFacts(
            packageName = PlatformPrincipals.PCS,
            visibility = PackageVisibility.VISIBLE,
            coverage = PackageReadCoverage(
                permissions = false,
                components = false,
                signers = false,
                metadata = false,
            ),
            signingSha256 = emptyList(),
            requestedPermissions = emptyList(),
        )
        assertEquals(PackageFactsFormatting.NOT_READ, PackageFactsFormatting.requestedPermissionsLabel(facts))
        assertEquals(PackageFactsFormatting.NOT_READ, PackageFactsFormatting.componentsLabel(facts))
        assertEquals(PackageFactsFormatting.NOT_READ, PackageFactsFormatting.signingLabel(facts))
        assertEquals(PackageFactsFormatting.NOT_READ, PackageFactsFormatting.metadataLabel(facts))
        assertFalse(facts.permissionsRead)
        assertFalse(facts.componentsRead)
        assertFalse(facts.signersRead)
    }

    @Test
    fun aFullReadStillRendersNoneWhenTheListIsGenuinelyEmpty() {
        val facts = PackageFacts(
            packageName = PlatformPrincipals.PCS,
            visibility = PackageVisibility.VISIBLE,
        )
        assertEquals(PackageFactsFormatting.NONE, PackageFactsFormatting.requestedPermissionsLabel(facts))
        assertEquals(PackageFactsFormatting.NONE, PackageFactsFormatting.componentsLabel(facts))
        assertEquals(PackageFactsFormatting.UNKNOWN, PackageFactsFormatting.signingLabel(facts))
    }

    @Test
    fun defaultEffectiveEnabledSettingFallsBackToTheManifestValue() {
        assertEquals(true, ComponentEnabledSetting.effective(true, ComponentEnabledSetting.DEFAULT))
        assertEquals(false, ComponentEnabledSetting.effective(false, ComponentEnabledSetting.DEFAULT))
        assertEquals(true, ComponentEnabledSetting.effective(false, ComponentEnabledSetting.ENABLED))
        assertEquals(false, ComponentEnabledSetting.effective(true, ComponentEnabledSetting.DISABLED))
        assertEquals(false, ComponentEnabledSetting.effective(true, ComponentEnabledSetting.DISABLED_USER))
        assertNull(ComponentEnabledSetting.effective(true, null))
    }

    @Test
    fun bothEnabledValuesSurviveOnTheComponentFact() {
        val component = ComponentFacts(
            kind = ComponentKind.SERVICE,
            className = "com.example.Service",
            exported = true,
            enabled = true,
            manifestEffectiveEnabled = true,
            runtimeEnabledSetting = ComponentEnabledSetting.DISABLED,
            effectivelyEnabled = false,
        )
        assertTrue(component.manifestEffectiveEnabled)
        assertEquals(ComponentEnabledSetting.DISABLED, component.runtimeEnabledSetting)
        assertEquals(false, component.effectivelyEnabled)
        val rendered = PackageFactsFormatting.renderComponent(component)
        assertTrue(rendered.contains("manifestEffectiveEnabled=true"))
        assertTrue(rendered.contains("runtimeEnabledSetting=disabled"))
        assertTrue(rendered.contains("effectiveEnabled=false"))
    }

    @Test
    fun aFailedEnabledLookupIsUnknownNotDisabled() {
        val component = ComponentFacts(
            kind = ComponentKind.SERVICE,
            className = "com.example.Service",
            exported = true,
            enabled = true,
            manifestEffectiveEnabled = true,
            runtimeEnabledSetting = null,
            effectivelyEnabled = ComponentEnabledSetting.effective(
                manifestEffectiveEnabled = true,
                setting = null,
            ),
        )
        assertNull(component.effectivelyEnabled)
        assertTrue(PackageFactsFormatting.renderComponent(component).contains("effectiveEnabled=unknown"))
        assertEquals(
            PcsBindEligibility.ENABLED_UNKNOWN,
            PcsBindCatalog.eligibilityOf(component),
        )
    }
}
