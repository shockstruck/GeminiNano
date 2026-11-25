package dev.shockstruck.gemininano.diagnostics

import dev.shockstruck.gemininano.model.AppUserFacts
import dev.shockstruck.gemininano.model.DiagnosticEvent
import dev.shockstruck.gemininano.model.FeatureMatrixFormatting
import dev.shockstruck.gemininano.model.FeatureProbeResult
import dev.shockstruck.gemininano.model.FeatureStatusKind
import dev.shockstruck.gemininano.model.GenAiFeature
import dev.shockstruck.gemininano.model.InstalledCodeDefaultValue
import dev.shockstruck.gemininano.model.InstalledCodeMarkerFacts
import dev.shockstruck.gemininano.model.InstalledCodeOutcome
import dev.shockstruck.gemininano.model.InstalledCodePackageEvidence
import dev.shockstruck.gemininano.model.InstalledCodePackageFacts
import dev.shockstruck.gemininano.model.OfflineInferenceResult
import dev.shockstruck.gemininano.model.PackageFacts
import dev.shockstruck.gemininano.model.PackageFactsFormatting
import dev.shockstruck.gemininano.model.PcsActivityLaunchResult
import dev.shockstruck.gemininano.model.PcsBindHistoryEntry
import dev.shockstruck.gemininano.model.PcsBindProvenance
import dev.shockstruck.gemininano.model.PcsBindReport
import dev.shockstruck.gemininano.model.PcsBindResult
import dev.shockstruck.gemininano.model.PcsBindRow
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
    /**
     * Bind rows with current/earlier/not-run provenance and the three separated facts.
     * When empty, rows are derived from [bindResults] so a stored-result call site keeps
     * working without inventing provenance.
     */
    val bindRows: List<PcsBindRow> = emptyList(),
    /** Explicit note when app-private bind storage was missing or unreadable. */
    val bindHistoryNote: String? = null,
    val offline: List<OfflineInferenceResult>,
    val events: List<DiagnosticEvent>,
    /** Full package facts, so every requested permission can be exported with all sources. */
    val packages: List<PackageFacts> = emptyList(),
    /** Results of explicit user-tapped PCS activity launches. */
    val launchResults: List<PcsActivityLaunchResult> = emptyList(),
    /** `getSystemAvailableFeatures` names relevant to NPU, AI, virtualization or PCC. */
    val systemFeatures: List<String> = emptyList(),
    val systemFeatureReadError: String? = null,
    /** Installed-manifest evidence from the installed-manifest reader. */
    val manifests: List<ManifestEvidenceSnapshot> = emptyList(),
    /** The pure classifier result computed from exactly the evidence in this report. */
    val classification: ProvisioningClassification? = null,
    /**
     * Per-package installed-code evidence, read once inside Run Full Diagnostics. Only the
     * `InstalledCodePackageFacts` type is exported; no bytecode, asset or model byte is.
     */
    val installedCode: List<InstalledCodePackageEvidence> = emptyList(),
    /** Whether this app runs in the system (owner) user, as `UserManager` reported it. */
    val appUser: AppUserFacts? = null,
    /** The ordered evaluation that names the first provisioning dependency shown failing. */
    val dependencies: ProvisioningDependencyEvaluation? = null,
    /** Every stored bind observation, oldest first; at most five per component. */
    val bindHistory: List<PcsBindHistoryEntry> = emptyList(),
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
        out.append(array(effectiveRows(input)) { bindRowJson(it) }).append(",\n")
        out.append("  \"pcsBindHistoryNote\": ")
        out.append(quote(input.bindHistoryNote)).append(",\n")
        out.append("  \"pcsLaunches\": ")
        out.append(array(input.launchResults) { launchJson(it) }).append(",\n")
        out.append("  \"manifests\": ")
        out.append(array(input.manifests) { manifestJson(it) }).append(",\n")
        out.append("  \"appUser\": ")
        out.append(appUserJson(input.appUser)).append(",\n")
        out.append("  \"installedCode\": ")
        out.append(installedCodeJson(input.installedCode)).append(",\n")
        out.append("  \"provisioningDependencies\": ")
        out.append(provisioningDependenciesJson(input.dependencies)).append(",\n")
        out.append("  \"pcsBindHistory\": ")
        out.append(bindHistoryJson(input.bindHistory, input.bindHistoryNote)).append(",\n")
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
        out.append("== PCS bind results (binding observed; RPC authorization and provisioning not tested) ==\n")
        val bindRows = effectiveRows(input)
        if (bindRows.isEmpty()) {
            out.append("not run: no bind plan was collected\n")
        } else {
            bindRows.forEach { row ->
                val result = row.result
                out.append(result.componentName).append(": ").append(row.provenance.label)
                row.observedAtEpochMillis?.let {
                    out.append(" at ").append(DiagnosticFormatting.formatTimestamp(it))
                }
                row.appVersionName?.let { out.append(" | app=").append(it) }
                row.pcsVersionLabel?.let { out.append(" | ").append(it) }
                out.append('\n')
                out.append("  binding (observed): ").append(row.bindingFact).append('\n')
                out.append("  RPC authorization: ").append(row.rpcAuthorizationFact).append('\n')
                out.append("  provisioning: ").append(row.provisioningFact).append('\n')
                out.append("  ")
                result.role?.let { out.append("role=").append(it).append(" | ") }
                result.declaredName?.let { out.append("declared=").append(it).append(" | ") }
                result.requiredPermission?.let {
                    out.append("requiredPermission=").append(it).append(" | ")
                }
                out.append("outcome=").append(result.outcome.label)
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
        input.bindHistoryNote?.let { note ->
            out.append("History: ").append(note).append('\n')
        }

        out.appendLine()
        out.append("== Stored PCS bind history (at most five per component) ==\n")
        if (input.bindHistory.isEmpty()) {
            out.append("no stored observation\n")
        } else {
            PcsBindReport.historyByComponent(input.bindHistory).forEach { (componentName, entries) ->
                out.append(componentName).append('\n')
                entries.forEach { entry ->
                    out.append("  ").append(DiagnosticFormatting.formatTimestamp(entry.observedAtEpochMillis))
                        .append(" | app=").append(entry.appVersionName)
                        .append(" (").append(entry.appVersionCode).append(')')
                        .append(" | pcsVersionCode=")
                        .append(entry.pcsVersionCode?.toString() ?: "unknown")
                        .append(" | outcome=").append(entry.result.outcome.label)
                        .append(" | binding: ").append(PcsBindReport.bindingFact(entry.result))
                        .append(" | unbindCalls=").append(entry.result.unbindCalls)
                        .append(" | elapsed=").append(entry.result.elapsedMillis).append("ms\n")
                }
            }
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
        out.append("== Installed manifest evidence ==\n")
        if (input.manifests.isEmpty()) {
            out.append("not read\n")
        } else {
            input.manifests.forEach { snapshot ->
                out.append(snapshot.requestedPackage).append(": ").append(snapshot.readLabel)
                    .append(" (").append(snapshot.readDetail).append(')')
                    .append(" | declaredPackage=").append(snapshot.declaredPackage ?: "unknown")
                    .append(" | complete=").append(snapshot.complete)
                    .append(" | partial=").append(snapshot.partial)
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
                if (snapshot.partial) {
                    out.append("  partial read: absence cannot be claimed\n")
                }
                out.append("  pccComponents: ")
                    .append(snapshot.pcc.pccComponents.state.name)
                    .append(" (").append(snapshot.pcc.pccComponents.reason).append(")\n")
                snapshot.pccComponents.forEach { component ->
                    val attribute = component.privateComputeCoreAttribute()
                    out.append("    ").append(component.kind)
                        .append(" ").append(component.name ?: "unnamed")
                        .append(" privateComputeCore=").append(component.privateComputeCore.name)
                        .append(" rawValue=").append(attribute?.rawValue ?: "not read")
                        .append(" typedValue=").append(attribute?.typedValue ?: "not read")
                        .append('\n')
                }
                out.append("  associationPolicy: ")
                    .append(snapshot.pcc.associationPolicy.state.name)
                    .append(" (").append(snapshot.pcc.associationPolicy.reason).append(")\n")
                snapshot.associationElements.forEach { element ->
                    out.append("    element ").append(element.name)
                    if (element.attributes.isNotEmpty()) {
                        out.append(" attributes=[")
                            .append(
                                element.attributes.joinToString("; ") {
                                    "${it.name}=${it.rawValue ?: it.typedValue}"
                                },
                            )
                            .append("]")
                    }
                    if (element.packages.isNotEmpty()) {
                        out.append(" packages=[")
                            .append(
                                element.packages.joinToString("; ") {
                                    "${it.name ?: "unnamed"}:${it.certificateDigests.joinToString(",")}"
                                },
                            )
                            .append("]")
                    }
                    out.append('\n')
                }
                if (snapshot.associationCertificateDigests.isNotEmpty()) {
                    out.append("    digests=")
                        .append(snapshot.associationCertificateDigests.joinToString(","))
                        .append('\n')
                }
                out.append("  otherSandboxAttributes: ")
                    .append(snapshot.pcc.otherSandboxAttributes.state.name)
                    .append(" (").append(snapshot.pcc.otherSandboxAttributes.reason).append(")\n")
                snapshot.sandboxAttributes.forEach { sandbox ->
                    out.append("    ").append(sandbox.componentKind)
                        .append(" ").append(sandbox.componentName ?: "unnamed")
                        .append(" ").append(sandbox.attribute.name)
                        .append("=").append(sandbox.attribute.rawValue ?: sandbox.attribute.typedValue)
                        .append('\n')
                }
            }
            out.append("A manifest declaration is not a runtime PCC UID, and a declared association policy is not proof of enforcement; no signer digest is compared because no public-source digest set is bundled.\n")
        }

        out.appendLine()
        out.append("== Installed code facts (static, read-only) ==\n")
        if (input.installedCode.isEmpty()) {
            out.append("not read\n")
        } else {
            input.installedCode.forEach { evidence -> appendInstalledCodeText(out, evidence) }
            out.append("Note: only the installed-code facts type is exported. Reader failure messages can name an APK file or a ZIP entry (the facts already carry the dex entry names) but cannot contain a decoded DEX string: every format error uses numeric offsets, indices, opcodes or fixed labels.\n")
        }

        out.appendLine()
        out.append("== First failing provisioning dependency ==\n")
        out.append("App user: ").append(input.appUser?.label ?: "not read").append('\n')
        val dependencies = input.dependencies
        if (dependencies == null) {
            out.append("not evaluated\n")
        } else {
            out.append("Result: ").append(dependencies.resultLabel).append('\n')
            out.append("Summary: ").append(dependencies.summary).append('\n')
            dependencies.steps.forEachIndexed { index, step ->
                out.append(index + 1).append(". [").append(step.outcome.label).append("] ")
                    .append(step.id.label).append(" — ").append(step.summary).append('\n')
                step.evidence.forEach { evidence ->
                    out.append("   ").append(evidence.label).append('\n')
                }
            }
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

    private fun bindRowJson(row: PcsBindRow): String {
        val result = row.result
        return "{" +
            "\"componentName\": " + quote(result.componentName) + ", " +
            "\"provenance\": " + quote(row.provenance.label) + ", " +
            "\"observedAt\": " +
            quote(row.observedAtEpochMillis?.let { DiagnosticFormatting.formatIsoTimestamp(it) }) + ", " +
            "\"observedAtEpochMillis\": " +
            (row.observedAtEpochMillis?.toString() ?: "null") + ", " +
            "\"appVersionName\": " + quote(row.appVersionName) + ", " +
            "\"appVersionCode\": " + (row.appVersionCode?.toString() ?: "null") + ", " +
            "\"pcsVersionCode\": " +
            ((row.storedPcsVersionCode ?: row.currentPcsVersionCode)?.toString() ?: "null") + ", " +
            "\"currentPcsVersionCode\": " +
            (row.currentPcsVersionCode?.toString() ?: "null") + ", " +
            "\"pcsVersionMismatch\": " + row.pcsVersionMismatch + ", " +
            "\"classifierInput\": " + row.classifierEligible + ", " +
            "\"binding\": " + quote(row.bindingFact) + ", " +
            "\"rpcAuthorization\": " + quote(row.rpcAuthorizationFact) + ", " +
            "\"provisioning\": " + quote(row.provisioningFact) + ", " +
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
    }

    /** Explicit rows win; otherwise the legacy bind results become current/not-run rows. */
    private fun effectiveRows(input: FullDiagnosticsInput): List<PcsBindRow> =
        if (input.bindRows.isNotEmpty()) {
            input.bindRows
        } else {
            input.bindResults.map { result ->
                PcsBindRow(
                    componentName = result.componentName,
                    role = result.role,
                    result = result,
                    provenance = if (result.outcome.attempted) {
                        PcsBindProvenance.CURRENT_RUN
                    } else {
                        PcsBindProvenance.NOT_RUN
                    },
                )
            }
        }

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
        effectiveRows(input)
            .filter { it.provenance == PcsBindProvenance.NOT_RUN && !it.result.outcome.attempted }
            .forEach {
                add("Bind ${it.componentName} (${it.result.outcome.label})")
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
        "\"partial\": " + snapshot.partial + ", " +
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
            extra = "\"components\": " + array(snapshot.pccComponents) { component ->
                val attribute = component.privateComputeCoreAttribute()
                "{" +
                    "\"kind\": " + quote(component.kind) + ", " +
                    "\"name\": " + quote(component.name) + ", " +
                    "\"privateComputeCore\": " + quote(component.privateComputeCore.name) + ", " +
                    "\"rawValue\": " + quote(attribute?.rawValue) + ", " +
                    "\"typedValue\": " + quote(attribute?.typedValue) +
                    "}"
            },
        ) + ", " +
        "\"associationPolicy\": {" +
        "\"state\": " + quote(snapshot.pcc.associationPolicy.state.name) + ", " +
        "\"reason\": " + quote(snapshot.pcc.associationPolicy.reason) + ", " +
        "\"elements\": " + array(snapshot.associationElements) { element ->
            "{" +
                "\"name\": " + quote(element.name) + ", " +
                "\"attributes\": " + array(element.attributes) { attributeJson(it) } + ", " +
                "\"packages\": " + array(element.packages) {
                    "{" +
                        "\"name\": " + quote(it.name) + ", " +
                        "\"certificateDigests\": " + array(it.certificateDigests) { digest -> quote(digest) } +
                        "}"
                } +
                "}"
        } + ", " +
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
            extra = "\"attributes\": " + array(snapshot.sandboxAttributes) {
                "{" +
                    "\"componentKind\": " + quote(it.componentKind) + ", " +
                    "\"componentName\": " + quote(it.componentName) + ", " +
                    "\"attribute\": " + attributeJson(it.attribute) +
                    "}"
            },
        ) +
        "}"

    private fun attributeJson(
        attribute: dev.shockstruck.gemininano.model.ManifestAttributeFacts,
    ): String = "{" +
        "\"name\": " + quote(attribute.name) + ", " +
        "\"namespace\": " + quote(attribute.namespace) + ", " +
        "\"resourceIdHex\": " + quote(attribute.resourceIdHex) + ", " +
        "\"rawValue\": " + quote(attribute.rawValue) + ", " +
        "\"typedValue\": " + quote(attribute.typedValue) + ", " +
        "\"dataType\": " + quote(attribute.dataType) +
        "}"

    private fun pccStateJson(
        state: String,
        reason: String,
        matched: Int,
        extra: String? = null,
    ): String = "{" +
        "\"state\": " + quote(state) + ", " +
        "\"reason\": " + quote(reason) + ", " +
        "\"matchedCount\": " + matched +
        (extra?.let { ", " + it } ?: "") +
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

    private fun appUserJson(appUser: AppUserFacts?): String {
        if (appUser == null) return "null"
        return "{" +
            "\"isSystemUser\": " + (appUser.isSystemUser?.toString() ?: "null") + ", " +
            "\"label\": " + quote(appUser.label) + ", " +
            "\"readError\": " + (appUser.readErrorLabel?.let { quote(it) } ?: "null") +
            "}"
    }

    private fun installedCodeJson(evidence: List<InstalledCodePackageEvidence>): String {
        val packages = array(evidence) { item ->
            val fields = mutableListOf(
                "\"requestedPackage\": " + quote(item.requestedPackage),
                "\"outcome\": " + quote(item.outcomeLabel),
                "\"elapsedMillis\": " + item.elapsedMillis,
            )
            when (val outcome = item.outcome) {
                is InstalledCodeOutcome.Read -> fields += installedCodeFactsJson(outcome.facts)
                is InstalledCodeOutcome.Partial -> fields += installedCodeFactsJson(outcome.facts)
                is InstalledCodeOutcome.NotVisible -> Unit
                is InstalledCodeOutcome.ReadError -> {
                    fields += "\"errorClass\": " + quote(outcome.errorClass)
                    fields += "\"message\": " + quote(outcome.message)
                }

                is InstalledCodeOutcome.TooLarge -> {
                    fields += "\"detail\": " + quote(outcome.detail)
                    fields += "\"versionCode\": " + (outcome.versionCode?.toString() ?: "null")
                    fields += "\"apkSha256\": " + array(outcome.apkSha256) {
                        "{" +
                            "\"label\": " + quote(it.apk.label) + ", " +
                            "\"kind\": " + quote(it.apk.kind.name) + ", " +
                            "\"sha256\": " + quote(it.sha256) +
                            "}"
                    }
                }
            }
            "{" + fields.joinToString(", ") + "}"
        }
        return "{" +
            "\"note\": " + quote(INSTALLED_CODE_EXPORT_NOTE) + ", " +
            "\"packages\": " + packages +
            "}"
    }

    private fun installedCodeFactsJson(facts: InstalledCodePackageFacts): List<String> = listOf(
        "\"complete\": " + facts.complete,
        "\"versionCode\": " + facts.versionCode,
        "\"referencesTruncated\": " + facts.referencesTruncated,
        "\"apks\": " + array(facts.apks) {
            "{" +
                "\"label\": " + quote(it.id.label) + ", " +
                "\"kind\": " + quote(it.id.kind.name) + ", " +
                "\"sha256\": " + quote(it.sha256) + ", " +
                "\"dexEntries\": " + array(it.dexEntries) { entry -> quote(entry) } +
                "}"
        },
        "\"failures\": " + array(facts.failures) {
            "{" +
                "\"apk\": " + quote(it.apk?.label) + ", " +
                "\"dexEntry\": " + quote(it.dexEntry) + ", " +
                "\"errorClass\": " + quote(it.errorClass) + ", " +
                "\"message\": " + quote(it.message) +
                "}"
        },
        "\"markers\": " + array(facts.markers) { installedCodeMarkerJson(it) },
        "\"readDeviceConfig\": {" +
            "\"anyReferencingMethodChecksPermission\": " +
            facts.readDeviceConfig.anyReferencingMethodChecksPermission + ", " +
            "\"references\": " + array(facts.readDeviceConfig.references) {
                "{" +
                    "\"apk\": " + quote(it.apk.label) + ", " +
                    "\"kind\": " + quote(it.apk.kind.name) + ", " +
                    "\"dexEntry\": " + quote(it.dexEntry) + ", " +
                    "\"referencingMethods\": " + it.referencingMethods + ", " +
                    "\"referencingMethodsWithPermissionCheck\": " +
                    it.referencingMethodsWithPermissionCheck + ", " +
                    "\"permissionCheckMethodNames\": " +
                    array(it.permissionCheckMethodNames) { name -> quote(name) } +
                    "}"
            } +
            "}",
        "\"deviceConfigMethods\": " + array(facts.deviceConfig.methodNames) { quote(it) },
        "\"assets\": {" +
            "\"totalAssetCount\": " + facts.assets.totalAssetCount + ", " +
            "\"matchingEntryCount\": " + facts.assets.entries.size + ", " +
            "\"truncated\": " + facts.assets.truncated + ", " +
            "\"interpretation\": " +
            quote("candidate non-DeviceConfig input only; presence is not use") + ", " +
            "\"entries\": " + array(facts.assets.entries) {
                "{" +
                    "\"apk\": " + quote(it.apk.label) + ", " +
                    "\"kind\": " + quote(it.apk.kind.name) + ", " +
                    "\"name\": " + quote(it.name) + ", " +
                    "\"sizeBytes\": " + it.sizeBytes +
                    "}"
            } +
            "}",
    )

    private fun installedCodeMarkerJson(marker: InstalledCodeMarkerFacts): String = "{" +
        "\"marker\": " + quote(marker.marker) + ", " +
        "\"match\": " + quote(marker.match.name) + ", " +
        "\"kind\": " + quote(marker.kind.name) + ", " +
        "\"presence\": " + quote(marker.presence.name) + ", " +
        "\"methodCount\": " + marker.methodCount + ", " +
        "\"references\": " + array(marker.references) {
            "{" +
                "\"apk\": " + quote(it.apk.label) + ", " +
                "\"kind\": " + quote(it.apk.kind.name) + ", " +
                "\"dexEntry\": " + quote(it.dexEntry) + ", " +
                "\"matchedString\": " + quote(it.matchedString) + ", " +
                "\"methodCount\": " + it.methodCount + ", " +
                "\"defaultsLabel\": " + quote(ProvisioningDependencyEvaluator.DEFAULTS_LABEL) + ", " +
                "\"defaults\": " + array(it.defaults) { value -> defaultLiteralJson(value) } +
                "}"
        } +
        "}"

    private fun defaultLiteralJson(value: InstalledCodeDefaultValue): String = when (value) {
        is InstalledCodeDefaultValue.BooleanValue ->
            "{\"type\": \"boolean\", \"value\": " + value.value + "}"

        is InstalledCodeDefaultValue.IntValue ->
            "{\"type\": \"int\", \"value\": " + value.value + "}"

        is InstalledCodeDefaultValue.StringValue ->
            "{\"type\": \"string\", \"value\": " + quote(value.value) + "}"

        InstalledCodeDefaultValue.Unresolved ->
            "{\"type\": \"unresolved\", \"value\": null}"
    }

    private fun provisioningDependenciesJson(
        evaluation: ProvisioningDependencyEvaluation?,
    ): String {
        if (evaluation == null) return "null"
        return "{" +
            "\"result\": " + quote(evaluation.resultLabel) + ", " +
            "\"firstFailingStep\": " + quote(evaluation.firstFailingStep?.id?.label) + ", " +
            "\"earliestUnsettledStep\": " + quote(evaluation.firstUnsettledStep?.id?.label) + ", " +
            "\"summary\": " + quote(evaluation.summary) + ", " +
            "\"steps\": " + array(evaluation.steps) { step ->
                "{" +
                    "\"step\": " + quote(step.id.label) + ", " +
                    "\"outcome\": " + quote(step.outcome.label) + ", " +
                    "\"summary\": " + quote(step.summary) + ", " +
                    "\"evidence\": " + array(step.evidence) {
                        "{" +
                            "\"strength\": " + quote(it.strength.label) + ", " +
                            "\"source\": " + quote(it.source) + ", " +
                            "\"statement\": " + quote(it.statement) +
                            "}"
                    } +
                    "}"
            } +
            "}"
    }

    private fun bindHistoryJson(
        entries: List<PcsBindHistoryEntry>,
        note: String?,
    ): String = "{" +
        "\"label\": " +
        quote("stored results per component, at most five, oldest first") + ", " +
        "\"note\": " + quote(note) + ", " +
        "\"components\": " +
        array(PcsBindReport.historyByComponent(entries)) { (componentName, perComponent) ->
            "{" +
                "\"componentName\": " + quote(componentName) + ", " +
                "\"entries\": " + array(perComponent) { entry ->
                    "{" +
                        "\"observedAt\": " +
                        quote(DiagnosticFormatting.formatIsoTimestamp(entry.observedAtEpochMillis)) + ", " +
                        "\"observedAtEpochMillis\": " + entry.observedAtEpochMillis + ", " +
                        "\"appVersionName\": " + quote(entry.appVersionName) + ", " +
                        "\"appVersionCode\": " + entry.appVersionCode + ", " +
                        "\"pcsVersionCode\": " +
                        (entry.pcsVersionCode?.toString() ?: "null") + ", " +
                        "\"outcome\": " + quote(entry.result.outcome.label) + ", " +
                        "\"binding\": " + quote(PcsBindReport.bindingFact(entry.result)) + ", " +
                        "\"interfaceDescriptor\": " +
                        quote(entry.result.interfaceDescriptor) + ", " +
                        "\"exceptionClass\": " + quote(entry.result.exceptionClass) + ", " +
                        "\"exceptionMessage\": " + quote(entry.result.exceptionMessage) + ", " +
                        "\"elapsedMillis\": " + entry.result.elapsedMillis + ", " +
                        "\"unbindCalls\": " + entry.result.unbindCalls +
                        "}"
                } +
                "}"
        } +
        "}"

    private fun appendInstalledCodeText(
        out: StringBuilder,
        evidence: InstalledCodePackageEvidence,
    ) {
        out.append(evidence.requestedPackage).append(": ").append(evidence.outcomeLabel)
        out.append(" | elapsed=").append(evidence.elapsedMillis).append("ms")
        when (val outcome = evidence.outcome) {
            is InstalledCodeOutcome.Read -> appendInstalledCodeFactsText(out, outcome.facts)
            is InstalledCodeOutcome.Partial -> {
                out.append(" | partial: absence cannot be claimed")
                appendInstalledCodeFactsText(out, outcome.facts)
            }

            is InstalledCodeOutcome.NotVisible ->
                out.append(" | PackageManager does not expose this package; nothing is claimed").append('\n')

            is InstalledCodeOutcome.ReadError ->
                out.append(" | ").append(outcome.errorClass).append(": ").append(outcome.message).append('\n')

            is InstalledCodeOutcome.TooLarge ->
                out.append(" | ").append(outcome.detail).append('\n')
        }
    }

    private fun appendInstalledCodeFactsText(out: StringBuilder, facts: InstalledCodePackageFacts) {
        out.append(" | versionCode=").append(facts.versionCode)
            .append(" | complete=").append(facts.complete)
            .append(" | dexReferencesTruncated=").append(facts.referencesTruncated)
            .append('\n')
        facts.apks.forEach { apk ->
            out.append("  apk ").append(apk.id.label).append(" (").append(apk.id.kind.name).append(")")
                .append(" sha256=").append(apk.sha256)
                .append(" dex=").append(apk.dexEntries.joinToString(",").ifBlank { "none" })
                .append('\n')
        }
        facts.failures.forEach { failure ->
            out.append("  failure ").append(failure.apk?.label ?: "package")
                .append(" ").append(failure.dexEntry ?: "(apk)")
                .append(" ").append(failure.errorClass)
                .append(": ").append(failure.message).append('\n')
        }
        facts.markers.forEach { marker ->
            out.append("  ").append(marker.marker)
                .append(": ").append(marker.presence.name)
                .append(" methods=").append(marker.methodCount)
                .append(" (").append(marker.kind.name).append(')')
            if (marker.references.isNotEmpty()) {
                val defaults = marker.references.flatMap { it.defaults }.distinct()
                out.append(" | defaults (").append(ProvisioningDependencyEvaluator.DEFAULTS_LABEL)
                    .append(")=")
                    .append(
                        if (defaults.isEmpty()) {
                            "none recorded"
                        } else {
                            defaults.joinToString(", ", transform = ProvisioningDependencyEvaluator::describeDefault)
                        },
                    )
            }
            out.append('\n')
        }
        out.append("  READ_DEVICE_CONFIG: anyReferencingMethodChecksPermission=")
            .append(facts.readDeviceConfig.anyReferencingMethodChecksPermission)
            .append(" (false means \"not shown\", never \"no check\")")
            .append(" | references=").append(facts.readDeviceConfig.references.size)
            .append('\n')
        out.append("  DeviceConfig methods: ")
            .append(facts.deviceConfig.methodNames.joinToString(", ").ifBlank { "none" })
            .append('\n')
        out.append("  assets: total=").append(facts.assets.totalAssetCount)
            .append(" matching=").append(facts.assets.entries.size)
            .append(" truncated=").append(facts.assets.truncated)
            .append(" (candidate non-DeviceConfig input only; presence is not use)").append('\n')
        facts.assets.entries.forEach { entry ->
            out.append("    ").append(entry.apk.label).append(" ").append(entry.name)
                .append(" size=").append(entry.sizeBytes).append('\n')
        }
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
        const val SCHEMA = "dev.shockstruck.gemininano.full-diagnostics/2"

        /**
         * Stated in every export beside the installed-code facts. It records the check that
         * no reader failure message can carry a string taken from the inspected code.
         */
        const val INSTALLED_CODE_EXPORT_NOTE =
            "Only InstalledCodePackageFacts is exported: package, versionCode, APK digests, " +
                "dex entry names, marker strings, literal defaults, DeviceConfig and " +
                "permission-check method names, asset entry names and sizes, and failure " +
                "class/message. A reader failure message can name an APK file or a ZIP entry " +
                "name, which the facts already carry as dex entries; no reader message can " +
                "embed a decoded DEX string, because every format error reports a numeric " +
                "offset, index or opcode with a fixed label."

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
