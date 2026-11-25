package dev.shockstruck.gemininano.diagnostics

import dev.shockstruck.gemininano.model.AicoreFeatureLabels
import dev.shockstruck.gemininano.model.FeatureStatusKind
import dev.shockstruck.gemininano.model.GrantState
import dev.shockstruck.gemininano.model.PcsBindOutcome
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
    fun pcsPccDenialStatesTheSandboxedInstallGap() {
        val lines = presenter.present(evidence(pcc = GrantState.DENIED)).lines
        assertTrue(lines.any { it.contains("requested · denied") })
        assertTrue(lines.any { it.contains("sandboxed-install gap") })
        assertTrue(lines.any { it.contains("does not by itself prove AICore cannot bind PCS") })
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
    fun binderReturnedPointsPastTheOsLayer() {
        val lines = presenter.present(
            evidence(
                binds = listOf(TestFixtures.bindResult(outcome = PcsBindOutcome.BINDER_RETURNED)),
            ),
        ).lines
        assertTrue(lines.any { it.contains("binder returned") })
        assertTrue(lines.any { it.contains("gRPC policy/API key") })
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
}
