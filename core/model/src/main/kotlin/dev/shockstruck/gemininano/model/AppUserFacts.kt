package dev.shockstruck.gemininano.model

/**
 * Whether this app's process runs in the system (owner) user, as
 * `UserManager.isSystemUser()` reports it.
 *
 * [isSystemUser] is null when the read itself failed; a failed read is never rendered or
 * used as "not the system user". The fact describes this app's user only; it says
 * nothing about which user another package runs in unless both are in the same profile.
 */
data class AppUserFacts(
    val isSystemUser: Boolean?,
    val readErrorClass: String? = null,
    val readErrorMessage: String? = null,
) {
    val readErrorLabel: String?
        get() = readErrorClass?.let { "$it: ${readErrorMessage ?: "no detail"}" }

    val label: String
        get() = when {
            readErrorLabel != null -> "not read (${readErrorLabel})"
            isSystemUser == true -> "system (owner) user"
            isSystemUser == false -> "not the system (owner) user"
            else -> "not read"
        }
}
