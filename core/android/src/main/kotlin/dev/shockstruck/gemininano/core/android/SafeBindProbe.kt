package dev.shockstruck.gemininano.core.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import dev.shockstruck.gemininano.model.ComponentFacts
import dev.shockstruck.gemininano.model.ComponentKind
import dev.shockstruck.gemininano.model.PcsBindOutcome
import dev.shockstruck.gemininano.model.PcsBindResult
import dev.shockstruck.gemininano.model.PcsBindSession
import dev.shockstruck.gemininano.model.PlatformPrincipals
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * One `bindService(BIND_AUTO_CREATE)` per fixed component with a three-second wait,
 * then `unbindService` exactly once. It never sends a binder transaction, never builds
 * an AIDL stub, never uses reflection and never calls a service method; only the
 * framework's own `ServiceConnection` callbacks are observed.
 */
class SafeBindProbe(
    private val context: Context,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {

    suspend fun probeAll(exported: List<ComponentFacts>): List<PcsBindResult> {
        val exportedServices = exported
            .filter { it.kind == ComponentKind.SERVICE && it.exported }
            .map { it.className }
            .toSet()
        return PcsSurfaceCatalog.BIND_SERVICES.map { serviceName ->
            if (serviceName !in exportedServices) {
                PcsBindResult(
                    componentName = serviceName,
                    outcome = PcsBindOutcome.NOT_EXPORTED,
                    exceptionMessage =
                        "PackageManager did not return this service as exported at runtime; no bind attempted.",
                )
            } else {
                probe(serviceName)
            }
        }
    }

    suspend fun probe(serviceName: String): PcsBindResult {
        val session = PcsBindSession(serviceName, SystemClock.elapsedRealtime())
        return suspendCoroutine { continuation ->
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
                    finish(
                        session.onServiceConnected(
                            interfaceDescriptor = service?.interfaceDescriptor,
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
                    session.onSecurityException(
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
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 3_000L
    }
}
