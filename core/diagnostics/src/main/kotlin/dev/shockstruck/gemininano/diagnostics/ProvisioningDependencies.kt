package dev.shockstruck.gemininano.diagnostics

import dev.shockstruck.gemininano.model.AppUserFacts
import dev.shockstruck.gemininano.model.InstalledCodeDefaultValue
import dev.shockstruck.gemininano.model.InstalledCodeMarkerFacts
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
 * One step of the provisioning chain the public source trace laid out, in the order that
 * trace shows them. The order is the evaluation order: the first step shown to fail is
 * the answer.
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

    /** Every step before the first failure whose outcome is not passed, in chain order. */
    val earlierStepsNotPassed: List<DependencyStep>
        get() {
            val failing = firstFailingStep ?: return emptyList()
            return steps.takeWhile { it.id != failing.id }
                .filter { it.outcome != DependencyStepOutcome.PASSED }
        }

    val earlierStepsLabel: String
        get() = earlierStepsNotPassed.joinToString("; ") {
            "${it.id.label} (${it.outcome.label})"
        }

    /**
     * The headline names the earliest step shown to fail. Earlier steps that are not
     * passed are named beside it, because an unsettled step before it means the failure
     * is the first one the evidence shows, not proof the chain failed there first.
     */
    val resultLabel: String
        get() = firstFailingStep?.let { "First step shown to fail: ${it.id.label}" }
            ?: "Cannot be determined: no step is shown to fail"

    val summary: String
        get() {
            val failing = firstFailingStep
            if (failing != null) {
                val earlier = earlierStepsLabel.ifEmpty { "none" }
                return failing.summary + " Earlier steps not passed: $earlier."
            }
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
 * Pure ordered evaluation of the provisioning chain the public source trace laid out.
 *
 * A step is [DependencyStepOutcome.FAILED] only from a fact about the installed package
 * plus an observed denial or absence. A dump fact is labelled
 * [DependencyEvidenceStrength.DUMP_ONLY] and never decides a step. A step that cannot be
 * observed is never reported as passed or failed.
 *
 * The flag gates (the AICore build id and PCS `ProtectedDownload__enabled`) are evaluated
 * per exact flag name. A name's default is established only when it has at least one
 * default site and every site carries the same single literal. A flag passed with no
 * parameter after it is [InstalledCodeDefaultValue.NoFollowingArgument], not a default
 * site; an argument that cannot be resolved is
 * [InstalledCodeDefaultValue.Unresolved] and blocks the step. A gate fails only when at
 * least one exact name is established, every established name reads the disabling
 * default of the flag's type, no name is unresolved or mixed, a method referencing
 * `READ_DEVICE_CONFIG` also invokes a permission check, and the permission is observed
 * denied. That the denial makes the flag read its default is inferred: the check is
 * found in the package, not on this flag's read path.
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
            associationStep(input.manifests, input.packages, aicore),
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
                "PCS reads DeviceConfig only with READ_DEVICE_CONFIG, and the July 2025 public " +
                    "dump rejects GetManifestConfig with \"feature disabled\" while the flag is " +
                    "false. The app cannot send that RPC or observe AICore's request, so no " +
                    "installed-code fact or observation decides this step.",
                "July 2025 public PCS dump",
            ),
            laterStep(
                DependencyStepId.MANIFEST_TO_MODEL,
                "The public source trace found no download call from the manifest to MDD file " +
                    "groups; the flags it named (for example " +
                    "AicModels__protected_download_v2_enabled) were read in the dump only. No " +
                    "observation of a download, verification or registration is available to " +
                    "this app.",
                "public source trace",
            ),
            laterStep(
                DependencyStepId.ISOLATED_INFERENCE,
                "Neither the download nor the isolated inference start was traced, and this app " +
                    "cannot observe AICore's process. Nothing here can be called passed or failed.",
                "public source trace",
            ),
        )
        return ProvisioningDependencyEvaluation(steps)
    }

    private fun clientStatusCheckStep() = DependencyStep(
        id = DependencyStepId.CLIENT_STATUS_CHECK,
        outcome = DependencyStepOutcome.NOT_TESTED,
        summary = "The public source trace did not cover the ML Kit client status check. This " +
            "app's own Prompt and summarization rows are client observations, not this step's " +
            "outcome.",
        evidence = listOf(
            DependencyEvidence(
                statement = "The client status check is the entry point of the chain and was not " +
                    "traced; it is reported as not tested rather than passed or failed.",
                strength = DependencyEvidenceStrength.SOURCE_TRACE,
                source = "public source trace",
            ),
        ),
    )

    private fun systemUserStep(
        appUser: AppUserFacts?,
        aicore: PackageFacts?,
    ): DependencyStep {
        val id = DependencyStepId.SYSTEM_USER
        val dumpEvidence = DependencyEvidence(
            statement = "In the July 2025 public AICore dump, model populators are set up only " +
                "for the system (owner) user.",
            strength = DependencyEvidenceStrength.DUMP_ONLY,
            source = "July 2025 public AICore dump",
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
                DependencyStepOutcome.NOT_OBSERVABLE,
                "This app runs in a secondary user profile, not the system (owner) user. The " +
                    "July 2025 public AICore dump sets up model populators only for the system " +
                    "user, but a dump fact is not this build. This is a candidate cause, not a " +
                    "failed step; the plain next step is to install and test in the owner profile.",
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
        summary = "The July 2025 public dump returns no populator unless a second flag holds; " +
            "that flag's name and default were not read, and no allowlisted installed-code " +
            "marker identifies it, so the installed code cannot settle this step.",
        evidence = listOf(
            DependencyEvidence(
                statement = "aicore_dump bem.java: case 0 returns no populator unless " +
                    "rq.z(context) (UserManager.isSystemUser()) and beg.d(flag) hold; the flag's " +
                    "default was not read.",
                strength = DependencyEvidenceStrength.DUMP_ONLY,
                source = "July 2025 public AICore dump",
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
            statement = "July 2025 public dump: the $namespace namespace reads $marker only " +
                "with READ_DEVICE_CONFIG and its default is $dumpDefault. That dump is not this " +
                "installed build.",
            strength = DependencyEvidenceStrength.DUMP_ONLY,
            source = "July 2025 public AICore/PCS dump",
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
        if (facts.referencesTruncated) {
            return DependencyStep(
                id,
                DependencyStepOutcome.NOT_OBSERVABLE,
                "The installed-code read truncated its marker references for " +
                    "${facts.packageName}, so this gate's default sites could be missing; no " +
                    "default or absence is claimed from a truncated reference list.",
                evidence + DependencyEvidence(
                    statement = "referencesTruncated=true for ${facts.packageName}.",
                    strength = DependencyEvidenceStrength.OBSERVED,
                    source = "installed-code reader",
                ),
            )
        }
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
                "Contradicts the July 2025 public dump: every dex of ${facts.packageName} was " +
                    "read and none contains \"$marker\", so the dump's gate was not found in the " +
                    "installed code. This is not evidence that the step passes.",
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
        val exactNames = exactFlagDefaults(markerFacts)
        val namesDescription = describeExactFlagDefaults(exactNames)
        evidence += DependencyEvidence(
            statement = "Exact flag names and their defaults ($DEFAULTS_LABEL; " +
                "\"no following argument\" is not a default site): $namesDescription.",
            strength = DependencyEvidenceStrength.OBSERVED,
            source = "installed-code reader",
        )
        val established = exactNames.mapNotNull { exact ->
            exact.establishedDefault?.let { exact.name to it }
        }
        val blocked = exactNames.filter { it.unresolvedSite || it.mixedSites }
        if (blocked.isNotEmpty()) {
            return DependencyStep(
                id,
                DependencyStepOutcome.NOT_OBSERVABLE,
                "Consistent with the dump, not shown: at least one exact flag name has an " +
                    "unresolved or mixed default site ($namesDescription). A step uses a " +
                    "default only when every site of an exact name is the same single literal.",
                evidence,
            )
        }
        if (established.isEmpty()) {
            return DependencyStep(
                id,
                DependencyStepOutcome.NOT_OBSERVABLE,
                "Consistent with the dump, not shown: no exact flag name has a default site " +
                    "($namesDescription); a flag passed with no following argument carries no " +
                    "value beside it.",
                evidence,
            )
        }
        val distinctEstablished = established.map { it.second }.distinct()
        val single = distinctEstablished.singleOrNull()
        if (single == null) {
            return DependencyStep(
                id,
                DependencyStepOutcome.NOT_OBSERVABLE,
                "Consistent with the dump, not shown: the established exact names disagree on " +
                    "their default ($namesDescription).",
                evidence,
            )
        }
        evidence += DependencyEvidence(
            statement = "Every established exact name reads the same default " +
                "($DEFAULTS_LABEL): ${describeDefault(single)}.",
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
                    "resolves a default, but the READ_DEVICE_CONFIG check was not found beside it.",
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
        if (status == PermissionStatus.DENIED) {
            evidence += DependencyEvidence(
                statement = "That the denied permission makes each of these flags read its " +
                    "default is inferred: the permission check is found in the package, not on " +
                    "each flag's read path.",
                strength = DependencyEvidenceStrength.INFERRED,
                source = "installed-code reader and permission matrix",
            )
            val names = describeEstablishedNames(established.map { it.first })
            return if (disabling) {
                DependencyStep(
                    id,
                    DependencyStepOutcome.FAILED,
                    "The installed code gates \"$marker\" on DeviceConfig: every established " +
                        "exact name ($names) reads the same disabling default " +
                        "(${describeDefault(single)}), the READ_DEVICE_CONFIG check is found " +
                        "beside it, and the permission is denied, so each flag reads its default. " +
                        "That the denial makes the flag read its default is inferred: the check " +
                        "is found in the package, not on each flag's read path.",
                    evidence,
                )
            } else {
                DependencyStep(
                    id,
                    DependencyStepOutcome.PASSED,
                    "The installed code gates \"$marker\" on DeviceConfig: every established " +
                        "exact name ($names) reads the same enabling default " +
                        "(${describeDefault(single)}); with the permission denied each flag reads " +
                        "that default, which leaves the gate open. That the denial makes the flag " +
                        "read its default is inferred: the check is found in the package, not on " +
                        "each flag's read path.",
                    evidence,
                )
            }
        }
        return DependencyStep(
            id,
            DependencyStepOutcome.NOT_OBSERVABLE,
            if (status == PermissionStatus.GRANTED) {
                "Consistent with the dump, not shown: READ_DEVICE_CONFIG is granted for " +
                    "${facts.packageName}, so the installed code reads DeviceConfig; the " +
                    "namespace value itself is not observable from this app."
            } else {
                "Consistent with the dump, not shown: the READ_DEVICE_CONFIG grant state for " +
                    "${facts.packageName} is not established " +
                    "(${status?.label ?: "matrix not collected"}); unknown is not denied."
            },
            evidence,
        )
    }

    /**
     * The framework checks component access both ways: PCS's policy must name AICore with a
     * matching digest, and AICore's own complete manifest must either declare no policy or
     * name PCS with a digest matching PCS's signer. A failure on the PCS side is possible
     * from observed facts, but whether this build enforces the policy is not published, so
     * that failure is inferred.
     */
    private fun associationStep(
        manifests: List<ManifestEvidenceSnapshot>,
        packages: List<PackageFacts>,
        aicore: PackageFacts?,
    ): DependencyStep {
        val id = DependencyStepId.PCS_ASSOCIATION
        val dumpEvidence = DependencyEvidence(
            statement = "GrapheneOS checks component access both ways for every non-system " +
                "pair; in the July 2025 public dump PCS's policy lists AICore and ASI " +
                "certificate digests.",
            strength = DependencyEvidenceStrength.DUMP_ONLY,
            source = "GrapheneOS ActivityManagerService and July 2025 public PCS dump",
        )
        if (aicore == null) {
            return DependencyStep(
                id,
                DependencyStepOutcome.NOT_OBSERVABLE,
                "The installed AICore package is not visible to this app, so its package name " +
                    "and signer are not established and no policy entry can be attributed to it.",
                listOf(dumpEvidence),
            )
        }
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
        val entries = pcs.associationElements.flatMap { element -> element.packages }
        val names = entries.mapNotNull { it.name }.distinct()
        val aicoreEntries = entries.filter { it.name == aicore.packageName }
        evidence += DependencyEvidence(
            statement = "Policy packages: ${names.joinToString(", ").ifBlank { "none" }}; " +
                "entries named for ${aicore.packageName}: ${aicoreEntries.size}.",
            strength = DependencyEvidenceStrength.OBSERVED,
            source = "installed-manifest reader",
        )
        if (aicoreEntries.isEmpty()) {
            return DependencyStep(
                id,
                DependencyStepOutcome.FAILED,
                "The installed PCS association policy has no entry named for the installed " +
                    "AICore package ${aicore.packageName} (it names " +
                    "${names.joinToString(", ").ifBlank { "no package" }}); if this build " +
                    "enforces the policy, the association check would refuse AICore.",
                evidence + DependencyEvidence(
                    statement = "Observed absence: none of the policy entries is named for " +
                        "${aicore.packageName}.",
                    strength = DependencyEvidenceStrength.INFERRED,
                    source = "installed-manifest reader",
                ),
            )
        }
        val policyDigests = aicoreEntries
            .flatMap { it.certificateDigests }
            .map(::normalizeDigest)
            .filter { it.isNotEmpty() }
            .distinct()
        if (policyDigests.isEmpty()) {
            return DependencyStep(
                id,
                DependencyStepOutcome.NOT_OBSERVABLE,
                "The policy entry named for ${aicore.packageName} carries no certificate digest, " +
                    "so its match with AICore's signer cannot be established.",
                evidence,
            )
        }
        if (!aicore.signersRead || aicore.signingSha256.isEmpty()) {
            return DependencyStep(
                id,
                DependencyStepOutcome.NOT_OBSERVABLE,
                "The installed AICore signer was not read, so it cannot be compared with the " +
                    "policy entry's digests.",
                evidence,
            )
        }
        val aicoreDigests = aicore.signingSha256.map(::normalizeDigest).filter { it.isNotEmpty() }
        evidence += DependencyEvidence(
            statement = "Installed AICore ${aicore.packageName} signer SHA-256: " +
                "${aicoreDigests.size} digest(s) read; the policy entry named for it carries " +
                "${policyDigests.size} digest(s).",
            strength = DependencyEvidenceStrength.OBSERVED,
            source = "package facts and installed-manifest reader",
        )
        if (!policyDigests.any { it in aicoreDigests }) {
            return DependencyStep(
                id,
                DependencyStepOutcome.FAILED,
                "No certificate digest in the policy entry named for ${aicore.packageName} " +
                    "equals the installed AICore signer's SHA-256; if this build enforces the " +
                    "policy, the association check would refuse AICore.",
                evidence + DependencyEvidence(
                    statement = "Observed mismatch: the entry's digests and AICore's signer " +
                        "digests are disjoint.",
                    strength = DependencyEvidenceStrength.INFERRED,
                    source = "package facts and installed-manifest reader",
                ),
            )
        }
        evidence += DependencyEvidence(
            statement = "The policy entry named for ${aicore.packageName} lists a digest equal " +
                "to the installed AICore signer's SHA-256.",
            strength = DependencyEvidenceStrength.OBSERVED,
            source = "package facts and installed-manifest reader",
        )
        val aicoreManifest = manifests.firstOrNull { it.requestedPackage == aicore.packageName }
        if (aicoreManifest == null || !aicoreManifest.complete) {
            return DependencyStep(
                id,
                DependencyStepOutcome.NOT_OBSERVABLE,
                "The PCS entry for AICore matches, but AICore's own manifest was not completely " +
                    "read; the framework checks component access both ways, so the pass is not " +
                    "established.",
                evidence,
            )
        }
        val reverseElements = aicoreManifest.associationElements
        if (reverseElements.isNotEmpty()) {
            if (!aicoreManifest.associationNamesComplete) {
                return DependencyStep(
                    id,
                    DependencyStepOutcome.NOT_OBSERVABLE,
                    "AICore's own manifest declares an association policy but its package " +
                        "entries are missing or unnamed, so the reverse check cannot be read.",
                    evidence,
                )
            }
            val pcsEntries = reverseElements
                .flatMap { element -> element.packages }
                .filter { it.name == PlatformPrincipals.PCS }
            if (pcsEntries.isEmpty()) {
                return DependencyStep(
                    id,
                    DependencyStepOutcome.NOT_OBSERVABLE,
                    "AICore's own complete manifest declares an association policy without an " +
                        "entry named for PCS; the reverse check is not shown to pass.",
                    evidence,
                )
            }
            val reverseDigests = pcsEntries
                .flatMap { it.certificateDigests }
                .map(::normalizeDigest)
                .filter { it.isNotEmpty() }
                .distinct()
            if (reverseDigests.isEmpty()) {
                return DependencyStep(
                    id,
                    DependencyStepOutcome.NOT_OBSERVABLE,
                    "AICore's policy entry named for PCS carries no certificate digest, so the " +
                        "reverse check is not shown to pass.",
                    evidence,
                )
            }
            val pcsPackage = packages.firstOrNull { it.packageName == PlatformPrincipals.PCS }
            val pcsSigners = if (pcsPackage?.signersRead == true) {
                pcsPackage.signingSha256.map(::normalizeDigest).filter { it.isNotEmpty() }
            } else {
                emptyList()
            }
            if (pcsSigners.isEmpty()) {
                return DependencyStep(
                    id,
                    DependencyStepOutcome.NOT_OBSERVABLE,
                    "The installed PCS signer was not read, so AICore's policy entry for PCS " +
                        "cannot be compared.",
                    evidence,
                )
            }
            if (reverseDigests.none { it in pcsSigners }) {
                return DependencyStep(
                    id,
                    DependencyStepOutcome.NOT_OBSERVABLE,
                    "AICore's policy entry for PCS does not list the installed PCS signer's " +
                        "SHA-256; the reverse check is not shown to pass.",
                    evidence,
                )
            }
            evidence += DependencyEvidence(
                statement = "AICore's complete manifest names PCS with a digest equal to the " +
                    "installed PCS signer's SHA-256.",
                strength = DependencyEvidenceStrength.OBSERVED,
                source = "package facts and installed-manifest reader",
            )
        } else {
            evidence += DependencyEvidence(
                statement = "AICore's complete manifest declares no association policy element.",
                strength = DependencyEvidenceStrength.OBSERVED,
                source = "installed-manifest reader",
            )
        }
        evidence += DependencyEvidence(
            statement = "Whether this build enforces the association policy is not published, " +
                "so the pass is conditional on enforcement.",
            strength = DependencyEvidenceStrength.INFERRED,
            source = "GrapheneOS source",
        )
        return DependencyStep(
            id,
            DependencyStepOutcome.PASSED,
            "The installed PCS policy names ${aicore.packageName} with a digest equal to the " +
                "installed AICore signer, and AICore's own complete manifest " +
                (if (reverseElements.isEmpty()) {
                    "declares no policy; the reverse check has no entry to fail."
                } else {
                    "names PCS with a digest equal to the installed PCS signer."
                }) +
                " Whether this build enforces the policy is not published.",
            evidence,
        )
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

    private fun exactFlagDefaults(
        markerFacts: InstalledCodeMarkerFacts,
    ): List<ExactFlagDefaults> =
        markerFacts.references
            .groupBy { it.matchedString }
            .entries
            .sortedBy { it.key }
            .map { (name, references) ->
                val values = references.flatMap { it.defaults }.distinct()
                ExactFlagDefaults(
                    name = name,
                    values = values,
                    defaultSites = values.filterNot {
                        it == InstalledCodeDefaultValue.NoFollowingArgument
                    },
                )
            }

    private fun describeExactFlagDefaults(names: List<ExactFlagDefaults>): String {
        val shown = names.take(MAX_DEFAULT_NAMES_IN_REPORT)
        val rendered = shown.joinToString("; ") { exact ->
            val values = if (exact.values.isEmpty()) {
                "no value recorded"
            } else {
                exact.values.joinToString(", ") { describeDefault(it) }
            }
            "${displayExactName(exact.name)} = $values"
        }
        val omitted = names.size - shown.size
        val count = if (omitted > 0) {
            "; $omitted more (${names.size} exact names total)"
        } else {
            "; ${names.size} exact name(s)"
        }
        return rendered + count
    }

    private fun describeEstablishedNames(names: List<String>): String {
        val shown = names.take(MAX_DEFAULT_NAMES_IN_REPORT)
        val rendered = shown.joinToString(", ") { displayExactName(it) }
        val omitted = names.size - shown.size
        return if (omitted > 0) "$rendered; $omitted more (${names.size} exact names total)" else rendered
    }

    /** The bare prefix is a run-time key: the installed code appends a suffix. */
    private fun displayExactName(name: String): String =
        if (name == AICORE_BUILD_ID_MARKER) {
            "$AICORE_BUILD_ID_MARKER<runtime suffix> (key composed at run time)"
        } else {
            name
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

        /** At most this many exact names are listed in one step's text; a count follows. */
        private const val MAX_DEFAULT_NAMES_IN_REPORT = 60

        fun describeDefault(value: InstalledCodeDefaultValue): String = when (value) {
            is InstalledCodeDefaultValue.BooleanValue -> "boolean ${value.value}"
            is InstalledCodeDefaultValue.IntValue -> "int ${value.value}"
            is InstalledCodeDefaultValue.StringValue -> "string \"${value.value}\""
            InstalledCodeDefaultValue.Unresolved -> "unresolved"
            InstalledCodeDefaultValue.NoFollowingArgument -> "no following argument"
        }
    }
}

/** One exact flag name's recorded defaults, with [NoFollowingArgument] separated out. */
private data class ExactFlagDefaults(
    val name: String,
    val values: List<InstalledCodeDefaultValue>,
    val defaultSites: List<InstalledCodeDefaultValue>,
) {
    val unresolvedSite: Boolean
        get() = defaultSites.contains(InstalledCodeDefaultValue.Unresolved)

    val mixedSites: Boolean
        get() = defaultSites.size > 1

    /** The single literal every default site agrees on, or null. */
    val establishedDefault: InstalledCodeDefaultValue?
        get() = defaultSites.singleOrNull()
            ?.takeIf { it != InstalledCodeDefaultValue.Unresolved }
}
