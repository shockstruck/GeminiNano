package dev.shockstruck.gemininano

import dev.shockstruck.gemininano.model.FeatureStatusKind
import dev.shockstruck.gemininano.ui.viewmodel.OverviewUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverviewUiStateTest {

    @Test
    fun downloadIsOfferedOnlyWhenDownloadable() {
        assertTrue(OverviewUiState(probeStatus = FeatureStatusKind.DOWNLOADABLE).canDownload)
        assertFalse(OverviewUiState(probeStatus = FeatureStatusKind.AVAILABLE).canDownload)
        assertFalse(OverviewUiState(probeStatus = FeatureStatusKind.PROBE_ERROR).canDownload)
        assertFalse(OverviewUiState(probeStatus = null).canDownload)
    }

    @Test
    fun downloadIsDisabledWhileBusy() {
        assertFalse(
            OverviewUiState(
                probeStatus = FeatureStatusKind.DOWNLOADABLE,
                probeBusy = true,
            ).canDownload,
        )
        assertFalse(
            OverviewUiState(
                probeStatus = FeatureStatusKind.DOWNLOADABLE,
                downloadBusy = true,
            ).canDownload,
        )
    }

    @Test
    fun summarizeRequiresTextAndIdleState() {
        assertFalse(OverviewUiState(noteText = "   ").canSummarize)
        assertTrue(OverviewUiState(noteText = "a note").canSummarize)
        assertFalse(OverviewUiState(noteText = "a note", inferenceBusy = true).canSummarize)
    }
}
