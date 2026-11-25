package dev.shockstruck.gemininano.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageFactsFormattingTest {

    private val visible = PackageFacts(
        packageName = "com.google.android.aicore",
        visibility = PackageVisibility.VISIBLE,
        versionName = "17.0.1",
        versionCode = 42L,
        installerPackage = "com.android.vending",
        signingSha256 = listOf("AB:CD"),
        targetSdk = 36,
        minSdk = 36,
        enabled = true,
    )

    @Test
    fun visiblePackageFormatsVersion() {
        assertEquals("17.0.1 (versionCode 42)", PackageFactsFormatting.versionLabel(visible))
        assertEquals("installed", PackageFactsFormatting.presenceLabel(visible))
    }

    @Test
    fun missingFactsAreLabelledUnknown() {
        val partial = visible.copy(versionName = null, versionCode = null, installerPackage = null)
        assertEquals("unknown (versionCode unknown)", PackageFactsFormatting.versionLabel(partial))
        assertEquals("unknown", PackageFactsFormatting.installerLabel(partial))
    }

    @Test
    fun invisiblePackageReportsVisibilityLimit() {
        val hidden = PackageFacts(
            packageName = "com.google.android.as.oss",
            visibility = PackageVisibility.NOT_VISIBLE,
        )
        assertEquals(PackageFactsFormatting.NOT_VISIBLE, PackageFactsFormatting.versionLabel(hidden))
        assertEquals(PackageFactsFormatting.NOT_VISIBLE, PackageFactsFormatting.presenceLabel(hidden))
        assertFalse(hidden.visible)
        assertTrue(PackageFactsFormatting.NOT_VISIBLE.contains("package visibility"))
    }

    @Test
    fun componentRenderingCarriesExportedPermissionAndActions() {
        val component = ComponentFacts(
            kind = ComponentKind.SERVICE,
            className = "com.example.Service",
            exported = true,
            requiredPermission = "com.example.PERMISSION",
            enabled = true,
            intentActions = listOf("android.intent.action.VIEW"),
            metaDataKeys = listOf("key"),
        )
        val rendered = PackageFactsFormatting.renderComponent(component)
        assertTrue(rendered.contains("service"))
        assertTrue(rendered.contains("com.example.Service"))
        assertTrue(rendered.contains("exported=true"))
        assertTrue(rendered.contains("permission=com.example.PERMISSION"))
        assertTrue(rendered.contains("actions=android.intent.action.VIEW"))
        assertTrue(rendered.contains("metadata=key"))
    }

    @Test
    fun componentWithoutPermissionSaysNone() {
        val component = ComponentFacts(
            kind = ComponentKind.ACTIVITY,
            className = "com.example.Activity",
            exported = false,
        )
        val rendered = PackageFactsFormatting.renderComponent(component)
        assertTrue(rendered.contains("permission=none"))
        assertTrue(rendered.contains("exported=false"))
    }

    @Test
    fun permissionGrantRendering() {
        assertEquals(
            "android.permission.INTERNET: denied",
            PackageFactsFormatting.renderPermissionGrant(
                PermissionGrantFacts("android.permission.INTERNET", GrantState.DENIED),
            ),
        )
        assertEquals(
            "android.permission.INTERNET: granted",
            PackageFactsFormatting.renderPermissionGrant(
                PermissionGrantFacts("android.permission.INTERNET", GrantState.GRANTED),
            ),
        )
    }

    @Test
    fun metadataRenderingShowsTypeAndValue() {
        assertEquals(
            "com.example.flag (Boolean) = true",
            PackageFactsFormatting.renderMetadata(MetadataFacts("com.example.flag", "true", "Boolean")),
        )
        assertEquals(
            "com.example.binary (byte[]) = …",
            PackageFactsFormatting.renderMetadata(MetadataFacts("com.example.binary", null, "byte[]")),
        )
    }

    @Test
    fun summaryLineIncludesPresenceAndVersion() {
        assertEquals(
            "com.google.android.aicore: installed; version 17.0.1 (versionCode 42)",
            PackageFactsFormatting.summaryLine(visible),
        )
    }
}
