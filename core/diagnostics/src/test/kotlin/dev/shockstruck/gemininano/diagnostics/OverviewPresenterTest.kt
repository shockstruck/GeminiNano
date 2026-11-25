package dev.shockstruck.gemininano.diagnostics

import dev.shockstruck.gemininano.model.DiagnosticSnapshot
import dev.shockstruck.gemininano.model.GrantState
import dev.shockstruck.gemininano.model.PackageVisibility
import dev.shockstruck.gemininano.testing.TestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverviewPresenterTest {

    private val presenter = OverviewPresenter()

    @Test
    fun unprobedStateLabelsEverythingUnknown() {
        val model = presenter.present(
            DiagnosticSnapshot(
                aicore = TestFixtures.packageFacts("com.google.android.aicore", versionName = "17.0.1"),
                pcs = TestFixtures.packageFacts("com.google.android.as.oss"),
                permissionMatrix = TestFixtures.permissionMatrix(pcsPcc = GrantState.DENIED),
                probe = null,
                lastFailure = null,
                build = TestFixtures.buildFacts(),
            ),
        )
        val feature = model.rows.first { it.label == "Summarization feature status" }
        assertEquals("not probed yet", feature.value)
        assertTrue(feature.isUnknown)
        val baseModel = model.rows.first { it.label == "Base model name" }
        assertEquals("unknown", baseModel.value)
        assertTrue(baseModel.isUnknown)
        assertEquals("none recorded", model.lastFailureText)
    }

    @Test
    fun probeFailureShowsExactCodeAndMessage() {
        val probe = TestFixtures.probeResult(
            status = dev.shockstruck.gemininano.model.FeatureStatusKind.PROBE_ERROR,
            baseModelName = null,
            failure = TestFixtures.failure(606),
        )
        val model = presenter.present(
            DiagnosticSnapshot(
                aicore = TestFixtures.packageFacts("com.google.android.aicore"),
                pcs = TestFixtures.packageFacts("com.google.android.as.oss"),
                permissionMatrix = TestFixtures.permissionMatrix(pcsPcc = GrantState.DENIED),
                probe = probe,
                lastFailure = null,
                build = TestFixtures.buildFacts(),
            ),
        )
        val feature = model.rows.first { it.label == "Summarization feature status" }
        assertTrue(feature.value.contains("606"))
        assertTrue(feature.value.contains("Feature 622 is not available."))
        assertTrue(model.recommendation.headline.contains("606"))
    }

    @Test
    fun invisibleAicoreAndPcsAreLabelledNotVisible() {
        val model = presenter.present(
            DiagnosticSnapshot(
                aicore = TestFixtures.packageFacts("com.google.android.aicore", visible = false),
                pcs = TestFixtures.packageFacts("com.google.android.as.oss", visible = false),
                permissionMatrix = TestFixtures.permissionMatrix(pcsVisible = false, pcsPcc = GrantState.UNKNOWN),
                probe = null,
                lastFailure = null,
                build = TestFixtures.buildFacts(),
            ),
        )
        val aicore = model.rows.first { it.label == "Android AICore" }
        assertEquals(dev.shockstruck.gemininano.model.PackageFactsFormatting.NOT_VISIBLE, aicore.value)
        val pcc = model.rows.first { it.label == "PROVIDE_PRIVATE_COMPUTE_SERVICES (PCS)" }
        assertEquals(
            dev.shockstruck.gemininano.model.PermissionStatus.NOT_VISIBLE.label,
            pcc.value,
        )
    }

    @Test
    fun deviceRowComesFromBuildFacts() {
        val model = presenter.present(
            DiagnosticSnapshot(
                aicore = TestFixtures.packageFacts("com.google.android.aicore"),
                pcs = TestFixtures.packageFacts("com.google.android.as.oss"),
                permissionMatrix = TestFixtures.permissionMatrix(pcsPcc = GrantState.GRANTED),
                probe = null,
                lastFailure = null,
                build = TestFixtures.buildFacts(),
            ),
        )
        val device = model.rows.first { it.label == "Device" }
        assertEquals("Google Pixel 10 Pro Fold · Android 17 (API 37)", device.value)
    }

    @Test
    fun lastFailureIsFormattedIntoAFullLine() {
        val model = presenter.present(
            DiagnosticSnapshot(
                aicore = TestFixtures.packageFacts("com.google.android.aicore"),
                pcs = TestFixtures.packageFacts("com.google.android.as.oss"),
                permissionMatrix = TestFixtures.permissionMatrix(pcsPcc = GrantState.DENIED),
                probe = null,
                lastFailure = TestFixtures.event(),
                build = TestFixtures.buildFacts(),
            ),
        )
        assertTrue(model.lastFailureText.contains("aicore/probeSummarization"))
        assertTrue(model.lastFailureText.contains("error=606"))
        assertTrue(model.lastFailureText.contains("Feature 622 is not available."))
    }

    @Test
    fun visiblePackageRowsUseVersionNameAndCode() {
        val model = presenter.present(
            DiagnosticSnapshot(
                aicore = TestFixtures.packageFacts(
                    "com.google.android.aicore",
                    versionName = "17.0.1",
                    versionCode = 42L,
                ),
                pcs = TestFixtures.packageFacts("com.google.android.as.oss"),
                permissionMatrix = TestFixtures.permissionMatrix(pcsPcc = GrantState.DENIED),
                probe = null,
                lastFailure = null,
                build = TestFixtures.buildFacts(),
            ),
        )
        assertEquals("17.0.1 (versionCode 42)", model.rows.first { it.label == "Android AICore" }.value)
    }

    @Test
    fun notVisibleUsesTheVisibilityEnum() {
        assertEquals(
            PackageVisibility.NOT_VISIBLE,
            TestFixtures.packageFacts("x", visible = false).visibility,
        )
    }
}
