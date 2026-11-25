package dev.shockstruck.gemininano.diagnostics

import dev.shockstruck.gemininano.model.FeatureStatusKind
import dev.shockstruck.gemininano.model.GrantState
import dev.shockstruck.gemininano.model.ManifestReadOutcome
import dev.shockstruck.gemininano.model.PcsBindCatalog
import dev.shockstruck.gemininano.model.PcsBindOutcome
import dev.shockstruck.gemininano.model.PcsBindResult
import dev.shockstruck.gemininano.model.PlatformPrincipals
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

    private fun input(
        messages: List<ClassifierMessage> = emptyList(),
        binds: List<PcsBindResult> = emptyList(),
        packages: List<dev.shockstruck.gemininano.model.PackageFacts> = emptyList(),
        manifests: List<ManifestEvidenceSnapshot> = emptyList(),
        summarization: dev.shockstruck.gemininano.model.SummarizationProbeResult? = null,
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
    fun pcsInitializationFailureIsSelectedFromANullBinding() {
        val result = classifier.classify(
            input(binds = listOf(grpc(PcsBindOutcome.NULL_BINDING))),
        )
        assertEquals(ProvisioningFailureClass.PCS_INITIALIZATION_FAILURE, result.result)
        assertTrue(result.summary.contains("this app's own gRPC bind"))
        assertTrue(result.refutations.any { it.contains("AICore's own bind") })
    }

    @Test
    fun pcsInitializationFailureIsSelectedFromATimeoutOrDisabledComponent() {
        assertEquals(
            ProvisioningFailureClass.PCS_INITIALIZATION_FAILURE,
            classifier.classify(input(binds = listOf(grpc(PcsBindOutcome.TIMEOUT)))).result,
        )
        assertEquals(
            ProvisioningFailureClass.PCS_INITIALIZATION_FAILURE,
            classifier.classify(input(binds = listOf(grpc(PcsBindOutcome.NOT_ENABLED)))).result,
        )
    }

    @Test
    fun pcsServiceAuthorizationFailureIsSelectedFromASecurityException() {
        val result = classifier.classify(
            input(
                binds = listOf(
                    grpc(
                        PcsBindOutcome.SECURITY_EXCEPTION,
                        exceptionMessage = "allow-component-access denied",
                    ),
                ),
            ),
        )
        assertEquals(ProvisioningFailureClass.PCS_SERVICE_AUTHORIZATION_FAILURE, result.result)
        assertTrue(result.summary.contains("AICore's own access is not established"))
    }

    @Test
    fun pcsServiceAuthorizationFailureIsSelectedFromAnUnexportedGrpcService() {
        val result = classifier.classify(
            input(binds = listOf(grpc(PcsBindOutcome.NOT_EXPORTED))),
        )
        assertEquals(ProvisioningFailureClass.PCS_SERVICE_AUTHORIZATION_FAILURE, result.result)
    }

    @Test
    fun pcsServiceAuthorizationFailureIsSelectedFromAReadableAssociationPolicyMismatch() {
        val pcsManifest = ManifestEvidenceSnapshot(
            requestedPackage = PlatformPrincipals.PCS,
            outcome = TestFixtures.manifestRead(
                packageName = PlatformPrincipals.PCS,
                pccElements = listOf(
                    TestFixtures.associationElement(PlatformPrincipals.AICORE),
                ),
            ),
        )
        val result = classifier.classify(
            input(
                packages = listOf(TestFixtures.packageFacts(PlatformPrincipals.AICORE_LEGACY)),
                manifests = listOf(pcsManifest),
            ),
        )
        assertEquals(ProvisioningFailureClass.PCS_SERVICE_AUTHORIZATION_FAILURE, result.result)
        assertTrue(result.summary.contains("installed AICore package is"))
        assertTrue(result.summary.contains(PlatformPrincipals.AICORE_LEGACY))
    }

    @Test
    fun aMatchingAssociationPolicyDoesNotSelectAnything() {
        val pcsManifest = ManifestEvidenceSnapshot(
            requestedPackage = PlatformPrincipals.PCS,
            outcome = TestFixtures.manifestRead(
                packageName = PlatformPrincipals.PCS,
                pccElements = listOf(
                    TestFixtures.associationElement(PlatformPrincipals.AICORE),
                ),
            ),
        )
        val result = classifier.classify(
            input(
                packages = listOf(TestFixtures.packageFacts(PlatformPrincipals.AICORE)),
                manifests = listOf(pcsManifest),
                summarization = summary606(),
            ),
        )
        assertEquals(ProvisioningFailureClass.INDETERMINATE, result.result)
    }

    @Test
    fun remoteRejectionIsSelectedFromAnObservedEligibilityMessage() {
        val result = classifier.classify(
            input(
                messages = listOf(
                    ClassifierMessage(
                        "download:summarization",
                        "Device is not eligible for this provisioning cohort (HTTP 403)",
                    ),
                ),
            ),
        )
        assertEquals(ProvisioningFailureClass.REMOTE_PROVISIONING_REJECTION, result.result)
        assertTrue(result.summary.contains("no request was sent"))
    }

    @Test
    fun modelRegistrationFailureIsSelectedFromAnObservedRegistrationMessage() {
        val result = classifier.classify(
            input(
                messages = listOf(
                    ClassifierMessage(
                        "aicore",
                        "model registration failed: feature 622 has no registered model",
                    ),
                ),
            ),
        )
        assertEquals(ProvisioningFailureClass.MODEL_REGISTRATION_FAILURE, result.result)
    }

    @Test
    fun npuRestrictionsAreSelectedOnlyFromAMessageNamingTheNpu() {
        val result = classifier.classify(
            input(
                messages = listOf(
                    ClassifierMessage("aicore", "NPU access denied for this process"),
                ),
            ),
        )
        assertEquals(ProvisioningFailureClass.NPU_ACCESS_RESTRICTIONS, result.result)
        assertTrue(result.summary.contains("no denied permission was used"))
    }

    @Test
    fun aDeniedNpuPermissionAloneNeverSelectsNpuRestrictions() {
        val result = classifier.classify(
            input(
                packages = listOf(TestFixtures.packageFacts(PlatformPrincipals.AICORE)),
                summarization = summary606(),
            ),
        )
        assertEquals(ProvisioningFailureClass.INDETERMINATE, result.result)
        assertFalse(result.result == ProvisioningFailureClass.NPU_ACCESS_RESTRICTIONS)
        assertTrue(
            result.candidates.any {
                it.failureClass == ProvisioningFailureClass.NPU_ACCESS_RESTRICTIONS
            },
        )
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
    fun aReturnedGrpcBinderIsNotAuthorizationOrInitializationEvidence() {
        val result = classifier.classify(
            input(
                binds = listOf(grpc(PcsBindOutcome.BINDER_RETURNED)),
                summarization = summary606(),
            ),
        )
        assertEquals(ProvisioningFailureClass.INDETERMINATE, result.result)
        assertFalse(
            result.candidates.any {
                it.failureClass == ProvisioningFailureClass.PCS_INITIALIZATION_FAILURE
            },
        )
        assertTrue(
            result.evidence.any { it.statement.contains("never stands in for AICore") },
        )
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
                binds = listOf(grpc(PcsBindOutcome.SECURITY_EXCEPTION)),
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
                    ProvisioningFailureClass.PCS_SERVICE_AUTHORIZATION_FAILURE,
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
    fun everyResultCarriesStrengthAndRefutations() {
        val cases = listOf(
            classifier.classify(input(binds = listOf(grpc(PcsBindOutcome.NULL_BINDING)))),
            classifier.classify(input(binds = listOf(grpc(PcsBindOutcome.SECURITY_EXCEPTION)))),
            classifier.classify(
                input(messages = listOf(ClassifierMessage("aicore", "NPU denied"))),
            ),
            classifier.classify(
                input(messages = listOf(ClassifierMessage("aicore", "not eligible"))),
            ),
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
                result.evidence.all { it.strength == EvidenceStrength.OBSERVED || it.strength == EvidenceStrength.INFERRED },
            )
            assertTrue("${result.result}: refutation missing", result.refutations.isNotEmpty())
            assertTrue("${result.result}: summary blank", result.summary.isNotBlank())
        }
    }

    @Test
    fun aSuccessfulGenerationLeavesNoFailureClass() {
        val prompt = TestFixtures.promptStatus(
            status = FeatureStatusKind.AVAILABLE,
            featureId = null,
            errorCode = null,
            message = null,
            generatedText = "OK",
        )
        val result = classifier.classify(
            ClassifierInput(prompt = listOf(prompt)),
        )
        assertEquals(ProvisioningFailureClass.INDETERMINATE, result.result)
        assertTrue(result.summary.contains("No failure signature"))
        assertTrue(result.candidates.isEmpty())
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

}
