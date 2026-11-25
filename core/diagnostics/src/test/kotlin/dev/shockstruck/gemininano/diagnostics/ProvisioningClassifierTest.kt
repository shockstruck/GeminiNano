package dev.shockstruck.gemininano.diagnostics

import dev.shockstruck.gemininano.model.FeatureProbeResult
import dev.shockstruck.gemininano.model.FeatureStatusKind
import dev.shockstruck.gemininano.model.GenAiFeature
import dev.shockstruck.gemininano.model.GrantState
import dev.shockstruck.gemininano.model.ManifestAssociationPackageFacts
import dev.shockstruck.gemininano.model.ManifestElementFacts
import dev.shockstruck.gemininano.model.ManifestReadOutcome
import dev.shockstruck.gemininano.model.OfflineInferenceOutcome
import dev.shockstruck.gemininano.model.OfflineInferenceResult
import dev.shockstruck.gemininano.model.PackageFacts
import dev.shockstruck.gemininano.model.PcsBindCatalog
import dev.shockstruck.gemininano.model.PcsBindOutcome
import dev.shockstruck.gemininano.model.PcsBindResult
import dev.shockstruck.gemininano.model.PlatformPrincipals
import dev.shockstruck.gemininano.model.PromptConfigStatus
import dev.shockstruck.gemininano.model.PromptModelConfig
import dev.shockstruck.gemininano.model.SummarizationProbeResult
import dev.shockstruck.gemininano.testing.TestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisioningClassifierTest {

    private val classifier = ProvisioningFailureClassifier()

    private fun summary606() = TestFixtures.probeResult(
        status = FeatureStatusKind.PROBE_ERROR,
        failure = TestFixtures.failure(606),
        baseModelName = null,
    )

    private fun unavailablePrompt(): List<PromptConfigStatus> =
        PromptModelConfig.ALL.map { config ->
            TestFixtures.promptStatus(
                config = config,
                status = FeatureStatusKind.UNAVAILABLE,
                featureId = null,
                errorCode = null,
                message = null,
            )
        }

    private fun input(
        messages: List<ClassifierMessage> = emptyList(),
        binds: List<PcsBindResult> = emptyList(),
        packages: List<PackageFacts> = emptyList(),
        manifests: List<ManifestEvidenceSnapshot> = emptyList(),
        summarization: SummarizationProbeResult? = null,
        prompt: List<PromptConfigStatus> = emptyList(),
        features: List<FeatureProbeResult> = emptyList(),
        offline: List<OfflineInferenceResult> = emptyList(),
        pcsReadDeviceConfig: GrantState? = null,
    ) = ClassifierInput(
        matrix = TestFixtures.permissionMatrix(
            pcsPcc = GrantState.DENIED,
            pcsReadDeviceConfig = pcsReadDeviceConfig,
        ),
        packages = packages,
        manifests = manifests,
        bindResults = binds,
        summarization = summarization,
        prompt = prompt,
        features = features,
        offline = offline,
        messages = messages,
    )

    private fun grpc(
        outcome: PcsBindOutcome,
        exceptionMessage: String? = null,
        descriptorTimedOut: Boolean = false,
    ) = TestFixtures.bindResult(
        componentName = PcsBindCatalog.ASTREA_GRPC,
        outcome = outcome,
        exceptionClass = if (outcome == PcsBindOutcome.SECURITY_EXCEPTION) {
            "java.lang.SecurityException"
        } else {
            null
        },
        exceptionMessage = exceptionMessage,
    ).copy(descriptorTimedOut = descriptorTimedOut)

    private fun pcsManifest(
        packages: List<PackageFacts> = listOf(TestFixtures.packageFacts(PlatformPrincipals.AICORE)),
        policyPackages: List<String> = listOf(PlatformPrincipals.AICORE),
        pcsSharedUserId: String? = null,
        aicoreSharedUserId: String? = null,
        pcsOutcome: ManifestReadOutcome? = null,
    ): List<ManifestEvidenceSnapshot> {
        val pcsRead = TestFixtures.manifestRead(
            packageName = PlatformPrincipals.PCS,
            sharedUserId = pcsSharedUserId,
            pccElements = listOf(TestFixtures.associationElement(*policyPackages.toTypedArray())),
        )
        val pcs = ManifestEvidenceSnapshot(
            requestedPackage = PlatformPrincipals.PCS,
            outcome = pcsOutcome ?: pcsRead,
        )
        val installed = packages.firstOrNull { it.visible }?.packageName
            ?: PlatformPrincipals.AICORE
        val aicore = ManifestEvidenceSnapshot(
            requestedPackage = installed,
            outcome = TestFixtures.manifestRead(
                packageName = installed,
                sharedUserId = aicoreSharedUserId,
            ),
        )
        return listOf(pcs, aicore)
    }

    private fun deviceLike(grpcRow: PcsBindResult?): ClassifierInput = ClassifierInput(
        matrix = TestFixtures.permissionMatrix(
            pcsPcc = GrantState.DENIED,
            pcsReadDeviceConfig = GrantState.DENIED,
        ),
        packages = listOf(
            TestFixtures.packageFacts(PlatformPrincipals.AICORE),
            TestFixtures.packageFacts(PlatformPrincipals.PCS),
        ),
        manifests = pcsManifest(),
        bindResults = listOfNotNull(grpcRow),
        summarization = summary606(),
        prompt = unavailablePrompt(),
    )

    // -- Item 1: this app's own bind is evidence, never a selection ---------------------

    @Test
    fun securityExceptionIsEvidenceOnlyAndBecomesAnAuthorizationCandidate() {
        val result = classifier.classify(
            input(binds = listOf(grpc(PcsBindOutcome.SECURITY_EXCEPTION, "allow-component-access denied"))),
        )
        assertEquals(ProvisioningFailureClass.INDETERMINATE, result.result)
        assertTrue(
            result.evidence.joinToString(" | ") { it.statement },
            result.evidence.any { it.statement.contains("expected even on a healthy device") },
        )
        val auth = result.candidates.first {
            it.failureClass == ProvisioningFailureClass.PCS_SERVICE_AUTHORIZATION_FAILURE
        }
        assertTrue(auth.missingObservation.contains("AICore's own bind"))
    }

    @Test
    fun timeoutIsEvidenceOnlyAndBecomesAnInitializationCandidate() {
        val result = classifier.classify(
            input(binds = listOf(grpc(PcsBindOutcome.TIMEOUT))),
        )
        assertEquals(ProvisioningFailureClass.INDETERMINATE, result.result)
        val init = result.candidates.first {
            it.failureClass == ProvisioningFailureClass.PCS_INITIALIZATION_FAILURE
        }
        assertTrue(init.why.contains("slow cold start"))
        assertTrue(init.missingObservation.contains("AICore's own bind"))
    }

    @Test
    fun nullBindingAndBindingDiedAreEvidenceOnly() {
        listOf(PcsBindOutcome.NULL_BINDING, PcsBindOutcome.BINDING_DIED).forEach { outcome ->
            val result = classifier.classify(input(binds = listOf(grpc(outcome))))
            assertEquals("$outcome", ProvisioningFailureClass.INDETERMINATE, result.result)
            val init = result.candidates.first {
                it.failureClass == ProvisioningFailureClass.PCS_INITIALIZATION_FAILURE
            }
            assertTrue(init.why.contains("source mirror"))
            assertTrue(init.missingObservation.contains("AICore's own bind"))
        }
    }

    @Test
    fun bindRefusedAndBindErrorAreEvidenceOnly() {
        listOf(PcsBindOutcome.BIND_REFUSED, PcsBindOutcome.BIND_ERROR).forEach { outcome ->
            val result = classifier.classify(input(binds = listOf(grpc(outcome))))
            assertEquals("$outcome", ProvisioningFailureClass.INDETERMINATE, result.result)
            val classes = result.candidates.map { it.failureClass }
            assertTrue(classes.contains(ProvisioningFailureClass.PCS_INITIALIZATION_FAILURE))
            assertTrue(classes.contains(ProvisioningFailureClass.PCS_SERVICE_AUTHORIZATION_FAILURE))
        }
    }

    @Test
    fun binderReturnedIsEvidenceOnly() {
        val result = classifier.classify(
            input(binds = listOf(grpc(PcsBindOutcome.BINDER_RETURNED)), summarization = summary606()),
        )
        assertEquals(ProvisioningFailureClass.INDETERMINATE, result.result)
        assertFalse(
            result.candidates.any {
                it.failureClass == ProvisioningFailureClass.PCS_INITIALIZATION_FAILURE
            },
        )
        assertTrue(result.evidence.any { it.statement.contains("never stands in for AICore") })
    }

    @Test
    fun aDisabledGrpcComponentSelectsInitialization() {
        val result = classifier.classify(
            input(binds = listOf(grpc(PcsBindOutcome.NOT_ENABLED))),
        )
        assertEquals(ProvisioningFailureClass.PCS_INITIALIZATION_FAILURE, result.result)
        assertTrue(result.summary.contains("every caller"))
        assertFalse(result.summary.contains("this app's own bind"))
    }

    @Test
    fun anUnexportedGrpcComponentSelectsAuthorizationWhenUidSharingIsNotEstablished() {
        val result = classifier.classify(
            input(
                binds = listOf(grpc(PcsBindOutcome.NOT_EXPORTED)),
                packages = listOf(TestFixtures.packageFacts(PlatformPrincipals.AICORE)),
            ),
        )
        assertEquals(ProvisioningFailureClass.PCS_SERVICE_AUTHORIZATION_FAILURE, result.result)
        assertTrue(
            result.evidence.any { it.statement.contains("UID sharing: not established") },
        )
    }

    @Test
    fun anUnexportedGrpcComponentDoesNotSelectWhenPcsAndAicoreShareAUid() {
        val result = classifier.classify(
            input(
                binds = listOf(grpc(PcsBindOutcome.NOT_EXPORTED)),
                packages = listOf(TestFixtures.packageFacts(PlatformPrincipals.AICORE)),
                manifests = pcsManifest(
                    pcsSharedUserId = "com.example.shared",
                    aicoreSharedUserId = "com.example.shared",
                ),
            ),
        )
        assertEquals(ProvisioningFailureClass.INDETERMINATE, result.result)
        assertTrue(result.evidence.any { it.statement.contains("same UID") })
        assertTrue(result.evidence.any { it.statement.contains("not a blocker") })
    }

    @Test
    fun anUnexportedGrpcComponentSelectsWhenUidsDiffer() {
        val result = classifier.classify(
            input(
                binds = listOf(grpc(PcsBindOutcome.NOT_EXPORTED)),
                packages = listOf(TestFixtures.packageFacts(PlatformPrincipals.AICORE)),
                manifests = pcsManifest(
                    pcsSharedUserId = "com.pcs.shared",
                    aicoreSharedUserId = "com.aicore.shared",
                ),
            ),
        )
        assertEquals(ProvisioningFailureClass.PCS_SERVICE_AUTHORIZATION_FAILURE, result.result)
        assertTrue(result.evidence.any { it.statement.contains("UID sharing: different UIDs") })
    }

    @Test
    fun aGenAiInferenceNullBindingIsNeverUsedForPcsEvidence() {
        val genAiInference = TestFixtures.bindResult(
            componentName = PcsBindCatalog.ASTREA_GENAI_INFERENCE,
            outcome = PcsBindOutcome.NULL_BINDING,
        )
        val result = classifier.classify(
            input(binds = listOf(genAiInference), summarization = summary606()),
        )
        assertEquals(ProvisioningFailureClass.INDETERMINATE, result.result)
        assertTrue(
            result.candidates.any {
                it.failureClass == ProvisioningFailureClass.PCS_INITIALIZATION_FAILURE
            },
        )
        assertTrue(result.evidence.any { it.statement.contains("GenAiInferenceService is never used") })
    }

    @Test
    fun theDeviceLikeFixtureIsIndeterminateForEveryEvidenceOnlyGrpcRow() {
        listOf(
            null,
            grpc(PcsBindOutcome.NOT_RUN),
            grpc(PcsBindOutcome.SECURITY_EXCEPTION),
            grpc(PcsBindOutcome.TIMEOUT),
            grpc(PcsBindOutcome.NULL_BINDING),
            grpc(PcsBindOutcome.BINDER_RETURNED),
        ).forEach { row ->
            val result = classifier.classify(deviceLike(row))
            assertEquals(
                "gRPC row ${row?.outcome ?: "absent"}",
                ProvisioningFailureClass.INDETERMINATE,
                result.result,
            )
            assertTrue(result.candidates.isNotEmpty())
        }
    }

    // -- Item 2: association mismatch -------------------------------------------------

    @Test
    fun associationMismatchSelectsAuthorizationFromACompleteVisibleRead() {
        val result = classifier.classify(
            input(
                packages = listOf(TestFixtures.packageFacts(PlatformPrincipals.AICORE_LEGACY)),
                manifests = pcsManifest(
                    packages = listOf(TestFixtures.packageFacts(PlatformPrincipals.AICORE_LEGACY)),
                    policyPackages = listOf(PlatformPrincipals.AICORE),
                ),
            ),
        )
        assertEquals(ProvisioningFailureClass.PCS_SERVICE_AUTHORIZATION_FAILURE, result.result)
        assertTrue(result.summary.contains("installed AICore package is"))
        assertTrue(result.summary.contains("not proof of enforcement"))
    }

    @Test
    fun associationMismatchDoesNotSelectFromAPartialRead() {
        val read = TestFixtures.manifestRead(
            packageName = PlatformPrincipals.PCS,
            pccElements = listOf(TestFixtures.associationElement(PlatformPrincipals.AICORE)),
        )
        val partial = ManifestReadOutcome.Partial(PlatformPrincipals.PCS, read.facts)
        val result = classifier.classify(
            input(
                packages = listOf(TestFixtures.packageFacts(PlatformPrincipals.AICORE_LEGACY)),
                manifests = listOf(
                    ManifestEvidenceSnapshot(PlatformPrincipals.PCS, partial),
                    ManifestEvidenceSnapshot(
                        PlatformPrincipals.AICORE_LEGACY,
                        TestFixtures.manifestRead(PlatformPrincipals.AICORE_LEGACY),
                    ),
                ),
            ),
        )
        assertEquals(ProvisioningFailureClass.INDETERMINATE, result.result)
    }

    @Test
    fun associationMismatchDoesNotSelectWhenAPackageNameWasNotRead() {
        val unnamed = ManifestElementFacts(
            name = "component-access",
            packages = listOf(
                ManifestAssociationPackageFacts(
                    name = null,
                    certificateDigests = listOf("AB:CD"),
                ),
            ),
        )
        val result = classifier.classify(
            input(
                packages = listOf(TestFixtures.packageFacts(PlatformPrincipals.AICORE_LEGACY)),
                manifests = listOf(
                    ManifestEvidenceSnapshot(
                        PlatformPrincipals.PCS,
                        TestFixtures.manifestRead(
                            packageName = PlatformPrincipals.PCS,
                            pccElements = listOf(unnamed),
                        ),
                    ),
                    ManifestEvidenceSnapshot(
                        PlatformPrincipals.AICORE_LEGACY,
                        TestFixtures.manifestRead(PlatformPrincipals.AICORE_LEGACY),
                    ),
                ),
            ),
        )
        assertEquals(ProvisioningFailureClass.INDETERMINATE, result.result)
    }

    @Test
    fun associationMismatchRequiresAVisibleInstalledAicore() {
        val result = classifier.classify(
            input(
                packages = listOf(
                    TestFixtures.packageFacts(PlatformPrincipals.AICORE_LEGACY, visible = false),
                ),
                manifests = listOf(
                    ManifestEvidenceSnapshot(
                        PlatformPrincipals.PCS,
                        TestFixtures.manifestRead(
                            packageName = PlatformPrincipals.PCS,
                            pccElements = listOf(
                                TestFixtures.associationElement(PlatformPrincipals.AICORE),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(ProvisioningFailureClass.INDETERMINATE, result.result)
        assertTrue(result.evidence.any { it.statement.contains("not visible to this app") })
    }

    @Test
    fun aMatchingAssociationPolicyDoesNotSelectAnything() {
        val result = classifier.classify(
            input(
                packages = listOf(TestFixtures.packageFacts(PlatformPrincipals.AICORE)),
                manifests = pcsManifest(),
                summarization = summary606(),
            ),
        )
        assertEquals(ProvisioningFailureClass.INDETERMINATE, result.result)
    }

    // -- Item 3: message rules --------------------------------------------------------

    private val mlkitWrapper =
        "[ErrorCode 606] AICore failed with error type 3-PREPARATION_ERROR and " +
            "error code 606-FEATURE_NOT_FOUND: "

    private fun wrapped(tail: String): String = mlkitWrapper + tail

    private fun messageResult(text: String) = classifier.classify(
        input(messages = listOf(ClassifierMessage("aicore", text))),
    )

    private fun assertSelected(expected: ProvisioningFailureClass, text: String) {
        val result = messageResult(text)
        assertEquals("text: $text", expected, result.result)
    }

    private fun assertIndeterminate(text: String) {
        val result = messageResult(text)
        assertEquals("text: $text", ProvisioningFailureClass.INDETERMINATE, result.result)
    }

    // The review table rows, each with its expected result.

    @Test
    fun tableRow1NpuPendingWithWrapperIsIndeterminate() {
        assertIndeterminate(wrapped("NPU model variant 5 is pending."))
    }

    @Test
    fun tableRow2NpuPresentDownloadFailedIsIndeterminate() {
        assertIndeterminate("NPU present; model download failed")
    }

    @Test
    fun tableRow3CurrentManifestWithWrapperIsIndeterminate() {
        assertIndeterminate(wrapped("Manifest version 5 is current."))
    }

    @Test
    fun tableRow4RegisteredWaitingWithWrapperIsIndeterminate() {
        assertIndeterminate(
            wrapped("Feature 622 model is registered and waiting for download."),
        )
    }

    @Test
    fun tableRow5RemoteExceptionCallRejectedIsIndeterminate() {
        val result = messageResult("android.os.RemoteException: call rejected")
        assertEquals(ProvisioningFailureClass.INDETERMINATE, result.result)
        assertTrue(
            result.candidates.none {
                it.failureClass == ProvisioningFailureClass.REMOTE_PROVISIONING_REJECTION
            },
        )
    }

    @Test
    fun tableRow6LocalEligibilityIsOnlyACandidate() {
        val result = messageResult("Not eligible on this device; server not contacted")
        assertEquals(ProvisioningFailureClass.INDETERMINATE, result.result)
        val candidate = result.candidates.first {
            it.failureClass == ProvisioningFailureClass.REMOTE_PROVISIONING_REJECTION
        }
        assertTrue(candidate.why.contains("whole-word"))
    }

    @Test
    fun tableRow7PhoneMessageWithWrapperSelectsNothing() {
        val result = messageResult(wrapped("Feature 622 is not available."))
        assertEquals(ProvisioningFailureClass.INDETERMINATE, result.result)
        assertTrue(
            result.candidates.none {
                it.failureClass in setOf(
                    ProvisioningFailureClass.MISSING_FEATURE_CONFIGURATION,
                    ProvisioningFailureClass.REMOTE_PROVISIONING_REJECTION,
                    ProvisioningFailureClass.MODEL_REGISTRATION_FAILURE,
                )
            },
        )
    }

    // Each rule positive and negative, with and without the wrapper.

    @Test
    fun npuRulePositiveWithoutWrapper() {
        assertSelected(
            ProvisioningFailureClass.NPU_ACCESS_RESTRICTIONS,
            "NPU access denied",
        )
    }

    @Test
    fun npuRulePositiveWithWrapper() {
        assertSelected(
            ProvisioningFailureClass.NPU_ACCESS_RESTRICTIONS,
            wrapped("NPU access denied"),
        )
    }

    @Test
    fun npuRuleNegativeWithoutWrapper() {
        assertIndeterminate("NPU present; capability reported")
    }

    @Test
    fun npuRuleNegativeWithWrapper() {
        assertIndeterminate(wrapped("NPU is present and reported."))
    }

    @Test
    fun configurationRulePositiveWithoutWrapper() {
        assertSelected(
            ProvisioningFailureClass.MISSING_FEATURE_CONFIGURATION,
            "feature configuration manifest is missing",
        )
    }

    @Test
    fun configurationRulePositiveWithWrapper() {
        assertSelected(
            ProvisioningFailureClass.MISSING_FEATURE_CONFIGURATION,
            wrapped("feature configuration manifest is missing"),
        )
    }

    @Test
    fun configurationRuleNegativeWithoutWrapper() {
        assertIndeterminate("manifest read; no error")
    }

    @Test
    fun configurationRuleNegativeWithWrapper() {
        assertIndeterminate(wrapped("Manifest version 5 is current."))
    }

    @Test
    fun registrationRulePositiveWithoutWrapper() {
        assertSelected(
            ProvisioningFailureClass.MODEL_REGISTRATION_FAILURE,
            "model registration failed",
        )
    }

    @Test
    fun registrationRulePositiveWithWrapper() {
        assertSelected(
            ProvisioningFailureClass.MODEL_REGISTRATION_FAILURE,
            wrapped("model registration failed"),
        )
    }

    @Test
    fun registrationRuleNegativeWithoutWrapper() {
        assertIndeterminate("model registration complete, download failed")
    }

    @Test
    fun registrationRuleNegativeWithWrapper() {
        assertIndeterminate(
            wrapped("Feature 622 model is registered and waiting for download."),
        )
    }

    @Test
    fun remoteRulePositiveWithoutWrapper() {
        assertSelected(
            ProvisioningFailureClass.REMOTE_PROVISIONING_REJECTION,
            "server rejected the request",
        )
    }

    @Test
    fun remoteRulePositiveWithWrapper() {
        assertSelected(
            ProvisioningFailureClass.REMOTE_PROVISIONING_REJECTION,
            wrapped("server rejected the request"),
        )
    }

    @Test
    fun remoteRuleNegativeWithoutWrapper() {
        assertIndeterminate("Not eligible on this device; server not contacted")
    }

    @Test
    fun remoteRuleNegativeWithWrapper() {
        assertIndeterminate(wrapped("Not eligible on this device; server not contacted"))
    }

    // Negated failure words do not count in any of the four rules.

    @Test
    fun negatedNpuDenialDoesNotSelect() {
        assertIndeterminate("NPU access not denied")
    }

    @Test
    fun negatedRemoteRejectionDoesNotSelect() {
        assertIndeterminate("server request not rejected")
    }

    @Test
    fun negatedMissingConfigurationDoesNotSelect() {
        assertIndeterminate("configuration manifest not missing")
    }

    @Test
    fun negatedRegistrationFailureDoesNotSelect() {
        assertIndeterminate("model registration not failed")
    }

    // Whole-word sources and wrapper variants.

    @Test
    fun systemServerWithUnderscoreIsNotARemoteSource() {
        assertIndeterminate("system_server: request rejected, download failed")
    }

    @Test
    fun aRejectionWithOnlyDownloadIsACandidateNotASelection() {
        val result = messageResult("download rejected with HTTP 403")
        assertEquals(ProvisioningFailureClass.INDETERMINATE, result.result)
        assertTrue(
            result.candidates.any {
                it.failureClass == ProvisioningFailureClass.REMOTE_PROVISIONING_REJECTION
            },
        )
    }

    @Test
    fun theWrapperWithoutErrorCodePrefixIsAlsoStripped() {
        val text = "AICore failed with error type 3-PREPARATION_ERROR and " +
            "error code 606-FEATURE_NOT_FOUND: Feature 622 is not available."
        val result = messageResult(text)
        assertEquals(ProvisioningFailureClass.INDETERMINATE, result.result)
        assertTrue(
            result.candidates.none {
                it.failureClass in setOf(
                    ProvisioningFailureClass.MISSING_FEATURE_CONFIGURATION,
                    ProvisioningFailureClass.REMOTE_PROVISIONING_REJECTION,
                    ProvisioningFailureClass.MODEL_REGISTRATION_FAILURE,
                )
            },
        )
    }

    @Test
    fun theFourDeviceMessagesAreIndeterminate() {
        listOf(622, 607, 614, 627).forEach { featureId ->
            val result = messageResult(wrapped("Feature $featureId is not available."))
            assertEquals(
                "feature $featureId",
                ProvisioningFailureClass.INDETERMINATE,
                result.result,
            )
            assertTrue(
                result.candidates.none {
                    it.failureClass in setOf(
                        ProvisioningFailureClass.MISSING_FEATURE_CONFIGURATION,
                        ProvisioningFailureClass.REMOTE_PROVISIONING_REJECTION,
                        ProvisioningFailureClass.MODEL_REGISTRATION_FAILURE,
                    )
                },
            )
            assertTrue(result.evidence.any { it.statement.contains("Feature $featureId") })
        }
    }

    // -- Item 4: a successful generation conflicts with a selected class --------------

    @Test
    fun aSuccessfulGenerationConflictsWithASelectedClass() {
        val prompt = TestFixtures.promptStatus(
            status = FeatureStatusKind.AVAILABLE,
            featureId = null,
            errorCode = null,
            message = null,
            generatedText = "OK",
        )
        val result = classifier.classify(
            input(
                binds = listOf(grpc(PcsBindOutcome.NOT_ENABLED)),
                prompt = listOf(prompt),
            ),
        )
        assertEquals(ProvisioningFailureClass.INDETERMINATE, result.result)
        assertTrue(result.summary.contains("a generation succeeded"))
        assertTrue(result.evidence.any { it.statement.contains("generation succeeded") })
        assertTrue(
            result.candidates.any {
                it.failureClass == ProvisioningFailureClass.PCS_INITIALIZATION_FAILURE
            },
        )
    }

    @Test
    fun aSuccessfulGenerationWithoutASelectedClassIsNotAConflict() {
        val prompt = TestFixtures.promptStatus(
            status = FeatureStatusKind.AVAILABLE,
            featureId = null,
            errorCode = null,
            message = null,
            generatedText = "OK",
        )
        val result = classifier.classify(ClassifierInput(prompt = listOf(prompt)))
        assertEquals(ProvisioningFailureClass.INDETERMINATE, result.result)
        assertTrue(result.summary.contains("No failure signature"))
        assertTrue(result.candidates.isEmpty())
    }

    // -- Item 5: manifest evidence names the facts ------------------------------------

    @Test
    fun manifestEvidenceNamesPccComponentsAndAssociationElements() {
        val pcs = ManifestEvidenceSnapshot(
            requestedPackage = PlatformPrincipals.PCS,
            outcome = TestFixtures.manifestRead(
                packageName = PlatformPrincipals.PCS,
                components = listOf(TestFixtures.pccComponent("com.example.PccService")),
                pccElements = listOf(
                    TestFixtures.associationElement(PlatformPrincipals.AICORE),
                ),
            ),
        )
        val aicore = ManifestEvidenceSnapshot(
            requestedPackage = PlatformPrincipals.AICORE,
            outcome = TestFixtures.manifestRead(PlatformPrincipals.AICORE),
        )
        val result = classifier.classify(
            input(
                packages = listOf(TestFixtures.packageFacts(PlatformPrincipals.AICORE)),
                manifests = listOf(pcs, aicore),
            ),
        )
        val line = result.evidence.first { it.statement.contains("PCS com.google.android.as.oss manifest") }
        assertTrue(line.statement.contains("service:com.example.PccService"))
        assertTrue(line.statement.contains("component-access"))
        assertTrue(line.statement.contains("a declared association policy is not proof of enforcement"))
    }

    @Test
    fun aPartialManifestIsMarkedAsPartialInTheEvidence() {
        val read = TestFixtures.manifestRead(PlatformPrincipals.PCS)
        val partial = ManifestReadOutcome.Partial(PlatformPrincipals.PCS, read.facts)
        val result = classifier.classify(
            input(
                manifests = listOf(
                    ManifestEvidenceSnapshot(PlatformPrincipals.PCS, partial),
                ),
            ),
        )
        assertTrue(
            result.evidence.any { it.statement.contains("partial read: absence cannot be claimed") },
        )
    }

    // -- Item 6: no tracker identifiers or internal stage names in shipped strings ---

    @Test
    fun shippedStringsCarryNoTrackerIdentifiersOrStageNames() {
        val result = classifier.classify(
            input(
                binds = listOf(grpc(PcsBindOutcome.SECURITY_EXCEPTION)),
                summarization = summary606(),
                packages = listOf(TestFixtures.packageFacts(PlatformPrincipals.AICORE)),
                manifests = pcsManifest(),
            ),
        )
        val strings = buildList {
            add(result.summary)
            result.evidence.forEach { add(it.statement); it.source?.let(::add) }
            result.candidates.forEach { add(it.why); add(it.missingObservation) }
            result.refutations.forEach(::add)
            result.nextObservation?.let(::add)
        }
        val trackerPrefix = "SH" + "OC-"
        assertTrue(strings.none { trackerPrefix in it })
        assertTrue(strings.none { "Stage 6" in it })
    }

    // -- Item 7: 601 from every probe result -----------------------------------------

    @Test
    fun sixZeroOneIsReadFromPromptFeaturesAndOfflineResults() {
        val prompt = TestFixtures.promptStatus(
            config = PromptModelConfig.ALL.first(),
            status = FeatureStatusKind.PROBE_ERROR,
            featureId = null,
            errorCode = 601,
            message = "AICore service failed to bind",
        )
        val feature = FeatureProbeResult(
            feature = GenAiFeature.REWRITING,
            status = FeatureStatusKind.PROBE_ERROR,
            failure = TestFixtures.failure(601, "AICore service failed to bind"),
        )
        val offline = OfflineInferenceResult(
            feature = GenAiFeature.REWRITING,
            outcome = OfflineInferenceOutcome.FAILED,
            failure = TestFixtures.failure(601, "AICore service failed to bind"),
        )
        val result = classifier.classify(
            ClassifierInput(prompt = listOf(prompt), features = listOf(feature), offline = listOf(offline)),
        )
        val line = result.evidence.first { it.statement.startsWith("601 observed") }
        assertTrue(line.statement.contains("Prompt STABLE/FAST"))
        assertTrue(line.statement.contains("features Rewriting"))
        assertTrue(line.statement.contains("offline Rewriting"))
    }

    @Test
    fun no601MeansNo601EvidenceLine() {
        val result = classifier.classify(input(summarization = summary606()))
        assertTrue(result.evidence.none { it.statement.startsWith("601 observed") })
    }

    // -- Existing invariants ----------------------------------------------------------

    @Test
    fun missingFeatureConfigurationIsSelectedFromANamedObservation() {
        val result = classifier.classify(
            input(
                messages = listOf(
                    ClassifierMessage(
                        "aicore",
                        "Feature configuration manifest is missing for feature 622",
                    ),
                ),
                summarization = summary606(),
            ),
        )
        assertEquals(ProvisioningFailureClass.MISSING_FEATURE_CONFIGURATION, result.result)
        assertTrue(result.summary.contains("update AICore"))
        assertTrue(result.evidence.any { it.statement.contains("Feature configuration") })
        assertTrue(result.refutations.any { it.contains("GetManifestConfig") })
    }

    @Test
    fun a606PatternAloneIsIndeterminateWithBothCandidatesAndTheMissingObservation() {
        for (rdc in listOf(GrantState.GRANTED, GrantState.DENIED, null)) {
            val result = classifier.classify(
                input(summarization = summary606(), pcsReadDeviceConfig = rdc),
            )
            assertEquals(ProvisioningFailureClass.INDETERMINATE, result.result)
            assertTrue(
                result.candidates.any {
                    it.failureClass == ProvisioningFailureClass.MISSING_FEATURE_CONFIGURATION
                },
            )
            assertTrue(
                result.candidates.any {
                    it.failureClass == ProvisioningFailureClass.PCS_SERVICE_AUTHORIZATION_FAILURE
                },
            )
            assertTrue(result.candidates.any { it.missingObservation.contains("GetManifestConfig") })
            assertNotNull(result.nextObservation)
        }
    }

    @Test
    fun a606PatternWithFourUnavailableConfigsAndDeniedRdcStaysIndeterminate() {
        val result = classifier.classify(
            input(
                summarization = summary606(),
                prompt = unavailablePrompt(),
                pcsReadDeviceConfig = GrantState.DENIED,
                packages = listOf(TestFixtures.packageFacts(PlatformPrincipals.AICORE)),
                manifests = pcsManifest(),
            ),
        )
        assertEquals(ProvisioningFailureClass.INDETERMINATE, result.result)
        assertTrue(result.evidence.any { it.statement.contains("UNAVAILABLE observed") })
        assertTrue(result.evidence.any { it.statement.contains("supports, but does not prove") })
    }

    @Test
    fun grantedReadDeviceConfigDoesNotAssertTheFlagGateIsRefuted() {
        val result = classifier.classify(
            input(summarization = summary606(), pcsReadDeviceConfig = GrantState.GRANTED),
        )
        assertEquals(ProvisioningFailureClass.INDETERMINATE, result.result)
        assertTrue(
            result.evidence.any {
                it.strength == EvidenceStrength.INFERRED &&
                    it.statement.contains("does not refute a PCS manifest refusal")
            },
        )
    }

    @Test
    fun deniedReadDeviceConfigSupportsButDoesNotProveTheGate() {
        val result = classifier.classify(
            input(summarization = summary606(), pcsReadDeviceConfig = GrantState.DENIED),
        )
        assertTrue(
            result.evidence.any {
                it.strength == EvidenceStrength.INFERRED &&
                    it.statement.contains("supports, but does not prove")
            },
        )
    }

    @Test
    fun aDescriptorReadTimedOutKeepsTheBinderOutcomeAndIsRecorded() {
        val binding = TestFixtures.bindResult(
            componentName = PcsBindCatalog.ASTREA_GRPC,
            outcome = PcsBindOutcome.BINDER_RETURNED,
        ).copy(descriptorTimedOut = true, descriptorReadMillis = 3_000L)
        val result = classifier.classify(
            input(binds = listOf(binding), summarization = summary606()),
        )
        assertTrue(result.evidence.any { it.statement.contains("descriptorRead=timed out") })
    }

    @Test
    fun conflictingObservationsProduceIndeterminate() {
        val result = classifier.classify(
            input(
                binds = listOf(grpc(PcsBindOutcome.NOT_ENABLED)),
                messages = listOf(
                    ClassifierMessage("aicore", "NPU access restricted for the model session"),
                ),
            ),
        )
        assertEquals(ProvisioningFailureClass.INDETERMINATE, result.result)
        assertTrue(result.summary.contains("at the same time"))
        assertTrue(
            result.candidates.map { it.failureClass }.containsAll(
                listOf(
                    ProvisioningFailureClass.PCS_INITIALIZATION_FAILURE,
                    ProvisioningFailureClass.NPU_ACCESS_RESTRICTIONS,
                ),
            ),
        )
    }

    @Test
    fun absentEvidenceIsIndeterminateAndNamesTheMissingObservation() {
        val result = classifier.classify(ClassifierInput())
        assertEquals(ProvisioningFailureClass.INDETERMINATE, result.result)
        assertTrue(result.summary.contains("no failure signature"))
        assertTrue(result.candidates.isNotEmpty())
        assertTrue(result.candidates.all { it.missingObservation.isNotBlank() })
        assertTrue(result.refutations.isNotEmpty())
    }

    @Test
    fun unreadableManifestsAreNotReportedAsAbsence() {
        val result = classifier.classify(
            input(
                packages = listOf(TestFixtures.packageFacts(PlatformPrincipals.AICORE)),
                manifests = listOf(
                    ManifestEvidenceSnapshot(
                        requestedPackage = PlatformPrincipals.PCS,
                        outcome = ManifestReadOutcome.NotVisible(PlatformPrincipals.PCS),
                    ),
                ),
            ),
        )
        val manifestEvidence = result.evidence.first {
            it.statement.contains(PlatformPrincipals.PCS) && it.statement.contains("manifest")
        }
        assertTrue(manifestEvidence.statement.contains("not visible"))
        assertFalse(manifestEvidence.statement.contains("not declared"))
    }

    @Test
    fun aDeclaredButUnregisteredPermissionIsNotReportedAsAbsent() {
        val aicoreManifest = ManifestEvidenceSnapshot(
            requestedPackage = PlatformPrincipals.AICORE,
            outcome = TestFixtures.manifestRead(
                packageName = PlatformPrincipals.AICORE,
                usesPermissions = listOf(
                    TestFixtures.usesPermission("android.permission.PROVIDE_ON_DEVICE_INTELLIGENCE"),
                    TestFixtures.usesPermission("android.permission.MANAGE_VIRTUAL_MACHINE"),
                ),
            ),
        )
        val result = classifier.classify(
            input(
                packages = listOf(TestFixtures.packageFacts(PlatformPrincipals.AICORE)),
                manifests = listOf(aicoreManifest),
            ),
        )
        val line = result.evidence.first {
            it.statement.contains("PROVIDE_ON_DEVICE_INTELLIGENCE")
        }
        assertTrue(line.statement.contains("not defined or not resolvable"))
        assertTrue(line.statement.contains("never absent"))
    }

    @Test
    fun everyResultCarriesStrengthAndRefutations() {
        val cases = listOf(
            classifier.classify(input(binds = listOf(grpc(PcsBindOutcome.NOT_ENABLED)))),
            classifier.classify(input(binds = listOf(grpc(PcsBindOutcome.SECURITY_EXCEPTION)))),
            classifier.classify(input(messages = listOf(ClassifierMessage("aicore", "NPU denied")))),
            classifier.classify(input(messages = listOf(ClassifierMessage("aicore", "not eligible")))),
            classifier.classify(
                input(messages = listOf(ClassifierMessage("aicore", "model registration failed"))),
            ),
            classifier.classify(
                input(
                    messages = listOf(
                        ClassifierMessage("aicore", "configuration manifest missing"),
                    ),
                ),
            ),
            classifier.classify(input(summarization = summary606())),
        )
        cases.forEach { result ->
            assertTrue("${result.result}: evidence missing", result.evidence.isNotEmpty())
            assertTrue(
                "${result.result}: an evidence item has no strength",
                result.evidence.all {
                    it.strength == EvidenceStrength.OBSERVED ||
                        it.strength == EvidenceStrength.INFERRED
                },
            )
            assertTrue("${result.result}: refutation missing", result.refutations.isNotEmpty())
            assertTrue("${result.result}: summary blank", result.summary.isNotBlank())
        }
    }

    @Test
    fun theClassifierMessagesAreBuiltFromTheExistingProbeResults() {
        val messages = ClassifierMessages.from(
            summarization = summary606(),
            prompt = listOf(TestFixtures.promptStatus()),
            features = emptyList(),
            offline = emptyList(),
        )
        assertTrue(messages.any { it.source == "summarization" })
        assertTrue(messages.any { it.source == "prompt:STABLE/FAST" })
    }
}
