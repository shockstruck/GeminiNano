package dev.shockstruck.gemininano.model

/** Which APK inside an installed package a manifest was read from. */
enum class ManifestApkKind {
    BASE,
    SPLIT,
}

/** Identifies one APK manifest requested from a package. */
data class ManifestSourceId(
    val label: String,
    val kind: ManifestApkKind,
)

/**
 * One decoded XML attribute.
 *
 * [resourceIdHex] is the attribute's resource id from the binary XML resource map when
 * the encoder recorded one. An attribute the reader cannot interpret is never dropped:
 * it keeps its name, its hex resource id (when any) and both its raw and typed value.
 */
data class ManifestAttributeFacts(
    val name: String,
    val namespace: String?,
    val resourceIdHex: String?,
    val rawValue: String?,
    val typedValue: String,
    val dataType: String,
)

/** A manifest element whose name matched one of the PCC-related tokens. */
data class ManifestElementFacts(
    val name: String,
    val attributes: List<ManifestAttributeFacts> = emptyList(),
    val textValues: List<String> = emptyList(),
    val certificateDigests: List<String> = emptyList(),
)

/** A declared `<permission>` with its decoded protection level. */
data class ManifestPermissionFacts(
    val name: String,
    val protectionLevelRaw: String?,
    val protectionLevelBase: String?,
    val protectionLevelFlags: List<String> = emptyList(),
    val attributes: List<ManifestAttributeFacts> = emptyList(),
)

/** A declared `uses-permission` or `uses-permission-sdk-23` element. */
data class ManifestUsesPermissionFacts(
    val name: String,
    val maxSdkVersion: String?,
    val attributes: List<ManifestAttributeFacts> = emptyList(),
)

/**
 * One of the four manifest component kinds with the facts this reader records about it.
 *
 * [matchingAttributes] keeps every attribute whose name contains `pcc`, `isolated`,
 * `process`, `privatecompute` or `sandbox`, alongside [exported], [permission] and
 * [process].
 */
data class ManifestComponentFacts(
    val kind: String,
    val name: String?,
    val exported: Boolean?,
    val permission: String?,
    val process: String?,
    val matchingAttributes: List<ManifestAttributeFacts> = emptyList(),
)

/** Everything one APK manifest declared that this reader records. */
data class ManifestSourceFacts(
    val source: ManifestSourceId,
    val declaredPackage: String,
    val sharedUserId: String?,
    val versionName: String?,
    val permissions: List<ManifestPermissionFacts> = emptyList(),
    val usesPermissions: List<ManifestUsesPermissionFacts> = emptyList(),
    val pccElements: List<ManifestElementFacts> = emptyList(),
    val components: List<ManifestComponentFacts> = emptyList(),
)

/** A manifest that could not be read; the class and message are the observed error. */
data class ManifestSourceFailure(
    val source: ManifestSourceId,
    val errorClass: String,
    val message: String,
)

/**
 * Declared facts collected from a package's readable manifests.
 *
 * [sources] holds only manifests read to the end; [failures] names every source that
 * was not. A fact missing from this record is "not declared" only when [complete] is
 * true, because an unread manifest could have declared it.
 */
data class ManifestPackageFacts(
    val packageName: String,
    val effectiveSharedUserId: String? = null,
    val signerCertificateSha256: List<String> = emptyList(),
    val sources: List<ManifestSourceFacts> = emptyList(),
    val failures: List<ManifestSourceFailure> = emptyList(),
) {
    val complete: Boolean get() = failures.isEmpty()

    val permissions: List<ManifestPermissionFacts> get() = sources.flatMap { it.permissions }
    val usesPermissions: List<ManifestUsesPermissionFacts> get() = sources.flatMap { it.usesPermissions }
    val pccElements: List<ManifestElementFacts> get() = sources.flatMap { it.pccElements }
    val components: List<ManifestComponentFacts> get() = sources.flatMap { it.components }
    val declaredSharedUserIds: List<String>
        get() = sources.mapNotNull { it.sharedUserId }.distinct()
}

/** The result of one installed-manifest read. Never collapsed into a boolean. */
sealed interface ManifestReadOutcome {
    val packageName: String

    /** Every requested manifest was read to the end and declared the requested package. */
    data class Read(
        override val packageName: String,
        val facts: ManifestPackageFacts,
    ) : ManifestReadOutcome

    /** PackageManager did not expose the package (absent or hidden by visibility rules). */
    data class NotVisible(
        override val packageName: String,
    ) : ManifestReadOutcome

    /** A manifest could not be opened or decoded; the class and message are real values. */
    data class ReadError(
        override val packageName: String,
        val errorClass: String,
        val message: String,
        val source: ManifestSourceId? = null,
    ) : ManifestReadOutcome

    /** A manifest was read but its `<manifest package>` is not the requested package. */
    data class Mismatch(
        override val packageName: String,
        val declaredPackage: String?,
        val source: ManifestSourceId,
    ) : ManifestReadOutcome

    /** Some manifests were read and at least one could not be; [facts] is not complete. */
    data class Partial(
        override val packageName: String,
        val facts: ManifestPackageFacts,
    ) : ManifestReadOutcome
}

/** The byte stream is not the Android binary XML format this reader understands. */
class ManifestFormatException(message: String) : Exception(message)

/** One manifest entry exceeded a configured input bound. */
class ManifestSizeLimitException(message: String) : Exception(message)

/** What a read manifest says about PCC-related declarations. */
enum class PccComponentEvidenceState {
    /** At least one matching declaration was read. */
    DECLARED,

    /** Every manifest was read to the end and none declared a matching entry. */
    NOT_DECLARED_IN_READ_MANIFEST,

    /** The package or a manifest could not be read, so absence cannot be claimed. */
    UNREADABLE,
}

/** The evidence behind [PccComponentEvidenceState]. */
data class PccComponentEvidence(
    val state: PccComponentEvidenceState,
    val packageName: String,
    val matchedElements: List<ManifestElementFacts> = emptyList(),
    val matchedComponents: List<ManifestComponentFacts> = emptyList(),
    val matchedPermissions: List<ManifestPermissionFacts> = emptyList(),
    val reason: String,
)

internal val MANIFEST_PCC_ELEMENT_TOKENS =
    listOf("component-access", "association", "pcc", "privatecompute")

internal val MANIFEST_COMPONENT_ATTRIBUTE_TOKENS =
    listOf("pcc", "isolated", "process", "privatecompute", "sandbox")

internal val MANIFEST_PCC_COMPONENT_TOKENS =
    listOf("pcc", "privatecompute", "sandbox", "isolated")

internal fun String.manifestHasAnyToken(tokens: List<String>): Boolean =
    tokens.any { token -> contains(token, ignoreCase = true) }

/**
 * Pure decision over one read outcome.
 *
 * [PccComponentEvidenceState.DECLARED] means the read manifest declares an element,
 * component attribute or permission this reader classifies as PCC-related.
 * [PccComponentEvidenceState.NOT_DECLARED_IN_READ_MANIFEST] is returned only when every
 * manifest was read to the end; anything less is UNREADABLE.
 *
 * A manifest declaration is not proof of a runtime PCC UID. The framework decides a
 * package's UID from install state, and a package's own XML is not the authority the
 * system consults for that decision.
 */
fun pccComponentEvidence(outcome: ManifestReadOutcome): PccComponentEvidence = when (outcome) {
    is ManifestReadOutcome.Read -> {
        val matched = outcome.facts.pccMatches()
        if (matched.any) {
            matched.toEvidence(outcome.packageName, "matching declarations were read from ${outcome.facts.sources.size} manifest(s)")
        } else {
            PccComponentEvidence(
                state = PccComponentEvidenceState.NOT_DECLARED_IN_READ_MANIFEST,
                packageName = outcome.packageName,
                reason = "${outcome.facts.sources.size} manifest(s) read completely; none declared a PCC-related element, component attribute or permission",
            )
        }
    }

    is ManifestReadOutcome.Partial -> {
        val matched = outcome.facts.pccMatches()
        if (matched.any) {
            matched.toEvidence(
                outcome.packageName,
                "matching declarations were read, but ${outcome.facts.failures.size} manifest(s) could not be read",
            )
        } else {
            PccComponentEvidence(
                state = PccComponentEvidenceState.UNREADABLE,
                packageName = outcome.packageName,
                reason = "${outcome.facts.failures.size} manifest(s) could not be read, so absence cannot be claimed",
            )
        }
    }

    is ManifestReadOutcome.NotVisible -> PccComponentEvidence(
        state = PccComponentEvidenceState.UNREADABLE,
        packageName = outcome.packageName,
        reason = "package is not visible to this app",
    )

    is ManifestReadOutcome.ReadError -> PccComponentEvidence(
        state = PccComponentEvidenceState.UNREADABLE,
        packageName = outcome.packageName,
        reason = "manifest read failed with ${outcome.errorClass}: ${outcome.message}",
    )

    is ManifestReadOutcome.Mismatch -> PccComponentEvidence(
        state = PccComponentEvidenceState.UNREADABLE,
        packageName = outcome.packageName,
        reason = "the read manifest belongs to ${outcome.declaredPackage ?: "no declared package"}, not ${outcome.packageName}",
    )
}

private class PccMatches(
    val elements: List<ManifestElementFacts>,
    val components: List<ManifestComponentFacts>,
    val permissions: List<ManifestPermissionFacts>,
) {
    val any: Boolean get() = elements.isNotEmpty() || components.isNotEmpty() || permissions.isNotEmpty()

    fun toEvidence(packageName: String, reason: String) = PccComponentEvidence(
        state = PccComponentEvidenceState.DECLARED,
        packageName = packageName,
        matchedElements = elements,
        matchedComponents = components,
        matchedPermissions = permissions,
        reason = reason,
    )
}

private fun ManifestPackageFacts.pccMatches(): PccMatches = PccMatches(
    elements = pccElements.filter { it.name.manifestHasAnyToken(MANIFEST_PCC_ELEMENT_TOKENS) },
    components = components.filter { component ->
        component.matchingAttributes.any { it.name.manifestHasAnyToken(MANIFEST_PCC_COMPONENT_TOKENS) } ||
            component.process?.manifestHasAnyToken(MANIFEST_PCC_COMPONENT_TOKENS) == true
    },
    permissions = permissions.filter { it.name.manifestHasAnyToken(MANIFEST_PCC_ELEMENT_TOKENS) },
)
