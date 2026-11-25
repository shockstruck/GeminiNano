package dev.shockstruck.gemininano

import dev.shockstruck.gemininano.ui.Section
import dev.shockstruck.gemininano.ui.SectionCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SectionCatalogTest {

    @Test
    fun exactlyTenSectionsExistInOrder() {
        assertEquals(10, SectionCatalog.sections.size)
        assertEquals(
            listOf(
                "Overview",
                "System Components",
                "AICore Features",
                "Private Compute Services",
                "Model Manager",
                "Provisioning Diagnostics",
                "Offline Inference",
                "Privacy",
                "Diagnostic Logs",
                "Settings",
            ),
            SectionCatalog.sections.map { it.title },
        )
    }

    @Test
    fun fiveSectionsAreHonestStagePlaceholders() {
        assertEquals(5, SectionCatalog.staged.size)
        assertEquals(
            setOf(
                Section.AICORE_FEATURES,
                Section.PRIVATE_COMPUTE_SERVICES,
                Section.MODEL_MANAGER,
                Section.PROVISIONING_DIAGNOSTICS,
                Section.OFFLINE_INFERENCE,
            ),
            SectionCatalog.staged.toSet(),
        )
        SectionCatalog.staged.forEach { section ->
            assertFalse(section.implementedInStage.isNullOrBlank())
        }
    }

    @Test
    fun implementedSectionsAreTheExpectedFive() {
        assertEquals(
            setOf(
                Section.OVERVIEW,
                Section.SYSTEM_COMPONENTS,
                Section.PRIVACY,
                Section.DIAGNOSTIC_LOGS,
                Section.SETTINGS,
            ),
            SectionCatalog.implemented.toSet(),
        )
        SectionCatalog.implemented.forEach { section ->
            assertTrue(section.implementedInStage == null)
        }
    }
}
