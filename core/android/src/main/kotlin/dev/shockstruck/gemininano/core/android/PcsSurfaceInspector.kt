package dev.shockstruck.gemininano.core.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import dev.shockstruck.gemininano.model.PackageFacts
import dev.shockstruck.gemininano.model.PcsActivityCatalog
import dev.shockstruck.gemininano.model.PcsSettingsResolution
import dev.shockstruck.gemininano.model.PcsSurface
import dev.shockstruck.gemininano.model.PlatformPrincipals

/**
 * Reads PCS's exported surface through public `PackageManager` APIs only. Components
 * that package visibility hides are reported as a visibility limit, never as absent;
 * a reduced read that never returned component lists says "not read", never "empty".
 */
class PcsSurfaceInspector(
    private val context: Context,
) {
    private val packageManager: PackageManager = context.packageManager

    fun inspect(pcs: PackageFacts?): PcsSurface {
        val visible = pcs?.visible == true
        val componentsRead = pcs?.componentsRead == true
        val exported = if (componentsRead) {
            pcs!!.components
                .filter { it.exported }
                .sortedWith(compareBy({ it.kind.ordinal }, { it.className }))
        } else {
            emptyList()
        }
        val readDetail = when {
            !visible -> "PCS is not visible to this app; components cannot be enumerated."
            pcs?.readError != null -> "PCS read error: ${pcs.readError?.label}"
            !componentsRead -> "Components were not returned by the reduced package read."
            else -> null
        }
        return PcsSurface(
            packageVisible = visible,
            exportedComponents = exported,
            settings = resolveSettings(),
            componentsRead = componentsRead,
            componentsReadDetail = readDetail,
            launchTargets = PcsActivityCatalog.targets(pcs),
        )
    }

    fun resolveSettings(): PcsSettingsResolution {
        val intent = Intent(Intent.ACTION_APPLICATION_PREFERENCES)
            .setPackage(PlatformPrincipals.PCS)
        val result = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                packageManager.resolveActivity(intent, 0)
            }
        } catch (t: Throwable) {
            null
        }
        val activityName = result?.activityInfo?.name
        return if (activityName != null) {
            PcsSettingsResolution(
                resolved = true,
                activityName = activityName,
                detail = "Resolved $activityName for ACTION_APPLICATION_PREFERENCES; the explicit launch controls below are separate.",
            )
        } else {
            PcsSettingsResolution(
                resolved = false,
                activityName = null,
                detail = "No activity resolved ACTION_APPLICATION_PREFERENCES for PCS.",
            )
        }
    }

    fun settingsIntent(): Intent = Intent(Intent.ACTION_APPLICATION_PREFERENCES)
        .setPackage(PlatformPrincipals.PCS)

    fun explicitActivityIntent(className: String): Intent =
        Intent().setComponent(ComponentName(PlatformPrincipals.PCS, className))
}
