package dev.shockstruck.gemininano.core.android

import android.content.Intent

/**
 * The package catalogue the manager inspects and the named permissions whose grant
 * state Android exposes to other apps through `PackageManager.checkPermission`.
 */
object PackageCatalog {

    const val AICORE_PACKAGE = "com.google.android.aicore"
    const val AICORE_LEGACY_PACKAGE = "com.google.android.apps.aicore"
    const val PCS_PACKAGE = "com.google.android.as.oss"
    const val ASI_PACKAGE = "com.google.android.as"
    const val GMS_PACKAGE = "com.google.android.gms"
    const val PLAY_STORE_PACKAGE = "com.android.vending"
    const val DIALER_PACKAGE = "com.google.android.dialer"
    const val GBOARD_PACKAGE = "com.google.android.inputmethod.latin"
    const val PSI_PACKAGE = "com.google.android.apps.pixel.psi"
    const val NAS_PACKAGE = "com.google.android.nas"
    const val PIXEL_AGENT_PACKAGE = "com.google.android.apps.pixel.agent"

    val AICORE_PACKAGES = listOf(AICORE_PACKAGE, AICORE_LEGACY_PACKAGE)

    val TARGET_PACKAGES = listOf(
        AICORE_PACKAGE,
        AICORE_LEGACY_PACKAGE,
        PCS_PACKAGE,
        ASI_PACKAGE,
        GMS_PACKAGE,
        PLAY_STORE_PACKAGE,
        DIALER_PACKAGE,
        GBOARD_PACKAGE,
        PSI_PACKAGE,
        NAS_PACKAGE,
        PIXEL_AGENT_PACKAGE,
    )

    const val PCC_PERMISSION = "android.permission.PROVIDE_PRIVATE_COMPUTE_SERVICES"
    const val AICORE_BIND_PERMISSION = "com.google.android.apps.aicore.service.BIND_SERVICE"

    /** Named permissions whose observable grant state is worth showing for each package. */
    val NAMED_PERMISSIONS = listOf(
        PCC_PERMISSION,
        AICORE_BIND_PERMISSION,
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.READ_LOGS",
        "android.permission.WRITE_SECURE_SETTINGS",
        "android.permission.QUERY_ALL_PACKAGES",
    )

    /**
     * Intent actions probed to surface declared component actions. Android never exposes
     * full intent-filter XML for another package; querying a fixed action set is the only
     * public way to learn which actions a component answers.
     */
    val PROBED_INTENT_ACTIONS = listOf(
        Intent.ACTION_MAIN,
        Intent.ACTION_VIEW,
        Intent.ACTION_SEND,
        Intent.ACTION_SENDTO,
        Intent.ACTION_DIAL,
        Intent.ACTION_PROCESS_TEXT,
        Intent.ACTION_GET_CONTENT,
        "android.intent.action.ASSIST",
        "android.intent.action.VOICE_ASSIST",
        "android.service.voice.VoiceInteractionService",
        "android.intent.action.TTS_SERVICE",
    )
}
