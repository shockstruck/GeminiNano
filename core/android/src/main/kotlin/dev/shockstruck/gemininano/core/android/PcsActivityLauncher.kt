package dev.shockstruck.gemininano.core.android

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import dev.shockstruck.gemininano.model.PcsActivityLaunchResult
import dev.shockstruck.gemininano.model.PcsActivityTarget
import dev.shockstruck.gemininano.model.PcsLaunchOutcome
import dev.shockstruck.gemininano.model.PlatformPrincipals

/**
 * Launches one PCS activity only from an explicit user tap. It records whether the
 * explicit component resolved, the launch outcome and its own exception; it reads
 * nothing back from the other app's screen.
 */
class PcsActivityLauncher(
    private val context: Context,
) {
    private val packageManager: PackageManager = context.packageManager

    fun launch(target: PcsActivityTarget): PcsActivityLaunchResult {
        if (!target.launchable) {
            return PcsActivityLaunchResult(
                label = target.label,
                className = target.className,
                resolved = false,
                outcome = PcsLaunchOutcome.NOT_ATTEMPTED,
                exceptionMessage = target.detail,
            )
        }

        val started = SystemClock.elapsedRealtime()
        val intent = Intent()
            .setComponent(ComponentName(PlatformPrincipals.PCS, target.className))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val resolved = try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                packageManager.resolveActivity(intent, 0)
            }
            info?.activityInfo?.name == target.className
        } catch (t: Throwable) {
            false
        }

        return try {
            context.startActivity(intent)
            PcsActivityLaunchResult(
                label = target.label,
                className = target.className,
                resolved = resolved,
                outcome = PcsLaunchOutcome.LAUNCHED,
                elapsedMillis = SystemClock.elapsedRealtime() - started,
            )
        } catch (e: ActivityNotFoundException) {
            failure(target, resolved, PcsLaunchOutcome.ACTIVITY_NOT_FOUND, e, started)
        } catch (e: SecurityException) {
            failure(target, resolved, PcsLaunchOutcome.SECURITY_EXCEPTION, e, started)
        } catch (t: Throwable) {
            failure(target, resolved, PcsLaunchOutcome.ERROR, t, started)
        }
    }

    private fun failure(
        target: PcsActivityTarget,
        resolved: Boolean,
        outcome: PcsLaunchOutcome,
        throwable: Throwable,
        startedAt: Long,
    ): PcsActivityLaunchResult = PcsActivityLaunchResult(
        label = target.label,
        className = target.className,
        resolved = resolved,
        outcome = outcome,
        exceptionClass = throwable.javaClass.name,
        exceptionMessage = throwable.message ?: throwable.javaClass.simpleName,
        elapsedMillis = SystemClock.elapsedRealtime() - startedAt,
    )
}
