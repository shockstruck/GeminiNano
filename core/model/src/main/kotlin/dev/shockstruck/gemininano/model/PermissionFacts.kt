package dev.shockstruck.gemininano.model

/** Package names that participate in the provisioning investigation. */
object PlatformPrincipals {
    const val PCS = "com.google.android.as.oss"
    const val ASI = "com.google.android.as"
    const val AICORE = "com.google.android.aicore"
    const val AICORE_LEGACY = "com.google.android.apps.aicore"
    const val APP = "dev.shockstruck.gemininano"
}

/** Named permissions whose requested and granted state the matrix observes separately. */
object PlatformPermissions {
    const val PCC = "android.permission.PROVIDE_PRIVATE_COMPUTE_SERVICES"
    const val AICORE_BIND = "com.google.android.apps.aicore.service.BIND_SERVICE"
    const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"
    const val MANAGE_VIRTUAL_MACHINE = "android.permission.MANAGE_VIRTUAL_MACHINE"
    const val READ_DEVICE_CONFIG = "android.permission.READ_DEVICE_CONFIG"
    const val READ_RESTRICTED_STATS = "android.permission.READ_RESTRICTED_STATS"
    const val INTERNET = "android.permission.INTERNET"

    /** The exact permission columns of the requested-vs-granted matrix. */
    val MATRIX: List<String> = listOf(
        PCC,
        AICORE_BIND,
        WRITE_SECURE_SETTINGS,
        MANAGE_VIRTUAL_MACHINE,
        READ_DEVICE_CONFIG,
        READ_RESTRICTED_STATS,
        INTERNET,
    )
}

data class PermissionPrincipal(
    val label: String,
    val packageName: String,
)

/**
 * One matrix cell. Requested and granted are separate facts: `checkPermission` returns
 * DENIED both for "requested but not granted" and for "never requested", so the two
 * must never be collapsed into a single denied state.
 */
data class PermissionObservation(
    val principalLabel: String,
    val packageName: String,
    val permission: String,
    val packageVisible: Boolean,
    val requested: Boolean,
    val grant: GrantState,
) {
    val status: PermissionStatus
        get() = when {
            !packageVisible -> PermissionStatus.NOT_VISIBLE
            !requested -> PermissionStatus.NOT_REQUESTED
            grant == GrantState.GRANTED -> PermissionStatus.GRANTED
            grant == GrantState.DENIED -> PermissionStatus.DENIED
            else -> PermissionStatus.UNKNOWN
        }
}

enum class PermissionStatus(val label: String) {
    GRANTED("requested · granted"),
    DENIED("requested · denied"),
    NOT_REQUESTED("not requested"),
    UNKNOWN("requested · unknown"),
    NOT_VISIBLE("unknown (package not visible)"),
}

data class PermissionMatrix(
    val principals: List<PermissionPrincipal>,
    val permissions: List<String>,
    val observations: List<PermissionObservation>,
) {
    fun cell(packageName: String, permission: String): PermissionObservation? =
        observations.firstOrNull {
            it.packageName == packageName && it.permission == permission
        }

    fun observationsFor(packageName: String): List<PermissionObservation> =
        observations.filter { it.packageName == packageName }
}

/**
 * Pure assembly of the matrix from already-inspected package facts. `:core:android`
 * supplies the facts; everything here is JVM-testable.
 */
object PermissionMatrixBuilder {

    fun build(
        principals: List<PermissionPrincipal>,
        permissions: List<String>,
        factsByPackage: Map<String, PackageFacts>,
    ): PermissionMatrix {
        val observations = principals.flatMap { principal ->
            val facts = factsByPackage[principal.packageName]
            val visible = facts?.visible == true
            val requestedPermissions = facts?.requestedPermissions.orEmpty()
            permissions.map { permission ->
                PermissionObservation(
                    principalLabel = principal.label,
                    packageName = principal.packageName,
                    permission = permission,
                    packageVisible = visible,
                    requested = visible && requestedPermissions.contains(permission),
                    grant = facts?.permissionGrants
                        ?.firstOrNull { it.permission == permission }
                        ?.state
                        ?: GrantState.UNKNOWN,
                )
            }
        }
        return PermissionMatrix(
            principals = principals,
            permissions = permissions,
            observations = observations,
        )
    }
}

/** Rendering helpers kept in `:core:model` so the exact UI strings are unit-tested. */
object PermissionMatrixFormatting {

    fun renderCell(observation: PermissionObservation?): String =
        observation?.status?.label ?: "unknown"

    fun shortPermissionLabel(permission: String): String = when (permission) {
        PlatformPermissions.PCC -> "PROVIDE_PRIVATE_COMPUTE_SERVICES"
        PlatformPermissions.AICORE_BIND -> "BIND_SERVICE"
        PlatformPermissions.INTERNET -> "INTERNET"
        PlatformPermissions.WRITE_SECURE_SETTINGS -> "WRITE_SECURE_SETTINGS"
        PlatformPermissions.MANAGE_VIRTUAL_MACHINE -> "MANAGE_VIRTUAL_MACHINE"
        PlatformPermissions.READ_DEVICE_CONFIG -> "READ_DEVICE_CONFIG"
        PlatformPermissions.READ_RESTRICTED_STATS -> "READ_RESTRICTED_STATS"
        else -> permission
    }

    fun pcsPcc(matrix: PermissionMatrix): PermissionObservation? =
        matrix.cell(PlatformPrincipals.PCS, PlatformPermissions.PCC)

    fun renderPrincipalRow(matrix: PermissionMatrix, packageName: String): String {
        val cells = matrix.observationsFor(packageName).joinToString("; ") { observation ->
            "${shortPermissionLabel(observation.permission)}=${observation.status.label}"
        }
        return "$packageName: $cells"
    }
}
