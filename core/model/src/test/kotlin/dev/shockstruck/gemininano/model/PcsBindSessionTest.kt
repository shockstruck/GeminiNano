package dev.shockstruck.gemininano.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcsBindSessionTest {

    private val component = "com.google.android.apps.miphone.pcs.grpc.PcsGrpcService"

    private fun session() = PcsBindSession(component, startedAtMillis = 1_000L)

    @Test
    fun securityExceptionIsReportedAndNeedsNoUnbind() {
        val session = session()
        val result = session.onSecurityException(
            exceptionClass = "java.lang.SecurityException",
            exceptionMessage = "not allowed to bind",
            nowMillis = 1_050L,
        )!!
        assertEquals(PcsBindOutcome.SECURITY_EXCEPTION, result.outcome)
        assertEquals("java.lang.SecurityException", result.exceptionClass)
        assertEquals("not allowed to bind", result.exceptionMessage)
        assertEquals(50L, result.elapsedMillis)
        assertFalse(session.requiresUnbind())
        assertEquals(0, session.unbindCalls)
    }

    @Test
    fun nullBindingRequiresExactlyOneUnbind() {
        val session = session()
        session.onBindAccepted()
        val result = session.onNullBinding(nowMillis = 1_100L)!!
        assertEquals(PcsBindOutcome.NULL_BINDING, result.outcome)
        assertTrue(session.requiresUnbind())
        session.onUnbindCalled()
        assertEquals(1, session.unbindCalls)
        assertFalse(session.requiresUnbind())
        session.onUnbindCalled()
        assertEquals("unbind is never counted twice", 1, session.unbindCalls)
    }

    @Test
    fun binderReturnedPreservesTheInterfaceDescriptor() {
        val session = session()
        session.onBindAccepted()
        val result = session.onServiceConnected("com.example.IPcs", nowMillis = 1_075L)!!
        assertEquals(PcsBindOutcome.BINDER_RETURNED, result.outcome)
        assertEquals("com.example.IPcs", result.interfaceDescriptor)
        assertTrue(session.requiresUnbind())
    }

    @Test
    fun anEmptyDescriptorIsKeptEmptyAndTheBinderIsAlive() {
        val session = session()
        session.onBindAccepted()
        val result = session.onBinderConnected(
            interfaceDescriptor = "",
            descriptorReadMillis = 7L,
            descriptorErrorClass = null,
            descriptorErrorMessage = null,
            binderAlive = true,
            nowMillis = 1_080L,
        )!!
        assertEquals(PcsBindOutcome.BINDER_RETURNED, result.outcome)
        assertEquals("", result.interfaceDescriptor)
        assertEquals(true, result.binderAlive)
        assertEquals(7L, result.descriptorReadMillis)
        assertEquals(80L, result.elapsedMillis)
    }

    @Test
    fun aFailedDescriptorReadKeepsTheBinderOutcomeAndRecordsItsOwnError() {
        val session = session()
        session.onBindAccepted()
        val result = session.onBinderConnected(
            interfaceDescriptor = null,
            descriptorReadMillis = 4L,
            descriptorErrorClass = "java.lang.IllegalStateException",
            descriptorErrorMessage = "descriptor unavailable",
            binderAlive = false,
            nowMillis = 1_090L,
        )!!
        assertEquals(PcsBindOutcome.BINDER_RETURNED, result.outcome)
        assertEquals(null, result.interfaceDescriptor)
        assertEquals("java.lang.IllegalStateException", result.descriptorErrorClass)
        assertEquals("descriptor unavailable", result.descriptorErrorMessage)
        assertEquals(false, result.binderAlive)
    }

    @Test
    fun aBinderWithNoDescriptorIsStillABinderNotANullBinding() {
        val session = session()
        session.onBindAccepted()
        val result = session.onBinderConnected(
            interfaceDescriptor = null,
            descriptorReadMillis = 0L,
            descriptorErrorClass = null,
            descriptorErrorMessage = null,
            binderAlive = null,
            nowMillis = 1_100L,
        )
        // The binder-present variant never maps null to NULL_BINDING; the callback for
        // a truly absent binder uses onNullBinding.
        assertEquals(PcsBindOutcome.BINDER_RETURNED, result!!.outcome)
    }

    @Test
    fun timeoutIsReportedAndStillUnbinds() {
        val session = session()
        session.onBindAccepted()
        val result = session.onTimeout(nowMillis = 4_000L)!!
        assertEquals(PcsBindOutcome.TIMEOUT, result.outcome)
        assertEquals(3_000L, result.elapsedMillis)
        assertTrue(session.requiresUnbind())
    }

    @Test
    fun bindingDiedIsReportedAndUnbinds() {
        val session = session()
        session.onBindAccepted()
        val result = session.onBindingDied(nowMillis = 1_200L)!!
        assertEquals(PcsBindOutcome.BINDING_DIED, result.outcome)
        assertTrue(result.bindingDied)
        assertTrue(session.requiresUnbind())
    }

    @Test
    fun bindRefusalNeedsNoUnbind() {
        val session = session()
        val result = session.onBindRefused(nowMillis = 1_010L)!!
        assertEquals(PcsBindOutcome.BIND_REFUSED, result.outcome)
        assertFalse(session.requiresUnbind())
    }

    @Test
    fun lateCallbackAfterCompletionIsIgnored() {
        val session = session()
        session.onBindAccepted()
        val first = session.onServiceConnected("com.example.IPcs", nowMillis = 1_050L)!!
        val late = session.onTimeout(nowMillis = 2_000L)
        assertNull(late)
        assertEquals(PcsBindOutcome.BINDER_RETURNED, first.outcome)
    }

    @Test
    fun serviceDisconnectIsRecordedOnTheNextResult() {
        val session = session()
        session.onBindAccepted()
        session.onServiceDisconnected()
        val result = session.onServiceConnected(null, nowMillis = 1_060L)!!
        assertEquals(PcsBindOutcome.NULL_BINDING, result.outcome)
        assertTrue(result.serviceDisconnected)
    }
}
