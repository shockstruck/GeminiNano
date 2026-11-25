package dev.shockstruck.gemininano.core.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import dev.shockstruck.gemininano.model.PcsBindCandidate
import dev.shockstruck.gemininano.model.PcsBindEligibility
import dev.shockstruck.gemininano.model.PcsBindOutcome
import dev.shockstruck.gemininano.model.PcsBindPlan
import dev.shockstruck.gemininano.model.PcsBindResult
import dev.shockstruck.gemininano.model.PcsBindSession
import dev.shockstruck.gemininano.model.PlatformPrincipals
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * One `bindService(BIND_AUTO_CREATE)` per eligible service with a three-second wait,
 * then `unbindService` exactly once. It never sends a binder transaction, never builds
 * an AIDL stub, never uses reflection or `asInterface`, and never calls a service
 * method; only the framework's own `ServiceConnection` callbacks are observed.
 *
 * The bind is always this app's own attempt. A returned binder says nothing about
 * AICore's access to PCS.
 */
class SafeBindProbe(
    private val context: Context,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {

    /**
     * Runs the eligible binds in plan order once each. Non-eligible plan entries and
     * entries when components were not read produce their static result and are never
     * bound.
     */
    suspend fun probeAll(plan: PcsBindPlan): List<PcsBindResult> = plan.candidates.map { candidate ->
        if (candidate.eligibility == PcsBindEligibility.ELIGIBLE) {
            probe(candidate)
        } else {
            candidate.projectedResult()
        }
    }

    suspend fun probe(serviceName: String): PcsBindResult =
        probe(PcsBindCandidate(definition = null, className = serviceName, eligibility = PcsBindEligibility.ELIGIBLE))

    private suspend fun probe(candidate: PcsBindCandidate): PcsBindResult {
        val serviceName = candidate.declaredName ?: candidate.className
        val session = PcsBindSession(serviceName, SystemClock.elapsedRealtime())
        val result = suspendCoroutine { continuation ->
            val finished = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            var connection: ServiceConnection? = null
            var timeout: Runnable? = null

            fun finish(result: PcsBindResult?) {
                if (result == null) return
                if (!finished.compareAndSet(false, true)) return
                timeout?.let { handler.removeCallbacks(it) }
                val active = connection
                if (active != null && session.requiresUnbind()) {
                    runCatching { context.unbindService(active) }
                    session.onUnbindCalled()
                }
                continuation.resume(result.copy(unbindCalls = session.unbindCalls))
            }

            val serviceConnection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    if (service == null) {
                        finish(session.onNullBinding(SystemClock.elapsedRealtime()))
                        return
                    }
                    val descriptorStart = SystemClock.elapsedRealtime()
                    var descriptor: String? = null
                    var descriptorError: Throwable? = null
                    try {
                        descriptor = service.interfaceDescriptor
                    } catch (t: Throwable) {
                        descriptorError = t
                    }
                    val descriptorMillis = SystemClock.elapsedRealtime() - descriptorStart
                    val binderAlive = try {
                        service.isBinderAlive
                    } catch (t: Throwable) {
                        null
                    }
                    finish(
                        session.onBinderConnected(
                            interfaceDescriptor = descriptor,
                            descriptorReadMillis = descriptorMillis,
                            descriptorErrorClass = descriptorError?.javaClass?.name,
                            descriptorErrorMessage = descriptorError?.message,
                            binderAlive = binderAlive,
                            nowMillis = SystemClock.elapsedRealtime(),
                        ),
                    )
                }

                override fun onNullBinding(name: ComponentName?) {
                    finish(session.onNullBinding(SystemClock.elapsedRealtime()))
                }

                override fun onBindingDied(name: ComponentName?) {
                    finish(session.onBindingDied(SystemClock.elapsedRealtime()))
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    session.onServiceDisconnected()
                }
            }
            connection = serviceConnection

            val intent = Intent().setComponent(ComponentName(PlatformPrincipals.PCS, serviceName))
            val bound = try {
                context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            } catch (e: SecurityException) {
                finish(
                    session.onSecurityException(
                        exceptionClass = e.javaClass.name,
                        exceptionMessage = e.message,
                        nowMillis = SystemClock.elapsedRealtime(),
                    ),
                )
                false
            } catch (t: Throwable) {
                finish(
                    session.onBindException(
                        exceptionClass = t.javaClass.name,
                        exceptionMessage = t.message,
                        nowMillis = SystemClock.elapsedRealtime(),
                    ),
                )
                false
            }

            if (finished.get()) return@suspendCoroutine
            if (!bound) {
                finish(session.onBindRefused(SystemClock.elapsedRealtime()))
                return@suspendCoroutine
            }

            session.onBindAccepted()
            timeout = Runnable { finish(session.onTimeout(SystemClock.elapsedRealtime())) }
                .also { handler.postDelayed(it, timeoutMillis) }
        }
        return result.copy(
            role = candidate.roleLabel,
            declaredName = serviceName,
            requiredPermission = candidate.requiredPermission,
        )
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 3_000L
    }
}
