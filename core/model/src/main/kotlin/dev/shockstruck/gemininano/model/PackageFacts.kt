package dev.shockstruck.gemininano.model

/** How much of a package Android let this app see. */
enum class PackageVisibility {
    VISIBLE,
    NOT_VISIBLE,
}

/** Observable grant state of a named permission for another package. */
enum class GrantState(val label: String) {
    GRANTED("granted"),
    DENIED("denied"),
    UNKNOWN("unknown"),
}

enum class ComponentKind(val label: String) {
    ACTIVITY("activity"),
    SERVICE("service"),
    RECEIVER("receiver"),
    PROVIDER("provider"),
}

data class PermissionGrantFacts(
    val permission: String,
    val state: GrantState,
    val requested: Boolean = true,
)

data class ComponentFacts(
    val kind: ComponentKind,
    val className: String,
    val exported: Boolean,
    val requiredPermission: String? = null,
    val enabled: Boolean = true,
    val intentActions: List<String> = emptyList(),
    val authorities: List<String> = emptyList(),
    val metaDataKeys: List<String> = emptyList(),
)

data class MetadataFacts(
    val key: String,
    val value: String?,
    val type: String,
)

/**
 * Everything an ordinary app can observe about another installed package.
 *
 * A package can be absent *or* hidden by Android's package visibility rules; both
 * cases surface as [PackageVisibility.NOT_VISIBLE] and the UI must say so instead of
 * claiming the package is missing.
 */
data class PackageFacts(
    val packageName: String,
    val visibility: PackageVisibility,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val installerPackage: String? = null,
    val signingSha256: List<String> = emptyList(),
    val targetSdk: Int? = null,
    val minSdk: Int? = null,
    val enabled: Boolean? = null,
    val requestedPermissions: List<String> = emptyList(),
    val permissionGrants: List<PermissionGrantFacts> = emptyList(),
    val components: List<ComponentFacts> = emptyList(),
    val metadata: List<MetadataFacts> = emptyList(),
) {
    val visible: Boolean get() = visibility == PackageVisibility.VISIBLE
}
