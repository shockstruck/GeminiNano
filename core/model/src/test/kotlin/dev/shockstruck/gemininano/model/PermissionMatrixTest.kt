package dev.shockstruck.gemininano.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionMatrixTest {

    private val principals = listOf(
        PermissionPrincipal("PCS", PlatformPrincipals.PCS),
        PermissionPrincipal("AICore", PlatformPrincipals.AICORE),
        PermissionPrincipal("App", PlatformPrincipals.APP),
    )

    private fun facts(
        packageName: String,
        visible: Boolean = true,
        requested: Set<String> = emptySet(),
        grants: Map<String, GrantState> = emptyMap(),
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

    private fun matrix(
        pcs: PackageFacts = facts(PlatformPrincipals.PCS),
        aicore: PackageFacts = facts(PlatformPrincipals.AICORE),
        app: PackageFacts = facts(PlatformPrincipals.APP),
    ) = PermissionMatrixBuilder.build(
        principals = principals,
        permissions = PlatformPermissions.MATRIX,
        factsByPackage = mapOf(
            PlatformPrincipals.PCS to pcs,
            PlatformPrincipals.AICORE to aicore,
            PlatformPrincipals.APP to app,
        ),
    )

    @Test
    fun pcsPccDenialLeavesAicoreRowUnchanged() {
        val result = matrix(
            pcs = facts(
                PlatformPrincipals.PCS,
                requested = setOf(PlatformPermissions.PCC),
                grants = mapOf(PlatformPermissions.PCC to GrantState.DENIED),
            ),
            aicore = facts(PlatformPrincipals.AICORE),
        )
        val pcs = result.cell(PlatformPrincipals.PCS, PlatformPermissions.PCC)!!
        val aicore = result.cell(PlatformPrincipals.AICORE, PlatformPermissions.PCC)!!
        assertEquals(PermissionStatus.DENIED, pcs.status)
        assertEquals(PermissionStatus.NOT_REQUESTED, aicore.status)
        assertFalse(aicore.requested)
        assertEquals("not requested", PermissionMatrixFormatting.renderCell(aicore))
        assertNotEquals(pcs.status, aicore.status)
    }

    @Test
    fun aicorePccShowsNotRequestedEvenWhenCheckPermissionIsDenied() {
        val result = matrix(
            aicore = facts(
                PlatformPrincipals.AICORE,
                requested = setOf(PlatformPermissions.INTERNET),
                grants = mapOf(PlatformPermissions.PCC to GrantState.DENIED),
            ),
        )
        val aicore = result.cell(PlatformPrincipals.AICORE, PlatformPermissions.PCC)!!
        assertEquals(PermissionStatus.NOT_REQUESTED, aicore.status)
        assertEquals("not requested", PermissionMatrixFormatting.renderCell(aicore))
    }

    @Test
    fun appBindServiceIsIndependentOfPcsPcc() {
        val result = matrix(
            pcs = facts(
                PlatformPrincipals.PCS,
                requested = setOf(PlatformPermissions.PCC),
                grants = mapOf(PlatformPermissions.PCC to GrantState.DENIED),
            ),
            app = facts(
                PlatformPrincipals.APP,
                requested = setOf(PlatformPermissions.AICORE_BIND),
                grants = mapOf(PlatformPermissions.AICORE_BIND to GrantState.GRANTED),
            ),
        )
        assertEquals(PermissionStatus.DENIED, result.cell(PlatformPrincipals.PCS, PlatformPermissions.PCC)!!.status)
        assertEquals(
            PermissionStatus.GRANTED,
            result.cell(PlatformPrincipals.APP, PlatformPermissions.AICORE_BIND)!!.status,
        )
    }

    @Test
    fun requestedButDeniedRendersSeparatelyFromNotRequested() {
        val result = matrix(
            app = facts(
                PlatformPrincipals.APP,
                requested = setOf(PlatformPermissions.AICORE_BIND),
                grants = mapOf(PlatformPermissions.AICORE_BIND to GrantState.DENIED),
            ),
        )
        val appBind = result.cell(PlatformPrincipals.APP, PlatformPermissions.AICORE_BIND)!!
        assertEquals("requested · denied", PermissionMatrixFormatting.renderCell(appBind))
        assertEquals(PermissionStatus.DENIED, appBind.status)
    }

    @Test
    fun grantCanNeverOverrideNotRequested() {
        val result = matrix(
            app = facts(
                PlatformPrincipals.APP,
                requested = emptySet(),
                grants = mapOf(PlatformPermissions.INTERNET to GrantState.GRANTED),
            ),
        )
        val appInternet = result.cell(PlatformPrincipals.APP, PlatformPermissions.INTERNET)!!
        assertEquals(PermissionStatus.NOT_REQUESTED, appInternet.status)
        assertFalse(appInternet.requested)
    }

    @Test
    fun invisiblePackageIsNotReportedAsNotRequested() {
        val result = matrix(
            pcs = facts(PlatformPrincipals.PCS, visible = false),
        )
        val pcs = result.cell(PlatformPrincipals.PCS, PlatformPermissions.PCC)!!
        assertEquals(PermissionStatus.NOT_VISIBLE, pcs.status)
        assertTrue(PermissionMatrixFormatting.renderCell(pcs).contains("not visible"))
    }

    @Test
    fun offlineAppManifestHasNoInternetRequest() {
        val result = matrix(
            app = facts(PlatformPrincipals.APP, requested = emptySet()),
        )
        val internet = result.cell(PlatformPrincipals.APP, PlatformPermissions.INTERNET)!!
        assertEquals(PermissionStatus.NOT_REQUESTED, internet.status)
        assertEquals("not requested", PermissionMatrixFormatting.renderCell(internet))
    }

    @Test
    fun principalRowRendersEveryPermission() {
        val result = matrix(
            app = facts(
                PlatformPrincipals.APP,
                requested = setOf(PlatformPermissions.AICORE_BIND, PlatformPermissions.INTERNET),
                grants = mapOf(
                    PlatformPermissions.AICORE_BIND to GrantState.GRANTED,
                    PlatformPermissions.INTERNET to GrantState.GRANTED,
                ),
            ),
        )
        val row = PermissionMatrixFormatting.renderPrincipalRow(result, PlatformPrincipals.APP)
        PlatformPermissions.MATRIX.forEach { permission ->
            assertTrue(row.contains(PermissionMatrixFormatting.shortPermissionLabel(permission)))
        }
        assertTrue(row.contains("BIND_SERVICE=requested · granted"))
        assertTrue(row.contains("INTERNET=requested · granted"))
    }
}
