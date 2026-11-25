package dev.shockstruck.gemininano.diagnostics

/**
 * Removes user note text, e-mail addresses and account-like digit strings from any
 * diagnostic text before it is stored or exported. Package names, feature ids, error
 * codes and class names are deliberately left untouched.
 */
class Redactor(
    private val emailPattern: Regex = EMAIL_PATTERN,
    private val accountNumberPattern: Regex = ACCOUNT_NUMBER_PATTERN,
) {

    fun redact(text: String?, sensitiveValues: Collection<String> = emptyList()): String? {
        val source: String = text ?: return null
        var result: String = source
        sensitiveValues
            .asSequence()
            .filter { it.isNotBlank() && it.length >= MIN_SENSITIVE_LENGTH }
            .distinct()
            .sortedByDescending { it.length }
            .forEach { value -> result = result.replace(value, REDACTED_TEXT) }
        result = emailPattern.replace(result, REDACTED_EMAIL)
        result = accountNumberPattern.replace(result, REDACTED_NUMBER)
        return result
    }

    companion object {
        const val REDACTED_TEXT = "[redacted]"
        const val REDACTED_EMAIL = "[redacted-email]"
        const val REDACTED_NUMBER = "[redacted-number]"

        /** Notes shorter than this would match too much unrelated text. */
        const val MIN_SENSITIVE_LENGTH = 3

        val EMAIL_PATTERN = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
        val ACCOUNT_NUMBER_PATTERN = Regex("(?<!\\d)\\d{12,19}(?!\\d)")
    }
}
