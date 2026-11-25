package dev.shockstruck.gemininano.diagnostics

import dev.shockstruck.gemininano.model.FeatureProbeResult
import dev.shockstruck.gemininano.model.FeatureStatusKind
import dev.shockstruck.gemininano.model.ManifestReadOutcome
import dev.shockstruck.gemininano.model.OfflineInferenceResult
import dev.shockstruck.gemininano.model.PackageFacts
import dev.shockstruck.gemininano.model.PcsBindCatalog
import dev.shockstruck.gemininano.model.PcsBindOutcome
import dev.shockstruck.gemininano.model.PcsBindRole
import dev.shockstruck.gemininano.model.PcsBindResult
import dev.shockstruck.gemininano.model.PccComponentEvidence
import dev.shockstruck.gemininano.model.PermissionMatrix
import dev.shockstruck.gemininano.model.PermissionMatrixFormatting
import dev.shockstruck.gemininano.model.PermissionObservation
import dev.shockstruck.gemininano.model.PermissionStatus
import dev.shockstruck.gemininano.model.PlatformPermissions
import dev.shockstruck.gemininano.model.PlatformPrincipals
import dev.shockstruck.gemininano.model.PromptConfigStatus
import dev.shockstruck.gemininano.model.SummarizationProbeResult
import dev.shockstruck.gemininano.model.pccComponentEvidence

/**
 * The one failure class the classifier returns. [INDETERMINATE] is a real answer: the
 * observed evidence cannot separate the remaining candidates, and the result names them
 * with the observation that would separate them.
 */
enum class ProvisioningFailureClass(val label: String) {
    MISSING_FEATURE_CONFIGURATION("missing feature configuration"),
    PCS_INITIALIZATION_FAILURE("PCS initialization failure"),
    PCS_SERVICE_AUTHORIZATION_FAILURE("PCS service authorization failure"),
    REMOTE_PROVISIONING_REJECTION("remote provisioning rejection"),
    MODEL_REGISTRATION_FAILURE("model registration failure"),
    NPU_ACCESS_RESTRICTIONS("NPU access restrictions"),
    INDETERMINATE("INDETERMINATE"),
}

enum class EvidenceStrength(val label: String) {
    OBSERVED("observed"),
    INFERRED("inferred"),
}

/** One evidence item with the strength of the claim it carries. */
data class ClassifierEvidence(
    val statement: String,
    val strength: EvidenceStrength,
    val source: String? = null,
) {
    val label: String get() = "[${strength.label}] $statement"
}

/** A still-open class with the observation that would separate it. */
data class ClassifierCandidate(
    val failureClass: ProvisioningFailureClass,
    val why: String,
    val missingObservation: String,
)

data class ProvisioningClassification(
    val result: ProvisioningFailureClass,
    val summary: String,
    val evidence: List<ClassifierEvidence>,
    val refutations: List<String>,
    val candidates: List<ClassifierCandidate> = emptyList(),
    val nextObservation: String? = null,
) {
    val isIndeterminate: Boolean get() = result == ProvisioningFailureClass.INDETERMINATE
}

/** One free-text failure observation already returned by a probe. */
data class ClassifierMessage(
    val source: String,
    val text: String,
)

/**
 * The installed-manifest facts one principal contributed, kept as the Stage 6 reader
 * produced them. A manifest declaration is never promoted to a runtime UID here.
 */
data class ManifestEvidenceSnapshot(
    val requestedPackage: String,
    val outcome: ManifestReadOutcome,
) {
    val pcc: PccComponentEvidence = pccComponentEvidence(outcome)

    val readLabel: String
        get() = when (outcome) {
            is ManifestReadOutcome.Read -> "read complete"
            is ManifestReadOutcome.Partial -> "partial read"
            is ManifestReadOutcome.NotVisible -> "not visible"
            is ManifestReadOutcome.ReadError -> "read error"
            is ManifestReadOutcome.Mismatch -> "wrong declared package"
        }

    val complete: Boolean get() = outcome is ManifestReadOutcome.Read

    val declaredPackage: String?
        get() = when (outcome) {
            is ManifestReadOutcome.Read -> outcome.facts.sources.firstOrNull()?.declaredPackage
            is ManifestReadOutcome.Partial -> outcome.facts.sources.firstOrNull()?.declaredPackage
            is ManifestReadOutcome.Mismatch -> outcome.declaredPackage
            else -> null
        }

    val signerSha256: List<String>
        get() = when (outcome) {
            is ManifestReadOutcome.Read -> outcome.facts.signerCertificateSha256
            is ManifestReadOutcome.Partial -> outcome.facts.signerCertificateSha256
            else -> emptyList()
        }

    val declaredPermissions: List<dev.shockstruck.gemininano.model.ManifestPermissionFacts>
        get() = when (outcome) {
            is ManifestReadOutcome.Read -> outcome.facts.permissions
            is ManifestReadOutcome.Partial -> outcome.facts.permissions
            else -> emptyList()
        }

    val usesPermissions: List<dev.shockstruck.gemininano.model.ManifestUsesPermissionFacts>
        get() = when (outcome) {
            is ManifestReadOutcome.Read -> outcome.facts.usesPermissions
            is ManifestReadOutcome.Partial -> outcome.facts.usesPermissions
            else -> emptyList()
        }

    val readDetail: String
        get() = when (outcome) {
            is ManifestReadOutcome.Read -> "${outcome.facts.sources.size} manifest source(s) read"
            is ManifestReadOutcome.Partial ->
                "${outcome.facts.sources.size} source(s) read, ${outcome.facts.failures.size} failed"

            is ManifestReadOutcome.NotVisible -> "PackageManager does not expose the package"
            is ManifestReadOutcome.ReadError -> "${outcome.errorClass}: ${outcome.message}"
            is ManifestReadOutcome.Mismatch ->
                "read manifest declares ${outcome.declaredPackage ?: "no package"}"
        }

    val associationPackageNames: List<String>
        get() = pcc.associationPolicy.matched
            .flatMap { element -> element.packages }
            .mapNotNull { it.name }
            .distinct()

    val associationCertificateDigests: List<String>
        get() = pcc.associationPolicy.matched
            .flatMap { element -> element.packages }
            .flatMap { it.certificateDigests }
            .distinct()
}

/** Everything the classifier may use; every member is already an observed fact. */
data class ClassifierInput(
    val matrix: PermissionMatrix? = null,
    val packages: List<PackageFacts> = emptyList(),
    val manifests: List<ManifestEvidenceSnapshot> = emptyList(),
    val bindResults: List<PcsBindResult> = emptyList(),
    val summarization: SummarizationProbeResult? = null,
    val prompt: List<PromptConfigStatus> = emptyList(),
    val features: List<FeatureProbeResult> = emptyList(),
    val offline: List<OfflineInferenceResult> = emptyList(),
    val messages: List<ClassifierMessage> = emptyList(),
    val systemFeatures: List<String> = emptyList(),
    val systemFeatureReadError: String? = null,
)

/**
 * Pure six-way provisioning-failure classifier.
 *
 * A class is selected only when an observation separates it from the others:
 *
 * - a message that itself names the missing configuration, the NPU, a remote
 *   rejection or model/feature registration;
 * - a gRPC bind row whose outcome distinguishes PCS initialization (null binding,
 *   timeout, binding died, disabled component) from PCS authorization
 *   (`SecurityException`, not exported);
 * - the installed PCS manifest's association policy naming a different AICore package
 *   than the one installed.
 *
 * Everything else is [ProvisioningFailureClass.INDETERMINATE] naming the candidates and
 * the observation that would separate them. In particular:
 *
 * - a 606/UNAVAILABLE pattern alone never selects a class: Google's documentation gives
 *   configuration-not-downloaded and an unlocked bootloader as 606 causes and leaves
 *   UNAVAILABLE undifferentiated, and the July 2025 PCS gate that could refuse the
 *   manifest was read in a source mirror, not on the phone;
 * - `READ_DEVICE_CONFIG` denied supports but does not prove the July 2025 gate; granted
 *   does not refute it, because an unwritten DeviceConfig namespace leaves the same
 *   default;
 * - a denied NPU, on-device-intelligence or virtual-machine permission is never
 *   evidence for NPU access restrictions;
 * - a null binding of `GenAiInferenceService` is never used: only the gRPC row counts,
 *   and this app's own bind never stands in for AICore's access;
 * - an empty or disabled PCS network log is not evidence for any class.
 */
class ProvisioningFailureClassifier {

    fun classify(input: ClassifierInput): ProvisioningClassification {
        val evidence = mutableListOf<ClassifierEvidence>()
        val aicorePackage = installedAicorePackage(input.packages)
        val pcsManifest = input.manifests.firstOrNull {
            it.requestedPackage == PlatformPrincipals.PCS
        }
        val aicoreManifest = aicorePackage?.let { installed ->
            input.manifests.firstOrNull { it.requestedPackage == installed }
        }
        val asiManifest = input.manifests.firstOrNull {
            it.requestedPackage == PlatformPrincipals.ASI
        }
        val grpc = grpcRow(input.bindResults)
        val pcsRdc = input.matrix?.cell(PlatformPrincipals.PCS, PlatformPermissions.READ_DEVICE_CONFIG)
        val aicoreRdc = aicoreReadDeviceConfig(input.matrix, aicorePackage)
        val pcsPcc = input.matrix?.cell(PlatformPrincipals.PCS, PlatformPermissions.PCC)

        evidence += packageEvidence(input.packages, aicorePackage)
        evidence += permissionEvidence(pcsRdc, aicoreRdc, pcsPcc)
        evidence += declaredPermissionEvidence(input.manifests)
        evidence += bindEvidence(grpc)
        evidence += manifestEvidence(input.manifests, aicorePackage)
        evidence += featurePatternEvidence(input)
        evidence += systemFeatureEvidence(input)
        evidence += messageEvidence(input.messages)

        val supporters = linkedMapOf<ProvisioningFailureClass, MutableList<String>>()
        fun support(failureClass: ProvisioningFailureClass, why: String) {
            supporters.getOrPut(failureClass) { mutableListOf() }.add(why)
        }

        npuMessages(input.messages).forEach { message ->
            support(
                ProvisioningFailureClass.NPU_ACCESS_RESTRICTIONS,
                "an observed message names the NPU: \"${message.text}\" (${message.source})",
            )
        }
        configurationMessages(input.messages).forEach { message ->
            support(
                ProvisioningFailureClass.MISSING_FEATURE_CONFIGURATION,
                "an observed message names missing configuration: \"${message.text}\" (${message.source})",
            )
        }
        remoteRejectionMessages(input.messages).forEach { message ->
            support(
                ProvisioningFailureClass.REMOTE_PROVISIONING_REJECTION,
                "an observed message names a remote rejection: \"${message.text}\" (${message.source})",
            )
        }
        registrationMessages(input.messages).forEach { message ->
            support(
                ProvisioningFailureClass.MODEL_REGISTRATION_FAILURE,
                "an observed message names model or feature registration: \"${message.text}\" (${message.source})",
            )
        }
        grpc?.let { result ->
            when (result.outcome) {
                PcsBindOutcome.NULL_BINDING,
                PcsBindOutcome.TIMEOUT,
                PcsBindOutcome.BINDING_DIED,
                PcsBindOutcome.NOT_ENABLED,
                -> support(
                    ProvisioningFailureClass.PCS_INITIALIZATION_FAILURE,
                    "the gRPC bind row observed ${result.outcome.label} for this app " +
                        "(${result.elapsedMillis} ms); the service did not become usable",
                )

                PcsBindOutcome.SECURITY_EXCEPTION,
                PcsBindOutcome.NOT_EXPORTED,
                -> support(
                    ProvisioningFailureClass.PCS_SERVICE_AUTHORIZATION_FAILURE,
                    "the gRPC bind row observed ${result.outcome.label}: " +
                        (result.exceptionMessage ?: "the component is not exported to other apps"),
                )

                else -> Unit
            }
        }
        associationPolicyMismatch(pcsManifest, aicorePackage)?.let { mismatch ->
            support(ProvisioningFailureClass.PCS_SERVICE_AUTHORIZATION_FAILURE, mismatch)
        }

        val supported = supporters.keys.toList()
        return when {
            supported.size == 1 -> selected(supported.single(), supporters.getValue(supported.single()), evidence, input, grpc)
            supported.size > 1 -> conflicting(supported, supporters, evidence)
            else -> indeterminate(input, evidence, grpc)
        }
    }

    private fun selected(
        failureClass: ProvisioningFailureClass,
        reasons: List<String>,
        evidence: List<ClassifierEvidence>,
        input: ClassifierInput,
        grpc: PcsBindResult?,
    ): ProvisioningClassification {
        val summary = buildString {
            append("Selected: ").append(failureClass.label).append(". ")
            append(reasons.joinToString("; "))
            when (failureClass) {
                ProvisioningFailureClass.NPU_ACCESS_RESTRICTIONS ->
                    append(". This is the only class with an observation naming the NPU; no denied " +
                        "permission was used to select it.")
                ProvisioningFailureClass.MISSING_FEATURE_CONFIGURATION ->
                    append(". The documented 606 remedy is the next observation: update AICore, " +
                        "restart the phone, probe again; persistent 606 moves the cause to the " +
                        "manifest/feature gate.")
                ProvisioningFailureClass.PCS_INITIALIZATION_FAILURE ->
                    append(". The result belongs to this app's own gRPC bind and does not by " +
                        "itself describe AICore's access.")
                ProvisioningFailureClass.PCS_SERVICE_AUTHORIZATION_FAILURE ->
                    append(". The result is this app's bind or the installed PCS association " +
                        "policy; AICore's own access is not established by either.")
                ProvisioningFailureClass.REMOTE_PROVISIONING_REJECTION ->
                    append(". The message is the only remote-side observation; no request was " +
                        "sent by this app.")
                ProvisioningFailureClass.MODEL_REGISTRATION_FAILURE ->
                    append(". The message is the only registration observation; no model was " +
                        "registered or downloaded by this app.")
                ProvisioningFailureClass.INDETERMINATE -> Unit
            }
        }
        val next = nextObservation(failureClass, input, grpc)
        return ProvisioningClassification(
            result = failureClass,
            summary = summary,
            evidence = evidence + ClassifierEvidence(
                statement = "The class is inferred from the observations above; it proves no " +
                    "layer the app cannot observe (a PCS GetManifestConfig response or AICore " +
                    "internals).",
                strength = EvidenceStrength.INFERRED,
            ),
            refutations = refutations(failureClass),
            nextObservation = next,
        )
    }

    private fun conflicting(
        supported: List<ProvisioningFailureClass>,
        supporters: Map<ProvisioningFailureClass, List<String>>,
        evidence: List<ClassifierEvidence>,
    ): ProvisioningClassification {
        val names = supported.joinToString { it.label }
        val candidates = supported.map { failureClass ->
            ClassifierCandidate(
                failureClass = failureClass,
                why = supporters.getValue(failureClass).joinToString("; "),
                missingObservation = missingsFor(failureClass),
            )
        }
        return ProvisioningClassification(
            result = ProvisioningFailureClass.INDETERMINATE,
            summary = "INDETERMINATE — observed evidence supports $names at the same time, " +
                "and the app has no observation that separates them.",
            evidence = evidence,
            refutations = supported.flatMap { refutations(it) }.distinct() +
                "An observation that repeats one class while the others' supporting evidence " +
                "disappears; the export keeps each observation's source so a later run can compare.",
            candidates = candidates,
            nextObservation = "Resolve the conflict by repeating only the probe behind each " +
                "supported class (bind, feature status or launch) and exporting again; the app " +
                "never sends a binder transaction or a PCS RPC.",
        )
    }

    private fun indeterminate(
        input: ClassifierInput,
        evidence: List<ClassifierEvidence>,
        grpc: PcsBindResult?,
    ): ProvisioningClassification {
        val observed606 = observes606(input)
        val observedUnavailable = observesUnavailable(input)
        val observedSuccess = observesSuccessfulGeneration(input)
        if (!observed606 && !observedUnavailable && observedSuccess) {
            return ProvisioningClassification(
                result = ProvisioningFailureClass.INDETERMINATE,
                summary = "No failure signature is present: at least one generation succeeded, " +
                    "so none of the six failure classes is supported for the tested path.",
                evidence = evidence,
                refutations = listOf(
                    "A later failing probe or a PCS bind failure would create a failure signature " +
                        "again; stale successes do not hide a new failure.",
                ),
            )
        }
        val candidates = mutableListOf<ClassifierCandidate>()
        if (observed606 || observedUnavailable) {
            candidates += ClassifierCandidate(
                failureClass = ProvisioningFailureClass.MISSING_FEATURE_CONFIGURATION,
                why = "606 is Google's documented configuration-not-downloaded signature" +
                    (if (observedUnavailable) " and at least one feature reported UNAVAILABLE" else ""),
                missingObservation = "a positive observation that AICore's feature store lacks the " +
                    "entry — a PCS GetManifestConfig response or an AICore message naming configuration",
            )
            candidates += ClassifierCandidate(
                failureClass = ProvisioningFailureClass.PCS_SERVICE_AUTHORIZATION_FAILURE,
                why = "PCS refuses GetManifestConfig with \"feature disabled\" in the July 2025 " +
                    "source mirror; READ_DEVICE_CONFIG denied supports that gate but does not " +
                    "prove it, and granted does not refute it because an unwritten DeviceConfig " +
                    "namespace leaves the default",
                missingObservation = "the PCS GetManifestConfig response; an ordinary app cannot " +
                    "send that RPC, so only an AICore-side observation or a public document can " +
                    "settle it",
            )
        }
        if (grpc?.outcome != PcsBindOutcome.BINDER_RETURNED) {
            candidates += ClassifierCandidate(
                failureClass = ProvisioningFailureClass.PCS_INITIALIZATION_FAILURE,
                why = "no gRPC bind result establishes PCS reachability for this app" +
                    (grpc?.let { " (last row: ${it.outcome.label})" } ?: " (bind probe not run)"),
                missingObservation = "one AstreaGrpcService bind by this app; AICore's own access " +
                    "is never established by it",
            )
        }
        if (observed606 || observedUnavailable) {
            candidates += ClassifierCandidate(
                failureClass = ProvisioningFailureClass.REMOTE_PROVISIONING_REJECTION,
                why = "no PCS or backend response was observed, so a remote rejection is neither " +
                    "supported nor excluded",
                missingObservation = "a PCS or backend response naming device eligibility or a " +
                    "rejection; the app sends no request",
            )
            candidates += ClassifierCandidate(
                failureClass = ProvisioningFailureClass.MODEL_REGISTRATION_FAILURE,
                why = "no AICore message naming model or feature registration was observed",
                missingObservation = "an AICore error naming model or feature registration",
            )
        }
        candidates += ClassifierCandidate(
            failureClass = ProvisioningFailureClass.NPU_ACCESS_RESTRICTIONS,
            why = "no observation names the NPU; a denied ACCESS_NPU_MODEL_MANAGER_API, " +
                "USE_ON_DEVICE_INTELLIGENCE or MANAGE_VIRTUAL_MACHINE grant is not evidence",
            missingObservation = "an AICore error naming NPU access; an NPU system-feature name " +
                "alone is availability, not a restriction",
        )
        return ProvisioningClassification(
            result = ProvisioningFailureClass.INDETERMINATE,
            summary = if (observed606 || observedUnavailable) {
                "INDETERMINATE — the 606/UNAVAILABLE pattern is observed, but the app cannot " +
                    "separate the remaining causes."
            } else {
                "INDETERMINATE — no failure signature with a separating observation is present."
            },
            evidence = evidence,
            refutations = candidates.map { "${it.failureClass.label}: ${it.missingObservation}" },
            candidates = candidates,
            nextObservation = nextObservation(ProvisioningFailureClass.INDETERMINATE, input, grpc),
        )
    }

    private fun nextObservation(
        failureClass: ProvisioningFailureClass,
        input: ClassifierInput,
        grpc: PcsBindResult?,
    ): String? = when (failureClass) {
        ProvisioningFailureClass.INDETERMINATE -> when {
            observes606(input) || observesUnavailable(input) -> {
                if (grpc == null || grpc.outcome == PcsBindOutcome.NOT_RUN) {
                    "Run the Private Compute Services gRPC bind probe, then Run Full Diagnostics " +
                        "and export again; the row is this app's own bind and never proves AICore's access."
                } else {
                    "The documented 606 remedy is to update AICore, restart the phone and probe " +
                        "again. If 606 persists, the cause is at the manifest/feature gate or a " +
                        "remote response the app cannot send; an AICore-side observation is needed."
                }
            }

            else -> "Run the summarization probe, the Prompt probe and the gRPC bind probe, then " +
                "export again; absent probes are missing observations, not negative evidence."
        }

        else -> null
    }

    private fun refutations(failureClass: ProvisioningFailureClass): List<String> = when (failureClass) {
        ProvisioningFailureClass.MISSING_FEATURE_CONFIGURATION -> listOf(
            "A PCS GetManifestConfig response naming \"feature disabled\" would move the cause to " +
                "PCS service authorization failure.",
            "A report that the bootloader is unlocked is the other documented 606 cause.",
            "An AICore update that makes the feature AVAILABLE would refute an enduring missing " +
                "configuration.",
        )

        ProvisioningFailureClass.PCS_INITIALIZATION_FAILURE -> listOf(
            "A later gRPC bind of the same declared service returning a binder would refute an " +
                "enduring initialization failure for this app.",
            "A PCS process that answers within the 3-second window would refute a timeout.",
            "AICore's own bind succeeding would show the failure was specific to this app, not PCS.",
        )

        ProvisioningFailureClass.PCS_SERVICE_AUTHORIZATION_FAILURE -> listOf(
            "An AICore-originated bind or RPC succeeding would refute a PCS-wide authorization " +
                "failure.",
            "An installed PCS association policy that lists the installed AICore package, and a " +
                "signer digest matching a public-source digest, would refute the component-access " +
                "branch (this app bundles no public digest set, so it does not perform that comparison).",
            "The same bind succeeding after an update or grant would refute a stable denial.",
        )

        ProvisioningFailureClass.REMOTE_PROVISIONING_REJECTION -> listOf(
            "A later PCS or backend response reporting the device eligible, or a manifest download " +
                "completing, would refute a remote rejection.",
        )

        ProvisioningFailureClass.MODEL_REGISTRATION_FAILURE -> listOf(
            "A later successful registration or a feature reporting AVAILABLE after the same " +
                "operation would refute an enduring registration failure.",
        )

        ProvisioningFailureClass.NPU_ACCESS_RESTRICTIONS -> listOf(
            "A generation succeeding without an NPU-named error would refute an NPU restriction " +
                "for that path.",
            "Evidence that this path does not use the NPU would remove the class entirely.",
        )

        ProvisioningFailureClass.INDETERMINATE -> listOf(
            "An observation that separates the named candidates; each candidate carries its own " +
                "missing observation.",
        )
    }

    private fun missingsFor(failureClass: ProvisioningFailureClass): String = when (failureClass) {
        ProvisioningFailureClass.MISSING_FEATURE_CONFIGURATION ->
            "a positive missing-configuration observation (PCS GetManifestConfig or AICore message)"
        ProvisioningFailureClass.PCS_INITIALIZATION_FAILURE ->
            "a gRPC bind result that separates initialization from authorization"
        ProvisioningFailureClass.PCS_SERVICE_AUTHORIZATION_FAILURE ->
            "an AICore-originated bind/RPC or the PCS manifest association policy"
        ProvisioningFailureClass.REMOTE_PROVISIONING_REJECTION ->
            "a PCS/backend response naming device eligibility"
        ProvisioningFailureClass.MODEL_REGISTRATION_FAILURE ->
            "an AICore message naming model or feature registration"
        ProvisioningFailureClass.NPU_ACCESS_RESTRICTIONS ->
            "an AICore message naming NPU access"
        ProvisioningFailureClass.INDETERMINATE -> "a separating observation"
    }

    // -- Evidence assembly -------------------------------------------------------------

    private fun packageEvidence(
        packages: List<PackageFacts>,
        aicorePackage: String?,
    ): List<ClassifierEvidence> {
        val facts = packages.filter {
            it.packageName in AICORE_PACKAGES || it.packageName == PlatformPrincipals.PCS
        }
        if (facts.isEmpty()) {
            return listOf(
                ClassifierEvidence(
                    statement = "Installed package facts were not collected; the installed AICore " +
                        "package name is not established.",
                    strength = EvidenceStrength.OBSERVED,
                ),
            )
        }
        return facts.map { packageFacts ->
            val role = when (packageFacts.packageName) {
                aicorePackage -> "installed AICore"
                in AICORE_PACKAGES -> "AICore package"
                else -> "PCS"
            }
            ClassifierEvidence(
                statement = "$role ${packageFacts.packageName}: " +
                    packageFacts.versionName.orEmpty().ifBlank { "versionName unknown" } +
                    " (versionCode ${packageFacts.versionCode ?: "unknown"}), " +
                    "installer ${packageFacts.installerPackage ?: "unknown"}, " +
                    "firstInstall=${packageFacts.firstInstallTime ?: "unknown"}, " +
                    "lastUpdate=${packageFacts.lastUpdateTime ?: "unknown"}. " +
                    "This is the package the app observed, not a policy expectation.",
                strength = EvidenceStrength.OBSERVED,
            )
        }.ifEmpty {
            listOf(
                ClassifierEvidence(
                    statement = "Installed AICore package: not visible to this app.",
                    strength = EvidenceStrength.OBSERVED,
                ),
            )
        }
    }

    private fun permissionEvidence(
        pcsRdc: PermissionObservation?,
        aicoreRdc: PermissionObservation?,
        pcsPcc: PermissionObservation?,
    ): List<ClassifierEvidence> = buildList {
        add(
            ClassifierEvidence(
                statement = "PCS ${PlatformPrincipals.PCS} READ_DEVICE_CONFIG: " +
                    (pcsRdc?.let {
                        PermissionMatrixFormatting.renderCell(it) + " · " +
                            PermissionMatrixFormatting.verificationLabel(it)
                    } ?: "not read by the matrix"),
                strength = EvidenceStrength.OBSERVED,
                source = "permission matrix",
            ),
        )
        add(
            ClassifierEvidence(
                statement = "AICore READ_DEVICE_CONFIG: " +
                    (aicoreRdc?.let {
                        PermissionMatrixFormatting.renderCell(it) + " · " +
                            PermissionMatrixFormatting.verificationLabel(it)
                    } ?: "not read by the matrix"),
                strength = EvidenceStrength.OBSERVED,
                source = "permission matrix",
            ),
        )
        add(
            ClassifierEvidence(
                statement = "PCS PROVIDE_PRIVATE_COMPUTE_SERVICES: " +
                    (pcsPcc?.let {
                        PermissionMatrixFormatting.renderCell(it) + " · " +
                            PermissionMatrixFormatting.verificationLabel(it)
                    } ?: "not read by the matrix") +
                    ". A granted PCC marks a PCS gateway UID; a manifest " +
                    "android:privateComputeCore declaration is not a runtime UID, and neither is " +
                    "derived from the other.",
                strength = EvidenceStrength.OBSERVED,
                source = "permission matrix",
            ),
        )
        val rdcStatus = pcsRdc?.status
        when (rdcStatus) {
            PermissionStatus.GRANTED -> add(
                ClassifierEvidence(
                    statement = "PCS can read DeviceConfig, so the permission alone does not block " +
                        "the flag read. It does not refute a PCS manifest refusal either: an " +
                        "unwritten DeviceConfig namespace leaves the same default false.",
                    strength = EvidenceStrength.INFERRED,
                    source = "SHOC-6785 / SHOC-6786 review",
                ),
            )

            PermissionStatus.DENIED -> add(
                ClassifierEvidence(
                    statement = "The denied PCS READ_DEVICE_CONFIG supports, but does not prove, " +
                        "the July 2025 PCS gate in which GetManifestConfig is refused as " +
                        "\"feature disabled\" unless DeviceConfig enables it. That gate was read " +
                        "in a source mirror; the phone's PCS build was not read.",
                    strength = EvidenceStrength.INFERRED,
                    source = "SHOC-6785",
                ),
            )

            PermissionStatus.GRANT_DISAGREEMENT -> add(
                ClassifierEvidence(
                    statement = "The two PCS READ_DEVICE_CONFIG grant sources disagree, so neither " +
                        "a clean grant nor a clean denial is asserted.",
                    strength = EvidenceStrength.OBSERVED,
                    source = "permission matrix",
                ),
            )

            null, PermissionStatus.NOT_REQUESTED, PermissionStatus.NOT_READ,
            PermissionStatus.NOT_VISIBLE, PermissionStatus.READ_ERROR, PermissionStatus.UNKNOWN,
            PermissionStatus.GRANTED_NOT_REQUESTED,
            -> add(
                ClassifierEvidence(
                    statement = "The PCS READ_DEVICE_CONFIG grant state is not established; " +
                        "unknown is not treated as denied.",
                    strength = EvidenceStrength.OBSERVED,
                    source = "permission matrix",
                ),
            )
        }
    }

    /**
     * A permission the OS did not return a definition for is "not defined or not
     * resolvable", never "absent": the installed manifest may still declare it.
     */
    private fun declaredPermissionEvidence(
        manifests: List<ManifestEvidenceSnapshot>,
    ): List<ClassifierEvidence> = buildList {
        manifests.forEach { snapshot ->
            val declared = snapshot.usesPermissions
                .map { it.name }
                .filter { name -> NAMED_PERMISSIONS.any { name == it || name.endsWith(".$it") } }
                .distinct()
            if (declared.isNotEmpty()) {
                add(
                    ClassifierEvidence(
                        statement = "${snapshot.requestedPackage} declares " +
                            declared.joinToString(", ") +
                            " in its installed manifest. A manifest declaration is not a grant " +
                            "and not an OS permission definition; getPermissionInfo failing means " +
                            "\"not defined or not resolvable\", never absent.",
                        strength = EvidenceStrength.OBSERVED,
                        source = "Stage 6 installed-manifest reader",
                    ),
                )
            }
        }
    }

    private fun bindEvidence(grpc: PcsBindResult?): List<ClassifierEvidence> {
        if (grpc == null) {
            return listOf(
                ClassifierEvidence(
                    statement = "No gRPC bind row exists. The classifier uses only " +
                        "AstreaGrpcService/PcsGrpcService; GenAiInferenceService is never used " +
                        "for PCS initialization or authorization evidence.",
                    strength = EvidenceStrength.OBSERVED,
                ),
            )
        }
        return listOf(
            ClassifierEvidence(
                statement = "gRPC bind row ${grpc.componentName}: outcome=${grpc.outcome.label}, " +
                    "attempted=${grpc.outcome.attempted}, elapsed=${grpc.elapsedMillis} ms, " +
                    "unbindCalls=${grpc.unbindCalls}" +
                    (grpc.exceptionClass?.let { ", class=$it" } ?: "") +
                    (grpc.exceptionMessage?.let { ", message=$it" } ?: "") +
                    (grpc.interfaceDescriptor?.let { ", descriptor=$it" } ?: "") +
                    (if (grpc.descriptorTimedOut) ", descriptorRead=timed out" else "") +
                    ". This app's own bind attempt never stands in for AICore's access.",
                strength = EvidenceStrength.OBSERVED,
                source = "PCS bind probe",
            ),
        )
    }

    private fun manifestEvidence(
        manifests: List<ManifestEvidenceSnapshot>,
        aicorePackage: String?,
    ): List<ClassifierEvidence> {
        if (manifests.isEmpty()) {
            return listOf(
                ClassifierEvidence(
                    statement = "Installed manifests were not read; no PCC component, association " +
                        "policy or signer fact is established.",
                    strength = EvidenceStrength.OBSERVED,
                ),
            )
        }
        return manifests.map { snapshot ->
            val role = when (snapshot.requestedPackage) {
                aicorePackage -> "installed AICore"
                in AICORE_PACKAGES -> "AICore package"
                PlatformPrincipals.PCS -> "PCS"
                PlatformPrincipals.ASI -> "ASI"
                else -> "package"
            }
            val policy = snapshot.pcc.associationPolicy
            val packageNames = snapshot.associationPackageNames
            ClassifierEvidence(
                statement = "$role ${snapshot.requestedPackage} manifest: ${snapshot.readLabel} " +
                    "(${snapshot.readDetail}); declaredPackage=${snapshot.declaredPackage ?: "unknown"}; " +
                    "signerDigests=${snapshot.signerSha256.size}" +
                    (if (snapshot.associationCertificateDigests.isNotEmpty()) {
                        " + ${snapshot.associationCertificateDigests.size} association digest(s)"
                    } else {
                        ""
                    }) +
                    "; declaredPermissions=${snapshot.declaredPermissions.size}, " +
                    "usesPermissions=${snapshot.usesPermissions.size}; " +
                    "PCC components: ${snapshot.pcc.pccComponents.state.name} " +
                    "(${snapshot.pcc.pccComponents.reason}); association policy: " +
                    "${policy.state.name} (${policy.reason})" +
                    (if (packageNames.isNotEmpty()) " packages=${packageNames.joinToString(",")}" else "") +
                    ". A declaration is not a runtime PCC UID; no signer digest is compared " +
                    "because no public-source digest set is bundled.",
                strength = EvidenceStrength.OBSERVED,
                source = "Stage 6 installed-manifest reader",
            )
        }
    }

    private fun featurePatternEvidence(input: ClassifierInput): List<ClassifierEvidence> = buildList {
        val summarizationCode = input.summarization?.failure?.errorCode
        val prompt606 = input.prompt.filter { it.errorCode == 606 }
        val feature606 = input.features.filter { it.errorCode == 606 }
        if (summarizationCode == 606 || prompt606.isNotEmpty() || feature606.isNotEmpty()) {
            add(
                ClassifierEvidence(
                    statement = "606 observed" +
                        (input.summarization?.failure?.let { " (summarization: ${it.message})" } ?: "") +
                        (if (prompt606.isNotEmpty()) {
                            " (Prompt ids ${prompt606.mapNotNull { it.featureId }.joinToString(",")
                                .ifBlank { "not parsed" }})"
                        } else {
                            ""
                        }) +
                        (if (feature606.isNotEmpty()) {
                            " (features ${feature606.map { it.feature.displayName }
                                .joinToString(",")}: ids ${feature606.mapNotNull { it.featureId }
                                .joinToString(",")})"
                        } else {
                            ""
                        }) +
                        ". Google documents 606 as configuration not yet downloaded or an unlocked " +
                        "bootloader; the documentation cannot separate those causes.",
                    strength = EvidenceStrength.OBSERVED,
                    source = "ML Kit probes",
                ),
            )
        }
        if (observesUnavailable(input)) {
            add(
                ClassifierEvidence(
                    statement = "UNAVAILABLE observed: " +
                        (input.prompt.filter { it.status == FeatureStatusKind.UNAVAILABLE }
                            .map { it.config.label } +
                            input.features.filter { it.status == FeatureStatusKind.UNAVAILABLE }
                                .map { it.feature.displayName } +
                            listOfNotNull(
                                input.summarization?.takeIf {
                                    it.status == FeatureStatusKind.UNAVAILABLE
                                }?.let { "summarization" },
                            )).joinToString(",") +
                        ". The documentation leaves UNAVAILABLE undifferentiated, so it is not a " +
                        "separating observation.",
                    strength = EvidenceStrength.OBSERVED,
                    source = "ML Kit probes",
                ),
            )
        }
        if (input.summarization?.failure?.errorCode == 601) {
            add(
                ClassifierEvidence(
                    statement = "601 observed: Google documents it as AICore failing to bind. The " +
                        "message does not name which layer refused, so it is not a separating " +
                        "observation for the six classes.",
                    strength = EvidenceStrength.OBSERVED,
                    source = "ML Kit probe",
                ),
            )
        }
    }

    private fun systemFeatureEvidence(input: ClassifierInput): List<ClassifierEvidence> = buildList {
        val error = input.systemFeatureReadError
        if (error != null) {
            add(
                ClassifierEvidence(
                    statement = "System-feature read failed: $error. That is not a claim that none " +
                        "exist, and probe execution success stays separate from feature availability.",
                    strength = EvidenceStrength.OBSERVED,
                    source = "getSystemAvailableFeatures",
                ),
            )
        } else if (input.systemFeatures.isNotEmpty()) {
            add(
                ClassifierEvidence(
                    statement = "Relevant system-feature names read: " +
                        input.systemFeatures.joinToString(", ") +
                        ". A name is availability only; it names no restriction and is not " +
                        "evidence for NPU access restrictions.",
                    strength = EvidenceStrength.OBSERVED,
                    source = "getSystemAvailableFeatures",
                ),
            )
        }
    }

    private fun messageEvidence(messages: List<ClassifierMessage>): List<ClassifierEvidence> =
        messages.map { message ->
            ClassifierEvidence(
                statement = "Message from ${message.source}: \"${message.text}\"",
                strength = EvidenceStrength.OBSERVED,
                source = message.source,
            )
        }

    // -- Message matching --------------------------------------------------------------

    private fun npuMessages(messages: List<ClassifierMessage>): List<ClassifierMessage> =
        messages.filter { message ->
            NPU_PATTERN.containsMatchIn(message.text) ||
                message.text.contains("neural processing", ignoreCase = true)
        }

    private fun configurationMessages(messages: List<ClassifierMessage>): List<ClassifierMessage> =
        messages.filter { message ->
            val text = message.text.lowercase()
            (text.contains("configuration") || text.contains("manifest")) &&
                MISSING_WORDS.any { text.contains(it) }
        }

    private fun remoteRejectionMessages(messages: List<ClassifierMessage>): List<ClassifierMessage> =
        messages.filter { message ->
            val text = message.text.lowercase()
            val eligibility = text.contains("not eligible") || text.contains("ineligible")
            val rejected = text.contains("reject") &&
                (text.contains("remote") || text.contains("server") || text.contains("provision"))
            val httpAuth = (text.contains("http 401") || text.contains("http 403") ||
                text.contains("forbidden")) &&
                (text.contains("server") || text.contains("remote") ||
                    text.contains("download") || text.contains("provision"))
            eligibility || rejected || httpAuth
        }

    private fun registrationMessages(messages: List<ClassifierMessage>): List<ClassifierMessage> =
        messages.filter { message ->
            val text = message.text.lowercase()
            (text.contains("regist") && (text.contains("model") || text.contains("feature"))) &&
                (text.contains("fail") || text.contains("error") || text.contains("unable") ||
                    text.contains("not registered") || text.contains("could not"))
        }

    // -- Small pure helpers ------------------------------------------------------------

    private fun observes606(input: ClassifierInput): Boolean =
        input.summarization?.failure?.errorCode == 606 ||
            input.prompt.any { it.errorCode == 606 } ||
            input.features.any { it.errorCode == 606 } ||
            input.offline.any { it.failure?.errorCode == 606 }

    private fun observesUnavailable(input: ClassifierInput): Boolean =
        input.summarization?.status == FeatureStatusKind.UNAVAILABLE ||
            input.prompt.any { it.status == FeatureStatusKind.UNAVAILABLE } ||
            input.features.any { it.status == FeatureStatusKind.UNAVAILABLE }

    private fun observesSuccessfulGeneration(input: ClassifierInput): Boolean =
        input.prompt.any { it.status == FeatureStatusKind.AVAILABLE && it.generationSucceeded } ||
            input.offline.any { it.outcome == dev.shockstruck.gemininano.model.OfflineInferenceOutcome.SUCCEEDED }

    private fun installedAicorePackage(packages: List<PackageFacts>): String? =
        packages.firstOrNull { it.packageName in AICORE_PACKAGES && it.visible }?.packageName
            ?: packages.firstOrNull { it.packageName in AICORE_PACKAGES }?.packageName

    private fun aicoreReadDeviceConfig(
        matrix: PermissionMatrix?,
        aicorePackage: String?,
    ): PermissionObservation? {
        if (matrix == null) return null
        val ordered = listOfNotNull(aicorePackage) + AICORE_PACKAGES
        return ordered.distinct()
            .firstNotNullOfOrNull { matrix.cell(it, PlatformPermissions.READ_DEVICE_CONFIG) }
    }

    private fun grpcRow(results: List<PcsBindResult>): PcsBindResult? =
        results.firstOrNull { it.role == "grpc" }
            ?: results.firstOrNull { it.componentName in GRPC_SERVICE_NAMES }

    /**
     * A readable PCS association policy that names AICore packages but not the installed
     * one supports the component-access branch of PCS service authorization failure.
     * A policy with no package name or no certificate digest is not used.
     */
    private fun associationPolicyMismatch(
        pcsManifest: ManifestEvidenceSnapshot?,
        aicorePackage: String?,
    ): String? {
        if (pcsManifest == null || aicorePackage == null) return null
        if (pcsManifest.pcc.associationPolicy.state !=
            dev.shockstruck.gemininano.model.PccComponentEvidenceState.DECLARED
        ) {
            return null
        }
        val names = pcsManifest.associationPackageNames
        if (names.isEmpty() || aicorePackage in names) return null
        return "the installed PCS manifest association policy names " +
            names.joinToString(", ") + ", but the installed AICore package is $aicorePackage"
    }

    private companion object {
        val AICORE_PACKAGES = listOf(
            PlatformPrincipals.AICORE,
            PlatformPrincipals.AICORE_LEGACY,
        )

        val GRPC_SERVICE_NAMES: Set<String> = PcsBindCatalog.KNOWN
            .first { it.role == PcsBindRole.GRPC }
            .allNames
            .toSet()

        val NPU_PATTERN = Regex("""\bnpu\b""", RegexOption.IGNORE_CASE)

        val NAMED_PERMISSIONS = listOf(
            "PROVIDE_ON_DEVICE_INTELLIGENCE",
            "MANAGE_VIRTUAL_MACHINE",
            "ACCESS_NPU_MODEL_MANAGER_API",
            "READ_DEVICE_CONFIG",
        )

        val MISSING_WORDS = listOf(
            "missing",
            "not found",
            "not downloaded",
            "absent",
            "no feature",
            "no configuration",
        )
    }
}

/** Builds the message list from the probe results the app already holds. */
object ClassifierMessages {

    fun from(
        summarization: SummarizationProbeResult?,
        prompt: List<PromptConfigStatus>,
        features: List<FeatureProbeResult>,
        offline: List<OfflineInferenceResult>,
    ): List<ClassifierMessage> {
        val messages = mutableListOf<ClassifierMessage>()
        summarization?.failure?.message?.takeIf { it.isNotBlank() }?.let {
            messages += ClassifierMessage("summarization", it)
        }
        prompt.forEach { status ->
            status.message?.takeIf { it.isNotBlank() }?.let {
                messages += ClassifierMessage("prompt:${status.config.label}", it)
            }
        }
        features.forEach { result ->
            result.failure?.message?.takeIf { it.isNotBlank() }?.let {
                messages += ClassifierMessage("feature:${result.feature.displayName}", it)
            }
            result.lastDownload?.failure?.message?.takeIf { it.isNotBlank() }?.let {
                messages += ClassifierMessage("download:${result.feature.displayName}", it)
            }
        }
        offline.forEach { result ->
            result.failure?.message?.takeIf { it.isNotBlank() }?.let {
                messages += ClassifierMessage("offline:${result.feature.displayName}", it)
            }
        }
        return messages.distinctBy { it.source to it.text }
    }
}
