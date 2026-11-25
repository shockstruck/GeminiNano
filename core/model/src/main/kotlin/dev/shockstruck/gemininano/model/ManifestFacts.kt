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
 * the encoder recorded one. [name] is empty when the encoder left the attribute without
 * a readable name; such an attribute is kept with its hex id and raw value instead of
 * being dropped. An attribute the reader cannot interpret is never discarded.
 */
data class ManifestAttributeFacts(
    val name: String,
    val namespace: String?,
    val resourceIdHex: String?,
    val rawValue: String?,
    val typedValue: String,
    val dataType: String,
) {
    val hasReadableName: Boolean get() = name.isNotBlank()
}

/** One package entry inside a component-access/association element. */
data class ManifestAssociationPackageFacts(
    val name: String?,
    val certificateDigests: List<String> = emptyList(),
    val attributes: List<ManifestAttributeFacts> = emptyList(),
)

/** A manifest element whose name matched one of the PCC-related tokens. */
data class ManifestElementFacts(
    val name: String,
    val attributes: List<ManifestAttributeFacts> = emptyList(),
    val textValues: List<String> = emptyList(),
    val certificateDigests: List<String> = emptyList(),
    val packages: List<ManifestAssociationPackageFacts> = emptyList(),
)

/** A declared `<permission>` with its decoded protection level. */
data class ManifestPermissionFacts(
    val name: String,
    val protectionLevelRaw: String?,
    val protectionLevelBase: String?,
    val protectionLevelFlags: List<String> = emptyList(),
    /** Bits outside the published base and flag values, as `0x…`, or null. */
    val protectionLevelUnknownBitsHex: String? = null,
    val attributes: List<ManifestAttributeFacts> = emptyList(),
)

/** A declared `uses-permission` or `uses-permission-sdk-23` element. */
data class ManifestUsesPermissionFacts(
    val name: String,
    val maxSdkVersion: String?,
    val minSdkVersion: String? = null,
    val attributes: List<ManifestAttributeFacts> = emptyList(),
)

/** The three-valued read of an `android:privateComputeCore` declaration. */
enum class ManifestBooleanValue {
    TRUE,
    FALSE,

    /** A resource reference or another value the reader cannot resolve to true or false. */
    UNRESOLVED,

    /** The attribute is not present. */
    ABSENT,
}

/** One `<intent-filter>` declared by a component, as plain facts. */
data class ManifestIntentFilterFacts(
    val actions: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val dataSchemes: List<String> = emptyList(),
    val dataHosts: List<String> = emptyList(),
)

/** The manifest-level `<queries>` declarations, as plain facts. */
data class ManifestQueriesFacts(
    val packages: List<String> = emptyList(),
    val intentActions: List<String> = emptyList(),
    val providerAuthorities: List<String> = emptyList(),
)

/**
 * One of the four manifest component kinds with the facts this reader records about it.
 *
 * [matchingAttributes] keeps every named attribute whose name contains `pcc`, `isolated`,
 * `process`, `privatecompute` or `sandbox`. [unnamedAttributes] keeps attributes whose
 * name the encoder left unreadable, as hex id plus raw value. [privateComputeCore] is
 * true, false, unresolved (a reference) or absent; only true is a PCC declaration.
 */
data class ManifestComponentFacts(
    val kind: String,
    val name: String?,
    val exported: Boolean?,
    val permission: String?,
    val process: String?,
    val privateComputeCore: ManifestBooleanValue = ManifestBooleanValue.ABSENT,
    val intentFilters: List<ManifestIntentFilterFacts> = emptyList(),
    val matchingAttributes: List<ManifestAttributeFacts> = emptyList(),
    val unnamedAttributes: List<ManifestAttributeFacts> = emptyList(),
) {
    val hasUnnamedAttributes: Boolean get() = unnamedAttributes.isNotEmpty()
    val declaresIntentFilter: Boolean get() = intentFilters.isNotEmpty()
}

/** One component attribute that describes an isolated or named process. */
data class ManifestSandboxAttributeFacts(
    val componentKind: String,
    val componentName: String?,
    val attribute: ManifestAttributeFacts,
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
    val queries: ManifestQueriesFacts = ManifestQueriesFacts(),
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
    val queries: List<ManifestQueriesFacts> get() = sources.map { it.queries }
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

/** What one category of read manifest says about PCC-related declarations. */
enum class PccComponentEvidenceState {
    /** At least one matching declaration was read. */
    DECLARED,

    /** Every manifest was read to the end and none declared a matching entry. */
    NOT_DECLARED_IN_READ_MANIFEST,

    /** The package or a manifest could not be read, or a value is unresolved. */
    UNREADABLE,
}

/** One category's evidence value with the facts it matched. */
data class PccEvidence<T>(
    val state: PccComponentEvidenceState,
    val matched: List<T> = emptyList(),
    val reason: String,
)

/**
 * The three separate PCC-related evidence values. None of them is proof of a runtime
 * PCC UID: a manifest declaration is only what the installed APK says about itself.
 */
data class PccComponentEvidence(
    val packageName: String,
    val pccComponents: PccEvidence<ManifestComponentFacts>,
    val associationPolicy: PccEvidence<ManifestElementFacts>,
    val otherSandboxAttributes: PccEvidence<ManifestSandboxAttributeFacts>,
)

private val MANIFEST_ASSOCIATION_ELEMENT_TOKENS = listOf("component-access", "association")

internal val MANIFEST_PCC_ELEMENT_TOKENS =
    listOf("component-access", "association", "pcc", "privatecompute")

internal val MANIFEST_COMPONENT_ATTRIBUTE_TOKENS =
    listOf("pcc", "isolated", "process", "privatecompute", "sandbox")

internal val MANIFEST_SANDBOX_ATTRIBUTE_TOKENS = listOf("isolated", "process")

internal fun String.manifestHasAnyToken(tokens: List<String>): Boolean =
    tokens.any { token -> contains(token, ignoreCase = true) }

/**
 * Pure decision over one read outcome, in three separate values.
 *
 * - [PccComponentEvidence.pccComponents]: an activity, service, receiver or provider
 *   with `android:privateComputeCore` true. A literal false is not a declaration; a
 *   resource-reference value is unresolved; an attribute without a readable name could
 *   be the declaration, so none of these permits NOT_DECLARED.
 * - [PccComponentEvidence.associationPolicy]: `component-access`/association elements
 *   with their package entries and certificate digests.
 * - [PccComponentEvidence.otherSandboxAttributes]: isolated or process attributes,
 *   informational only; they never make a PCC component declared.
 *
 * NOT_DECLARED_IN_READ_MANIFEST is returned per value only when every manifest was read
 * to the end and that value has no unresolved or unnamed blocker. Anything less is
 * UNREADABLE. A manifest declaration is not proof of a runtime PCC UID: the framework
 * decides a package's UID from install state, not from its own XML.
 */
fun pccComponentEvidence(outcome: ManifestReadOutcome): PccComponentEvidence = when (outcome) {
    is ManifestReadOutcome.Read -> evidenceFromFacts(
        packageName = outcome.packageName,
        facts = outcome.facts,
        complete = true,
    )

    is ManifestReadOutcome.Partial -> evidenceFromFacts(
        packageName = outcome.packageName,
        facts = outcome.facts,
        complete = false,
    )

    is ManifestReadOutcome.NotVisible -> unreadableEvidence(
        outcome.packageName,
        "package is not visible to this app",
    )

    is ManifestReadOutcome.ReadError -> unreadableEvidence(
        outcome.packageName,
        "manifest read failed with ${outcome.errorClass}: ${outcome.message}",
    )

    is ManifestReadOutcome.Mismatch -> unreadableEvidence(
        outcome.packageName,
        "the read manifest belongs to ${outcome.declaredPackage ?: "no declared package"}, not ${outcome.packageName}",
    )
}

private fun unreadableEvidence(packageName: String, reason: String) = PccComponentEvidence(
    packageName = packageName,
    pccComponents = PccEvidence(PccComponentEvidenceState.UNREADABLE, reason = reason),
    associationPolicy = PccEvidence(PccComponentEvidenceState.UNREADABLE, reason = reason),
    otherSandboxAttributes = PccEvidence(PccComponentEvidenceState.UNREADABLE, reason = reason),
)

private fun evidenceFromFacts(
    packageName: String,
    facts: ManifestPackageFacts,
    complete: Boolean,
): PccComponentEvidence = PccComponentEvidence(
    packageName = packageName,
    pccComponents = componentEvidence(facts, complete),
    associationPolicy = associationEvidence(facts, complete),
    otherSandboxAttributes = sandboxEvidence(facts, complete),
)

private fun componentEvidence(
    facts: ManifestPackageFacts,
    complete: Boolean,
): PccEvidence<ManifestComponentFacts> {
    val components = facts.components
    val declared = components.filter { it.privateComputeCore == ManifestBooleanValue.TRUE }
    if (declared.isNotEmpty()) {
        return PccEvidence(
            PccComponentEvidenceState.DECLARED,
            declared,
            "${declared.size} component(s) declare android:privateComputeCore=\"true\"",
        )
    }
    if (!complete) {
        return PccEvidence(
            PccComponentEvidenceState.UNREADABLE,
            reason = "${facts.failures.size} manifest(s) could not be read, so absence cannot be claimed",
        )
    }
    val unresolved = components.filter { it.privateComputeCore == ManifestBooleanValue.UNRESOLVED }
    if (unresolved.isNotEmpty()) {
        return PccEvidence(
            PccComponentEvidenceState.UNREADABLE,
            unresolved,
            "${unresolved.size} component(s) declare android:privateComputeCore as an unresolved value",
        )
    }
    val unnamed = components.filter { it.hasUnnamedAttributes }
    if (unnamed.isNotEmpty()) {
        return PccEvidence(
            PccComponentEvidenceState.UNREADABLE,
            unnamed,
            "${unnamed.size} component(s) carry an attribute without a readable name, which could be the PCC declaration",
        )
    }
    return PccEvidence(
        PccComponentEvidenceState.NOT_DECLARED_IN_READ_MANIFEST,
        reason = "${components.size} component(s) read completely; none declares android:privateComputeCore=\"true\"",
    )
}

private fun associationEvidence(
    facts: ManifestPackageFacts,
    complete: Boolean,
): PccEvidence<ManifestElementFacts> {
    val elements = facts.pccElements.filter { it.name.manifestHasAnyToken(MANIFEST_ASSOCIATION_ELEMENT_TOKENS) }
    if (elements.isNotEmpty()) {
        return PccEvidence(
            PccComponentEvidenceState.DECLARED,
            elements,
            "${elements.size} association element(s) with ${elements.sumOf { it.packages.size }} package entr(ies)",
        )
    }
    if (!complete) {
        return PccEvidence(
            PccComponentEvidenceState.UNREADABLE,
            reason = "${facts.failures.size} manifest(s) could not be read, so absence cannot be claimed",
        )
    }
    return PccEvidence(
        PccComponentEvidenceState.NOT_DECLARED_IN_READ_MANIFEST,
        reason = "no component-access or association element was read",
    )
}

private fun sandboxEvidence(
    facts: ManifestPackageFacts,
    complete: Boolean,
): PccEvidence<ManifestSandboxAttributeFacts> {
    val attributes = facts.components.flatMap { component ->
        component.matchingAttributes
            .filter { it.name.manifestHasAnyToken(MANIFEST_SANDBOX_ATTRIBUTE_TOKENS) }
            .map { attribute ->
                ManifestSandboxAttributeFacts(
                    componentKind = component.kind,
                    componentName = component.name,
                    attribute = attribute,
                )
            }
    }
    if (attributes.isNotEmpty()) {
        return PccEvidence(
            PccComponentEvidenceState.DECLARED,
            attributes,
            "${attributes.size} isolated/process attribute(s); informational only",
        )
    }
    if (!complete) {
        return PccEvidence(
            PccComponentEvidenceState.UNREADABLE,
            reason = "${facts.failures.size} manifest(s) could not be read, so absence cannot be claimed",
        )
    }
    return PccEvidence(
        PccComponentEvidenceState.NOT_DECLARED_IN_READ_MANIFEST,
        reason = "no isolated or process component attribute was read",
    )
}
