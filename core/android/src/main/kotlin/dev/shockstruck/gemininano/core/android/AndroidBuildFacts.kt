package dev.shockstruck.gemininano.core.android

import android.os.Build
import dev.shockstruck.gemininano.model.BuildFacts

/** Reads the host build identity from `android.os.Build`; no new permissions needed. */
object AndroidBuildFacts {

    fun read(): BuildFacts = BuildFacts(
        manufacturer = Build.MANUFACTURER.orEmpty().ifBlank { "unknown" },
        brand = Build.BRAND.orEmpty().ifBlank { "unknown" },
        model = Build.MODEL.orEmpty().ifBlank { "unknown" },
        device = Build.DEVICE.orEmpty().ifBlank { "unknown" },
        product = Build.PRODUCT.orEmpty().ifBlank { "unknown" },
        hardware = Build.HARDWARE.orEmpty().ifBlank { "unknown" },
        fingerprint = Build.FINGERPRINT.orEmpty().ifBlank { "unknown" },
        androidRelease = Build.VERSION.RELEASE.orEmpty().ifBlank { "unknown" },
        sdkInt = Build.VERSION.SDK_INT,
        securityPatch = Build.VERSION.SECURITY_PATCH.takeIf { it.isNotBlank() },
        supportedAbis = Build.SUPPORTED_ABIS?.toList().orEmpty(),
    )
}
