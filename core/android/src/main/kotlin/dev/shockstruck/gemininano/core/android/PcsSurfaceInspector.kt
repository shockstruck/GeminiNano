package dev.shockstruck.gemininano.core.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import dev.shockstruck.gemininano.model.PackageFacts
import dev.shockstruck.gemininano.model.PcsSettingsResolution
import dev.shockstruck.gemininano.model.PcsSurface
import dev.shockstruck.gemininano.model.PlatformPrincipals

/** The fixed components the safe bind probe may touch. */
object PcsSurfaceCatalog {

    const val GRPC_SERVICE = "com.google.android.apps.miphone.pcs.grpc.PcsGrpcService"
    const val GENAI_INFERENCE_SERVICE = "com.google.android.as.oss.ai.service.GenAiInferenceService"
    const val SUPER_ICON_SERVICE = "com.google.android.as.oss.supericon.service.SuperIconRenderService"
    const val CONVERSATION_UPDATE_SERVICE =
        "com.google.android.as.oss.conversationid.service.ConversationIdUpdateService"
    const val CONVERSATION_LISTENER_SERVICE =
        "com.google.android.as.oss.conversationid.service.ConversationIdListenerService"
    const val FEEDBACK_HTTP_CLIENT_SERVICE =
        "com.google.android.as.oss.feedback.gateway.service.FeedbackHttpClientService"

    val BIND_SERVICES = listOf(
        GRPC_SERVICE,
        GENAI_INFERENCE_SERVICE,
        SUPER_ICON_SERVICE,
        CONVERSATION_UPDATE_SERVICE,
        CONVERSATION_LISTENER_SERVICE,
        FEEDBACK_HTTP_CLIENT_SERVICE,
    )

    val SETTINGS_INTENT: Intent = Intent(Intent.ACTION_APPLICATION_PREFERENCES)
        .setPackage(PlatformPrincipals.PCS)

    fun bindIntent(serviceName: String): Intent =
        Intent().setComponent(ComponentName(PlatformPrincipals.PCS, serviceName))
}

/**
 * Reads PCS's exported surface through public `PackageManager` APIs only. Components
 * that package visibility hides are reported as a visibility limit, never as absent.
 */
class PcsSurfaceInspector(
    private val context: Context,
) {
    private val packageManager: PackageManager = context.packageManager

    fun inspect(pcs: PackageFacts?): PcsSurface {
        val visible = pcs?.visible == true
        val exported = pcs?.components.orEmpty()
            .filter { it.exported }
            .sortedWith(compareBy({ it.kind.ordinal }, { it.className }))
        return PcsSurface(
            packageVisible = visible,
            exportedComponents = exported,
            settings = resolveSettings(),
        )
    }

    fun resolveSettings(): PcsSettingsResolution {
        val intent = Intent(PcsSurfaceCatalog.SETTINGS_INTENT)
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
                detail = "Resolved $activityName for one-tap launch; nothing is launched automatically.",
            )
        } else {
            PcsSettingsResolution(
                resolved = false,
                activityName = null,
                detail = "No activity resolved ACTION_APPLICATION_PREFERENCES for PCS.",
            )
        }
    }

    fun settingsIntent(): Intent = Intent(PcsSurfaceCatalog.SETTINGS_INTENT)
}
