package dev.shockstruck.gemininano.core.android

import android.content.Context
import android.os.Looper
import dev.shockstruck.gemininano.model.ManifestFactsReader
import dev.shockstruck.gemininano.model.ManifestReadLimits
import dev.shockstruck.gemininano.model.ManifestReadOutcome
import dev.shockstruck.gemininano.model.PlatformPrincipals

/**
 * Reads the installed APK manifests of AICore, PCS and the Android System Intelligence
 * package as plain declared facts.
 *
 * Every read opens files, so it is blocking and must run off the main thread; this class
 * throws [IllegalStateException] when called on it. The APK's own manifest is the only
 * input: the resulting facts are not proof of a runtime UID or of how the platform
 * installed the package.
 */
class InstalledManifestReader(
    context: Context,
    limits: ManifestReadLimits = ManifestReadLimits(),
) {

    private val applicationContext = context.applicationContext ?: context
    private val sources = InstalledManifestSources(applicationContext.packageManager, limits)
    private val reader = ManifestFactsReader(limits)

    fun read(packageName: String): ManifestReadOutcome {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "Installed manifest reads must not run on the main thread"
        }
        return reader.read(packageName, sources)
    }

    /** AICore (current and legacy package names), PCS and ASI. */
    fun readPlatformPrincipals(): Map<String, ManifestReadOutcome> =
        PLATFORM_PRINCIPALS.associateWith(::read)

    companion object {
        val PLATFORM_PRINCIPALS: List<String> = listOf(
            PlatformPrincipals.AICORE,
            PlatformPrincipals.AICORE_LEGACY,
            PlatformPrincipals.PCS,
            PlatformPrincipals.ASI,
        )
    }
}
