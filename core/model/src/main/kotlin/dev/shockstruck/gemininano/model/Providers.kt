package dev.shockstruck.gemininano.model

/**
 * Platform boundary for package-system reads. Implemented by `:core:android`; the app
 * and tests depend only on this interface.
 */
interface PackageFactsProvider {
    fun inspectPackages(): List<PackageFacts>

    /** Observable grant of [permission] for [packageName] (PackageManager.checkPermission). */
    fun checkPermission(packageName: String, permission: String): GrantState

    fun buildFacts(): BuildFacts
}
