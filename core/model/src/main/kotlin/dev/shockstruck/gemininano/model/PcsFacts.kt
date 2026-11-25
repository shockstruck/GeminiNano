package dev.shockstruck.gemininano.model

/** What a single safe `bindService` attempt observed, or why none was attempted. */
enum class PcsBindOutcome(val label: String, val attempted: Boolean) {
    BINDER_RETURNED("binder returned", attempted = true),
    NULL_BINDING("null binding", attempted = true),

    /** Only a real `SecurityException`, never another throwable. */
    SECURITY_EXCEPTION("SecurityException", attempted = true),

    /** `bindService` threw something that is not a `SecurityException`. */
    BIND_ERROR("bindService threw", attempted = true),

    BIND_REFUSED("bindService returned false", attempted = true),
    TIMEOUT("timeout", attempted = true),
    BINDING_DIED("binding died", attempted = true),

    /** Declared by the installed package, but not exported at runtime. */
    NOT_EXPORTED("declared, not exported", attempted = false),

    /** Declared, but the effective enabled state is false. */
    NOT_ENABLED("declared, not effectively enabled", attempted = false),

    /** Declared, but the public enabled-setting lookup failed: not proven enabled. */
    ENABLED_UNKNOWN("declared, enabled state unknown", attempted = false),

    /** Declared, but it requires a manifest-level binding permission. */
    REQUIRES_PERMISSION("declared, requires a permission", attempted = false),

    /** A known catalogue service the installed PCS package does not declare. */
    NOT_DECLARED("not declared by the installed package", attempted = false),

    /** Components were not read (reduced flag set), so nothing can be said about them. */
    NOT_READ("components not read", attempted = false),

    /** The planned bind exists but the user has not run the probe. */
    NOT_RUN("NOT RUN", attempted = false),
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
    val role: String? = null,
    val declaredName: String? = null,
    val requiredPermission: String? = null,
    val bindFlag: String = "BIND_AUTO_CREATE",
    val binderAlive: Boolean? = null,
    /** The descriptor read's own duration; when [descriptorTimedOut] it is the pending wait. */
    val descriptorReadMillis: Long? = null,
    val descriptorErrorClass: String? = null,
    val descriptorErrorMessage: String? = null,
    /** True when the binder arrived but its descriptor read was still pending at the timeout. */
    val descriptorTimedOut: Boolean = false,
) {
    /** "null", "alive", "not alive" or "unknown"; never infers a binder from a label. */
    val binderLabel: String
        get() = when {
            outcome == PcsBindOutcome.NULL_BINDING -> "null (no binder)"
            binderAlive == true -> "alive"
            binderAlive == false -> "not alive"
            else -> "unknown"
        }
}

/** The outcome of resolving the PCS settings activity for a one-tap launch. */
data class PcsSettingsResolution(
    val resolved: Boolean,
    val activityName: String?,
    val detail: String,
)

/** One launch control candidate, built from runtime component facts only. */
data class PcsActivityTarget(
    val label: String,
    val className: String,
    val declared: Boolean,
    val exported: Boolean,
    val effectivelyEnabled: Boolean?,
    val requiredPermission: String?,
    /** True only when the definition allows it, it is exported, enabled and permission-free. */
    val launchable: Boolean,
    /** Why it is not launchable, or the explicit-tap-only note when it is. */
    val detail: String,
)

enum class PcsLaunchOutcome(val label: String) {
    LAUNCHED("launched"),
    NOT_ATTEMPTED("not attempted"),
    NOT_RESOLVED("not resolved"),
    ACTIVITY_NOT_FOUND("ActivityNotFoundException"),
    SECURITY_EXCEPTION("SecurityException"),
    ERROR("other error"),
}

data class PcsActivityLaunchResult(
    val label: String,
    val className: String,
    val resolved: Boolean,
    val outcome: PcsLaunchOutcome,
    val exceptionClass: String? = null,
    val exceptionMessage: String? = null,
    val elapsedMillis: Long? = null,
) {
    val outcomeLabel: String get() = outcome.label
}

/** Static facts about one PCS component, always read through `PackageManager`. */
data class PcsSurface(
    val packageVisible: Boolean,
    val exportedComponents: List<ComponentFacts>,
    val settings: PcsSettingsResolution,
    /** False when the read never returned component lists; an empty list is not proof. */
    val componentsRead: Boolean = true,
    val componentsReadDetail: String? = null,
    val launchTargets: List<PcsActivityTarget> = emptyList(),
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
    private var binderPresent = false
    private var binderArrivalMillis: Long? = null

    var unbindCalls: Int = 0
        private set

    @Synchronized
    fun onBindRefused(nowMillis: Long): PcsBindResult? =
        complete(
            PcsBindResult(
                componentName = componentName,
                outcome = PcsBindOutcome.BIND_REFUSED,
                elapsedMillis = elapsed(nowMillis),
            ),
        )

    /** The system accepted the bind; an unbind is now required exactly once. */
    @Synchronized
    fun onBindAccepted() {
        if (!completed) unbindRequired = true
    }

    /**
     * A binder arrived but its descriptor read has not completed. The read happens off
     * the main thread; the timeout may fire while it is still pending.
     */
    @Synchronized
    fun onBinderArrived(nowMillis: Long) {
        if (completed || binderPresent) return
        binderPresent = true
        binderArrivalMillis = nowMillis
    }

    @Synchronized
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

    /** Any non-`SecurityException` throwable keeps its own outcome and class. */
    @Synchronized
    fun onBindException(
        exceptionClass: String,
        exceptionMessage: String?,
        nowMillis: Long,
    ): PcsBindResult? =
        complete(
            PcsBindResult(
                componentName = componentName,
                outcome = PcsBindOutcome.BIND_ERROR,
                exceptionClass = exceptionClass,
                exceptionMessage = exceptionMessage,
                elapsedMillis = elapsed(nowMillis),
                serviceDisconnected = serviceDisconnected,
            ),
        )

    /**
     * A real binder arrived and its descriptor read finished. A failed read keeps
     * [PcsBindOutcome.BINDER_RETURNED] and records its own class, and an empty descriptor
     * is kept as the empty string.
     */
    @Synchronized
    fun onBinderConnected(
        interfaceDescriptor: String?,
        descriptorReadMillis: Long,
        descriptorErrorClass: String?,
        descriptorErrorMessage: String?,
        binderAlive: Boolean?,
        nowMillis: Long,
    ): PcsBindResult? =
        complete(
            PcsBindResult(
                componentName = componentName,
                outcome = PcsBindOutcome.BINDER_RETURNED,
                interfaceDescriptor = interfaceDescriptor,
                binderAlive = binderAlive,
                descriptorReadMillis = descriptorReadMillis,
                descriptorErrorClass = descriptorErrorClass,
                descriptorErrorMessage = descriptorErrorMessage,
                elapsedMillis = elapsed(nowMillis),
                serviceDisconnected = serviceDisconnected,
            ),
        )

    /** Compatibility helper for a descriptor-only callback; null means no binder. */
    @Synchronized
    fun onServiceConnected(
        interfaceDescriptor: String?,
        nowMillis: Long,
    ): PcsBindResult? = if (interfaceDescriptor == null) {
        complete(
            PcsBindResult(
                componentName = componentName,
                outcome = PcsBindOutcome.NULL_BINDING,
                elapsedMillis = elapsed(nowMillis),
                serviceDisconnected = serviceDisconnected,
            ),
        )
    } else {
        onBinderArrived(nowMillis)
        onBinderConnected(
            interfaceDescriptor = interfaceDescriptor,
            descriptorReadMillis = 0L,
            descriptorErrorClass = null,
            descriptorErrorMessage = null,
            binderAlive = null,
            nowMillis = nowMillis,
        )
    }

    @Synchronized
    fun onNullBinding(nowMillis: Long): PcsBindResult? =
        complete(
            PcsBindResult(
                componentName = componentName,
                outcome = PcsBindOutcome.NULL_BINDING,
                elapsedMillis = elapsed(nowMillis),
                serviceDisconnected = serviceDisconnected,
            ),
        )

    /**
     * The 3-second window elapsed. If a binder arrived and its descriptor read is still
     * pending, the binder outcome is kept and the read is recorded as timed out; only a
     * bind with no binder at all is reported as [PcsBindOutcome.TIMEOUT].
     */
    @Synchronized
    fun onTimeout(nowMillis: Long): PcsBindResult? {
        if (binderPresent) {
            return complete(
                PcsBindResult(
                    componentName = componentName,
                    outcome = PcsBindOutcome.BINDER_RETURNED,
                    elapsedMillis = elapsed(nowMillis),
                    serviceDisconnected = serviceDisconnected,
                    descriptorTimedOut = true,
                    descriptorReadMillis = binderArrivalMillis?.let { nowMillis - it },
                ),
            )
        }
        return complete(
            PcsBindResult(
                componentName = componentName,
                outcome = PcsBindOutcome.TIMEOUT,
                elapsedMillis = elapsed(nowMillis),
                serviceDisconnected = serviceDisconnected,
            ),
        )
    }

    @Synchronized
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

    @Synchronized
    fun onServiceDisconnected() {
        serviceDisconnected = true
    }

    @Synchronized
    fun requiresUnbind(): Boolean = unbindRequired && unbindCalls == 0

    /** Counted at most once, mirroring the one `unbindService` per accepted bind. */
    @Synchronized
    fun onUnbindCalled() {
        if (unbindRequired && unbindCalls == 0) unbindCalls++
    }

    @Synchronized
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
