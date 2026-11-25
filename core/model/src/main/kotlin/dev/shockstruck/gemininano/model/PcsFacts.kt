package dev.shockstruck.gemininano.model

/** What a single safe `bindService` attempt observed. */
enum class PcsBindOutcome(val label: String) {
    BINDER_RETURNED("binder returned"),
    NULL_BINDING("null binding"),
    SECURITY_EXCEPTION("SecurityException"),
    BIND_REFUSED("bindService returned false"),
    TIMEOUT("timeout"),
    BINDING_DIED("binding died"),
    NOT_EXPORTED("not exported at runtime"),
}

data class PcsBindResult(
    val componentName: String,
    val outcome: PcsBindOutcome,
    val exceptionClass: String? = null,
    val exceptionMessage: String? = null,
    val interfaceDescriptor: String? = null,
    val elapsedMillis: Long = 0L,
    val serviceDisconnected: Boolean = false,
    val bindingDied: Boolean = false,
    val unbindCalls: Int = 0,
)

/** The outcome of resolving the PCS settings activity for a one-tap launch. */
data class PcsSettingsResolution(
    val resolved: Boolean,
    val activityName: String?,
    val detail: String,
)

/** Static facts about one PCS component, always read through `PackageManager`. */
data class PcsSurface(
    val packageVisible: Boolean,
    val exportedComponents: List<ComponentFacts>,
    val settings: PcsSettingsResolution,
) {
    val visibilityLimited: Boolean get() = !packageVisible
}

/**
 * Pure state machine for one bind attempt. The Android glue calls these methods from
 * the framework callbacks; the model owns the outcome mapping and the exactly-once
 * unbind bookkeeping, and never performs a binder transaction.
 */
class PcsBindSession(
    val componentName: String,
    private val startedAtMillis: Long,
) {
    private var unbindRequired = false
    private var completed = false
    private var serviceDisconnected = false
    private var bindingDied = false

    var unbindCalls: Int = 0
        private set

    fun onBindRefused(nowMillis: Long): PcsBindResult? =
        complete(
            PcsBindResult(
                componentName = componentName,
                outcome = PcsBindOutcome.BIND_REFUSED,
                elapsedMillis = elapsed(nowMillis),
            ),
        )

    /** The system accepted the bind; an unbind is now required exactly once. */
    fun onBindAccepted() {
        if (!completed) unbindRequired = true
    }

    fun onSecurityException(
        exceptionClass: String,
        exceptionMessage: String?,
        nowMillis: Long,
    ): PcsBindResult? =
        complete(
            PcsBindResult(
                componentName = componentName,
                outcome = PcsBindOutcome.SECURITY_EXCEPTION,
                exceptionClass = exceptionClass,
                exceptionMessage = exceptionMessage,
                elapsedMillis = elapsed(nowMillis),
                serviceDisconnected = serviceDisconnected,
            ),
        )

    fun onServiceConnected(
        interfaceDescriptor: String?,
        nowMillis: Long,
    ): PcsBindResult? =
        complete(
            PcsBindResult(
                componentName = componentName,
                outcome = if (interfaceDescriptor == null) {
                    PcsBindOutcome.NULL_BINDING
                } else {
                    PcsBindOutcome.BINDER_RETURNED
                },
                interfaceDescriptor = interfaceDescriptor,
                elapsedMillis = elapsed(nowMillis),
                serviceDisconnected = serviceDisconnected,
            ),
        )

    fun onNullBinding(nowMillis: Long): PcsBindResult? =
        complete(
            PcsBindResult(
                componentName = componentName,
                outcome = PcsBindOutcome.NULL_BINDING,
                elapsedMillis = elapsed(nowMillis),
                serviceDisconnected = serviceDisconnected,
            ),
        )

    fun onTimeout(nowMillis: Long): PcsBindResult? =
        complete(
            PcsBindResult(
                componentName = componentName,
                outcome = PcsBindOutcome.TIMEOUT,
                elapsedMillis = elapsed(nowMillis),
                serviceDisconnected = serviceDisconnected,
            ),
        )

    fun onBindingDied(nowMillis: Long): PcsBindResult? {
        bindingDied = true
        return complete(
            PcsBindResult(
                componentName = componentName,
                outcome = PcsBindOutcome.BINDING_DIED,
                elapsedMillis = elapsed(nowMillis),
                serviceDisconnected = serviceDisconnected,
                bindingDied = true,
            ),
        )
    }

    fun onServiceDisconnected() {
        serviceDisconnected = true
    }

    fun requiresUnbind(): Boolean = unbindRequired && unbindCalls == 0

    /** Counted at most once, mirroring the one `unbindService` per accepted bind. */
    fun onUnbindCalled() {
        if (unbindRequired && unbindCalls == 0) unbindCalls++
    }

    private fun complete(result: PcsBindResult): PcsBindResult? {
        if (completed) return null
        completed = true
        return result.copy(bindingDied = bindingDied || result.bindingDied)
    }

    private fun elapsed(nowMillis: Long): Long =
        (nowMillis - startedAtMillis).coerceAtLeast(0L)
}

/** The exact feature label the Provisioning Diagnostics screen must show for 622. */
object AicoreFeatureLabels {
    const val FEATURE_622 =
        "622 — AICore AIDL constant SAPI_SUMMARIZATION_EN_2 (decompiled source, not a model); model unknown."
}
