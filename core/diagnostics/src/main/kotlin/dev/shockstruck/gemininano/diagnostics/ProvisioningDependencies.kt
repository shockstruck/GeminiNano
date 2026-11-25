package dev.shockstruck.gemininano.diagnostics

import dev.shockstruck.gemininano.model.AppUserFacts
import dev.shockstruck.gemininano.model.InstalledCodeDefaultValue
import dev.shockstruck.gemininano.model.InstalledCodeOutcome
import dev.shockstruck.gemininano.model.InstalledCodePackageEvidence
import dev.shockstruck.gemininano.model.InstalledCodePackageFacts
import dev.shockstruck.gemininano.model.InstalledCodePresence
import dev.shockstruck.gemininano.model.ManifestReadOutcome
import dev.shockstruck.gemininano.model.PackageFacts
import dev.shockstruck.gemininano.model.PermissionMatrix
import dev.shockstruck.gemininano.model.PermissionMatrixFormatting
import dev.shockstruck.gemininano.model.PermissionObservation
import dev.shockstruck.gemininano.model.PermissionStatus
import dev.shockstruck.gemininano.model.PlatformPermissions
import dev.shockstruck.gemininano.model.PlatformPrincipals

/**
 * One step of the provisioning chain Stage 9 traced, in the order the dump shows them.
 * The order is the evaluation order: the first step shown to fail is the answer.
 */
enum class DependencyStepId(val label: String) {
    CLIENT_STATUS_CHECK("client status check (ML Kit)"),
    SYSTEM_USER("system (owner) user"),
    AICORE_POPULATOR_GATE("AICore populator selection gate"),
    AICORE_BUILD_ID("AICore build-id gate (AicDataRelease__build_id_*)"),
    PCS_ASSOCIATION("PCS association check"),
    PCS_PROTECTED_DOWNLOAD("PCS ProtectedDownload__enabled gate"),
    PCS_MANIFEST_RPC("PCS GetManifestConfig RPC with integrity and attestation"),
    MANIFEST_TO_MODEL("manifest to download, verification and registration"),
    ISOLATED_INFERENCE("isolated inference start"),
}

/** What the evidence shows for one step. A missing observation is never "passed". */
enum class DependencyStepOutcome(val label: String) {
    PASSED("passed"),
    FAILED("failed"),

    /** The traced step exists, but the fact that would decide it is missing or unreadable. */
    NOT_OBSERVABLE("not observable"),

    /** The step is part of the chain but has no observation path in this app at all. */
    NOT_TESTED("not tested"),
}

/** How strong the statement is: this device's observation, an inference, or a public dump. */
enum class DependencyEvidenceStrength(val label: String) {
    OBSERVED("observed"),
    INFERRED("inferred"),

    /** A July 2025 public-dump fact; never a fact about the installed build. */
    DUMP_ONLY("dump only"),

    /** A fact read from the public source trace; not an observation of this phone. */
    SOURCE_TRACE("source trace"),
}

/** One evidence item for a step. Every claim says where it came from. */
data class DependencyEvidence(
    val statement: String,
    val strength: DependencyEvidenceStrength,
    val source: String,
) {
    val label: String get() = "[${strength.label}] $statement ($source)"
}

/** One evaluated step. */
data class DependencyStep(
    val id: DependencyStepId,
    val outcome: DependencyStepOutcome,
    val summary: String,
    val evidence: List<DependencyEvidence> = emptyList(),
)

/** The ordered evaluation. [steps] is always the full chain, in chain order. */
data class ProvisioningDependencyEvaluation(
    val steps: List<DependencyStep>,
) {
    val firstFailingStep: DependencyStep?
        get() = steps.firstOrNull { it.outcome == DependencyStepOutcome.FAILED }

    /** The first step with an observation path whose deciding fact is missing. */
    val firstUnsettledStep: DependencyStep?
        get() = steps.firstOrNull { it.outcome == DependencyStepOutcome.NOT_OBSERVABLE }

    val resultLabel: String
        get() = firstFailingStep?.let { "First failing dependency: ${it.id.label}" }
            ?: "Cannot be determined: no step is shown to fail"

    val summary: String
        get() {
            val failing = firstFailingStep
            if (failing != null) return failing.summary
            val unsettled = firstUnsettledStep
                ?: return "Every traced step passed; the chain itself did not fail here."
            return "No step is shown to fail. The earliest step the evidence cannot settle is " +
                "\"${unsettled.id.label}\" (${unsettled.outcome.label})."
        }
}

/** Everything the evaluation may use; every member is an observed fact already. */
data class DependencyEvaluationInput(
    val appUser: AppUserFacts? = null,
    val packages: List<PackageFacts> = emptyList(),
    val matrix: PermissionMatrix? = null,
    val manifests: List<ManifestEvidenceSnapshot> = emptyList(),
    val installedCode: List<InstalledCodePackageEvidence> = emptyList(),
)

/**
 * Pure ordered evaluation of the provisioning chain Stage 9 traced.
 *
 * A step is [DependencyStepOutcome.FAILED] only from a fact about the installed package
 * plus an observed denial or absence. A dump fact is labelled
 * [DependencyEvidenceStrength.DUMP_ONLY] and never decides a step. A step that cannot be
 * observed is never reported as passed or failed.
 *
 * The flag gates (the AICore build id and PCS `ProtectedDownload__enabled`) fail only
 * when all of the following hold: the marker is present and loaded by code, every
 * reference resolves the same single literal default, that literal fits the flag's type,
 * a method referencing `READ_DEVICE_CONFIG` also invokes a permission check, and
 * `READ_DEVICE_CONFIG` is observed denied. Anything less is "not observable".
 */
class ProvisioningDependencyEvaluator {

    fun evaluate(input: DependencyEvaluationInput): ProvisioningDependencyEvaluation {
        val aicore = installedAicorePackage(input.packages)
        val aicoreCode = aicoreCodeEvidence(input.installedCode)
        val aicoreGrantPackage = aicoreCode?.requestedPackage ?: aicore?.packageName
        val pcsCode = input.installedCode.firstOrNull {
            it.requestedPackage == PlatformPrincipals.PCS
        }

        val steps = listOf(
            clientStatusCheckStep(),
            systemUserStep(input.appUser, aicore),
            populatorGateStep(),
            flagGateStep(
                id = DependencyStepId.AICORE_BUILD_ID,
                marker = AICORE_BUILD_ID_MARKER,
                namespace = "aicore",
                expected = FlagType.INT,
                code = aicoreCode,
                grant = aicoreGrantPackage?.let {
                    input.matrix?.cell(it, PlatformPermissions.READ_DEVICE_CONFIG)
                },
                dumpDefault = "0",
            ),
            associationStep(input.manifests, aicore),
            flagGateStep(
                id = DependencyStepId.PCS_PROTECTED_DOWNLOAD,
                marker = PROTECTED_DOWNLOAD_MARKER,
                namespace = "device_personalization_services",
                expected = FlagType.BOOLEAN,
                code = pcsCode,
                grant = input.matrix?.cell(
                    PlatformPrincipals.PCS,
                    PlatformPermissions.READ_DEVICE_CONFIG,
                ),
                dumpDefault = "false",
            ),
            laterStep(
                DependencyStepId.PCS_MANIFEST_RPC,
                "PCS reads DeviceConfig only with READ_DEVICE_CONFIG, and the July 2025 dump " +
                    "rejects GetManifestConfig with \"feature disabled\" while the flag is " +
                    "false. The app cannot send that RPC or observe AICore's request, so no " +
                    "installed-code fact or observation decides this step.",
                "July 2025 PCS dump (Stage 6f and Stage 9)",
            ),
            laterStep(
                DependencyStepId.MANIFEST_TO_MODEL,
                "Stage 9 traced no download call from the manifest to MDD file groups; the " +
                    "flags it named (for example AicModels__protected_download_v2_enabled) were " +
                    "read in the dump only. No observation of a download, verification or " +
                    "registration is available to this app.",
                "Stage 9 ordered trace",
            ),
            laterStep(
                DependencyStepId.ISOLATED_INFERENCE,
                "Neither the download nor the isolated inference start was traced, and this app " +
                    "cannot observe AICore's process. Nothing here can be called passed or failed.",
                "Stage 9 ordered trace",
            ),
        )
        return ProvisioningDependencyEvaluation(steps)
    }

    private fun clientStatusCheckStep() = DependencyStep(
        id = DependencyStepId.CLIENT_STATUS_CHECK,
        outcome = DependencyStepOutcome.NOT_TESTED,
        summary = "Stage 9 did not trace the ML Kit client status check. This app's own Prompt " +
            "and summarization rows are client observations, not this step's outcome.",
        evidence = listOf(
            DependencyEvidence(
                statement = "The client status check is the entry point of the chain and was not " +
                    "traced; it is reported as not tested rather than passed or failed.",
                strength = DependencyEvidenceStrength.SOURCE_TRACE,
                source = "Stage 9 ordered trace",
            ),
        ),
    )

    private fun systemUserStep(
        appUser: AppUserFacts?,
        aicore: PackageFacts?,
    ): DependencyStep {
        val id = DependencyStepId.SYSTEM_USER
        val dumpEvidence = DependencyEvidence(
            statement = "In the July 2025 AICore dump, model populators are set up only for the " +
                "system (owner) user.",
            strength = DependencyEvidenceStrength.DUMP_ONLY,
            source = "July 2025 public AICore dump (Stage 9 review)",
        )
        if (appUser == null) {
            return DependencyStep(
                id,
                DependencyStepOutcome.NOT_OBSERVABLE,
                "UserManager.isSystemUser() was not read, so this app's user is not established.",
                listOf(dumpEvidence),
            )
        }
        val evidence = mutableListOf(dumpEvidence)
        appUser.readErrorLabel?.let { error ->
            evidence += DependencyEvidence(
                statement = "UserManager.isSystemUser() failed: $error.",
                strength = DependencyEvidenceStrength.OBSERVED,
                source = "app process",
            )
        }
        return when (appUser.isSystemUser) {
            null -> DependencyStep(
                id,
                DependencyStepOutcome.NOT_OBSERVABLE,
                "This app's user cannot be established: UserManager.isSystemUser() returned no value.",
                evidence,
            )

            false -> DependencyStep(
                id,
                DependencyStepOutcome.FAILED,
                "This app runs in a secondary user profile, not the system (owner) user. In the " +
                    "July 2025 AICore dump populators exist only for the system user, so " +
                    "provisioning would not start. Next step: install and test in the owner profile.",
                evidence + DependencyEvidence(
                    statement = "UserManager.isSystemUser() reported false for this app's process.",
                    strength = DependencyEvidenceStrength.OBSERVED,
                    source = "app process",
                ),
            )

            true -> {
                evidence += DependencyEvidence(
                    statement = "UserManager.isSystemUser() reported true for this app's process.",
                    strength = DependencyEvidenceStrength.OBSERVED,
                    source = "app process",
                )
                if (aicore?.visible == true) {
                    DependencyStep(
                        id,
                        DependencyStepOutcome.PASSED,
                        "This app runs in the system (owner) user and sees the installed AICore " +
                            "package in the same profile, so both are in the same user.",
                        evidence + DependencyEvidence(
                            statement = "The installed AICore package ${aicore.packageName} is " +
                                "visible to this app in its own profile.",
                            strength = DependencyEvidenceStrength.OBSERVED,
                            source = "package facts",
                        ),
                    )
                } else {
                    DependencyStep(
                        id,
                        DependencyStepOutcome.NOT_OBSERVABLE,
                        "This app is in the system (owner) user, but the installed AICore package " +
                            "is not visible in this profile, so AICore's user membership is not " +
                            "established from this app alone.",
                        evidence,
                    )
                }
            }
        }
    }

    private fun populatorGateStep() = DependencyStep(
        id = DependencyStepId.AICORE_POPULATOR_GATE,
        outcome = DependencyStepOutcome.NOT_OBSERVABLE,
        summary = "The July 2025 dump returns no populator unless a second flag holds; that " +
            "flag's name and default were not read, and no allowlisted installed-code marker " +
            "identifies it, so the installed code cannot settle this step.",
        evidence = listOf(
            DependencyEvidence(
                statement = "aicore_dump bem.java: case 0 returns no populator unless " +
                    "rq.z(context) (UserManager.isSystemUser()) and beg.d(flag) hold; the flag's " +
                    "default was not read.",
                strength = DependencyEvidenceStrength.DUMP_ONLY,
                source = "July 2025 public AICore dump (Stage 9 review)",
            ),
        ),
    )

    private fun laterStep(
        id: DependencyStepId,
        reason: String,
        source: String,
    ) = DependencyStep(
        id = id,
        outcome = DependencyStepOutcome.NOT_OBSERVABLE,
        summary = reason,
        evidence = listOf(
            DependencyEvidence(
                statement = "This step was not traced to an observation and this app holds no " +
                    "probe for it.",
                strength = DependencyEvidenceStrength.SOURCE_TRACE,
                source = source,
            ),
        ),
    )

    private fun flagGateStep(
        id: DependencyStepId,
        marker: String,
        namespace: String,
        expected: FlagType,
        code: InstalledCodePackageEvidence?,
        grant: PermissionObservation?,
        dumpDefault: String,
    ): DependencyStep {
        val dumpEvidence = DependencyEvidence(
            statement = "July 2025 dump: the $namespace namespace reads $marker only with " +
                "READ_DEVICE_CONFIG and its default is $dumpDefault. That dump is not this " +
                "installed build.",
            strength = DependencyEvidenceStrength.DUMP_ONLY,
            source = "July 2025 public AICore/PCS dump (Stage 9)",
        )
        if (code == null) {
            return DependencyStep(
                id,
                DependencyStepOutcome.NOT_OBSERVABLE,
                "The installed code of the package carrying $marker was not read.",
                listOf(dumpEvidence),
            )
        }
        return when (val outcome = code.outcome) {
            is InstalledCodeOutcome.NotVisible -> DependencyStep(
                id,
                DependencyStepOutcome.NOT_OBSERVABLE,
                "The installed ${code.requestedPackage} package is not visible to this app, so no " +
                    "code fact about $marker exists.",
                listOf(
                    dumpEvidence,
                    DependencyEvidence(
                        statement = "PackageManager does not expose ${code.requestedPackage}.",
                        strength = DependencyEvidenceStrength.OBSERVED,
                        source = "installed-code reader",
                    ),
                ),
            )

            is InstalledCodeOutcome.ReadError -> DependencyStep(
                id,
                DependencyStepOutcome.NOT_OBSERVABLE,
                "The installed-code read failed with ${outcome.errorClass}: ${outcome.message}; " +
                    "no fact about $marker exists.",
                listOf(
                    dumpEvidence,
                    DependencyEvidence(
                        statement = "Read error: ${outcome.errorClass}: ${outcome.message}.",
                        strength = DependencyEvidenceStrength.OBSERVED,
                        source = "installed-code reader",
                    ),
                ),
            )

            is InstalledCodeOutcome.TooLarge -> DependencyStep(
                id,
                DependencyStepOutcome.NOT_OBSERVABLE,
                "The installed-code read was refused: ${outcome.detail}.",
                listOf(
                    dumpEvidence,
                    DependencyEvidence(
                        statement = "TooLarge: ${outcome.detail}.",
                        strength = DependencyEvidenceStrength.OBSERVED,
                        source = "installed-code reader",
                    ),
                ),
            )

            is InstalledCodeOutcome.Partial -> DependencyStep(
                id,
                DependencyStepOutcome.NOT_OBSERVABLE,
                "The installed-code read of ${outcome.facts.packageName} is partial " +
                    "(${outcome.facts.failures.size} dex or APK failure(s)); a default across " +
                    "every reference cannot be established.",
                listOf(
                    dumpEvidence,
                    DependencyEvidence(
                        statement = "Partial read: ${outcome.facts.failures.size} dex or APK " +
                            "failure(s), so absence and mixed defaults cannot be claimed.",
                        strength = DependencyEvidenceStrength.OBSERVED,
                        source = "installed-code reader",
                    ),
                ),
            )

            is InstalledCodeOutcome.Read -> decideFlagStep(
                id = id,
                marker = marker,
                expected = expected,
                facts = outcome.facts,
                grant = grant,
                dumpEvidence = dumpEvidence,
            )
        }
    }

    private fun decideFlagStep(
        id: DependencyStepId,
        marker: String,
        expected: FlagType,
        facts: InstalledCodePackageFacts,
        grant: PermissionObservation?,
        dumpEvidence: DependencyEvidence,
    ): DependencyStep {
        val evidence = mutableListOf(dumpEvidence)
        val markerFacts = facts.marker(marker)
        if (markerFacts == null) {
            return DependencyStep(
                id,
                DependencyStepOutcome.NOT_OBSERVABLE,
                "The installed-code facts do not carry \"$marker\".",
                evidence,
            )
        }
        when (markerFacts.presence) {
            InstalledCodePresence.ABSENT -> return DependencyStep(
                id,
                DependencyStepOutcome.NOT_OBSERVABLE,
                "Contradicts the July 2025 dump: every dex of ${facts.packageName} was read and " +
                    "none contains \"$marker\", so the dump's gate was not found in the installed " +
                    "code. This is not evidence that the step passes.",
                evidence + DependencyEvidence(
                    statement = "\"$marker\" is absent from the complete installed-code read of " +
                        "${facts.packageName}.",
                    strength = DependencyEvidenceStrength.OBSERVED,
                    source = "installed-code reader",
                ),
            )

            InstalledCodePresence.UNKNOWN -> return DependencyStep(
                id,
                DependencyStepOutcome.NOT_OBSERVABLE,
                "Presence of \"$marker\" is unknown: at least one dex was not read " +
                    "(${facts.failures.size} failure(s)); absence cannot be claimed.",
                evidence + DependencyEvidence(
                    statement = "The installed-code read is incomplete.",
                    strength = DependencyEvidenceStrength.OBSERVED,
                    source = "installed-code reader",
                ),
            )

            InstalledCodePresence.PRESENT -> Unit
        }
        evidence += DependencyEvidence(
            statement = "The installed ${facts.packageName} code contains \"$marker\" in " +
                "${markerFacts.presentIn.size} dex location(s), referenced by " +
                "${markerFacts.methodCount} method(s).",
            strength = DependencyEvidenceStrength.OBSERVED,
            source = "installed-code reader",
        )
        if (markerFacts.methodCount == 0) {
            return DependencyStep(
                id,
                DependencyStepOutcome.NOT_OBSERVABLE,
                "\"$marker\" is in the string table but loaded by no instruction the reader saw; " +
                    "it never decides a step.",
                evidence,
            )
        }
        val defaults = markerFacts.references.flatMap { it.defaults }
        val distinct = defaults.distinct()
        val single = distinct.singleOrNull()
        if (single == null || single == InstalledCodeDefaultValue.Unresolved) {
            val description = if (distinct.isEmpty()) {
                "no literal was resolved"
            } else {
                distinct.joinToString(", ", transform = ::describeDefault)
            }
            return DependencyStep(
                id,
                DependencyStepOutcome.NOT_OBSERVABLE,
                "Consistent with the dump, not shown: the flag default is not established " +
                    "($description). A step uses a default only when every reference carries the " +
                    "same single literal.",
                evidence + DependencyEvidence(
                    statement = "Defaults ($DEFAULTS_LABEL): $description.",
                    strength = DependencyEvidenceStrength.OBSERVED,
                    source = "installed-code reader",
                ),
            )
        }
        evidence += DependencyEvidence(
            statement = "Default ($DEFAULTS_LABEL): ${describeDefault(single)}.",
            strength = DependencyEvidenceStrength.OBSERVED,
            source = "installed-code reader",
        )
        val disabling = when {
            expected == FlagType.BOOLEAN && single is InstalledCodeDefaultValue.BooleanValue ->
                !single.value

            expected == FlagType.INT && single is InstalledCodeDefaultValue.IntValue ->
                single.value == 0

            else -> return DependencyStep(
                id,
                DependencyStepOutcome.NOT_OBSERVABLE,
                "Consistent with the dump, not shown: the resolved literal " +
                    "${describeDefault(single)} has a type that does not fit the $marker flag; " +
                    "it is shown and decides nothing.",
                evidence,
            )
        }
        val checkFound = facts.readDeviceConfig.anyReferencingMethodChecksPermission
        evidence += DependencyEvidence(
            statement = if (checkFound) {
                "A method referencing ${PlatformPermissions.READ_DEVICE_CONFIG} also invokes a " +
                    "permission check: found beside it."
            } else {
                "No method referencing ${PlatformPermissions.READ_DEVICE_CONFIG} was seen " +
                    "invoking checkSelfPermission or checkCallingOrSelfPermission: not shown, " +
                    "never \"no check\"; the check can sit in another method or a renamed wrapper."
            },
            strength = DependencyEvidenceStrength.OBSERVED,
            source = "installed-code reader",
        )
        if (!checkFound) {
            return DependencyStep(
                id,
                DependencyStepOutcome.NOT_OBSERVABLE,
                "Consistent with the dump, not shown: the installed code contains \"$marker\" and " +
                    "resolves its default, but the READ_DEVICE_CONFIG check was not found beside it.",
                evidence,
            )
        }
        val status = grant?.status
        evidence += DependencyEvidence(
            statement = "READ_DEVICE_CONFIG for ${facts.packageName}: " +
                (grant?.let { PermissionMatrixFormatting.renderCell(it) } ?: "not read by the matrix") +
                ".",
            strength = DependencyEvidenceStrength.OBSERVED,
            source = "permission matrix",
        )
        return when (status) {
            PermissionStatus.GRANTED -> DependencyStep(
                id,
                DependencyStepOutcome.NOT_OBSERVABLE,
                "Consistent with the dump, not shown: READ_DEVICE_CONFIG is granted for " +
                    "${facts.packageName}, so the installed code reads DeviceConfig; the namespace " +
                    "value itself is not observable from this app.",
                evidence,
            )

            PermissionStatus.DENIED -> if (disabling) {
                DependencyStep(
                    id,
                    DependencyStepOutcome.FAILED,
                    "The installed code gates \"$marker\" on DeviceConfig with default " +
                        "${describeDefault(single)}, the READ_DEVICE_CONFIG check is found beside " +
                        "it, and the permission is denied, so the flag reads its default (disabled).",
                    evidence,
                )
            } else {
                DependencyStep(
                    id,
                    DependencyStepOutcome.PASSED,
                    "The installed code gates \"$marker\" on DeviceConfig with default " +
                        "${describeDefault(single)}; with the permission denied the flag reads that " +
                        "default, which leaves the gate open.",
                    evidence,
                )
            }

            else -> DependencyStep(
                id,
                DependencyStepOutcome.NOT_OBSERVABLE,
                "Consistent with the dump, not shown: the READ_DEVICE_CONFIG grant state for " +
                    "${facts.packageName} is not established " +
                    "(${status?.label ?: "matrix not collected"}); unknown is not denied.",
                evidence,
            )
        }
    }

    private fun associationStep(
        manifests: List<ManifestEvidenceSnapshot>,
        aicore: PackageFacts?,
    ): DependencyStep {
        val id = DependencyStepId.PCS_ASSOCIATION
        val dumpEvidence = DependencyEvidence(
            statement = "GrapheneOS checks component access both ways for every non-system pair; " +
                "in the July 2025 dump PCS's policy lists AICore and ASI certificate digests.",
            strength = DependencyEvidenceStrength.DUMP_ONLY,
            source = "GrapheneOS ActivityManagerService and PCS dump (Stage 9 review)",
        )
        val pcs = manifests.firstOrNull { it.requestedPackage == PlatformPrincipals.PCS }
            ?: return DependencyStep(
                id,
                DependencyStepOutcome.NOT_OBSERVABLE,
                "The installed PCS manifest was not read, so no association policy fact exists.",
                listOf(dumpEvidence),
            )
        when (val outcome = pcs.outcome) {
            is ManifestReadOutcome.Read -> Unit
            is ManifestReadOutcome.Partial -> return DependencyStep(
                id,
                DependencyStepOutcome.NOT_OBSERVABLE,
                "The installed PCS manifest read is partial; absence of an association entry " +
                    "cannot be claimed.",
                listOf(dumpEvidence),
            )

            is ManifestReadOutcome.NotVisible -> return DependencyStep(
                id,
                DependencyStepOutcome.NOT_OBSERVABLE,
                "The installed PCS package is not visible to this app; no association policy was read.",
                listOf(dumpEvidence),
            )

            is ManifestReadOutcome.ReadError -> return DependencyStep(
                id,
                DependencyStepOutcome.NOT_OBSERVABLE,
                "The installed PCS manifest read failed with ${outcome.errorClass}: " +
                    "${outcome.message}.",
                listOf(dumpEvidence),
            )

            is ManifestReadOutcome.Mismatch -> return DependencyStep(
                id,
                DependencyStepOutcome.NOT_OBSERVABLE,
                "The read manifest declares ${outcome.declaredPackage ?: "no package"}, not " +
                    "PCS; no association policy is attributed to PCS.",
                listOf(dumpEvidence),
            )
        }
        val evidence = mutableListOf(
            dumpEvidence,
            DependencyEvidence(
                statement = "Installed PCS association policy: " +
                    pcs.pcc.associationPolicy.state.name +
                    " (${pcs.pcc.associationPolicy.reason}).",
                strength = DependencyEvidenceStrength.OBSERVED,
                source = "installed-manifest reader",
            ),
        )
        if (pcs.associationElements.isEmpty()) {
            return DependencyStep(
                id,
                DependencyStepOutcome.NOT_OBSERVABLE,
                "The installed PCS manifest returned no association-policy element; the default " +
                    "enforcement is not established.",
                evidence,
            )
        }
        if (!pcs.associationNamesComplete) {
            return DependencyStep(
                id,
                DependencyStepOutcome.NOT_OBSERVABLE,
                "The association policy has a missing or unnamed package entry; a mismatch is " +
                    "never claimed from it.",
                evidence,
            )
        }
        val names = pcs.associationPackageNames
        val policyDigests = pcs.associationCertificateDigests.map(::normalizeDigest).filter { it.isNotEmpty() }
        evidence += DependencyEvidence(
            statement = "Policy packages: ${names.joinToString(", ").ifBlank { "none" }}; " +
                "certificate digests: ${policyDigests.size} read.",
            strength = DependencyEvidenceStrength.OBSERVED,
            source = "installed-manifest reader",
        )
        if (aicore == null) {
            return DependencyStep(
                id,
                DependencyStepOutcome.NOT_OBSERVABLE,
                "The installed AICore package is not visible to this app, so its signer cannot be " +
                    "compared with the policy.",
                evidence,
            )
        }
        val aicoreDigests = if (aicore.signersRead) {
            aicore.signingSha256.map(::normalizeDigest).filter { it.isNotEmpty() }
        } else {
            emptyList()
        }
        evidence += DependencyEvidence(
            statement = "Installed AICore ${aicore.packageName} signer SHA-256: " +
                (if (!aicore.signersRead) {
                    "not read"
                } else if (aicoreDigests.isEmpty()) {
                    "none reported"
                } else {
                    "${aicoreDigests.size} digest(s) read"
                }) + ".",
            strength = DependencyEvidenceStrength.OBSERVED,
            source = "package facts",
        )
        if (policyDigests.isNotEmpty() && aicoreDigests.isNotEmpty()) {
            return if (policyDigests.any { it in aicoreDigests }) {
                DependencyStep(
                    id,
                    DependencyStepOutcome.PASSED,
                    "The installed PCS association policy lists a certificate digest equal to the " +
                        "installed AICore signer's SHA-256.",
                    evidence,
                )
            } else {
                DependencyStep(
                    id,
                    DependencyStepOutcome.FAILED,
                    "The installed PCS association policy lists certificate digests, none of which " +
                        "equals the installed AICore signer's SHA-256; the association check would " +
                        "refuse AICore.",
                    evidence,
                )
            }
        }
        return if (names.contains(aicore.packageName)) {
            DependencyStep(
                id,
                DependencyStepOutcome.PASSED,
                "The installed PCS association policy names the installed AICore package " +
                    "${aicore.packageName}; signer digests were not compared on this read.",
                evidence,
            )
        } else {
            DependencyStep(
                id,
                DependencyStepOutcome.FAILED,
                "The installed PCS association policy names ${names.joinToString(", ").ifBlank { "no package" }}, " +
                    "not the installed AICore package ${aicore.packageName}; the association check " +
                    "would refuse AICore.",
                evidence,
            )
        }
    }

    private fun installedAicorePackage(packages: List<PackageFacts>): PackageFacts? =
        listOf(PlatformPrincipals.AICORE, PlatformPrincipals.AICORE_LEGACY)
            .mapNotNull { name -> packages.firstOrNull { it.packageName == name } }
            .sortedByDescending { it.visible }
            .firstOrNull()

    private fun aicoreCodeEvidence(
        installedCode: List<InstalledCodePackageEvidence>,
    ): InstalledCodePackageEvidence? {
        val candidates = listOf(PlatformPrincipals.AICORE, PlatformPrincipals.AICORE_LEGACY)
            .mapNotNull { name -> installedCode.firstOrNull { it.requestedPackage == name } }
        return candidates.firstOrNull {
            it.outcome is InstalledCodeOutcome.Read || it.outcome is InstalledCodeOutcome.Partial
        } ?: candidates.firstOrNull()
    }

    private fun normalizeDigest(value: String): String =
        value.replace(":", "").trim().lowercase()

    private enum class FlagType {
        BOOLEAN,
        INT,
    }

    companion object {
        const val AICORE_BUILD_ID_MARKER = "AicDataRelease__build_id_"
        const val PROTECTED_DOWNLOAD_MARKER = "ProtectedDownload__enabled"

        /** What `defaults` holds; the literal immediately after the flag name. */
        const val DEFAULTS_LABEL = "literal argument right after the flag name"

        fun describeDefault(value: InstalledCodeDefaultValue): String = when (value) {
            is InstalledCodeDefaultValue.BooleanValue -> "boolean ${value.value}"
            is InstalledCodeDefaultValue.IntValue -> "int ${value.value}"
            is InstalledCodeDefaultValue.StringValue -> "string \"${value.value}\""
            InstalledCodeDefaultValue.Unresolved -> "unresolved"
        }
    }
}
