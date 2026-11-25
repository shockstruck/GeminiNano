package dev.shockstruck.gemininano.core.android

import android.content.Context
import dev.shockstruck.gemininano.model.SystemFeatureFilter
import dev.shockstruck.gemininano.model.SystemFeaturesFacts

/**
 * The public `PackageManager.getSystemAvailableFeatures()` names that mention NPU, AI,
 * virtualization or private compute. Names only; a failed read is recorded as a read
 * error, never as "none exist".
 */
class SystemFeatureFacts(
    private val context: Context,
) {
    fun read(): SystemFeaturesFacts = try {
        SystemFeaturesFacts(
            names = SystemFeatureFilter.filter(
                context.packageManager.systemAvailableFeatures
                    .orEmpty()
                    .mapNotNull { it.name },
            ),
        )
    } catch (t: Throwable) {
        SystemFeaturesFacts(
            names = emptyList(),
            readErrorClass = t.javaClass.name,
            readErrorMessage = t.message ?: t.javaClass.simpleName,
        )
    }
}
