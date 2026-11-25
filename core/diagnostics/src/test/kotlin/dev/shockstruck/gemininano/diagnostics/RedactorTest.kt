package dev.shockstruck.gemininano.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {

    private val redactor = Redactor()

    @Test
    fun redactsRegisteredNoteText() {
        val note = "My private note about the bank visit"
        val redacted = redactor.redact("Probe failed while handling: $note", listOf(note))
        assertEquals("Probe failed while handling: [redacted]", redacted)
    }

    @Test
    fun redactsEmailAddresses() {
        val redacted = redactor.redact("contact person@example.com for details")
        assertEquals("contact [redacted-email] for details", redacted)
    }

    @Test
    fun redactsAccountLikeDigitStrings() {
        val redacted = redactor.redact("account 1234567890123456 declined")
        assertEquals("account [redacted-number] declined", redacted)
    }

    @Test
    fun keepsFeatureIdsCodesAndPackageNames() {
        val text = "Feature 622 is not available (error 606) in com.google.android.aicore"
        assertEquals(text, redactor.redact(text))
    }

    @Test
    fun nullStaysNull() {
        assertNull(redactor.redact(null))
    }

    @Test
    fun veryShortSensitiveValuesAreIgnoredToAvoidMangling() {
        val redacted = redactor.redact("a normal diagnostic message", listOf("a"))
        assertEquals("a normal diagnostic message", redacted)
    }

    @Test
    fun longestSensitiveValueWins() {
        val redacted = redactor.redact("note A note B", listOf("note A", "note A note B"))
        assertEquals("[redacted]", redacted)
    }

    @Test
    fun emptyAndWhitespaceSensitiveValuesAreIgnored() {
        val redacted = redactor.redact("unchanged", listOf("", "   "))
        assertEquals("unchanged", redacted)
        assertFalse(redacted!!.contains("redacted"))
    }
}
