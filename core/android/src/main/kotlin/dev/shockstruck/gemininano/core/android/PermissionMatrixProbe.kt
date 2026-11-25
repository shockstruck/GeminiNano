package dev.shockstruck.gemininano.core.android

import dev.shockstruck.gemininano.model.PackageFacts
import dev.shockstruck.gemininano.model.PackageFactsProvider
import dev.shockstruck.gemininano.model.PermissionMatrix
import dev.shockstruck.gemininano.model.PermissionMatrixBuilder
import dev.shockstruck.gemininano.model.PermissionPrincipal
import dev.shockstruck.gemininano.model.PlatformPermissions
import dev.shockstruck.gemininano.model.PlatformPrincipals

/**
 * Builds the requested-vs-granted matrix for PCS, ASI, both AICore package names and
 * this app. The app's own row is read separately because it is not part of the
 * inspected system package catalogue.
 */
class PermissionMatrixProbe(
    private val provider: PackageFactsProvider,
    private val appPackageName: String = PlatformPrincipals.APP,
) {
    val principals: List<PermissionPrincipal> = listOf(
        PermissionPrincipal("PCS (${PlatformPrincipals.PCS})", PlatformPrincipals.PCS),
        PermissionPrincipal("ASI (${PlatformPrincipals.ASI})", PlatformPrincipals.ASI),
        PermissionPrincipal("AICore (${PlatformPrincipals.AICORE})", PlatformPrincipals.AICORE),
        PermissionPrincipal(
            "AICore legacy (${PlatformPrincipals.AICORE_LEGACY})",
            PlatformPrincipals.AICORE_LEGACY,
        ),
        PermissionPrincipal("App ($appPackageName)", appPackageName),
    )

    fun build(packages: List<PackageFacts>): PermissionMatrix {
        val byPackage = packages.associateBy { it.packageName }.toMutableMap()
        byPackage[appPackageName] = provider.inspectPackage(appPackageName)
        return PermissionMatrixBuilder.build(
            principals = principals,
            permissions = MATRIX_PERMISSIONS,
            factsByPackage = byPackage,
        )
    }

    companion object {
        val MATRIX_PERMISSIONS: List<String> = PlatformPermissions.MATRIX
    }
}
