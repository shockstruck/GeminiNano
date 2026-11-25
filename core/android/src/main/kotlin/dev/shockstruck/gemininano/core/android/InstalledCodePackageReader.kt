package dev.shockstruck.gemininano.core.android

import android.content.Context
import android.os.Looper
import dev.shockstruck.gemininano.model.InstalledCodeOutcome
import dev.shockstruck.gemininano.model.InstalledCodeReadLimits
import dev.shockstruck.gemininano.model.InstalledCodeReader
import dev.shockstruck.gemininano.model.PlatformPrincipals

/**
 * Reads the installed AICore and PCS code as marker, flag-default and asset facts.
 *
 * Every read opens and hashes APK files, so it is blocking and must run off the main
 * thread; this class throws [IllegalStateException] when called on it. The facts are
 * static properties of the installed APKs, not proof of how either package runs.
 */
class InstalledCodePackageReader(
    context: Context,
    limits: InstalledCodeReadLimits = InstalledCodeReadLimits(),
) {

    private val applicationContext = context.applicationContext ?: context
    private val sources = InstalledCodeSources(applicationContext.packageManager)
    private val reader = InstalledCodeReader(limits)

    fun read(packageName: String): InstalledCodeOutcome {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "Installed code reads must not run on the main thread"
        }
        return reader.read(packageName, sources)
    }

    /** The current and legacy AICore package names and PCS. */
    fun readPlatformPrincipals(): Map<String, InstalledCodeOutcome> =
        PLATFORM_PRINCIPALS.associateWith(::read)

    companion object {
        val PLATFORM_PRINCIPALS: List<String> = listOf(
            PlatformPrincipals.AICORE,
            PlatformPrincipals.AICORE_LEGACY,
            PlatformPrincipals.PCS,
        )
    }
}
