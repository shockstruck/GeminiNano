package dev.shockstruck.gemininano.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageReadsTest {

    @Test
    fun nameNotFoundIsTheOnlyNotVisibleOutcome() {
        val attempts = mutableListOf<Int>()
        val outcome = PackageReads.withFallbacks<String>(
            attempts = listOf(
                { attempts += 0; throw FakeNameNotFound() },
                { attempts += 1; "should not run" },
            ),
            isNameNotFound = { it is FakeNameNotFound },
        )
        assertEquals(PackageReadOutcome.NotVisible, outcome)
        assertEquals(listOf(0), attempts)
    }

    @Test
    fun anotherThrowableIsRetriedWithTheNextSmallerFlagSet() {
        var calls = 0
        val outcome = PackageReads.withFallbacks(
            attempts = listOf(
                { calls++; throw IllegalStateException("full flags failed") },
                { calls++; "reduced" },
                { calls++; "not reached" },
            ),
            isNameNotFound = { false },
        )
        assertEquals(PackageReadOutcome.Success("reduced", attempt = 1), outcome)
        assertEquals(2, calls)
    }

    @Test
    fun everyAttemptFailingIsAReadErrorWithClassAndMessage() {
        val outcome = PackageReads.withFallbacks<String>(
            attempts = listOf(
                { throw IllegalArgumentException("first") },
                { throw IllegalStateException("last") },
            ),
            isNameNotFound = { false },
        )
        assertTrue(outcome is PackageReadOutcome.Failed)
        val error = (outcome as PackageReadOutcome.Failed).error
        assertEquals("java.lang.IllegalStateException", error.exceptionClass)
        assertEquals("last", error.message)
        assertEquals(2, error.attempts)
        assertTrue(error.label.contains("java.lang.IllegalStateException: last"))
    }

    @Test
    fun readErrorRenderingIsNotTheNotVisibleText() {
        val facts = PackageFacts(
            packageName = "com.example",
            visibility = PackageVisibility.READ_ERROR,
            readError = PackageReadError("java.lang.SecurityException", "denied", 3),
        )
        assertTrue(PackageFactsFormatting.visibilityLabel(facts).contains("read error"))
        assertTrue(PackageFactsFormatting.presenceLabel(facts).contains("SecurityException"))
        assertTrue(PackageFactsFormatting.versionLabel(facts).contains("read error"))
    }

    @Test
    fun disabledComponentsCarryTheirEffectiveEnabledState() {
        val component = ComponentFacts(
            kind = ComponentKind.SERVICE,
            className = "com.example.DisabledService",
            exported = false,
            enabled = false,
            effectivelyEnabled = false,
        )
        val rendered = PackageFactsFormatting.renderComponent(component)
        assertTrue(rendered.contains("enabled=false"))
        assertTrue(rendered.contains("effectiveEnabled=false"))
    }

    private class FakeNameNotFound : RuntimeException("not found")
}
