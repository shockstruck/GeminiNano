package dev.shockstruck.gemininano

import dev.shockstruck.gemininano.ui.Section
import dev.shockstruck.gemininano.ui.SectionCatalog
import org.junit.Assert.assertEquals
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
    fun noSectionRemainsAStagePlaceholder() {
        assertTrue(SectionCatalog.staged.isEmpty())
        assertEquals(10, SectionCatalog.implemented.size)
        SectionCatalog.sections.forEach { section ->
            assertTrue(section.implementedInStage == null)
        }
    }
}
