package dev.shockstruck.gemininano.diagnostics

import dev.shockstruck.gemininano.model.AicoreFeatureLabels
import dev.shockstruck.gemininano.model.FeatureStatusKind
import dev.shockstruck.gemininano.model.GrantState
import dev.shockstruck.gemininano.model.PcsBindOutcome
import dev.shockstruck.gemininano.model.PcsBindResult
import dev.shockstruck.gemininano.model.PromptModelConfig
import dev.shockstruck.gemininano.testing.TestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisioningDiagnosticsTest {

    private val presenter = ProvisioningDiagnosticsPresenter()

    private fun evidence(
        pcc: GrantState = GrantState.DENIED,
        pccRequested: Boolean = true,
        prompt: List<dev.shockstruck.gemininano.model.PromptConfigStatus> = emptyList(),
        binds: List<dev.shockstruck.gemininano.model.PcsBindResult> = emptyList(),
    ) = ProvisioningEvidence(
        matrix = TestFixtures.permissionMatrix(
            pcsPcc = pcc,
            pcsPccRequested = pccRequested,
        ),
        prompt = prompt,
        bindResults = binds,
    )

    @Test
    fun pcsPccDenialStatesTheObservedGrantWithoutACause() {
        val lines = presenter.present(evidence(pcc = GrantState.DENIED)).lines
        assertTrue(lines.any { it.contains("requested · denied") })
        assertTrue(lines.any { it.contains("observed grant state") })
        assertTrue(lines.any { it.contains("does not say why") })
        assertTrue(lines.none { it.contains("sandboxed-install gap") })
        assertTrue(lines.none { it.contains("is not a PCS UID") })
    }

    @Test
    fun fourUnavailablePromptConfigsGetTheirOwnLineWithoutModelClaims() {
        val prompt = PromptModelConfig.ALL.map { config ->
            TestFixtures.promptStatus(
                config = config,
                status = FeatureStatusKind.UNAVAILABLE,
                featureId = null,
                errorCode = null,
                message = null,
            )
        }
        val lines = presenter.present(evidence(prompt = prompt)).lines
        val unavailable = lines.first { it.contains("all four configs UNAVAILABLE") }
        assertTrue(unavailable.contains("says nothing about the base model"))
        assertTrue(unavailable.contains("model registry"))
        assertTrue(lines.none { it.contains("is AVAILABLE") })
        assertTrue(lines.none { it.lowercase().contains("provisioned") })
    }

    @Test
    fun aSecurityExceptionWithoutAnAssociationMessageDoesNotBlameTheOs() {
        val lines = presenter.present(
            evidence(
                binds = listOf(
                    TestFixtures.bindResult(
                        outcome = PcsBindOutcome.SECURITY_EXCEPTION,
                        exceptionClass = "java.lang.SecurityException",
                        exceptionMessage = "uid 10234 is not allowed to bind this service",
                    ),
                ),
            ),
        ).lines
        val line = lines.first { it.contains("SecurityException") }
        assertTrue(line.contains("cause is not identified"))
        assertTrue(!line.contains("next fix is OS-side"))
    }

    @Test
    fun aNonSecurityBindThrowableKeepsItsOwnOutcomeAndSaysCauseUnknown() {
        val lines = presenter.present(
            evidence(
                binds = listOf(
                    TestFixtures.bindResult(
                        outcome = PcsBindOutcome.BIND_ERROR,
                        exceptionClass = "java.lang.IllegalStateException",
                        exceptionMessage = "service unavailable",
                    ),
                ),
            ),
        ).lines
        val line = lines.first { it.contains("IllegalStateException") }
        assertTrue(line.contains("not a SecurityException"))
        assertTrue(line.contains("cause is not identified"))
        assertTrue(!line.contains("association"))
    }

    @Test
    fun allPrompt606KeepsSummarizationOutOfIt() {
        val prompt = PromptModelConfig.ALL.mapIndexed { index, config ->
            TestFixtures.promptStatus(
                config = config,
                status = FeatureStatusKind.PROBE_ERROR,
                featureId = 636 + index,
                errorCode = 606,
                message = "Feature ${636 + index} is not available.",
            )
        }
        val lines = presenter.present(evidence(prompt = prompt)).lines
        assertTrue(lines.any { it.contains("Prompt all 606") })
        assertTrue(lines.any { it.contains("summarization 622 is not a base-model absence") })
    }

    @Test
    fun promptAvailableWithBaseModelReportsABaseModel() {
        val prompt = TestFixtures.promptStatus(
            config = PromptModelConfig.ALL.first(),
            status = FeatureStatusKind.AVAILABLE,
            featureId = null,
            errorCode = null,
            message = null,
            baseModelName = "gemini-nano-base",
            generatedText = "OK",
        )
        val lines = presenter.present(evidence(prompt = listOf(prompt))).lines
        assertTrue(lines.any { it.contains("generation succeeded") })
        assertTrue(lines.any { it.contains("gemini-nano-base") })
        assertTrue(lines.any { it.contains("summarization slot") })
    }

    @Test
    fun bindSecurityExceptionPointsAtTheOsLayer() {
        val lines = presenter.present(
            evidence(
                binds = listOf(
                    TestFixtures.bindResult(
                        outcome = PcsBindOutcome.SECURITY_EXCEPTION,
                        exceptionClass = "java.lang.SecurityException",
                        exceptionMessage = "allow-component-access denied",
                    ),
                ),
            ),
        ).lines
        assertTrue(lines.any { it.contains("SecurityException") })
        assertTrue(lines.any { it.contains("next fix is OS-side") })
    }

    @Test
    fun binderReturnedIsThisAppsOwnResultAndClaimsNothingAboutAicore() {
        val lines = presenter.present(
            evidence(
                binds = listOf(TestFixtures.bindResult(outcome = PcsBindOutcome.BINDER_RETURNED)),
            ),
        ).lines
        val line = lines.first { it.contains("binder returned") }
        assertTrue(line.contains("this app's own bind attempt"))
        assertTrue(line.contains("does not prove or describe AICore's access"))
        assertTrue(!line.contains("gRPC policy/API key"))
    }

    @Test
    fun aPlannedBindIsNotReportedAsARunOrAFailure() {
        val lines = presenter.present(
            evidence(
                binds = listOf(
                    PcsBindResult(
                        componentName = "com.google.android.apps.miphone.astrea.grpc.AstreaGrpcService",
                        outcome = PcsBindOutcome.NOT_RUN,
                        role = "grpc",
                    ),
                ),
            ),
        ).lines
        val line = lines.first { it.contains("gRPC service") }
        assertTrue(line.contains("has not run"))
        assertTrue(line.contains("explicit tap"))
        assertTrue(!line.contains("association"))
    }

    @Test
    fun aKnownServiceThePackageDoesNotDeclareSaysSo() {
        val lines = presenter.present(
            evidence(
                binds = listOf(
                    PcsBindResult(
                        componentName = "com.google.android.apps.miphone.astrea.grpc.AstreaGrpcService",
                        outcome = PcsBindOutcome.NOT_DECLARED,
                        role = "grpc",
                    ),
                ),
            ),
        ).lines
        val line = lines.first { it.contains("gRPC service") }
        assertTrue(line.contains("does not declare"))
        assertTrue(line.contains("no bind was attempted"))
    }

    @Test
    fun feature622LabelIsExact() {
        assertEquals(
            "622 — AICore AIDL constant SAPI_SUMMARIZATION_EN_2 (decompiled source, not a model); model unknown.",
            AicoreFeatureLabels.FEATURE_622,
        )
        assertTrue(
            presenter.present(evidence()).lines.any {
                it == AicoreFeatureLabels.FEATURE_622
            },
        )
    }

    @Test
    fun noProvisionedClaimWithoutAvailableAndGeneration() {
        val prompt = PromptModelConfig.ALL.map { config ->
            TestFixtures.promptStatus(
                config = config,
                status = FeatureStatusKind.PROBE_ERROR,
                featureId = 648,
                errorCode = 606,
                message = "Feature 648 is not available.",
            )
        }
        val lines = presenter.present(
            evidence(
                prompt = prompt,
                binds = listOf(TestFixtures.bindResult(outcome = PcsBindOutcome.SECURITY_EXCEPTION)),
            ),
        ).lines
        assertTrue(lines.none { it.contains("provisioned", ignoreCase = true) })
    }

    @Test
    fun unknownEvidenceSaysSoInsteadOfGuessing() {
        val lines = presenter.present(
            ProvisioningEvidence(matrix = null, prompt = emptyList(), bindResults = emptyList()),
        ).lines
        assertTrue(lines.any { it.contains("not collected yet") })
        assertTrue(lines.any { it.contains("has not been probed yet") })
        assertTrue(lines.any { it.contains("bind probe has not run") })
        assertFalse(lines.any { it.contains("provisioned", ignoreCase = true) })
    }
    @Test
    fun theDecisionCarriesTheClassifierResultForTheSameEvidence() {
        val decision = presenter.present(
            evidence(
                binds = listOf(
                    TestFixtures.bindResult(
                        componentName = "com.google.android.apps.miphone.astrea.grpc.AstreaGrpcService",
                        outcome = PcsBindOutcome.NOT_ENABLED,
                    ),
                ),
            ),
        )
        assertEquals(
            ProvisioningFailureClass.PCS_INITIALIZATION_FAILURE,
            decision.classification.result,
        )
        assertTrue(decision.classification.evidence.isNotEmpty())
    }

    @Test
    fun anIndeterminateDecisionNamesCandidatesAndAMissingObservation() {
        val decision = presenter.present(
            evidence(
                prompt = PromptModelConfig.ALL.map { config ->
                    TestFixtures.promptStatus(
                        config = config,
                        status = FeatureStatusKind.PROBE_ERROR,
                        errorCode = 606,
                        message = "Feature 636 is not available.",
                    )
                },
            ),
        )
        assertEquals(ProvisioningFailureClass.INDETERMINATE, decision.classification.result)
        assertTrue(decision.classification.candidates.isNotEmpty())
        assertTrue(
            decision.classification.candidates.all { it.missingObservation.isNotBlank() },
        )
    }

}
