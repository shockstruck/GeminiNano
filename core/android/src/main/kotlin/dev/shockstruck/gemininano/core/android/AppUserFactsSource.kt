package dev.shockstruck.gemininano.core.android

import android.content.Context
import android.os.UserManager
import dev.shockstruck.gemininano.model.AppUserFacts

/**
 * Reads whether this process runs in the system (owner) user, through the public
 * `UserManager.isSystemUser()` call. Read-only: nothing is changed and no other user is
 * inspected. A failure is reported as a read error, never as "not the system user".
 */
class AppUserFactsSource(context: Context) {

    private val applicationContext = context.applicationContext ?: context

    fun read(): AppUserFacts = try {
        val userManager =
            applicationContext.getSystemService(Context.USER_SERVICE) as? UserManager
        if (userManager == null) {
            AppUserFacts(
                isSystemUser = null,
                readErrorClass = "MissingUserManager",
                readErrorMessage = "UserManager is not available",
            )
        } else {
            AppUserFacts(isSystemUser = userManager.isSystemUser)
        }
    } catch (t: Throwable) {
        AppUserFacts(
            isSystemUser = null,
            readErrorClass = t.javaClass.name,
            readErrorMessage = t.message ?: "(no message)",
        )
    }
}
