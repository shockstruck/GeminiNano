package dev.shockstruck.gemininano.model

/** Package names that participate in the provisioning investigation. */
object PlatformPrincipals {
    const val PCS = "com.google.android.as.oss"
    const val ASI = "com.google.android.as"
    const val AICORE = "com.google.android.aicore"
    const val AICORE_LEGACY = "com.google.android.apps.aicore"
    const val APP = "dev.shockstruck.gemininano"
    const val GMS = "com.google.android.gms"
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
    const val QUERY_ALL_PACKAGES = "android.permission.QUERY_ALL_PACKAGES"

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

    /** The only permissions reported for packages other than AICore and PCS. */
    val OTHER_PACKAGE_PERMISSIONS: List<String> = MATRIX + listOf(AICORE_BIND, QUERY_ALL_PACKAGES)
}

data class PermissionPrincipal(
    val label: String,
    val packageName: String,
)

/**
 * One matrix cell. Requested, the `requestedPermissionsFlags` bit, `checkPermission`
 * and the OS declaration are separate facts:
 *
 * - `checkPermission` returns DENIED both for "requested but not granted" and for
 *   "never requested", so the two are never collapsed.
 * - The two grant sources are compared, and a disagreement is its own status.
 * - A grant is never hidden by "not requested": an observed grant always shows.
 */
data class PermissionObservation(
    val principalLabel: String,
    val packageName: String,
    val permission: String,
    val packageVisibility: PackageVisibility,
    val requested: Boolean,
    val grant: GrantState,
    val requestedFlagsGrant: Boolean? = null,
    val declaration: PermissionDeclaration? = null,
    /** False when the package read never returned `requestedPermissions`. */
    val permissionsRead: Boolean = true,
) {
    val packageVisible: Boolean get() = packageVisibility == PackageVisibility.VISIBLE

    val anyGrantObserved: Boolean
        get() = grant == GrantState.GRANTED || requestedFlagsGrant == true

    val grantSourcesDisagree: Boolean
        get() = requestedFlagsGrant != null &&
            grant != GrantState.UNKNOWN &&
            (grant == GrantState.GRANTED) != requestedFlagsGrant

    val isSignatureOrPrivileged: Boolean
        get() = declaration?.isSignatureOrPrivileged == true

    val status: PermissionStatus
        get() = when {
            packageVisibility == PackageVisibility.NOT_VISIBLE -> PermissionStatus.NOT_VISIBLE
            packageVisibility == PackageVisibility.READ_ERROR -> PermissionStatus.READ_ERROR
            !permissionsRead -> PermissionStatus.NOT_READ
            !requested && anyGrantObserved -> PermissionStatus.GRANTED_NOT_REQUESTED
            requested && grantSourcesDisagree -> PermissionStatus.GRANT_DISAGREEMENT
            requested && anyGrantObserved -> PermissionStatus.GRANTED
            requested && grant == GrantState.DENIED -> PermissionStatus.DENIED
            requested && grant == GrantState.UNKNOWN -> PermissionStatus.UNKNOWN
            else -> PermissionStatus.NOT_REQUESTED
        }
}

enum class PermissionStatus(val label: String) {
    GRANTED("requested · granted"),
    DENIED("requested · denied"),
    GRANT_DISAGREEMENT("requested · grant sources disagree"),
    GRANTED_NOT_REQUESTED("grant observed · not requested"),
    NOT_REQUESTED("not requested"),
    UNKNOWN("requested · unknown"),
    NOT_VISIBLE("unknown (package not visible)"),
    READ_ERROR("unknown (package read error)"),
    NOT_READ("unknown (permissions not read)"),
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

    /** Requested-but-denied signature or privileged cells of this matrix. */
    fun requestedButDeniedSignatureOrPrivileged(): List<PermissionObservation> =
        observations.filter {
            it.requested && it.status == PermissionStatus.DENIED && it.isSignatureOrPrivileged
        }
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
            val visibility = facts?.visibility ?: PackageVisibility.NOT_VISIBLE
            val visible = visibility == PackageVisibility.VISIBLE
            val permissionsRead = visible && facts?.readCoverage?.permissions == true
            val requestedPermissions = facts?.requestedPermissions.orEmpty()
            permissions.map { permission ->
                val fact = facts?.permissionGrants?.firstOrNull { it.permission == permission }
                PermissionObservation(
                    principalLabel = principal.label,
                    packageName = principal.packageName,
                    permission = permission,
                    packageVisibility = visibility,
                    requested = visible && requestedPermissions.contains(permission),
                    grant = fact?.state ?: GrantState.UNKNOWN,
                    requestedFlagsGrant = fact?.requestedFlagsGrant,
                    declaration = fact?.declaration,
                    permissionsRead = permissionsRead,
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

    fun requestedFlagsLabel(value: Boolean?): String = when (value) {
        true -> "granted"
        false -> "not granted"
        null -> "not reported"
    }

    fun declarationLabel(declaration: PermissionDeclaration?): String = when {
        declaration == null -> "declaration not read"
        declaration.lookupErrorClass != null ->
            "declaration read error: ${declaration.lookupErrorClass}: " +
                (declaration.lookupErrorMessage ?: "no detail")

        !declaration.defined -> "not defined on this OS"
        else -> "declared by ${declaration.declaringPackage ?: "unknown"} · " +
            "protection=${declaration.protectionLabel ?: "unknown"}"
    }

    fun renderCell(observation: PermissionObservation?): String {
        val status = observation?.status ?: return "unknown"
        if (status != PermissionStatus.GRANT_DISAGREEMENT) return status.label
        return "${status.label} " +
            "(checkPermission=${observation.grant.label}, " +
            "requestedPermissionsFlags=${requestedFlagsLabel(observation.requestedFlagsGrant)})"
    }

    /** The two grant sources and the OS declaration, for one matrix cell. */
    fun verificationLabel(observation: PermissionObservation?): String {
        if (observation == null) return "requestedPermissionsFlags=not reported · declaration not read"
        return "requestedPermissionsFlags=${requestedFlagsLabel(observation.requestedFlagsGrant)} · " +
            declarationLabel(observation.declaration)
    }

    /** One requested-permission fact exactly as the System Components screen shows it. */
    fun renderPermissionFact(fact: PermissionGrantFacts): String {
        val request = if (fact.requested) "requested" else "not requested"
        val agreement = if (fact.grantSourcesDisagree) " · GRANT SOURCES DISAGREE" else ""
        return "${fact.permission}: $request · " +
            "checkPermission=${fact.state.label} · " +
            "requestedPermissionsFlags=${requestedFlagsLabel(fact.requestedFlagsGrant)}" +
            agreement + " · " +
            declarationLabel(fact.declaration)
    }

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
