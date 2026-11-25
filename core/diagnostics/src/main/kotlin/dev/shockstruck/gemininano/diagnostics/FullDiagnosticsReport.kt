package dev.shockstruck.gemininano.diagnostics

import dev.shockstruck.gemininano.model.DiagnosticEvent
import dev.shockstruck.gemininano.model.FeatureMatrixFormatting
import dev.shockstruck.gemininano.model.FeatureProbeResult
import dev.shockstruck.gemininano.model.FeatureStatusKind
import dev.shockstruck.gemininano.model.GenAiFeature
import dev.shockstruck.gemininano.model.OfflineInferenceResult
import dev.shockstruck.gemininano.model.PackageFacts
import dev.shockstruck.gemininano.model.PackageFactsFormatting
import dev.shockstruck.gemininano.model.PcsActivityLaunchResult
import dev.shockstruck.gemininano.model.PcsBindResult
import dev.shockstruck.gemininano.model.PcsSurface
import dev.shockstruck.gemininano.model.PermissionGrantFacts
import dev.shockstruck.gemininano.model.PermissionMatrix
import dev.shockstruck.gemininano.model.PermissionMatrixFormatting
import dev.shockstruck.gemininano.model.PlatformPermissions
import dev.shockstruck.gemininano.model.PlatformPrincipals
import dev.shockstruck.gemininano.model.PromptConfigStatus
import dev.shockstruck.gemininano.model.PromptModelConfig
import dev.shockstruck.gemininano.model.SummarizationProbeResult

/** Everything the full diagnostic report may contain, already collected by the app. */
data class FullDiagnosticsInput(
    val generatedAtEpochMillis: Long,
    val metadata: ExportMetadata,
    val matrix: PermissionMatrix?,
    val features: List<FeatureProbeResult>,
    val prompt: List<PromptConfigStatus>,
    val summarization: SummarizationProbeResult?,
    val surface: PcsSurface?,
    val bindResults: List<PcsBindResult>,
    val offline: List<OfflineInferenceResult>,
    val events: List<DiagnosticEvent>,
    /** Full package facts, so every requested permission can be exported with all sources. */
    val packages: List<PackageFacts> = emptyList(),
    /** Results of explicit user-tapped PCS activity launches. */
    val launchResults: List<PcsActivityLaunchResult> = emptyList(),
    /** `getSystemAvailableFeatures` names relevant to NPU, AI, virtualization or PCC. */
    val systemFeatures: List<String> = emptyList(),
    val systemFeatureReadError: String? = null,
    /** Installed-manifest evidence from the Stage 6 reader. */
    val manifests: List<ManifestEvidenceSnapshot> = emptyList(),
    /** The pure classifier result computed from exactly the evidence in this report. */
    val classification: ProvisioningClassification? = null,
)

data class FullDiagnosticsReport(
    val generatedAtEpochMillis: Long,
    val json: String,
    val text: String,
)

/**
 * One comprehensive report of the safe probe evidence: permission matrix, every GenAI
 * feature status, Prompt configs, the retained summarization probe, the PCS surface,
 * any bind results the user already ran, the offline inference matrix and the
 * diagnostic snapshot. It never downloads, binds or provisions anything.
 */
class FullDiagnosticsReportBuilder {

    fun build(input: FullDiagnosticsInput): FullDiagnosticsReport = FullDiagnosticsReport(
        generatedAtEpochMillis = input.generatedAtEpochMillis,
        json = json(input),
        text = text(input),
    )

    private fun json(input: FullDiagnosticsInput): String {
        val out = StringBuilder()
        out.append("{\n")
        out.append("  \"schema\": ").append(quote(SCHEMA)).append(",\n")
        out.append("  \"generatedAt\": ")
            .append(quote(DiagnosticFormatting.formatIsoTimestamp(input.generatedAtEpochMillis)))
            .append(",\n")
        out.append("  \"app\": {\n")
        out.append("    \"applicationId\": ").append(quote(input.metadata.applicationId)).append(",\n")
        out.append("    \"versionName\": ").append(quote(input.metadata.versionName)).append(",\n")
        out.append("    \"versionCode\": ").append(input.metadata.versionCode).append('\n')
        out.append("  },\n")
        input.metadata.build?.let { build ->
            out.append("  \"device\": {\n")
            out.append("    \"manufacturer\": ").append(quote(build.manufacturer)).append(",\n")
            out.append("    \"model\": ").append(quote(build.model)).append(",\n")
            out.append("    \"device\": ").append(quote(build.device)).append(",\n")
            out.append("    \"fingerprint\": ").append(quote(build.fingerprint)).append(",\n")
            out.append("    \"androidRelease\": ").append(quote(build.androidRelease)).append(",\n")
            out.append("    \"sdkInt\": ").append(build.sdkInt).append(",\n")
            out.append("    \"securityPatch\": ").append(quote(build.securityPatch)).append('\n')
            out.append("  },\n")
        }
        out.append("  \"permissionMatrix\": ")
        out.append(permissionMatrixJson(input.matrix)).append(",\n")
        out.append("  \"features\": ")
        out.append(array(featureRows(input.features)) { featureJson(it) }).append(",\n")
        out.append("  \"permissionVerification\": ")
        out.append(packagePermissionsJson(input)).append(",\n")
        out.append("  \"packageIdentities\": ")
        out.append(packageIdentitiesJson(input.packages)).append(",\n")
        out.append("  \"systemFeatures\": ")
        out.append(array(input.systemFeatures) { quote(it) }).append(",\n")
        out.append("  \"systemFeaturesReadError\": ")
        out.append(quote(input.systemFeatureReadError)).append(",\n")
        out.append("  \"prompt\": ")
        out.append(array(promptRows(input.prompt)) { promptJson(it) }).append(",\n")
        out.append("  \"summarization\": ")
        out.append(summarizationJson(input.summarization)).append(",\n")
        out.append("  \"pcs\": ")
        out.append(pcsJson(input.surface)).append(",\n")
        out.append("  \"pcsBind\": ")
        out.append(array(input.bindResults) { bindJson(it) }).append(",\n")
        out.append("  \"pcsLaunches\": ")
        out.append(array(input.launchResults) { launchJson(it) }).append(",\n")
        out.append("  \"manifests\": ")
        out.append(array(input.manifests) { manifestJson(it) }).append(",\n")
        out.append("  \"provisioningClassifier\": ")
        out.append(classificationJson(input.classification)).append(",\n")
        out.append("  \"untestedOperations\": ")
        out.append(array(untestedOperations(input)) { quote(it) }).append(",\n")
        out.append("  \"offline\": ")
        out.append(array(input.offline) { offlineJson(it) }).append(",\n")
        out.append("  \"diagnostics\": {\n")
        out.append("    \"eventCount\": ").append(input.events.size).append(",\n")
        out.append("    \"events\": ")
        out.append(array(input.events) { eventJson(it) }).append('\n')
        out.append("  }\n")
        out.append("}\n")
        return out.toString()
    }

    private fun text(input: FullDiagnosticsInput): String {
        val out = StringBuilder()
        out.append("Gemini Nano Manager — full diagnostics report\n")
        out.append("Generated: ").append(DiagnosticFormatting.formatTimestamp(input.generatedAtEpochMillis)).append('\n')
        out.append("App: ").append(input.metadata.applicationId)
            .append(' ').append(input.metadata.versionName)
            .append(" (versionCode ").append(input.metadata.versionCode).append(")\n")
        input.metadata.build?.let { build ->
            out.append("Device: ").append(build.manufacturer).append(' ').append(build.model)
                .append(" · Android ").append(build.androidRelease)
                .append(" (API ").append(build.sdkInt).append(")\n")
            out.append("Fingerprint: ").append(build.fingerprint).append('\n')
            out.append("Security patch: ").append(build.securityPatch ?: "unknown").append('\n')
        }

        out.appendLine()
        out.append("== Permission matrix ==\n")
        val matrix = input.matrix
        if (matrix == null) {
            out.append("not collected\n")
        } else {
            matrix.principals.forEach { principal ->
                out.append(principal.label).append(" (").append(principal.packageName).append(")\n")
                matrix.observationsFor(principal.packageName).forEach { observation ->
                    out.append("  ")
                        .append(PermissionMatrixFormatting.shortPermissionLabel(observation.permission))
                        .append(": ")
                        .append(PermissionMatrixFormatting.renderCell(observation))
                        .append(" | ")
                        .append(PermissionMatrixFormatting.verificationLabel(observation))
                        .append('\n')
                }
            }
            val denied = matrix.requestedButDeniedSignatureOrPrivileged()
            out.append("Requested-but-denied signature or privileged cells: ")
                .append(if (denied.isEmpty()) "none" else denied.joinToString(", ") {
                    "${it.packageName}/${PermissionMatrixFormatting.shortPermissionLabel(it.permission)}"
                })
                .append('\n')
        }

        out.appendLine()
        out.append("== GenAI features ==\n")
        GenAiFeature.MATRIX.forEach { feature ->
            val result = featureRows(input.features).first { it.feature == feature }
            out.append(feature.displayName).append(": ").append(result.status.label)
            out.append(" | probeExecuted=").append(result.status != FeatureStatusKind.NOT_PROBED)
            out.append(" | featureAvailable=").append(result.status == FeatureStatusKind.AVAILABLE)
            result.detail?.let { out.append(" | ").append(it) }
            result.failure?.let {
                out.append(" | error=").append(it.codeLabel)
                    .append(" class=").append(it.exceptionClass)
                    .append(" message=").append(it.message)
            }
            result.featureId?.let { out.append(" | featureId=").append(it) }
            result.baseModelName?.let { out.append(" | baseModel=").append(it) }
            result.modelSignature?.let { out.append(" | signature=").append(it) }
            result.metadataDimension?.let { out.append(" | dimension=").append(it) }
            result.tokenLimit?.let { out.append(" | tokenLimit=").append(it) }
            if (result.status != FeatureStatusKind.NOT_PROBED) {
                out.append(" | latency=").append(result.latencyMillis).append("ms")
                FeatureMatrixFormatting.probeTimeLabel(result.probedAtEpochMillis)?.let {
                    out.append(" | probedAt=").append(it)
                }
            }
            out.append('\n')
        }

        out.appendLine()
        out.append("== Permission verification (requested · checkPermission · requestedPermissionsFlags · declaration) ==\n")
        if (input.packages.isEmpty()) {
            out.append("package facts not collected\n")
        } else {
            val aicoreFirstInstall = aicorePackage(input.packages)?.firstInstallTime
            input.packages.forEach { packageFacts ->
                out.append(packageFacts.packageName).append(": ")
                    .append(PackageFactsFormatting.visibilityLabel(packageFacts))
                    .append(" | coverage: permissions=").append(packageFacts.readCoverage.permissions)
                    .append(" components=").append(packageFacts.readCoverage.components)
                    .append(" signers=").append(packageFacts.readCoverage.signers)
                    .append(" metadata=").append(packageFacts.readCoverage.metadata)
                    .append('\n')
                if (!packageFacts.permissionsRead) {
                    out.append("  permissions not read on this reduced package read; not requested cannot be concluded.\n")
                } else {
                    selectedPermissions(packageFacts).forEach { fact ->
                        out.append("  ").append(permissionFactText(fact, packageFacts, aicoreFirstInstall)).append('\n')
                    }
                    val denied = selectedPermissions(packageFacts)
                        .filter { it.isRequestedButDeniedSignatureOrPrivileged }
                    if (denied.isNotEmpty()) {
                        out.append("  requested-but-denied signature/privileged: ")
                            .append(denied.joinToString(", ") {
                                PermissionMatrixFormatting.shortPermissionLabel(it.permission)
                            })
                            .append('\n')
                    }
                }
            }
        }

        out.appendLine()
        out.append("== Prompt ==\n")
        promptRows(input.prompt).forEach { status ->
            out.append(status.config.label).append(": ").append(status.status.label)
            out.append(" | probeExecuted=").append(status.status != FeatureStatusKind.NOT_PROBED)
            out.append(" | featureAvailable=").append(status.status == FeatureStatusKind.AVAILABLE)
            status.errorCode?.let { out.append(" | error=").append(it) }
            status.exceptionClass?.let { out.append(" | class=").append(it) }
            status.message?.let { out.append(" | message=").append(it) }
            status.featureId?.let { out.append(" | featureId=").append(it) }
            status.baseModelName?.let { out.append(" | baseModel=").append(it) }
            out.append(" | latency=").append(status.latencyMillis).append("ms\n")
        }

        out.appendLine()
        out.append("== Summarization ==\n")
        input.summarization?.let { probe ->
            out.append(probe.status.label).append(" | ").append(probe.statusDetail)
            probe.baseModelName?.let { out.append(" | baseModel=").append(it) }
            probe.failure?.let {
                out.append(" | error=").append(it.codeLabel)
                    .append(" class=").append(it.exceptionClass)
                    .append(" message=").append(it.message)
            }
            out.append('\n')
        } ?: out.append("not probed\n")

        out.appendLine()
        out.append("== Private Compute Services surface ==\n")
        val surface = input.surface
        if (surface == null) {
            out.append("not inspected\n")
        } else if (!surface.packageVisible) {
            out.append("visibility limited: PCS is not visible to this app (not proof of absence)\n")
        } else if (!surface.componentsRead) {
            out.append("components NOT READ: ").append(surface.componentsReadDetail ?: "reduced package read")
                .append(". An empty surface is not claimed.\n")
        } else {
            out.append("Exported components: ").append(surface.exportedComponents.size).append('\n')
            surface.exportedComponents.forEach { component ->
                out.append("  ").append(PackageFactsFormatting.renderComponent(component)).append('\n')
            }
            out.append("Settings: ")
                .append(if (surface.settings.resolved) surface.settings.activityName ?: "resolved" else "not resolved")
                .append(" | ").append(surface.settings.detail).append('\n')
            out.append("Launch controls (explicit tap only; nothing is read back from another app's screen):\n")
            surface.launchTargets.forEach { target ->
                out.append("  ").append(target.label).append(" (").append(target.className).append("): ")
                    .append(if (target.launchable) "launchable" else "no launch")
                    .append(" | ").append(target.detail).append('\n')
            }
        }

        out.appendLine()
        out.append("== PCS bind results (only if the user ran the separate bind probe) ==\n")
        if (input.bindResults.isEmpty()) {
            out.append("not run: no bind plan was collected\n")
        } else {
            input.bindResults.forEach { result ->
                out.append(result.componentName).append(": ").append(result.outcome.label)
                result.role?.let { out.append(" | role=").append(it) }
                result.declaredName?.let { out.append(" | declared=").append(it) }
                result.requiredPermission?.let { out.append(" | requiredPermission=").append(it) }
                out.append(" | attempted=").append(result.outcome.attempted)
                out.append(" | bindFlag=").append(result.bindFlag)
                if (result.interfaceDescriptor != null) {
                    out.append(" | interface=\"").append(result.interfaceDescriptor).append("\"")
                }
                if (result.outcome.attempted) {
                    out.append(" | binder=").append(result.binderLabel)
                }
                result.descriptorReadMillis?.let { out.append(" | descriptorReadMs=").append(it) }
                if (result.descriptorTimedOut) out.append(" | descriptorRead=timed out")
                result.descriptorErrorClass?.let { out.append(" | descriptorErrorClass=").append(it) }
                result.descriptorErrorMessage?.let { out.append(" | descriptorErrorMessage=").append(it) }
                result.exceptionClass?.let { out.append(" | class=").append(it) }
                result.exceptionMessage?.let { out.append(" | message=").append(it) }
                out.append(" | unbindCalls=").append(result.unbindCalls)
                out.append(" | elapsed=").append(result.elapsedMillis).append("ms\n")
            }
            out.append("Note: each result belongs to this app's own bind attempt; it does not prove AICore's access to PCS.\n")
        }

        out.appendLine()
        out.append("== Provisioning failure classifier ==\n")
        val classification = input.classification
        if (classification == null) {
            out.append("not computed\n")
        } else {
            out.append("Result: ").append(classification.result.label).append('\n')
            out.append("Summary: ").append(classification.summary).append('\n')
            out.append("Evidence:\n")
            classification.evidence.forEach { item ->
                out.append("  ").append(item.label).append('\n')
            }
            out.append("What would refute it:\n")
            classification.refutations.forEach { refutation ->
                out.append("  - ").append(refutation).append('\n')
            }
            if (classification.candidates.isNotEmpty()) {
                out.append("Candidates and the observation that would separate them:\n")
                classification.candidates.forEach { candidate ->
                    out.append("  - ").append(candidate.failureClass.label).append(": ")
                        .append(candidate.why)
                        .append(" Missing observation: ").append(candidate.missingObservation)
                        .append('\n')
                }
            }
            classification.nextObservation?.let {
                out.append("Next observation: ").append(it).append('\n')
            }
        }

        out.appendLine()
        out.append("== Installed manifest evidence (Stage 6 reader) ==\n")
        if (input.manifests.isEmpty()) {
            out.append("not read\n")
        } else {
            input.manifests.forEach { snapshot ->
                out.append(snapshot.requestedPackage).append(": ").append(snapshot.readLabel)
                    .append(" (").append(snapshot.readDetail).append(')')
                    .append(" | declaredPackage=").append(snapshot.declaredPackage ?: "unknown")
                    .append(" | complete=").append(snapshot.complete)
                    .append(" | signerSha256=")
                    .append(
                        if (snapshot.signerSha256.isEmpty()) {
                            "not read"
                        } else {
                            snapshot.signerSha256.joinToString(",")
                        },
                    )
                    .append(" | declaredPermissions=")
                    .append(snapshot.declaredPermissions.size)
                    .append(" | usesPermissions=")
                    .append(snapshot.usesPermissions.size)
                    .append('\n')
                snapshot.declaredPermissions.forEach { permission ->
                    out.append("  permission: ").append(permission.name)
                        .append(" protection=")
                        .append(permission.protectionLevelRaw ?: "unknown")
                        .append('\n')
                }
                snapshot.usesPermissions
                    .map { it.name }
                    .filter { name -> NAMED_MANIFEST_PERMISSIONS.any { name == it || name.endsWith(".$it") } }
                    .distinct()
                    .forEach { name -> out.append("  uses-permission: ").append(name).append('\n') }
                out.append("  pccComponents: ")
                    .append(snapshot.pcc.pccComponents.state.name)
                    .append(" (").append(snapshot.pcc.pccComponents.reason).append(")\n")
                out.append("  associationPolicy: ")
                    .append(snapshot.pcc.associationPolicy.state.name)
                    .append(" (").append(snapshot.pcc.associationPolicy.reason).append(")")
                if (snapshot.associationPackageNames.isNotEmpty()) {
                    out.append(" packages=")
                        .append(snapshot.associationPackageNames.joinToString(","))
                }
                if (snapshot.associationCertificateDigests.isNotEmpty()) {
                    out.append(" digests=")
                        .append(snapshot.associationCertificateDigests.joinToString(","))
                }
                out.append('\n')
                out.append("  otherSandboxAttributes: ")
                    .append(snapshot.pcc.otherSandboxAttributes.state.name)
                    .append(" (").append(snapshot.pcc.otherSandboxAttributes.reason).append(")\n")
            }
            out.append("A manifest declaration is not a runtime PCC UID; no signer digest is compared because no public-source digest set is bundled.\n")
        }

        out.appendLine()
        out.append("== PCS activity launches (explicit user taps only) ==\n")
        if (input.launchResults.isEmpty()) {
            out.append("no launch attempted\n")
        } else {
            input.launchResults.forEach { result ->
                out.append(result.label).append(" (").append(result.className).append("): ")
                    .append(result.outcomeLabel)
                out.append(" | resolved=").append(result.resolved)
                result.exceptionClass?.let { out.append(" | class=").append(it) }
                result.exceptionMessage?.let { out.append(" | message=").append(it) }
                result.elapsedMillis?.let { out.append(" | elapsed=").append(it).append("ms") }
                out.append('\n')
            }
            out.append("Nothing is read back from another app's screen.\n")
        }

        out.appendLine()
        out.append("== Package identities ==\n")
        val identities = identityPackages(input.packages)
        if (identities.isEmpty()) {
            out.append("not collected\n")
        } else {
            identities.forEach { packageFacts ->
                out.append(packageFacts.packageName).append(": versionCode=")
                    .append(packageFacts.versionCode ?: "unknown")
                    .append(" | signerSha256=")
                    .append(
                        if (!packageFacts.readCoverage.signers) "not read"
                        else packageFacts.signingSha256.joinToString(",").ifEmpty { "none" },
                    )
                    .append(" | visibility=").append(PackageFactsFormatting.visibilityLabel(packageFacts))
                    .append('\n')
            }
        }

        out.appendLine()
        out.append("== System features naming NPU, AI, virtualization or private compute (names only) ==\n")
        input.systemFeatureReadError?.let { out.append("getSystemAvailableFeatures read error: ").append(it).append('\n') }
        if (input.systemFeatures.isEmpty()) {
            out.append(
                if (input.systemFeatureReadError == null) {
                    "none reported by getSystemAvailableFeatures\n"
                } else {
                    "no names read; the failure above is not a claim that none exist\n"
                },
            )
        } else {
            input.systemFeatures.forEach { out.append("  ").append(it).append('\n') }
        }

        out.appendLine()
        out.append("== Explicitly untested operations ==\n")
        val untested = untestedOperations(input)
        if (untested.isEmpty()) {
            out.append("none\n")
        } else {
            untested.forEach { out.append("  ").append(it).append('\n') }
        }

        out.appendLine()
        out.append("== Offline inference (fixed inputs) ==\n")
        if (input.offline.isEmpty()) {
            out.append("not run\n")
        } else {
            input.offline.forEach { result ->
                out.append(result.feature.displayName).append(": ").append(result.outcome.label)
                result.output?.let { out.append(" | output=").append(it) }
                result.detail?.let { out.append(" | ").append(it) }
                result.failure?.let {
                    out.append(" | error=").append(it.codeLabel)
                        .append(" class=").append(it.exceptionClass)
                        .append(" message=").append(it.message)
                }
                out.append(" | latency=").append(result.latencyMillis).append("ms\n")
            }
        }
        out.append("Note: offline operation is only demonstrated if connectivity was disabled externally.\n")

        out.appendLine()
        out.append("== Diagnostic snapshot ==\n")
        out.append("Events: ").append(input.events.size).append('\n')
        if (input.events.isEmpty()) {
            out.append("(no events recorded)\n")
        } else {
            input.events.forEach { event ->
                out.append(DiagnosticFormatting.formatEvent(event)).append('\n')
            }
        }
        return out.toString()
    }

    private fun permissionMatrixJson(matrix: PermissionMatrix?): String {
        if (matrix == null) return "null"
        val out = StringBuilder()
        out.append("{\n")
        out.append("    \"principals\": ")
        out.append(array(matrix.principals) {
            "{" + "\"label\": " + quote(it.label) + ", \"packageName\": " + quote(it.packageName) + "}"
        }).append(",\n")
        out.append("    \"permissions\": ")
        out.append(array(matrix.permissions) { quote(it) }).append(",\n")
        out.append("    \"observations\": ")
        out.append(
            array(matrix.observations) { observation ->
                "{" +
                    "\"principal\": " + quote(observation.packageName) + ", " +
                    "\"permission\": " + quote(observation.permission) + ", " +
                    "\"requested\": " + observation.requested + ", " +
                    "\"checkPermission\": " + quote(observation.grant.label) + ", " +
                    "\"requestedPermissionsFlagsGrant\": " +
                    (observation.requestedFlagsGrant?.toString() ?: "null") + ", " +
                    "\"grantSourcesDisagree\": " + observation.grantSourcesDisagree + ", " +
                    "\"declaration\": " + declarationJson(observation.declaration) + ", " +
                    "\"status\": " + quote(observation.status.label) +
                    "}"
            },
        ).append('\n')
        out.append("  }")
        return out.toString()
    }

    private fun packagePermissionsJson(input: FullDiagnosticsInput): String {
        if (input.packages.isEmpty()) return "[]"
        val aicoreFirstInstall = aicorePackage(input.packages)?.firstInstallTime
        return array(input.packages) { packageFacts ->
            val selected = if (packageFacts.permissionsRead) selectedPermissions(packageFacts) else emptyList()
            "{" +
                "\"packageName\": " + quote(packageFacts.packageName) + ", " +
                "\"visibility\": " + quote(packageFacts.visibility.name) + ", " +
                "\"permissionsRead\": " + packageFacts.permissionsRead + ", " +
                "\"coverage\": {" +
                "\"permissions\": " + packageFacts.readCoverage.permissions + ", " +
                "\"components\": " + packageFacts.readCoverage.components + ", " +
                "\"signers\": " + packageFacts.readCoverage.signers + ", " +
                "\"metadata\": " + packageFacts.readCoverage.metadata + "}, " +
                "\"readError\": " + (packageFacts.readError?.let {
                    "{\"exceptionClass\": " + quote(it.exceptionClass) + ", " +
                        "\"message\": " + quote(it.message) + ", " +
                        "\"attempts\": " + it.attempts + "}"
                } ?: "null") + ", " +
                "\"requestedPermissions\": " +
                array(selectedPermissionNames(packageFacts)) { quote(it) } + ", " +
                "\"permissions\": " + array(selected) { permissionFactJson(it, packageFacts, aicoreFirstInstall) } + ", " +
                "\"requestedButDeniedSignatureOrPrivileged\": " +
                array(selected.filter { it.isRequestedButDeniedSignatureOrPrivileged }) { quote(it.permission) } +
                "}"
        }
    }

    private fun permissionFactJson(
        fact: PermissionGrantFacts,
        holder: PackageFacts?,
        aicoreFirstInstallTime: Long?,
    ): String {
        val installs = if (fact.permission == PlatformPermissions.AICORE_BIND) {
            "\"firstInstallTime\": " + (holder?.firstInstallTime?.toString() ?: "null") + ", " +
                "\"lastUpdateTime\": " + (holder?.lastUpdateTime?.toString() ?: "null") + ", " +
                "\"aicoreFirstInstallTime\": " + (aicoreFirstInstallTime?.toString() ?: "null") + ", "
        } else {
            ""
        }
        return "{" +
            "\"permission\": " + quote(fact.permission) + ", " +
            "\"requested\": " + fact.requested + ", " +
            "\"checkPermission\": " + quote(fact.state.label) + ", " +
            "\"requestedPermissionsFlagsGrant\": " +
            (fact.requestedFlagsGrant?.toString() ?: "null") + ", " +
            "\"grantSourcesDisagree\": " + fact.grantSourcesDisagree + ", " +
            installs +
            "\"declaration\": " + declarationJson(fact.declaration) +
            "}"
    }

    private fun packageIdentitiesJson(packages: List<PackageFacts>): String =
        array(identityPackages(packages)) { packageFacts ->
            "{" +
                "\"packageName\": " + quote(packageFacts.packageName) + ", " +
                "\"visibility\": " + quote(packageFacts.visibility.name) + ", " +
                "\"versionName\": " + quote(packageFacts.versionName) + ", " +
                "\"versionCode\": " + (packageFacts.versionCode?.toString() ?: "null") + ", " +
                "\"signersRead\": " + packageFacts.readCoverage.signers + ", " +
                "\"signers\": " + quote(if (packageFacts.readCoverage.signers) "read" else "not read") + ", " +
                "\"signerSha256\": " +
                array(packageFacts.signingSha256) { quote(it) } + ", " +
                "\"firstInstallTime\": " + (packageFacts.firstInstallTime?.toString() ?: "null") + ", " +
                "\"lastUpdateTime\": " + (packageFacts.lastUpdateTime?.toString() ?: "null") +
                "}"
        }

    private fun declarationJson(declaration: dev.shockstruck.gemininano.model.PermissionDeclaration?): String {
        if (declaration == null) return "null"
        return "{" +
            "\"defined\": " + declaration.defined + ", " +
            "\"declaringPackage\": " + quote(declaration.declaringPackage) + ", " +
            "\"protectionBase\": " + (declaration.protectionBase?.toString() ?: "null") + ", " +
            "\"protectionFlags\": " + declaration.protectionFlags + ", " +
            "\"protection\": " + quote(declaration.protectionLabel) + ", " +
            "\"lookupErrorClass\": " + quote(declaration.lookupErrorClass) + ", " +
            "\"lookupErrorMessage\": " + quote(declaration.lookupErrorMessage) +
            "}"
    }

    private fun featureJson(result: FeatureProbeResult): String = "{" +
        "\"feature\": " + quote(result.feature.key) + ", " +
        "\"label\": " + quote(result.feature.displayName) + ", " +
        "\"artifact\": " + quote(result.feature.artifact) + ", " +
        "\"status\": " + quote(result.status.label) + ", " +
        "\"probeExecuted\": " + (result.status != FeatureStatusKind.NOT_PROBED) + ", " +
        "\"featureAvailable\": " + (result.status == FeatureStatusKind.AVAILABLE) + ", " +
        "\"featureId\": " + (result.featureId?.toString() ?: "null") + ", " +
        "\"errorCode\": " + (result.errorCode?.toString() ?: "null") + ", " +
        "\"exceptionClass\": " + quote(result.exceptionClass) + ", " +
        "\"message\": " + quote(result.message) + ", " +
        "\"baseModelName\": " + quote(result.baseModelName) + ", " +
        "\"modelSignature\": " + quote(result.modelSignature) + ", " +
        "\"metadataDimension\": " + (result.metadataDimension?.toString() ?: "null") + ", " +
        "\"tokenLimit\": " + (result.tokenLimit?.toString() ?: "null") + ", " +
        "\"latencyMillis\": " + result.latencyMillis + ", " +
        "\"probedAtEpochMillis\": " + result.probedAtEpochMillis + ", " +
        "\"detail\": " + quote(result.detail) +
        "}"

    private fun promptJson(status: PromptConfigStatus): String = "{" +
        "\"config\": " + quote(status.config.label) + ", " +
        "\"status\": " + quote(status.status.label) + ", " +
        "\"probeExecuted\": " + (status.status != FeatureStatusKind.NOT_PROBED) + ", " +
        "\"featureAvailable\": " + (status.status == FeatureStatusKind.AVAILABLE) + ", " +
        "\"featureId\": " + (status.featureId?.toString() ?: "null") + ", " +
        "\"errorCode\": " + (status.errorCode?.toString() ?: "null") + ", " +
        "\"exceptionClass\": " + quote(status.exceptionClass) + ", " +
        "\"message\": " + quote(status.message) + ", " +
        "\"baseModelName\": " + quote(status.baseModelName) + ", " +
        "\"generationSucceeded\": " + status.generationSucceeded + ", " +
        "\"latencyMillis\": " + status.latencyMillis + ", " +
        "\"probedAtEpochMillis\": " + status.probedAtEpochMillis +
        "}"

    private fun summarizationJson(probe: SummarizationProbeResult?): String {
        if (probe == null) return "null"
        return "{" +
            "\"status\": " + quote(probe.status.label) + ", " +
            "\"detail\": " + quote(probe.statusDetail) + ", " +
            "\"baseModelName\": " + quote(probe.baseModelName) + ", " +
            "\"errorCode\": " + (probe.failure?.errorCode?.toString() ?: "null") + ", " +
            "\"exceptionClass\": " + quote(probe.failure?.exceptionClass) + ", " +
            "\"message\": " + quote(probe.failure?.message) + ", " +
            "\"probedAtEpochMillis\": " + probe.probedAtEpochMillis +
            "}"
    }

    private fun pcsJson(surface: PcsSurface?): String {
        if (surface == null) return "null"
        return "{" +
            "\"packageVisible\": " + surface.packageVisible + ", " +
            "\"componentsRead\": " + surface.componentsRead + ", " +
            "\"componentsReadDetail\": " + quote(surface.componentsReadDetail) + ", " +
            "\"exportedComponents\": " + array(surface.exportedComponents) {
                "{" +
                    "\"kind\": " + quote(it.kind.label) + ", " +
                    "\"className\": " + quote(it.className) + ", " +
                    "\"exported\": " + it.exported + ", " +
                    "\"enabled\": " + it.enabled + ", " +
                    "\"manifestEffectiveEnabled\": " + it.manifestEffectiveEnabled + ", " +
                    "\"runtimeEnabledSetting\": " + (it.runtimeEnabledSetting?.toString() ?: "null") + ", " +
                    "\"effectivelyEnabled\": " + (it.effectivelyEnabled?.toString() ?: "null") + ", " +
                    "\"permission\": " + quote(it.requiredPermission) +
                    "}"
            } + ", " +
            "\"launchTargets\": " + array(surface.launchTargets) {
                "{" +
                    "\"label\": " + quote(it.label) + ", " +
                    "\"className\": " + quote(it.className) + ", " +
                    "\"declared\": " + it.declared + ", " +
                    "\"exported\": " + it.exported + ", " +
                    "\"effectivelyEnabled\": " + (it.effectivelyEnabled?.toString() ?: "null") + ", " +
                    "\"requiredPermission\": " + quote(it.requiredPermission) + ", " +
                    "\"launchable\": " + it.launchable + ", " +
                    "\"detail\": " + quote(it.detail) +
                    "}"
            } + ", " +
            "\"settings\": {" +
            "\"resolved\": " + surface.settings.resolved + ", " +
            "\"activityName\": " + quote(surface.settings.activityName) + ", " +
            "\"detail\": " + quote(surface.settings.detail) +
            "}}"
    }

    private fun bindJson(result: PcsBindResult): String = "{" +
        "\"componentName\": " + quote(result.componentName) + ", " +
        "\"outcome\": " + quote(result.outcome.label) + ", " +
        "\"attempted\": " + result.outcome.attempted + ", " +
        "\"role\": " + quote(result.role) + ", " +
        "\"declaredName\": " + quote(result.declaredName) + ", " +
        "\"requiredPermission\": " + quote(result.requiredPermission) + ", " +
        "\"bindFlag\": " + quote(result.bindFlag) + ", " +
        "\"binderAlive\": " + (result.binderAlive?.toString() ?: "null") + ", " +
        "\"binder\": " + quote(result.binderLabel) + ", " +
        "\"descriptorReadMillis\": " + (result.descriptorReadMillis?.toString() ?: "null") + ", " +
        "\"descriptorErrorClass\": " + quote(result.descriptorErrorClass) + ", " +
        "\"descriptorErrorMessage\": " + quote(result.descriptorErrorMessage) + ", " +
        "\"descriptorTimedOut\": " + result.descriptorTimedOut + ", " +
        "\"exceptionClass\": " + quote(result.exceptionClass) + ", " +
        "\"exceptionMessage\": " + quote(result.exceptionMessage) + ", " +
        "\"interfaceDescriptor\": " + quote(result.interfaceDescriptor) + ", " +
        "\"elapsedMillis\": " + result.elapsedMillis + ", " +
        "\"unbindCalls\": " + result.unbindCalls +
        "}"

    private fun launchJson(result: PcsActivityLaunchResult): String = "{" +
        "\"label\": " + quote(result.label) + ", " +
        "\"className\": " + quote(result.className) + ", " +
        "\"resolved\": " + result.resolved + ", " +
        "\"outcome\": " + quote(result.outcomeLabel) + ", " +
        "\"exceptionClass\": " + quote(result.exceptionClass) + ", " +
        "\"exceptionMessage\": " + quote(result.exceptionMessage) + ", " +
        "\"elapsedMillis\": " + (result.elapsedMillis?.toString() ?: "null") +
        "}"

    /** Every matrix feature, with a NOT_PROBED row where no result exists. */
    private fun featureRows(features: List<FeatureProbeResult>): List<FeatureProbeResult> {
        val byFeature = features.associateBy { it.feature }
        return GenAiFeature.MATRIX.map { byFeature[it] ?: FeatureProbeResult.notProbed(it) }
    }

    /** Every Prompt config, with a NOT_PROBED row where no result exists. */
    private fun promptRows(prompt: List<PromptConfigStatus>): List<PromptConfigStatus> {
        val byConfig = prompt.associateBy { it.config }
        return PromptModelConfig.ALL.map { config ->
            byConfig[config] ?: PromptConfigStatus(
                config = config,
                status = FeatureStatusKind.NOT_PROBED,
                featureId = null,
                errorCode = null,
                exceptionClass = null,
                message = null,
                baseModelName = null,
                generatedText = null,
                latencyMillis = 0L,
                probedAtEpochMillis = 0L,
            )
        }
    }

    private fun selectedPermissionNames(packageFacts: PackageFacts): List<String> =
        if (packageFacts.packageName in AICORE_AND_PCS) {
            packageFacts.requestedPermissions
        } else {
            packageFacts.requestedPermissions.filter { it in PlatformPermissions.OTHER_PACKAGE_PERMISSIONS }
        }

    private fun selectedPermissions(packageFacts: PackageFacts): List<PermissionGrantFacts> =
        if (packageFacts.packageName in AICORE_AND_PCS) {
            packageFacts.permissionGrants
        } else {
            packageFacts.permissionGrants.filter {
                it.permission in PlatformPermissions.OTHER_PACKAGE_PERMISSIONS
            }
        }

    private fun permissionFactText(
        fact: PermissionGrantFacts,
        holder: PackageFacts,
        aicoreFirstInstallTime: Long?,
    ): String {
        val base = PermissionMatrixFormatting.renderPermissionFact(fact)
        if (fact.permission != PlatformPermissions.AICORE_BIND) return base
        return "$base · firstInstall=${holder.firstInstallTime ?: "unknown"} · " +
            "lastUpdate=${holder.lastUpdateTime ?: "unknown"} · " +
            "aicoreFirstInstall=${aicoreFirstInstallTime ?: "unknown"}"
    }

    private fun identityPackages(packages: List<PackageFacts>): List<PackageFacts> =
        packages.filter { it.packageName in IDENTITY_PACKAGES }.sortedBy { it.packageName }

    private fun aicorePackage(packages: List<PackageFacts>): PackageFacts? =
        packages.firstOrNull { it.packageName == PlatformPrincipals.AICORE }
            ?: packages.firstOrNull { it.packageName == PlatformPrincipals.AICORE_LEGACY }

    private fun untestedOperations(input: FullDiagnosticsInput): List<String> = buildList {
        featureRows(input.features)
            .filter { it.status == FeatureStatusKind.NOT_PROBED }
            .forEach { add("Probe ${it.feature.displayName}") }
        promptRows(input.prompt)
            .filter { it.status == FeatureStatusKind.NOT_PROBED }
            .forEach { add("Prompt ${it.config.label}") }
        if (input.summarization == null) add("Summarization probe")
        input.bindResults.filter { !it.outcome.attempted }.forEach {
            add("Bind ${it.componentName} (${it.outcome.label})")
        }
        input.surface?.launchTargets.orEmpty().forEach { target ->
            val launched = input.launchResults.any { it.className == target.className }
            if (!target.launchable) {
                add("Launch ${target.label} not attempted: ${target.detail}")
            } else if (!launched) {
                add("Launch ${target.label} (${target.className})")
            }
        }
        if (input.offline.isEmpty()) add("Offline inference matrix")
    }

    private fun offlineJson(result: OfflineInferenceResult): String = "{" +
        "\"feature\": " + quote(result.feature.key) + ", " +
        "\"outcome\": " + quote(result.outcome.label) + ", " +
        "\"statusLabel\": " + quote(result.statusLabel) + ", " +
        "\"output\": " + quote(result.output) + ", " +
        "\"detail\": " + quote(result.detail) + ", " +
        "\"errorCode\": " + (result.failure?.errorCode?.toString() ?: "null") + ", " +
        "\"exceptionClass\": " + quote(result.failure?.exceptionClass) + ", " +
        "\"message\": " + quote(result.failure?.message) + ", " +
        "\"latencyMillis\": " + result.latencyMillis +
        "}"

    private fun eventJson(event: DiagnosticEvent): String = "{" +
        "\"timestamp\": " + quote(DiagnosticFormatting.formatIsoTimestamp(event.timestampEpochMillis)) + ", " +
        "\"component\": " + quote(event.component) + ", " +
        "\"operation\": " + quote(event.operation) + ", " +
        "\"outcome\": " + quote(event.outcome.label) + ", " +
        "\"severity\": " + quote(event.severity.label) + ", " +
        "\"exceptionClass\": " + quote(event.exceptionClass) + ", " +
        "\"errorCode\": " + (event.errorCode?.toString() ?: "null") + ", " +
        "\"message\": " + quote(event.message) + ", " +
        "\"featureId\": " + quote(event.featureId) + ", " +
        "\"bytes\": " + (event.bytes?.toString() ?: "null") + ", " +
        "\"durationMillis\": " + (event.durationMillis?.toString() ?: "null") +
        "}"

    private fun manifestJson(snapshot: ManifestEvidenceSnapshot): String = "{" +
        "\"requestedPackage\": " + quote(snapshot.requestedPackage) + ", " +
        "\"read\": " + quote(snapshot.readLabel) + ", " +
        "\"readDetail\": " + quote(snapshot.readDetail) + ", " +
        "\"declaredPackage\": " + quote(snapshot.declaredPackage) + ", " +
        "\"complete\": " + snapshot.complete + ", " +
        "\"signerSha256\": " + array(snapshot.signerSha256) { quote(it) } + ", " +
        "\"declaredPermissions\": " + array(snapshot.declaredPermissions) {
            "{" +
                "\"name\": " + quote(it.name) + ", " +
                "\"protectionLevelRaw\": " + quote(it.protectionLevelRaw) + ", " +
                "\"protectionLevelBase\": " + quote(it.protectionLevelBase) + ", " +
                "\"protectionLevelFlags\": " + array(it.protectionLevelFlags) { flag -> quote(flag) } +
                "}"
        } + ", " +
        "\"usesPermissions\": " + array(snapshot.usesPermissions) {
            "{" +
                "\"name\": " + quote(it.name) + ", " +
                "\"maxSdkVersion\": " + quote(it.maxSdkVersion) +
                "}"
        } + ", " +
        "\"pccComponents\": " + pccStateJson(
            snapshot.pcc.pccComponents.state.name,
            snapshot.pcc.pccComponents.reason,
            snapshot.pcc.pccComponents.matched.size,
        ) + ", " +
        "\"associationPolicy\": {" +
        "\"state\": " + quote(snapshot.pcc.associationPolicy.state.name) + ", " +
        "\"reason\": " + quote(snapshot.pcc.associationPolicy.reason) + ", " +
        "\"packages\": " + array(
            snapshot.pcc.associationPolicy.matched.flatMap { it.packages },
        ) {
            "{" +
                "\"name\": " + quote(it.name) + ", " +
                "\"certificateDigests\": " + array(it.certificateDigests) { digest -> quote(digest) } +
                "}"
        } +
        "}, " +
        "\"otherSandboxAttributes\": " + pccStateJson(
            snapshot.pcc.otherSandboxAttributes.state.name,
            snapshot.pcc.otherSandboxAttributes.reason,
            snapshot.pcc.otherSandboxAttributes.matched.size,
        ) +
        "}"

    private fun pccStateJson(state: String, reason: String, matched: Int): String = "{" +
        "\"state\": " + quote(state) + ", " +
        "\"reason\": " + quote(reason) + ", " +
        "\"matchedCount\": " + matched +
        "}"

    private fun classificationJson(classification: ProvisioningClassification?): String {
        if (classification == null) return "null"
        return "{" +
            "\"result\": " + quote(classification.result.label) + ", " +
            "\"indeterminate\": " + classification.isIndeterminate + ", " +
            "\"summary\": " + quote(classification.summary) + ", " +
            "\"nextObservation\": " + quote(classification.nextObservation) + ", " +
            "\"evidence\": " + array(classification.evidence) {
                "{" +
                    "\"strength\": " + quote(it.strength.label) + ", " +
                    "\"source\": " + quote(it.source) + ", " +
                    "\"statement\": " + quote(it.statement) +
                    "}"
            } + ", " +
            "\"refutations\": " + array(classification.refutations) { quote(it) } + ", " +
            "\"candidates\": " + array(classification.candidates) {
                "{" +
                    "\"class\": " + quote(it.failureClass.label) + ", " +
                    "\"why\": " + quote(it.why) + ", " +
                    "\"missingObservation\": " + quote(it.missingObservation) +
                    "}"
            } +
            "}"
    }

    private fun <T> array(items: List<T>, render: (T) -> String): String =
        items.joinToString(prefix = "[\n    ", postfix = "\n  ]", separator = ",\n    ") { render(it) }

    private fun quote(value: String?): String {
        if (value == null) return "null"
        val out = StringBuilder(value.length + 2)
        out.append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch.code < 0x20) {
                    out.append("\\u").append(String.format("%04x", ch.code))
                } else {
                    out.append(ch)
                }
            }
        }
        out.append('"')
        return out.toString()
    }

    companion object {
        const val SCHEMA = "dev.shockstruck.gemininano.full-diagnostics/1"

        /** Packages whose full requested-permission list is exported. */
        private val AICORE_AND_PCS = setOf(
            PlatformPrincipals.AICORE,
            PlatformPrincipals.AICORE_LEGACY,
            PlatformPrincipals.PCS,
        )

        /** Packages whose versionCode and signer are exported. */
        private val IDENTITY_PACKAGES =
            AICORE_AND_PCS + setOf(PlatformPrincipals.ASI, PlatformPrincipals.GMS)

        /** Manifest uses-permissions worth printing in the text report. */
        private val NAMED_MANIFEST_PERMISSIONS = listOf(
            "PROVIDE_PRIVATE_COMPUTE_SERVICES",
            "PROVIDE_ON_DEVICE_INTELLIGENCE",
            "MANAGE_VIRTUAL_MACHINE",
            "ACCESS_NPU_MODEL_MANAGER_API",
            "READ_DEVICE_CONFIG",
            "BIND_SERVICE",
        )
    }
}
