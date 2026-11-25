package dev.shockstruck.gemininano.model

/** Where a bind row's observation came from, in the report and on screen. */
enum class PcsBindProvenance(val label: String) {
    CURRENT_RUN("current run"),
    EARLIER_RUN("earlier run"),
    NOT_RUN("not run"),
}

/**
 * One report row. Binding, RPC authorization and provisioning are three separate facts:
 * the probe observes the first, and the last two are never claimed from a bind.
 */
data class PcsBindRow(
    val componentName: String,
    val role: String?,
    val result: PcsBindResult,
    val provenance: PcsBindProvenance,
    /** Wall-clock time of the observation: this run's probe, or the stored earlier one. */
    val observedAtEpochMillis: Long? = null,
    val appVersionName: String? = null,
    val appVersionCode: Long? = null,
    val storedPcsVersionCode: Long? = null,
    val currentPcsVersionCode: Long? = null,
    /**
     * The current plan's own fact when it says the service is not declared, not exported
     * or not enabled. A stored observation from another PCS versionCode never hides it.
     */
    val currentPlanResult: PcsBindResult? = null,
) {
    /** Binding was observed; RPC authorization and provisioning were not tested. */
    val bindingFact: String get() = PcsBindReport.bindingFact(result)

    val rpcAuthorizationFact: String get() = PcsBindReport.RPC_AUTHORIZATION_NOT_TESTED

    val provisioningFact: String get() = PcsBindReport.PROVISIONING_NOT_TESTED

    /**
     * A stored result from a different PCS version is labelled and never classifier
     * input. When either version is unknown the match cannot be shown, so a stored
     * observation is not used as classifier input either; a current-run observation and
     * the current plan facts are.
     */
    val classifierEligible: Boolean
        get() = when (provenance) {
            PcsBindProvenance.CURRENT_RUN -> true
            PcsBindProvenance.NOT_RUN -> true
            PcsBindProvenance.EARLIER_RUN ->
                storedPcsVersionCode != null && currentPcsVersionCode == storedPcsVersionCode
        }

    val pcsVersionMismatch: Boolean
        get() = provenance == PcsBindProvenance.EARLIER_RUN &&
            storedPcsVersionCode != null &&
            currentPcsVersionCode != null &&
            storedPcsVersionCode != currentPcsVersionCode

    val pcsVersionLabel: String?
        get() = when (provenance) {
            PcsBindProvenance.CURRENT_RUN ->
                currentPcsVersionCode?.let { "PCS versionCode $it" }

            PcsBindProvenance.NOT_RUN -> null

            PcsBindProvenance.EARLIER_RUN -> when {
                storedPcsVersionCode == null ->
                    "PCS versionCode at observation unknown"

                currentPcsVersionCode == null ->
                    "stored for PCS versionCode $storedPcsVersionCode; current PCS versionCode unknown"

                pcsVersionMismatch ->
                    "stored for PCS versionCode $storedPcsVersionCode; current is " +
                        "$currentPcsVersionCode; not used as classifier input"

                else -> "stored for PCS versionCode $storedPcsVersionCode (matches current)"
            }
        }
}

/**
 * Builds the persistent report rows from the current plan, any observation made in this
 * run and the stored history. It never turns an unattempted row into an observation.
 */
object PcsBindReport {

    const val RPC_AUTHORIZATION_NOT_TESTED = "not tested: no transaction is sent"
    const val PROVISIONING_NOT_TESTED = "not tested"

    fun rows(
        plan: PcsBindPlan?,
        currentResults: List<PcsBindResult> = emptyList(),
        history: PcsBindHistory = PcsBindHistory(),
        currentPcsVersionCode: Long? = null,
        currentRunAtEpochMillis: Long? = null,
        appVersionName: String? = null,
        appVersionCode: Long? = null,
    ): List<PcsBindRow> {
        val currentAttempts = currentResults
            .filter { it.outcome.attempted }
            .associateBy { it.componentName }
        val candidates = plan?.candidates.orEmpty()
        val plannedNames = candidates.map { it.declaredName ?: it.className }.toSet()

        val plannedRows = candidates.map { candidate ->
            val projected = candidate.projectedResult()
            val current = currentAttempts[projected.componentName]
            val stored = history.latestFor(projected.componentName)
            val currentPlanFact = projected.takeIf { it.outcome in CURRENT_PLAN_OUTCOMES }
            when {
                current != null -> PcsBindRow(
                    componentName = current.componentName,
                    role = current.role,
                    result = current,
                    provenance = PcsBindProvenance.CURRENT_RUN,
                    observedAtEpochMillis = currentRunAtEpochMillis,
                    appVersionName = appVersionName,
                    appVersionCode = appVersionCode,
                    storedPcsVersionCode = currentPcsVersionCode,
                    currentPcsVersionCode = currentPcsVersionCode,
                )

                currentPlanFact != null -> PcsBindRow(
                    componentName = projected.componentName,
                    role = projected.role,
                    result = projected,
                    provenance = PcsBindProvenance.NOT_RUN,
                    currentPcsVersionCode = currentPcsVersionCode,
                    currentPlanResult = projected,
                )

                stored != null -> earlierRow(stored, currentPcsVersionCode)

                else -> PcsBindRow(
                    componentName = projected.componentName,
                    role = projected.role,
                    result = projected,
                    provenance = PcsBindProvenance.NOT_RUN,
                    currentPcsVersionCode = currentPcsVersionCode,
                )
            }
        }

        val storedOnlyRows = history.entries
            .filter { it.result.componentName !in plannedNames }
            .groupBy { it.result.componentName }
            .values
            .mapNotNull { perComponent ->
                perComponent.withIndex()
                    .maxWithOrNull(compareBy({ it.value.observedAtEpochMillis }, { it.index }))
                    ?.value
            }
            .sortedBy { it.result.componentName }
            .map { earlierRow(it, currentPcsVersionCode) }

        return plannedRows + storedOnlyRows
    }

    fun historyEntries(
        results: List<PcsBindResult>,
        observedAtEpochMillis: Long,
        appVersionName: String,
        appVersionCode: Long,
        pcsVersionCode: Long?,
    ): List<PcsBindHistoryEntry> = results
        .filter { it.outcome.attempted }
        .map { result ->
            PcsBindHistoryEntry(
                result = result,
                observedAtEpochMillis = observedAtEpochMillis,
                appVersionName = appVersionName,
                appVersionCode = appVersionCode,
                pcsVersionCode = pcsVersionCode,
            )
        }

    /** Only rows whose PCS version is current or provably unchanged feed the classifier. */
    fun classifierResults(rows: List<PcsBindRow>): List<PcsBindResult> =
        rows.mapNotNull { row ->
            row.currentPlanResult ?: row.result.takeIf { row.classifierEligible }
        }

    /**
     * The stored observations grouped per component, oldest first. The policy already
     * keeps at most [PcsBindHistoryPolicy.MAX_RESULTS_PER_COMPONENT] per component.
     */
    fun historyByComponent(
        entries: List<PcsBindHistoryEntry>,
    ): List<Pair<String, List<PcsBindHistoryEntry>>> =
        entries.groupBy { it.result.componentName }
            .toSortedMap()
            .map { (componentName, perComponent) ->
                componentName to perComponent.sortedBy { it.observedAtEpochMillis }
            }

    fun bindingFact(result: PcsBindResult): String = when {
        result.outcome.attempted -> observedBindingFact(result)
        result.outcome == PcsBindOutcome.NOT_RUN -> "not run"
        else -> "not run: ${result.outcome.label}"
    }

    private fun earlierRow(entry: PcsBindHistoryEntry, currentPcsVersionCode: Long?) =
        PcsBindRow(
            componentName = entry.result.componentName,
            role = entry.result.role,
            result = entry.result,
            provenance = PcsBindProvenance.EARLIER_RUN,
            observedAtEpochMillis = entry.observedAtEpochMillis,
            appVersionName = entry.appVersionName,
            appVersionCode = entry.appVersionCode,
            storedPcsVersionCode = entry.pcsVersionCode,
            currentPcsVersionCode = currentPcsVersionCode,
        )

    private fun observedBindingFact(result: PcsBindResult): String = when (result.outcome) {
        PcsBindOutcome.BINDER_RETURNED -> "binder returned (${descriptorFact(result)})"
        PcsBindOutcome.NULL_BINDING -> "null binding (no binder)"
        PcsBindOutcome.SECURITY_EXCEPTION -> "SecurityException" + detail(result)
        PcsBindOutcome.BIND_ERROR ->
            "bindService threw " + (result.exceptionClass ?: "an exception") + detail(result)

        PcsBindOutcome.BIND_REFUSED -> "bindService returned false"
        PcsBindOutcome.TIMEOUT -> "timeout: no callback within the 3-second window"
        PcsBindOutcome.BINDING_DIED -> "binding died"
        else -> result.outcome.label
    }

    private fun descriptorFact(result: PcsBindResult): String = when {
        result.descriptorTimedOut -> "descriptor read timed out"
        result.descriptorErrorClass != null ->
            "descriptor read failed: ${result.descriptorErrorClass}" +
                result.descriptorErrorMessage?.let { ": $it" }.orEmpty()

        result.interfaceDescriptor == null -> "descriptor null"
        result.interfaceDescriptor.isEmpty() -> "descriptor empty"
        else -> "descriptor \"${result.interfaceDescriptor}\""
    }

    private fun detail(result: PcsBindResult): String =
        result.exceptionMessage?.let { ": $it" }.orEmpty()

    /** Plan outcomes that are facts about the current installed package, not bind runs. */
    private val CURRENT_PLAN_OUTCOMES = setOf(
        PcsBindOutcome.NOT_DECLARED,
        PcsBindOutcome.NOT_EXPORTED,
        PcsBindOutcome.NOT_ENABLED,
    )
}

/** The pure order of one Run Full Diagnostics tap, so the bind always precedes the report. */
enum class FullDiagnosticsStep(val label: String) {
    COLLECT_EVIDENCE("collect evidence"),
    RUN_BIND_PROBE("run the PCS bind probe"),
    BUILD_REPORT("build the report"),
}

object PcsBindRunPolicy {

    const val OPTION_LABEL = "Include PCS bind probe"

    /** The bind step is inserted only by consent, and only when a service is eligible. */
    fun steps(includeBindProbe: Boolean, eligibleCount: Int): List<FullDiagnosticsStep> =
        if (shouldBind(includeBindProbe, eligibleCount)) {
            listOf(
                FullDiagnosticsStep.COLLECT_EVIDENCE,
                FullDiagnosticsStep.RUN_BIND_PROBE,
                FullDiagnosticsStep.BUILD_REPORT,
            )
        } else {
            listOf(FullDiagnosticsStep.COLLECT_EVIDENCE, FullDiagnosticsStep.BUILD_REPORT)
        }

    fun shouldBind(includeBindProbe: Boolean, eligibleCount: Int): Boolean =
        includeBindProbe && eligibleCount > 0
}
