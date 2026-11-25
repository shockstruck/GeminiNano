package dev.shockstruck.gemininano.model

/** How much of a package Android let this app see. */
enum class PackageVisibility {
    VISIBLE,

    /**
     * `PackageManager.NameNotFoundException` only: the package is absent, or Android's
     * package visibility hides it. No other throwable may be mapped here.
     */
    NOT_VISIBLE,

    /** A read threw something other than `NameNotFoundException`; see [PackageReadError]. */
    READ_ERROR,
}

/** A package read that failed for a reason other than "not visible". */
data class PackageReadError(
    val exceptionClass: String,
    val message: String,
    val attempts: Int,
) {
    val label: String get() = "$exceptionClass: $message (after $attempts read attempt(s))"
}

sealed class PackageReadOutcome<out T> {
    data class Success<T>(val value: T, val attempt: Int) : PackageReadOutcome<T>()

    /** `NameNotFoundException`: the only outcome called "not visible". */
    data object NotVisible : PackageReadOutcome<Nothing>()

    /** Every flag-set attempt failed with a non-`NameNotFoundException` throwable. */
    data class Failed(val error: PackageReadError) : PackageReadOutcome<Nothing>()
}

/**
 * The package read ladder: a full flag set, then smaller ones. `NameNotFoundException`
 * ends the ladder as [PackageReadOutcome.NotVisible]; any other throwable is retried
 * with the next set and only becomes a read error when every attempt failed.
 */
object PackageReads {

    fun <T> withFallbacks(
        attempts: List<() -> T>,
        isNameNotFound: (Throwable) -> Boolean,
    ): PackageReadOutcome<T> {
        var last: Throwable? = null
        attempts.forEachIndexed { index, attempt ->
            try {
                return PackageReadOutcome.Success(attempt(), index)
            } catch (t: Throwable) {
                if (isNameNotFound(t)) return PackageReadOutcome.NotVisible
                last = t
            }
        }
        val failure = last ?: IllegalStateException("no package read attempt was made")
        return PackageReadOutcome.Failed(
            PackageReadError(
                exceptionClass = failure.javaClass.name,
                message = failure.message ?: failure.javaClass.simpleName,
                attempts = attempts.size,
            ),
        )
    }
}

/** Observable grant state of a named permission for another package. */
enum class GrantState(val label: String) {
    GRANTED("granted"),
    DENIED("denied"),
    UNKNOWN("unknown"),
}

/**
 * The OS's own definition of a permission, read with `getPermissionInfo`. [defined]
 * is false for `NameNotFoundException`, which is the only evidence that the permission
 * does not exist on this OS.
 */
data class PermissionDeclaration(
    val defined: Boolean,
    val declaringPackage: String? = null,
    val protectionBase: Int? = null,
    val protectionFlags: Int = 0,
    val lookupErrorClass: String? = null,
    val lookupErrorMessage: String? = null,
) {
    val protectionLabel: String?
        get() = protectionBase?.let { PermissionProtection.label(it, protectionFlags) }

    val isSignatureOrPrivileged: Boolean
        get() = protectionBase == PermissionProtection.BASE_SIGNATURE ||
            protectionBase == PermissionProtection.BASE_SIGNATURE_OR_SYSTEM ||
            protectionFlags and PermissionProtection.FLAG_PRIVILEGED != 0
}

/** Bit meanings mirrored from `android.content.pm.PermissionInfo` for pure-JVM display. */
object PermissionProtection {
    const val BASE_NORMAL = 0
    const val BASE_DANGEROUS = 1
    const val BASE_SIGNATURE = 2
    const val BASE_SIGNATURE_OR_SYSTEM = 3
    const val BASE_INTERNAL = 4

    const val FLAG_PRIVILEGED = 0x10
    const val FLAG_DEVELOPMENT = 0x20
    const val FLAG_APPOP = 0x40
    const val FLAG_PRE23 = 0x80
    const val FLAG_INSTALLER = 0x100
    const val FLAG_VERIFIER = 0x200
    const val FLAG_PREINSTALLED = 0x400
    const val FLAG_SETUP = 0x800
    const val FLAG_INSTANT = 0x1000
    const val FLAG_RUNTIME_ONLY = 0x2000

    fun baseLabel(base: Int): String = when (base) {
        BASE_NORMAL -> "normal"
        BASE_DANGEROUS -> "dangerous"
        BASE_SIGNATURE -> "signature"
        BASE_SIGNATURE_OR_SYSTEM -> "signatureOrSystem"
        BASE_INTERNAL -> "internal"
        else -> "base=$base"
    }

    fun flagLabels(flags: Int): List<String> = buildList {
        if (flags and FLAG_PRIVILEGED != 0) add("privileged")
        if (flags and FLAG_DEVELOPMENT != 0) add("development")
        if (flags and FLAG_APPOP != 0) add("appop")
        if (flags and FLAG_PRE23 != 0) add("pre23")
        if (flags and FLAG_INSTALLER != 0) add("installer")
        if (flags and FLAG_VERIFIER != 0) add("verifier")
        if (flags and FLAG_PREINSTALLED != 0) add("preinstalled")
        if (flags and FLAG_SETUP != 0) add("setup")
        if (flags and FLAG_INSTANT != 0) add("instant")
        if (flags and FLAG_RUNTIME_ONLY != 0) add("runtimeOnly")
        val known = FLAG_PRIVILEGED or FLAG_DEVELOPMENT or FLAG_APPOP or FLAG_PRE23 or
            FLAG_INSTALLER or FLAG_VERIFIER or FLAG_PREINSTALLED or FLAG_SETUP or
            FLAG_INSTANT or FLAG_RUNTIME_ONLY
        val unknown = flags and known.inv()
        if (unknown != 0) add("other=0x${Integer.toHexString(unknown)}")
    }

    fun label(base: Int, flags: Int): String {
        val parts = listOf(baseLabel(base)) + flagLabels(flags)
        return parts.joinToString("|")
    }
}

/**
 * One named permission as observed for one package: the request list, the
 * `requestedPermissionsFlags` grant bit, `checkPermission`, and the OS declaration.
 */
data class PermissionGrantFacts(
    val permission: String,
    val state: GrantState,
    val requested: Boolean = true,
    val requestedFlagsGrant: Boolean? = null,
    val declaration: PermissionDeclaration? = null,
) {
    /** The two independent grant sources disagree; neither is silently preferred. */
    val grantSourcesDisagree: Boolean
        get() = requestedFlagsGrant != null &&
            state != GrantState.UNKNOWN &&
            (state == GrantState.GRANTED) != requestedFlagsGrant

    /** `checkPermission` or the request flag says granted, whatever `requested` says. */
    val anyGrantObserved: Boolean
        get() = state == GrantState.GRANTED || requestedFlagsGrant == true

    val isRequestedButDeniedSignatureOrPrivileged: Boolean
        get() = requested &&
            state == GrantState.DENIED &&
            declaration?.isSignatureOrPrivileged == true
}

enum class ComponentKind(val label: String) {
    ACTIVITY("activity"),
    SERVICE("service"),
    RECEIVER("receiver"),
    PROVIDER("provider"),
}

data class ComponentFacts(
    val kind: ComponentKind,
    val className: String,
    val exported: Boolean,
    val requiredPermission: String? = null,
    val enabled: Boolean = true,
    /** `ComponentInfo.isEnabled()`: the component's own flag plus the application's. */
    val effectivelyEnabled: Boolean = true,
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
 * claiming the package is missing. Any other failure is [PackageVisibility.READ_ERROR]
 * with its own class and message, never "not visible".
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
    val readError: PackageReadError? = null,
) {
    val visible: Boolean get() = visibility == PackageVisibility.VISIBLE

    /** Requested, denied, and declared signature or privileged. */
    val requestedButDeniedSignatureOrPrivileged: List<PermissionGrantFacts>
        get() = permissionGrants.filter { it.isRequestedButDeniedSignatureOrPrivileged }
}
