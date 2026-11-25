package dev.shockstruck.gemininano.model

/**
 * One known PCS service role. [canonicalName] is the runtime name observed on the
 * current build; [aliases] keep the open-source names we already knew.
 */
data class PcsBindServiceDefinition(
    val role: PcsBindRole,
    val displayName: String,
    val canonicalName: String,
    val aliases: List<String> = emptyList(),
) {
    val allNames: List<String> get() = listOf(canonicalName) + aliases
}

enum class PcsBindRole {
    GRPC,
    GENAI_INFERENCE,
    SUPER_ICON,
    CONVERSATION_UPDATE,
    CONVERSATION_LISTENER,
    FEEDBACK_HTTP_CLIENT,
}

enum class PcsBindEligibility(val label: String) {
    ELIGIBLE("exported, effectively enabled, no required permission"),
    NOT_DECLARED("not declared by the installed package"),
    NOT_EXPORTED("declared, not exported"),
    NOT_ENABLED("declared, not effectively enabled"),
    ENABLED_UNKNOWN("declared, enabled state unknown"),
    REQUIRES_PERMISSION("declared, requires a permission"),
    NOT_READ("components not read"),
}

/** One planned bind: a known role, or an extra runtime-exported service. */
data class PcsBindCandidate(
    val definition: PcsBindServiceDefinition?,
    val className: String,
    val declaredName: String? = null,
    val requiredPermission: String? = null,
    val eligibility: PcsBindEligibility,
) {
    val displayName: String
        get() = definition?.displayName ?: className.substringAfterLast('.')

    val roleLabel: String? get() = definition?.role?.name?.lowercase()

    fun projectedResult(): PcsBindResult = PcsBindResult(
        componentName = declaredName ?: className,
        outcome = when (eligibility) {
            PcsBindEligibility.ELIGIBLE -> PcsBindOutcome.NOT_RUN
            PcsBindEligibility.NOT_DECLARED -> PcsBindOutcome.NOT_DECLARED
            PcsBindEligibility.NOT_EXPORTED -> PcsBindOutcome.NOT_EXPORTED
            PcsBindEligibility.NOT_ENABLED -> PcsBindOutcome.NOT_ENABLED
            PcsBindEligibility.ENABLED_UNKNOWN -> PcsBindOutcome.ENABLED_UNKNOWN
            PcsBindEligibility.REQUIRES_PERMISSION -> PcsBindOutcome.REQUIRES_PERMISSION
            PcsBindEligibility.NOT_READ -> PcsBindOutcome.NOT_READ
        },
        role = roleLabel,
        declaredName = declaredName,
        requiredPermission = requiredPermission,
        exceptionMessage = eligibility.label.takeIf { eligibility != PcsBindEligibility.ELIGIBLE },
    )
}

data class PcsBindPlan(
    val componentsRead: Boolean,
    val candidates: List<PcsBindCandidate>,
) {
    val eligibleCount: Int
        get() = candidates.count { it.eligibility == PcsBindEligibility.ELIGIBLE }

    fun projectedResults(): List<PcsBindResult> = candidates.map { it.projectedResult() }
}

/**
 * Builds the bind plan from the installed package's own component facts only.
 *
 * The two runtime names observed on the current PCS build come first
 * (`AstreaGrpcService`, then `GenAiInferenceService`), the remaining known roles follow,
 * then any other service the package reports as exported, effectively enabled and
 * permission-free. The open-source names are aliases, not separate services.
 */
object PcsBindCatalog {

    const val ASTREA_GRPC = "com.google.android.apps.miphone.astrea.grpc.AstreaGrpcService"
    const val ASTREA_GENAI_INFERENCE =
        "com.google.android.apps.miphone.astrea.ai.service.GenAiInferenceService"
    const val ASTREA_SUPER_ICON =
        "com.google.android.apps.miphone.astrea.supericon.service.SuperIconRenderService"
    const val ASTREA_CONVERSATION_UPDATE =
        "com.google.android.apps.miphone.astrea.conversationid.service.ConversationIdUpdateService"
    const val ASTREA_CONVERSATION_LISTENER =
        "com.google.android.apps.miphone.astrea.conversationid.service.ConversationIdListenerService"
    const val ASTREA_FEEDBACK_HTTP_CLIENT =
        "com.google.android.apps.miphone.astrea.feedback.gateway.service.FeedbackHttpClientService"

    const val OPEN_SOURCE_GRPC = "com.google.android.apps.miphone.pcs.grpc.PcsGrpcService"
    const val OPEN_SOURCE_GRPC_ALT = "com.google.android.as.oss.grpc.PcsGrpcService"
    const val OPEN_SOURCE_GENAI_INFERENCE =
        "com.google.android.as.oss.ai.service.GenAiInferenceService"
    const val OPEN_SOURCE_SUPER_ICON =
        "com.google.android.as.oss.supericon.service.SuperIconRenderService"
    const val OPEN_SOURCE_CONVERSATION_UPDATE =
        "com.google.android.as.oss.conversationid.service.ConversationIdUpdateService"
    const val OPEN_SOURCE_CONVERSATION_LISTENER =
        "com.google.android.as.oss.conversationid.service.ConversationIdListenerService"
    const val OPEN_SOURCE_FEEDBACK_HTTP_CLIENT =
        "com.google.android.as.oss.feedback.gateway.service.FeedbackHttpClientService"

    val KNOWN: List<PcsBindServiceDefinition> = listOf(
        PcsBindServiceDefinition(
            role = PcsBindRole.GRPC,
            displayName = "Astrea gRPC",
            canonicalName = ASTREA_GRPC,
            aliases = listOf(OPEN_SOURCE_GRPC, OPEN_SOURCE_GRPC_ALT),
        ),
        PcsBindServiceDefinition(
            role = PcsBindRole.GENAI_INFERENCE,
            displayName = "GenAI inference",
            canonicalName = ASTREA_GENAI_INFERENCE,
            aliases = listOf(OPEN_SOURCE_GENAI_INFERENCE),
        ),
        PcsBindServiceDefinition(
            role = PcsBindRole.SUPER_ICON,
            displayName = "Super icon render",
            canonicalName = ASTREA_SUPER_ICON,
            aliases = listOf(OPEN_SOURCE_SUPER_ICON),
        ),
        PcsBindServiceDefinition(
            role = PcsBindRole.CONVERSATION_UPDATE,
            displayName = "Conversation id update",
            canonicalName = ASTREA_CONVERSATION_UPDATE,
            aliases = listOf(OPEN_SOURCE_CONVERSATION_UPDATE),
        ),
        PcsBindServiceDefinition(
            role = PcsBindRole.CONVERSATION_LISTENER,
            displayName = "Conversation id listener",
            canonicalName = ASTREA_CONVERSATION_LISTENER,
            aliases = listOf(OPEN_SOURCE_CONVERSATION_LISTENER),
        ),
        PcsBindServiceDefinition(
            role = PcsBindRole.FEEDBACK_HTTP_CLIENT,
            displayName = "Feedback HTTP client",
            canonicalName = ASTREA_FEEDBACK_HTTP_CLIENT,
            aliases = listOf(OPEN_SOURCE_FEEDBACK_HTTP_CLIENT),
        ),
    )

    private val knownNames: Set<String> = KNOWN.flatMap { it.allNames }.toSet()

    fun definitionFor(className: String): PcsBindServiceDefinition? =
        KNOWN.firstOrNull { className in it.allNames }

    fun plan(pcs: PackageFacts?): PcsBindPlan {
        val componentsRead = pcs?.componentsRead == true
        if (!componentsRead) {
            return PcsBindPlan(
                componentsRead = false,
                candidates = KNOWN.map { definition ->
                    PcsBindCandidate(
                        definition = definition,
                        className = definition.canonicalName,
                        eligibility = PcsBindEligibility.NOT_READ,
                    )
                },
            )
        }

        val services = pcs!!.components.filter {
            it.kind == ComponentKind.SERVICE && it.className in knownNames
        }
        val knownCandidates = KNOWN.map { definition ->
            val declared = definition.allNames.firstNotNullOfOrNull { name ->
                services.firstOrNull { it.className == name }
            }
            candidateFor(definition, declared)
        }

        val extras = pcs.components
            .filter { it.kind == ComponentKind.SERVICE && it.className !in knownNames }
            .filter { eligibilityOf(it) == PcsBindEligibility.ELIGIBLE }
            .sortedBy { it.className }
            .map { component ->
                PcsBindCandidate(
                    definition = null,
                    className = component.className,
                    declaredName = component.className,
                    requiredPermission = component.requiredPermission,
                    eligibility = PcsBindEligibility.ELIGIBLE,
                )
            }

        return PcsBindPlan(
            componentsRead = true,
            candidates = knownCandidates + extras,
        )
    }

    private fun candidateFor(
        definition: PcsBindServiceDefinition,
        declared: ComponentFacts?,
    ): PcsBindCandidate = PcsBindCandidate(
        definition = definition,
        className = definition.canonicalName,
        declaredName = declared?.className,
        requiredPermission = declared?.requiredPermission,
        eligibility = eligibilityOf(declared),
    )

    fun eligibilityOf(declared: ComponentFacts?): PcsBindEligibility = when {
        declared == null -> PcsBindEligibility.NOT_DECLARED
        !declared.exported -> PcsBindEligibility.NOT_EXPORTED
        declared.effectivelyEnabled == null -> PcsBindEligibility.ENABLED_UNKNOWN
        !declared.effectivelyEnabled -> PcsBindEligibility.NOT_ENABLED
        !declared.requiredPermission.isNullOrBlank() -> PcsBindEligibility.REQUIRES_PERMISSION
        else -> PcsBindEligibility.ELIGIBLE
    }
}

/** One known PCS activity, with its runtime name and the open-source alias. */
data class PcsActivityDefinition(
    val label: String,
    val canonicalName: String,
    val aliases: List<String> = emptyList(),
    val launchable: Boolean = true,
    val notLaunchableReason: String? = null,
) {
    val allNames: List<String> get() = listOf(canonicalName) + aliases
}

/**
 * Launch controls are built only from runtime component facts. The item-details
 * activity needs extras the app cannot know, so it is listed but never launched.
 */
object PcsActivityCatalog {

    const val SETTINGS = "com.google.android.apps.miphone.astrea.settings.ui.PcsSettingsActivity"
    const val NETWORK_USAGE_LOG =
        "com.google.android.apps.miphone.astrea.networkusage.ui.user.NetworkUsageLogActivity"
    const val NETWORK_USAGE_ITEM_DETAILS =
        "com.google.android.apps.miphone.astrea.networkusage.ui.user.NetworkUsageItemDetailsActivity"

    val KNOWN: List<PcsActivityDefinition> = listOf(
        PcsActivityDefinition(
            label = "PCS settings",
            canonicalName = SETTINGS,
            aliases = listOf("com.google.android.apps.miphone.pcs.settings.ui.PcsSettingsActivity"),
        ),
        PcsActivityDefinition(
            label = "Network usage log",
            canonicalName = NETWORK_USAGE_LOG,
            aliases = listOf("com.google.android.as.oss.networkusage.ui.user.NetworkUsageLogActivity"),
        ),
        PcsActivityDefinition(
            label = "Network usage item details",
            canonicalName = NETWORK_USAGE_ITEM_DETAILS,
            aliases = listOf(
                "com.google.android.as.oss.networkusage.ui.user.NetworkUsageItemDetailsActivity",
            ),
            launchable = false,
            notLaunchableReason =
                "requires extras that are not publicly known; no launch is attempted and none are guessed.",
        ),
    )

    fun targets(pcs: PackageFacts?): List<PcsActivityTarget> {
        val componentsRead = pcs?.componentsRead == true
        if (!componentsRead) {
            return KNOWN.map { definition ->
                PcsActivityTarget(
                    label = definition.label,
                    className = definition.canonicalName,
                    declared = false,
                    exported = false,
                    effectivelyEnabled = null,
                    requiredPermission = null,
                    launchable = false,
                    detail = "components not read; nothing can be said about this activity.",
                )
            }
        }

        val activities = pcs!!.components.filter { it.kind == ComponentKind.ACTIVITY }
        return KNOWN.map { definition ->
            val declared = definition.allNames.firstNotNullOfOrNull { name ->
                activities.firstOrNull { it.className == name }
            }
            targetFor(definition, declared)
        }
    }

    private fun targetFor(
        definition: PcsActivityDefinition,
        declared: ComponentFacts?,
    ): PcsActivityTarget {
        if (declared == null) {
            return PcsActivityTarget(
                label = definition.label,
                className = definition.canonicalName,
                declared = false,
                exported = false,
                effectivelyEnabled = null,
                requiredPermission = null,
                launchable = false,
                detail = "not declared by the installed package.",
            )
        }

        val facts = "exported=${declared.exported} · " +
            "effectiveEnabled=${declared.effectivelyEnabled ?: "unknown"} · " +
            "permission=${declared.requiredPermission ?: "none"}"
        val launchable = definition.launchable &&
            declared.exported &&
            declared.effectivelyEnabled == true &&
            declared.requiredPermission.isNullOrBlank()

        val detail = when {
            !definition.launchable -> definition.notLaunchableReason.orEmpty()
            !declared.exported -> "declared, not exported; no launch attempted ($facts)."
            declared.effectivelyEnabled == null ->
                "declared, effective enabled state unknown; no launch attempted ($facts)."

            declared.effectivelyEnabled == false ->
                "declared, not effectively enabled; no launch attempted ($facts)."

            !declared.requiredPermission.isNullOrBlank() ->
                "declared, requires ${declared.requiredPermission}; no launch attempted ($facts)."

            else -> "exported, effectively enabled and permission-free; explicit tap only ($facts)."
        }

        return PcsActivityTarget(
            label = definition.label,
            className = declared.className,
            declared = true,
            exported = declared.exported,
            effectivelyEnabled = declared.effectivelyEnabled,
            requiredPermission = declared.requiredPermission,
            launchable = launchable,
            detail = detail,
        )
    }
}

/** Maps the launcher's own throwable to the recorded outcome; pure and testable. */
object PcsLaunchOutcomeMapping {
    fun fromExceptionClass(className: String): PcsLaunchOutcome = when (className) {
        "android.content.ActivityNotFoundException" -> PcsLaunchOutcome.ACTIVITY_NOT_FOUND
        "java.lang.SecurityException" -> PcsLaunchOutcome.SECURITY_EXCEPTION
        else -> PcsLaunchOutcome.ERROR
    }
}

/** The filtered feature names and whether the platform read itself failed. */
data class SystemFeaturesFacts(
    val names: List<String>,
    val readErrorClass: String? = null,
    val readErrorMessage: String? = null,
) {
    val readErrorLabel: String?
        get() = readErrorClass?.let { "$it: ${readErrorMessage ?: "no detail"}" }
}

/** Pure filter for the system features relevant to the provisioning investigation. */
object SystemFeatureFilter {

    private val PHRASES = listOf(
        "virtualization",
        "privatecompute",
        "private_compute",
        "private-compute",
        "private compute",
    )

    fun matches(name: String): Boolean {
        val lower = name.lowercase()
        if (PHRASES.any { it in lower }) return true
        return lower.split(Regex("[^a-z0-9]+")).any { it == "ai" || it == "npu" }
    }

    fun filter(names: List<String>): List<String> = names.filter(::matches).sorted()
}
