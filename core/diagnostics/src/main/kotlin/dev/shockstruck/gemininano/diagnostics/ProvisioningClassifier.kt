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
 * The installed-manifest facts one principal contributed, kept as the installed-manifest
 * reader produced them. A manifest declaration is never promoted to a runtime UID here.
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

    val partial: Boolean get() = outcome is ManifestReadOutcome.Partial

    /** The app's declared shared user id, when the read returned it. */
    val effectiveSharedUserId: String?
        get() = when (outcome) {
            is ManifestReadOutcome.Read -> outcome.facts.effectiveSharedUserId
            is ManifestReadOutcome.Partial -> outcome.facts.effectiveSharedUserId
            else -> null
        }

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

    val pccComponents: List<dev.shockstruck.gemininano.model.ManifestComponentFacts>
        get() = pcc.pccComponents.matched

    val associationElements: List<dev.shockstruck.gemininano.model.ManifestElementFacts>
        get() = pcc.associationPolicy.matched

    val sandboxAttributes: List<dev.shockstruck.gemininano.model.ManifestSandboxAttributeFacts>
        get() = pcc.otherSandboxAttributes.matched

    /**
     * True only when every matched association element returned at least one package
     * entry and every entry has a readable name. A policy with a missing name is not
     * used to claim a mismatch.
     */
    val associationNamesComplete: Boolean
        get() {
            val elements = associationElements
            if (elements.isEmpty()) return false
            return elements.all { element ->
                element.packages.isNotEmpty() && element.packages.all { !it.name.isNullOrBlank() }
            }
        }
}

/** The raw `android:privateComputeCore` attribute a component carries, when readable. */
fun dev.shockstruck.gemininano.model.ManifestComponentFacts
    .privateComputeCoreAttribute(): dev.shockstruck.gemininano.model.ManifestAttributeFacts? =
    matchingAttributes.firstOrNull {
        it.name.substringAfterLast(':') == "privateComputeCore"
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
 * A class is selected only when an observation holds for every caller, or when the
 * message itself names the failure:
 *
 * - a message whose same clause names the missing configuration, an NPU denial, a
 *   remote rejection or model/feature registration;
 * - the installed PCS gRPC service is declared but not effectively enabled
 *   (initialization), or declared but not exported (authorization, unless PCS and
 *   AICore share a UID);
 * - a complete installed PCS manifest whose association policy names AICore packages
 *   other than the visible installed AICore package.
 *
 * Everything else is [ProvisioningFailureClass.INDETERMINATE] naming the candidates and
 * the observation that would separate them. In particular:
 *
 * - this app's own bind outcomes are evidence only. PCS's policy lists AICore and not
 *   this app, so a `SecurityException` is expected on a healthy device; a timeout looks
 *   like a slow cold start; a null binding or a died binding, and the "no caller check"
 *   reading, come from a source mirror rather than this PCS;
 * - a 606/UNAVAILABLE pattern alone never selects a class: Google's documentation gives
 *   configuration-not-downloaded and an unlocked bootloader as 606 causes and leaves
 *   UNAVAILABLE undifferentiated, and the July 2025 public PCS dump that refuses the
 *   manifest was not read on the phone;
 * - `READ_DEVICE_CONFIG` denied supports but does not prove the July 2025 gate; granted
 *   does not refute it, because an unwritten DeviceConfig namespace leaves the same
 *   default;
 * - a denied NPU, on-device-intelligence or virtual-machine permission is never
 *   evidence for NPU access restrictions; a message must name the NPU and a denial,
 *   refusal or failed access;
 * - a local "not eligible" message without a server, remote or backend source is a
 *   candidate only;
 * - a null binding of `GenAiInferenceService` is never used: only the gRPC row counts,
 *   and this app's own bind never stands in for AICore's access;
 * - a generation that succeeded conflicts with any selected class and makes the result
 *   INDETERMINATE;
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

        val successes = successEvidence(input)
        evidence += successes

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
                PcsBindOutcome.NOT_ENABLED -> support(
                    ProvisioningFailureClass.PCS_INITIALIZATION_FAILURE,
                    "the installed PCS declares the gRPC service but it is not effectively " +
                        "enabled; that holds for every caller, not only this app",
                )

                PcsBindOutcome.NOT_EXPORTED -> {
                    val sharing = uidSharing(pcsManifest, aicoreManifest)
                    evidence += ClassifierEvidence(
                        statement = "The installed PCS declares the gRPC service but it is not " +
                            "exported; PCS and AICore UID sharing: ${sharing.label}." +
                            when (sharing) {
                                UidSharing.SAME ->
                                    " Within a shared UID an unexported component remains " +
                                        "reachable to AICore and is not a blocker."
                                UidSharing.DIFFERENT ->
                                    " Different UIDs mean every other app, including AICore, is " +
                                        "refused by the component's export setting."
                                UidSharing.UNKNOWN ->
                                    " UID sharing is not established, so the unexported component " +
                                        "may still be reachable within a shared UID."
                            },
                        strength = EvidenceStrength.INFERRED,
                        source = "installed-manifest reader",
                    )
                    when (sharing) {
                        UidSharing.SAME -> Unit
                        UidSharing.DIFFERENT -> support(
                            ProvisioningFailureClass.PCS_SERVICE_AUTHORIZATION_FAILURE,
                            "the installed PCS declares the gRPC service but it is not exported, " +
                                "and PCS and AICore have different UIDs; that holds for every caller",
                        )

                        UidSharing.UNKNOWN -> support(
                            ProvisioningFailureClass.PCS_SERVICE_AUTHORIZATION_FAILURE,
                            "the installed PCS declares the gRPC service but it is not exported; " +
                                "PCS and AICore UID sharing is not established, so an unexported " +
                                "component may still be reachable within a shared UID",
                        )
                    }
                }

                // This app's own bind outcome is evidence only, never a selection.
                else -> Unit
            }
        }
        associationPolicyMismatch(pcsManifest, aicorePackage)?.let { mismatch ->
            support(ProvisioningFailureClass.PCS_SERVICE_AUTHORIZATION_FAILURE, mismatch)
        }

        val supported = supporters.keys.toList()
        return when {
            supported.isNotEmpty() && successes.isNotEmpty() ->
                conflictingWithSuccess(supported, supporters, evidence)

            supported.size == 1 ->
                selected(supported.single(), supporters.getValue(supported.single()), evidence, input, grpc)

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
                    append(". The selected observation holds for every caller; this app's own " +
                        "failed bind is not used as the selection.")
                ProvisioningFailureClass.PCS_SERVICE_AUTHORIZATION_FAILURE ->
                    append(". The selected observation holds for every caller unless PCS and " +
                        "AICore share a UID; this app's own failed bind is not used as the " +
                        "selection.")
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

    private fun conflictingWithSuccess(
        supported: List<ProvisioningFailureClass>,
        supporters: Map<ProvisioningFailureClass, List<String>>,
        evidence: List<ClassifierEvidence>,
    ): ProvisioningClassification {
        val names = supported.joinToString { it.label }
        val candidates = supported.map { failureClass ->
            ClassifierCandidate(
                failureClass = failureClass,
                why = supporters.getValue(failureClass).joinToString("; ") +
                    "; a generation succeeded, which conflicts with a class asserted for the " +
                    "same path",
                missingObservation = "a repeated failure on the same path with no successful " +
                    "generation, or an explanation of which path succeeded",
            )
        }
        return ProvisioningClassification(
            result = ProvisioningFailureClass.INDETERMINATE,
            summary = "INDETERMINATE — observed evidence supports $names, but a generation " +
                "succeeded, and the app has no observation that reconciles the success with the " +
                "failure class.",
            evidence = evidence,
            refutations = listOf(
                "A successful generation is direct evidence that the tested path is provisioned; " +
                    "it conflicts with any selected failure class.",
                "A repeated failure on the same path with no successful generation would remove " +
                    "the conflict.",
            ),
            candidates = candidates,
            nextObservation = "Repeat the failing probe and the succeeding generation separately " +
                "and export again; the export keeps both observations with their sources.",
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
        val successes = successEvidence(input)
        if (!observed606 && !observedUnavailable && successes.isNotEmpty()) {
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
        val candidates = linkedMapOf<ProvisioningFailureClass, ClassifierCandidate>()
        fun candidate(
            failureClass: ProvisioningFailureClass,
            why: String,
            missingObservation: String,
        ) {
            val existing = candidates[failureClass]
            candidates[failureClass] = if (existing == null) {
                ClassifierCandidate(failureClass, why, missingObservation)
            } else {
                existing.copy(
                    why = existing.why + " " + why,
                    missingObservation = existing.missingObservation + " " + missingObservation,
                )
            }
        }

        if (observed606 || observedUnavailable) {
            candidate(
                ProvisioningFailureClass.MISSING_FEATURE_CONFIGURATION,
                "606 is Google's documented configuration-not-downloaded signature" +
                    (if (observedUnavailable) " and at least one feature reported UNAVAILABLE" else ""),
                "a positive observation that AICore's feature store lacks the entry — a PCS " +
                    "GetManifestConfig response or an AICore message naming configuration",
            )
            candidate(
                ProvisioningFailureClass.PCS_SERVICE_AUTHORIZATION_FAILURE,
                "PCS refuses GetManifestConfig with \"feature disabled\" in the July 2025 " +
                    "public PCS dump; READ_DEVICE_CONFIG denied supports that gate but does not " +
                    "prove it, and granted does not refute it because an unwritten DeviceConfig " +
                    "namespace leaves the default",
                "the PCS GetManifestConfig response; an ordinary app cannot send that RPC, so " +
                    "only an AICore-side observation or a public document can settle it",
            )
        }
        when (grpc?.outcome) {
            null, PcsBindOutcome.NOT_RUN, PcsBindOutcome.NOT_READ,
            PcsBindOutcome.NOT_DECLARED, PcsBindOutcome.ENABLED_UNKNOWN,
            -> candidate(
                ProvisioningFailureClass.PCS_INITIALIZATION_FAILURE,
                "no gRPC bind result establishes PCS reachability" +
                    (grpc?.let { " (last row: ${it.outcome.label})" } ?: " (bind probe not run)"),
                "one AstreaGrpcService bind by this app; AICore's own access is never " +
                    "established by it",
            )

            PcsBindOutcome.SECURITY_EXCEPTION -> candidate(
                ProvisioningFailureClass.PCS_SERVICE_AUTHORIZATION_FAILURE,
                "this app's own gRPC bind threw a SecurityException; PCS's policy lists AICore " +
                    "and not this app, so this is expected on a healthy device",
                "AICore's own bind result",
            )

            PcsBindOutcome.TIMEOUT -> candidate(
                ProvisioningFailureClass.PCS_INITIALIZATION_FAILURE,
                "this app's own gRPC bind timed out; a slow cold start looks the same",
                "AICore's own bind result, or a completed PCS response",
            )

            PcsBindOutcome.REQUIRES_PERMISSION -> candidate(
                ProvisioningFailureClass.PCS_SERVICE_AUTHORIZATION_FAILURE,
                "the gRPC service declares a binding permission this app does not hold; that " +
                    "says nothing about AICore's access",
                "AICore's own bind result and whether AICore holds the required permission",
            )

            PcsBindOutcome.NULL_BINDING, PcsBindOutcome.BINDING_DIED -> candidate(
                ProvisioningFailureClass.PCS_INITIALIZATION_FAILURE,
                "this app's own gRPC bind returned ${grpc.outcome.label}; the \"no caller " +
                    "check\" reading comes from a source mirror, not this PCS",
                "AICore's own bind result",
            )

            PcsBindOutcome.BIND_REFUSED, PcsBindOutcome.BIND_ERROR -> {
                candidate(
                    ProvisioningFailureClass.PCS_INITIALIZATION_FAILURE,
                    "this app's own gRPC bind was ${grpc.outcome.label}; it could be an " +
                        "initialization or a caller-specific result",
                    "AICore's own bind result",
                )
                candidate(
                    ProvisioningFailureClass.PCS_SERVICE_AUTHORIZATION_FAILURE,
                    "this app's own gRPC bind was ${grpc.outcome.label}; it could be a " +
                        "caller-specific refusal",
                    "AICore's own bind result",
                )
            }

            // These two select a class when they are unambiguous; reaching here means a
            // success conflict or a same-UID case, and neither becomes a candidate.
            PcsBindOutcome.NOT_ENABLED, PcsBindOutcome.NOT_EXPORTED,
            PcsBindOutcome.BINDER_RETURNED,
            -> Unit
        }
        if (localEligibilityMessages(input.messages).isNotEmpty()) {
            candidate(
                ProvisioningFailureClass.REMOTE_PROVISIONING_REJECTION,
                "an eligibility message has no server, remote or backend wording; local " +
                    "eligibility reads the same",
                "a PCS or backend response naming device eligibility",
            )
        }
        if (observed606 || observedUnavailable) {
            candidate(
                ProvisioningFailureClass.REMOTE_PROVISIONING_REJECTION,
                "no PCS or backend response was observed, so a remote rejection is neither " +
                    "supported nor excluded",
                "a PCS or backend response naming device eligibility or a rejection; the app " +
                    "sends no request",
            )
            candidate(
                ProvisioningFailureClass.MODEL_REGISTRATION_FAILURE,
                "no AICore message naming model or feature registration was observed",
                "an AICore error naming model or feature registration",
            )
        }
        candidate(
            ProvisioningFailureClass.NPU_ACCESS_RESTRICTIONS,
            "no observation names the NPU and a denial, refusal or failed access; a denied " +
                "ACCESS_NPU_MODEL_MANAGER_API, USE_ON_DEVICE_INTELLIGENCE or " +
                "MANAGE_VIRTUAL_MACHINE grant is not evidence",
            "an AICore error naming NPU access and a denial; an NPU system-feature name alone " +
                "is availability, not a restriction",
        )
        val list = candidates.values.toList()
        return ProvisioningClassification(
            result = ProvisioningFailureClass.INDETERMINATE,
            summary = if (observed606 || observedUnavailable) {
                "INDETERMINATE — the 606/UNAVAILABLE pattern is observed, but the app cannot " +
                    "separate the remaining causes."
            } else {
                "INDETERMINATE — no failure signature with a separating observation is present."
            },
            evidence = evidence,
            refutations = list.map { "${it.failureClass.label}: ${it.missingObservation}" },
            candidates = list,
            nextObservation = nextObservation(ProvisioningFailureClass.INDETERMINATE, input, grpc),
        )
    }

    private fun nextObservation(
        failureClass: ProvisioningFailureClass,
        input: ClassifierInput,
        grpc: PcsBindResult?,
    ): String? = when (failureClass) {
        ProvisioningFailureClass.INDETERMINATE -> when {
            grpc?.outcome in EVIDENCE_ONLY_BIND_OUTCOMES -> {
                "This app's own gRPC result (${grpc?.outcome?.label}) is evidence only. The next " +
                    "separating observation is AICore's own bind or a PCS GetManifestConfig " +
                    "response, which an ordinary app cannot obtain; update AICore, restart, and " +
                    "probe again."
            }

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
            "The declared gRPC service becoming effectively enabled and returning a binder would " +
                "refute a disabled-component initialization failure.",
            "A PCS process that answers within the 3-second window would refute a timeout.",
            "AICore's own bind succeeding would show the failure was specific to this app, not PCS.",
        )

        ProvisioningFailureClass.PCS_SERVICE_AUTHORIZATION_FAILURE -> listOf(
            "An AICore-originated bind or RPC succeeding would refute a PCS-wide authorization " +
                "failure.",
            "PCS and AICore sharing a UID would refute an unexported-component failure.",
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
                    (if (packageFacts.visible) "visible" else "not visible to this app") + ", " +
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
                    source = "July 2025 public PCS dump review",
                ),
            )

            PermissionStatus.DENIED -> add(
                ClassifierEvidence(
                    statement = "The denied PCS READ_DEVICE_CONFIG supports, but does not prove, " +
                        "the July 2025 PCS gate in which GetManifestConfig is refused as " +
                        "\"feature disabled\" unless DeviceConfig enables it. That gate was read " +
                        "in a source mirror; the phone's PCS build was not read.",
                    strength = EvidenceStrength.INFERRED,
                    source = "July 2025 public PCS dump",
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
                        source = "installed-manifest reader",
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
        val evidence = mutableListOf(
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
        if (grpc.outcome == PcsBindOutcome.SECURITY_EXCEPTION) {
            evidence += ClassifierEvidence(
                statement = "A SecurityException from this app's own bind is expected even on a " +
                    "healthy device: PCS's policy lists AICore and not this app. It is evidence " +
                    "about this app, not about AICore's access.",
                strength = EvidenceStrength.INFERRED,
                source = "PCS bind probe",
            )
        }
        return evidence
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
            val componentNames = snapshot.pccComponents.map {
                "${it.kind}:${it.name ?: "unnamed"}"
            }
            val elementNames = snapshot.associationElements.map {
                it.name + "(" + it.attributes.joinToString(",") { attribute -> attribute.name } + ")"
            }
            val sandboxNames = snapshot.sandboxAttributes.map {
                "${it.componentKind}:${it.componentName ?: "unnamed"}/${it.attribute.name}"
            }
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
                    "(${snapshot.pcc.pccComponents.reason})" +
                    (if (componentNames.isNotEmpty()) " [" + componentNames.joinToString(", ") + "]" else "") +
                    "; association policy: ${policy.state.name} (${policy.reason})" +
                    (if (elementNames.isNotEmpty()) " elements=[" + elementNames.joinToString(", ") + "]" else "") +
                    (if (packageNames.isNotEmpty()) " packages=${packageNames.joinToString(",")}" else "") +
                    (if (sandboxNames.isNotEmpty()) " sandbox=[" + sandboxNames.joinToString(", ") + "]" else "") +
                    (if (snapshot.partial) " partial read: absence cannot be claimed;" else "") +
                    ". A declaration is not a runtime PCC UID, and a declared association policy " +
                    "is not proof of enforcement; no signer digest is compared because no " +
                    "public-source digest set is bundled.",
                strength = EvidenceStrength.OBSERVED,
                source = "installed-manifest reader",
            )
        }
    }

    private fun featurePatternEvidence(input: ClassifierInput): List<ClassifierEvidence> = buildList {
        val summarizationCode = input.summarization?.failure?.errorCode
        val prompt606 = input.prompt.filter { it.errorCode == 606 }
        val feature606 = input.features.filter { it.errorCode == 606 }
        val offline606 = input.offline.filter { it.failure?.errorCode == 606 }
        if (summarizationCode == 606 || prompt606.isNotEmpty() ||
            feature606.isNotEmpty() || offline606.isNotEmpty()
        ) {
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
                        (if (offline606.isNotEmpty()) {
                            " (offline ${offline606.map { it.feature.displayName }.joinToString(",")})"
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
        val summarization601 = summarizationCode == 601
        val prompt601 = input.prompt.filter { it.errorCode == 601 }
        val feature601 = input.features.filter { it.errorCode == 601 }
        val offline601 = input.offline.filter { it.failure?.errorCode == 601 }
        if (summarization601 || prompt601.isNotEmpty() ||
            feature601.isNotEmpty() || offline601.isNotEmpty()
        ) {
            add(
                ClassifierEvidence(
                    statement = "601 observed" +
                        (if (summarization601) " (summarization)" else "") +
                        (if (prompt601.isNotEmpty()) {
                            " (Prompt ${prompt601.map { it.config.label }.joinToString(",")})"
                        } else {
                            ""
                        }) +
                        (if (feature601.isNotEmpty()) {
                            " (features ${feature601.map { it.feature.displayName }.joinToString(",")})"
                        } else {
                            ""
                        }) +
                        (if (offline601.isNotEmpty()) {
                            " (offline ${offline601.map { it.feature.displayName }.joinToString(",")})"
                        } else {
                            ""
                        }) +
                        ": Google documents it as AICore failing to bind. The message does not " +
                        "name which layer refused, so it is not a separating observation for the " +
                        "six classes.",
                    strength = EvidenceStrength.OBSERVED,
                    source = "ML Kit probes",
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
            val text = message.text.lowercase()
            val namesNpu = NPU_PATTERN.containsMatchIn(text) ||
                text.contains("neural processing")
            namesNpu && DENIAL_WORDS.any { text.contains(it) } && !hasNegatedFailure(text)
        }

    private fun configurationMessages(messages: List<ClassifierMessage>): List<ClassifierMessage> =
        messages.filter { message ->
            clausesOf(message.text).any { clause ->
                (clause.contains("configuration") || clause.contains("manifest")) &&
                    (MISSING_WORDS.any { clause.contains(it) } ||
                        FAILURE_WORDS.any { clause.contains(it) }) &&
                    !hasNegatedFailure(clause)
            }
        }

    private fun remoteRejectionMessages(messages: List<ClassifierMessage>): List<ClassifierMessage> =
        messages.filter { message ->
            val text = message.text.lowercase()
            val eligibility = isEligibilityNegative(text) &&
                REMOTE_SOURCE_WORDS.any { text.contains(it) }
            val rejected = text.contains("reject") &&
                (REMOTE_SOURCE_WORDS.any { text.contains(it) } || text.contains("provision"))
            val httpAuth = (text.contains("http 401") || text.contains("http 403") ||
                text.contains("forbidden")) &&
                (REMOTE_SOURCE_WORDS.any { text.contains(it) } ||
                    text.contains("download") || text.contains("provision"))
            eligibility || rejected || httpAuth
        }

    private fun localEligibilityMessages(messages: List<ClassifierMessage>): List<ClassifierMessage> =
        messages.filter { message ->
            val text = message.text.lowercase()
            isEligibilityNegative(text) && REMOTE_SOURCE_WORDS.none { text.contains(it) }
        }

    private fun registrationMessages(messages: List<ClassifierMessage>): List<ClassifierMessage> =
        messages.filter { message ->
            clausesOf(message.text).any { clause ->
                clause.contains("regist") &&
                    (clause.contains("model") || clause.contains("feature")) &&
                    FAILURE_WORDS.any { clause.contains(it) } &&
                    !hasNegatedFailure(clause) &&
                    !hasSuccessWording(clause)
            }
        }

    private fun isEligibilityNegative(text: String): Boolean =
        text.contains("not eligible") || text.contains("ineligible")

    private fun clausesOf(text: String): List<String> =
        text.lowercase()
            .split(CLAUSE_SPLIT)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** A failure word in a negated clause ("no error", "without failure") is not a failure. */
    private fun hasNegatedFailure(text: String): Boolean =
        NEGATED_FAILURE_PHRASES.any { text.contains(it) }

    /** "registration complete" is not a failure even though the clause has "regist". */
    private fun hasSuccessWording(text: String): Boolean =
        SUCCESS_WORDS.any { word ->
            Regex("""\b${Regex.escape(word)}\b""").containsMatchIn(text)
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

    /** Any generation the app itself observed to return, with the source that observed it. */
    private fun successEvidence(input: ClassifierInput): List<ClassifierEvidence> = buildList {
        input.prompt
            .filter { it.status == FeatureStatusKind.AVAILABLE && it.generationSucceeded }
            .forEach { status ->
                add(
                    ClassifierEvidence(
                        statement = "Prompt ${status.config.label} is AVAILABLE and its generation " +
                            "succeeded" +
                            (status.baseModelName?.let { " (base model: $it)" } ?: "") + ".",
                        strength = EvidenceStrength.OBSERVED,
                        source = "ML Kit Prompt probe",
                    ),
                )
            }
        input.offline
            .filter {
                it.outcome == dev.shockstruck.gemininano.model.OfflineInferenceOutcome.SUCCEEDED
            }
            .forEach { result ->
                add(
                    ClassifierEvidence(
                        statement = "Offline inference for ${result.feature.displayName} returned " +
                            (result.output?.let { "\"$it\"" } ?: "a result") + ".",
                        strength = EvidenceStrength.OBSERVED,
                        source = "offline inference matrix",
                    ),
                )
            }
    }

    /** Only a visible package can be called the installed AICore; no fallback. */
    private fun installedAicorePackage(packages: List<PackageFacts>): String? =
        packages.firstOrNull { it.packageName in AICORE_PACKAGES && it.visible }?.packageName

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
     * A complete, fully readable PCS association policy that names AICore packages but not
     * the visible installed AICore package supports the component-access branch of PCS
     * service authorization failure. A partial read, an unnamed package entry, or a
     * non-visible AICore is not used.
     */
    private fun associationPolicyMismatch(
        pcsManifest: ManifestEvidenceSnapshot?,
        aicorePackage: String?,
    ): String? {
        if (pcsManifest == null || aicorePackage == null) return null
        if (!pcsManifest.complete) return null
        if (!pcsManifest.associationNamesComplete) return null
        if (pcsManifest.pcc.associationPolicy.state !=
            dev.shockstruck.gemininano.model.PccComponentEvidenceState.DECLARED
        ) {
            return null
        }
        val names = pcsManifest.associationPackageNames
        if (names.isEmpty() || aicorePackage in names) return null
        return "the complete installed PCS manifest association policy names " +
            names.joinToString(", ") + ", but the visible installed AICore package is " +
            aicorePackage + "; a declared policy is not proof of enforcement"
    }

    private fun uidSharing(
        pcsManifest: ManifestEvidenceSnapshot?,
        aicoreManifest: ManifestEvidenceSnapshot?,
    ): UidSharing {
        if (pcsManifest?.outcome !is ManifestReadOutcome.Read) return UidSharing.UNKNOWN
        if (aicoreManifest?.outcome !is ManifestReadOutcome.Read) return UidSharing.UNKNOWN
        val pcsUid = pcsManifest.effectiveSharedUserId
        val aicoreUid = aicoreManifest.effectiveSharedUserId
        return if (pcsUid != null && pcsUid == aicoreUid) UidSharing.SAME else UidSharing.DIFFERENT
    }

    private enum class UidSharing(val label: String) {
        SAME("same UID"),
        DIFFERENT("different UIDs"),
        UNKNOWN("not established"),
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

        /** Outcomes of this app's own bind: evidence and candidates only, never selections. */
        val EVIDENCE_ONLY_BIND_OUTCOMES = setOf(
            PcsBindOutcome.SECURITY_EXCEPTION,
            PcsBindOutcome.TIMEOUT,
            PcsBindOutcome.NULL_BINDING,
            PcsBindOutcome.BINDING_DIED,
            PcsBindOutcome.BIND_REFUSED,
            PcsBindOutcome.BIND_ERROR,
        )

        val DENIAL_WORDS = listOf(
            "denied",
            "denial",
            "refused",
            "refusal",
            "forbidden",
            "blocked",
            "restricted",
            "not allowed",
            "unavailable",
            "cannot",
            "no access",
            "fail",
        )

        val FAILURE_WORDS = listOf(
            "fail",
            "error",
            "unable",
            "not registered",
            "could not",
            "cannot",
            "refused",
            "rejected",
            "missing",
        )

        val SUCCESS_WORDS = listOf(
            "complete",
            "completed",
            "succeeded",
            "successfully",
            "success",
            "passed",
        )

        val NEGATED_FAILURE_PHRASES = listOf(
            "no error",
            "no failure",
            "without error",
            "without failure",
            "not an error",
            "not failed",
            "no errors",
            "0 errors",
            "no denial",
            "no refusal",
            "no problem",
        )

        val REMOTE_SOURCE_WORDS = listOf("server", "remote", "backend", "cloud")

        val CLAUSE_SPLIT = Regex(
            """[;,.!?]|\bbut\b|\bwhile\b|\balthough\b|\bhowever\b|\bthen\b""",
        )

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
