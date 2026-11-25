package dev.shockstruck.gemininano.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemFeatureFilterTest {

    @Test
    fun npuAiVirtualizationAndPrivateComputeNamesMatch() {
        assertTrue(SystemFeatureFilter.matches("android.hardware.npu"))
        assertTrue(SystemFeatureFilter.matches("android.hardware.ai"))
        assertTrue(SystemFeatureFilter.matches("android.software.virtualization"))
        assertTrue(SystemFeatureFilter.matches("android.software.privatecompute"))
        assertTrue(SystemFeatureFilter.matches("com.google.android.feature.AI"))
        assertTrue(SystemFeatureFilter.matches("android.hardware.private_compute"))
    }

    @Test
    fun unrelatedNamesDoNotMatchOnSubstrings() {
        assertFalse(SystemFeatureFilter.matches("android.hardware.camera"))
        assertFalse(SystemFeatureFilter.matches("android.software.activities_on_secondary_displays"))
        assertFalse(SystemFeatureFilter.matches("android.hardware.audio.output"))
        assertFalse(SystemFeatureFilter.matches("android.software.available"))
    }

    @Test
    fun filterKeepsOnlyMatchesSortedAndNamesOnly() {
        val filtered = SystemFeatureFilter.filter(
            listOf(
                "android.hardware.camera",
                "android.hardware.virtualization",
                "android.hardware.npu",
                "android.software.privatecompute",
            ),
        )
        assertEquals(
            listOf(
                "android.hardware.npu",
                "android.hardware.virtualization",
                "android.software.privatecompute",
            ),
            filtered,
        )
    }
}
