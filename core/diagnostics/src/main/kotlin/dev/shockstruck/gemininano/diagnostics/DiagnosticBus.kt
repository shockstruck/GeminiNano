package dev.shockstruck.gemininano.diagnostics

import dev.shockstruck.gemininano.model.DiagnosticEvent
import dev.shockstruck.gemininano.model.Outcome
import dev.shockstruck.gemininano.model.Severity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory, bounded diagnostic event bus. No network, no telemetry, no persistence
 * beyond the life of the process (exports are written only where the user chooses).
 *
 * Every event's free-text message is redacted with the currently registered sensitive
 * values (user note text) plus e-mail and account-like patterns.
 */
class DiagnosticBus(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val redactor: Redactor = Redactor(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val minimumSeverity: () -> Severity = { Severity.VERBOSE },
) {

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val lock = Any()
    private val _events = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    private val sensitiveValues = linkedSetOf<String>()

    val events: StateFlow<List<DiagnosticEvent>> = _events.asStateFlow()

    fun registerSensitiveValue(value: String) {
        if (value.isBlank()) return
        synchronized(lock) { sensitiveValues += value }
    }

    fun clearSensitiveValues() {
        synchronized(lock) { sensitiveValues.clear() }
    }

    fun emit(event: DiagnosticEvent): DiagnosticEvent? {
        if (event.severity.rank < minimumSeverity().rank) return null
        val safe = synchronized(lock) {
            event.copy(message = redactor.redact(event.message, sensitiveValues))
        }
        synchronized(lock) {
            val next = (_events.value + safe).takeLast(capacity)
            _events.value = next
        }
        return safe
    }

    fun log(
        component: String,
        operation: String,
        outcome: Outcome,
        severity: Severity = Severity.INFO,
        exceptionClass: String? = null,
        errorCode: Int? = null,
        message: String? = null,
        featureId: String? = null,
        bytes: Long? = null,
        durationMillis: Long? = null,
    ): DiagnosticEvent? = emit(
        DiagnosticEvent(
            timestampEpochMillis = clock(),
            component = component,
            operation = operation,
            outcome = outcome,
            severity = severity,
            exceptionClass = exceptionClass,
            errorCode = errorCode,
            message = message,
            featureId = featureId,
            bytes = bytes,
            durationMillis = durationMillis,
        )
    )

    fun snapshot(): List<DiagnosticEvent> = _events.value

    fun clear() {
        synchronized(lock) { _events.value = emptyList() }
    }

    /** Most recent event that was recorded as a failure or at ERROR severity. */
    fun lastFailure(): DiagnosticEvent? =
        _events.value.lastOrNull { it.outcome == Outcome.FAILURE || it.severity == Severity.ERROR }

    companion object {
        const val DEFAULT_CAPACITY = 500
    }
}
