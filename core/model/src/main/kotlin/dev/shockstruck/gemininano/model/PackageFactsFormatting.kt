package dev.shockstruck.gemininano.model

/**
 * Rendering helpers for package facts. Kept in `:core:model` so the exact strings the
 * UI shows are covered by JVM unit tests (including the visibility-limit wording).
 */
object PackageFactsFormatting {

    const val UNKNOWN = "unknown"
    const val NOT_VISIBLE = "not visible (not installed, or hidden by package visibility)"
    const val NONE = "none"

    fun presenceLabel(facts: PackageFacts): String =
        if (facts.visible) "installed" else NOT_VISIBLE

    fun versionLabel(facts: PackageFacts): String {
        if (!facts.visible) return NOT_VISIBLE
        val name = facts.versionName ?: UNKNOWN
        val code = facts.versionCode?.toString() ?: UNKNOWN
        return "$name (versionCode $code)"
    }

    fun installerLabel(facts: PackageFacts): String =
        if (!facts.visible) NOT_VISIBLE else facts.installerPackage ?: UNKNOWN

    fun signingLabel(facts: PackageFacts): String = when {
        !facts.visible -> NOT_VISIBLE
        facts.signingSha256.isEmpty() -> UNKNOWN
        else -> facts.signingSha256.joinToString(", ")
    }

    fun sdkLabel(facts: PackageFacts): String = when {
        !facts.visible -> NOT_VISIBLE
        else -> "minSdk ${facts.minSdk?.toString() ?: UNKNOWN} · targetSdk ${facts.targetSdk?.toString() ?: UNKNOWN}"
    }

    fun enabledLabel(facts: PackageFacts): String = when {
        !facts.visible -> NOT_VISIBLE
        facts.enabled == null -> UNKNOWN
        facts.enabled -> "enabled"
        else -> "disabled"
    }

    fun requestedPermissionsLabel(facts: PackageFacts): String = when {
        !facts.visible -> NOT_VISIBLE
        facts.requestedPermissions.isEmpty() -> NONE
        else -> facts.requestedPermissions.size.toString()
    }

    fun renderPermissionGrant(grant: PermissionGrantFacts): String =
        "${grant.permission}: ${grant.state.label}"

    fun renderComponent(component: ComponentFacts): String {
        val parts = mutableListOf<String>()
        parts += component.kind.label
        parts += component.className
        parts += "exported=${component.exported}"
        parts += "enabled=${component.enabled}"
        parts += "permission=${component.requiredPermission ?: NONE}"
        if (component.authorities.isNotEmpty()) {
            parts += "authorities=${component.authorities.joinToString(",")}"
        }
        if (component.intentActions.isNotEmpty()) {
            parts += "actions=${component.intentActions.joinToString(",")}"
        }
        if (component.metaDataKeys.isNotEmpty()) {
            parts += "metadata=${component.metaDataKeys.joinToString(",")}"
        }
        return parts.joinToString(" · ")
    }

    fun renderMetadata(entry: MetadataFacts): String =
        "${entry.key} (${entry.type}) = ${entry.value ?: "…"}"

    fun summaryLine(facts: PackageFacts): String =
        "${facts.packageName}: ${presenceLabel(facts)}; version ${versionLabel(facts)}"
}
