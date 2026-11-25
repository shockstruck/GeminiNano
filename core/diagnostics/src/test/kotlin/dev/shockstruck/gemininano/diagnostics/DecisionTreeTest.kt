package dev.shockstruck.gemininano.diagnostics

import dev.shockstruck.gemininano.model.DiagnosticSnapshot
import dev.shockstruck.gemininano.model.FeatureStatusKind
import dev.shockstruck.gemininano.model.GrantState
import dev.shockstruck.gemininano.model.RecommendationKind
import dev.shockstruck.gemininano.testing.TestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionTreeTest {

    private val tree = DecisionTree()

    private fun snapshot(
        probeStatus: FeatureStatusKind? = FeatureStatusKind.AVAILABLE,
        errorCode: Int? = null,
        aicoreVisible: Boolean = true,
        pcsVisible: Boolean = true,
        pcc: GrantState = GrantState.DENIED,
    ): DiagnosticSnapshot {
        val probe = if (probeStatus == null) {
            null
        } else {
            TestFixtures.probeResult(
                status = probeStatus,
                failure = errorCode?.let { TestFixtures.failure(it) },
            )
        }
        return DiagnosticSnapshot(
            aicore = TestFixtures.packageFacts("com.google.android.aicore", visible = aicoreVisible),
            pcs = TestFixtures.packageFacts("com.google.android.as.oss", visible = pcsVisible),
            pccPermissionState = pcc,
            probe = probe,
            lastFailure = null,
            build = TestFixtures.buildFacts(),
        )
    }

    @Test
    fun noProbeAsksForAProbe() {
        val recommendation = tree.recommend(snapshot(probeStatus = null))
        assertEquals(RecommendationKind.UNKNOWN, recommendation.kind)
        assertTrue(recommendation.headline.contains("probe", ignoreCase = true))
    }

    @Test
    fun missingAicoreIsBlocked() {
        val recommendation = tree.recommend(snapshot(aicoreVisible = false))
        assertEquals(RecommendationKind.BLOCKED, recommendation.kind)
        assertTrue(recommendation.headline.contains("AICore"))
    }

    @Test
    fun availableIsOk() {
        val recommendation = tree.recommend(snapshot(FeatureStatusKind.AVAILABLE))
        assertEquals(RecommendationKind.OK, recommendation.kind)
    }

    @Test
    fun downloadableIsAnAction() {
        val recommendation = tree.recommend(snapshot(FeatureStatusKind.DOWNLOADABLE))
        assertEquals(RecommendationKind.ACTION, recommendation.kind)
        assertTrue(recommendation.detail.contains("Download"))
    }

    @Test
    fun downloadingIsAWait() {
        assertEquals(RecommendationKind.WAIT, tree.recommend(snapshot(FeatureStatusKind.DOWNLOADING)).kind)
    }

    @Test
    fun unavailableIsBlocked() {
        assertEquals(RecommendationKind.BLOCKED, tree.recommend(snapshot(FeatureStatusKind.UNAVAILABLE)).kind)
    }

    @Test
    fun error606IsBlockedAndExplainsFeature622() {
        val recommendation = tree.recommend(
            snapshot(FeatureStatusKind.PROBE_ERROR, errorCode = 606, pcc = GrantState.DENIED),
        )
        assertEquals(RecommendationKind.BLOCKED, recommendation.kind)
        assertTrue(recommendation.headline.contains("606"))
        assertTrue(recommendation.headline.contains("622"))
        assertTrue(recommendation.detail.contains("denied"))
        assertTrue(recommendation.detail.contains("PROVIDE_PRIVATE_COMPUTE_SERVICES"))
    }

    @Test
    fun error606MentionsMissingPcsVisibility() {
        val recommendation = tree.recommend(
            snapshot(FeatureStatusKind.PROBE_ERROR, errorCode = 606, pcsVisible = false),
        )
        assertTrue(recommendation.detail.contains("PCS package is not visible"))
    }

    @Test
    fun error601IsAnAction() {
        val recommendation = tree.recommend(snapshot(FeatureStatusKind.PROBE_ERROR, errorCode = 601))
        assertEquals(RecommendationKind.ACTION, recommendation.kind)
        assertTrue(recommendation.headline.contains("601"))
    }

    @Test
    fun otherProbeErrorsKeepTheirCode() {
        val recommendation = tree.recommend(snapshot(FeatureStatusKind.PROBE_ERROR, errorCode = 42))
        assertEquals(RecommendationKind.ACTION, recommendation.kind)
        assertTrue(recommendation.headline.contains("42"))
    }

    @Test
    fun probeErrorWithoutCodeDoesNotInventOne() {
        val probe = TestFixtures.probeResult(FeatureStatusKind.PROBE_ERROR, baseModelName = null, failure = null)
        val recommendation = tree.recommend(
            snapshot(probeStatus = FeatureStatusKind.PROBE_ERROR).copy(probe = probe),
        )
        assertEquals(RecommendationKind.ACTION, recommendation.kind)
        assertTrue(!recommendation.headline.contains("606"))
    }

    @Test
    fun unknownStatusIsUnknown() {
        assertEquals(
            RecommendationKind.UNKNOWN,
            tree.recommend(snapshot(FeatureStatusKind.UNKNOWN)).kind,
        )
    }
}
