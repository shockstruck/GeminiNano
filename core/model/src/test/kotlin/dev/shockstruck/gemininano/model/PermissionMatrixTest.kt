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
        requestedFlagsGrants: Map<String, Boolean?> = emptyMap(),
        declarations: Map<String, PermissionDeclaration> = emptyMap(),
    ) = PackageFacts(
        packageName = packageName,
        visibility = if (visible) PackageVisibility.VISIBLE else PackageVisibility.NOT_VISIBLE,
        requestedPermissions = PlatformPermissions.MATRIX.filter { it in requested },
        permissionGrants = PlatformPermissions.MATRIX.map { permission ->
            PermissionGrantFacts(
                permission = permission,
                state = grants[permission] ?: GrantState.DENIED,
                requested = permission in requested,
                requestedFlagsGrant = if (requestedFlagsGrants.containsKey(permission)) {
                    requestedFlagsGrants[permission]
                } else {
                    permission in requested && grants[permission] == GrantState.GRANTED
                },
                declaration = declarations[permission],
            )
        },
    )

    private fun declaration(
        base: Int = PermissionProtection.BASE_SIGNATURE,
        flags: Int = PermissionProtection.FLAG_PRIVILEGED,
    ) = PermissionDeclaration(
        defined = true,
        declaringPackage = "android",
        protectionBase = base,
        protectionFlags = flags,
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
    fun anObservedGrantIsShownEvenWhenNotRequested() {
        val result = matrix(
            app = facts(
                PlatformPrincipals.APP,
                requested = emptySet(),
                grants = mapOf(PlatformPermissions.INTERNET to GrantState.GRANTED),
                requestedFlagsGrants = mapOf(PlatformPermissions.INTERNET to true),
            ),
        )
        val appInternet = result.cell(PlatformPrincipals.APP, PlatformPermissions.INTERNET)!!
        assertFalse(appInternet.requested)
        assertEquals(PermissionStatus.GRANTED_NOT_REQUESTED, appInternet.status)
        assertEquals(
            "grant observed · not requested",
            PermissionMatrixFormatting.renderCell(appInternet),
        )
    }

    @Test
    fun theTwoGrantSourcesDisagreementIsItsOwnStatus() {
        val result = matrix(
            pcs = facts(
                PlatformPrincipals.PCS,
                requested = setOf(PlatformPermissions.PCC),
                grants = mapOf(PlatformPermissions.PCC to GrantState.GRANTED),
                requestedFlagsGrants = mapOf(PlatformPermissions.PCC to false),
            ),
        )
        val pcc = result.cell(PlatformPrincipals.PCS, PlatformPermissions.PCC)!!
        assertEquals(PermissionStatus.GRANT_DISAGREEMENT, pcc.status)
        assertTrue(pcc.grantSourcesDisagree)
        val rendered = PermissionMatrixFormatting.renderCell(pcc)
        assertTrue(rendered.contains("grant sources disagree"))
        assertTrue(rendered.contains("checkPermission=granted"))
        assertTrue(rendered.contains("requestedPermissionsFlags=not granted"))
    }

    @Test
    fun aPermissionFactShowsADisagreementExplicitly() {
        val fact = PermissionGrantFacts(
            permission = PlatformPermissions.PCC,
            state = GrantState.GRANTED,
            requested = true,
            requestedFlagsGrant = false,
            declaration = declaration(),
        )
        assertTrue(fact.grantSourcesDisagree)
        val rendered = PermissionMatrixFormatting.renderPermissionFact(fact)
        assertTrue(rendered.contains("checkPermission=granted"))
        assertTrue(rendered.contains("requestedPermissionsFlags=not granted"))
        assertTrue(rendered.contains("GRANT SOURCES DISAGREE"))
        assertTrue(rendered.contains("declared by android · protection=signature|privileged"))
    }

    @Test
    fun aReadErrorIsNeverReportedAsNotVisible() {
        val result = matrix(
            pcs = PackageFacts(
                packageName = PlatformPrincipals.PCS,
                visibility = PackageVisibility.READ_ERROR,
                readError = PackageReadError(
                    exceptionClass = "java.lang.IllegalStateException",
                    message = "boom",
                    attempts = 3,
                ),
            ),
        )
        val pcc = result.cell(PlatformPrincipals.PCS, PlatformPermissions.PCC)!!
        assertEquals(PermissionStatus.READ_ERROR, pcc.status)
        assertEquals(
            "unknown (package read error)",
            PermissionMatrixFormatting.renderCell(pcc),
        )
    }

    @Test
    fun requestedButDeniedSignatureOrPrivilegedIsListedWithItsDeclaration() {
        val result = matrix(
            pcs = facts(
                PlatformPrincipals.PCS,
                requested = setOf(PlatformPermissions.PCC),
                grants = mapOf(PlatformPermissions.PCC to GrantState.DENIED),
                requestedFlagsGrants = mapOf(PlatformPermissions.PCC to false),
                declarations = mapOf(PlatformPermissions.PCC to declaration()),
            ),
        )
        val pcc = result.cell(PlatformPrincipals.PCS, PlatformPermissions.PCC)!!
        assertEquals(PermissionStatus.DENIED, pcc.status)
        assertTrue(pcc.isSignatureOrPrivileged)
        assertEquals(listOf(pcc), result.requestedButDeniedSignatureOrPrivileged())
        assertEquals(
            "declared by android · protection=signature|privileged",
            PermissionMatrixFormatting.declarationLabel(pcc.declaration),
        )
    }

    @Test
    fun anUndefinedPermissionSaysNotDefinedOnThisOs() {
        val result = matrix(
            aicore = facts(
                PlatformPrincipals.AICORE,
                requested = setOf(PlatformPermissions.READ_DEVICE_CONFIG),
                grants = mapOf(PlatformPermissions.READ_DEVICE_CONFIG to GrantState.DENIED),
                requestedFlagsGrants = mapOf(PlatformPermissions.READ_DEVICE_CONFIG to false),
                declarations = mapOf(
                    PlatformPermissions.READ_DEVICE_CONFIG to PermissionDeclaration(defined = false),
                ),
            ),
        )
        val observation = result.cell(PlatformPrincipals.AICORE, PlatformPermissions.READ_DEVICE_CONFIG)!!
        assertEquals(
            "not defined on this OS",
            PermissionMatrixFormatting.declarationLabel(observation.declaration),
        )
        assertTrue(
            PermissionMatrixFormatting.verificationLabel(observation)
                .contains("requestedPermissionsFlags=not granted"),
        )
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
