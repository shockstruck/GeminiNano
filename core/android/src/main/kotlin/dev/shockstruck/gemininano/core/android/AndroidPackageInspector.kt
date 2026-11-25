package dev.shockstruck.gemininano.core.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ComponentInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import dev.shockstruck.gemininano.model.BuildFacts
import dev.shockstruck.gemininano.model.ComponentFacts
import dev.shockstruck.gemininano.model.ComponentKind
import dev.shockstruck.gemininano.model.GrantState
import dev.shockstruck.gemininano.model.MetadataFacts
import dev.shockstruck.gemininano.model.PackageFacts
import dev.shockstruck.gemininano.model.PackageFactsProvider
import dev.shockstruck.gemininano.model.PackageVisibility
import dev.shockstruck.gemininano.model.PermissionGrantFacts
import java.security.MessageDigest
import java.util.Locale

/**
 * Reads everything Android exposes about the catalogue packages through public
 * PackageManager APIs. Package visibility is declared by the app's scoped `<queries>`
 * block; if a package cannot be seen, [PackageVisibility.NOT_VISIBLE] is reported
 * instead of pretending the package is missing.
 */
class AndroidPackageInspector(
    private val context: Context,
) : PackageFactsProvider {

    private val packageManager: PackageManager = context.packageManager

    override fun inspectPackages(): List<PackageFacts> {
        val actionsByPackage = collectIntentActions()
        return PackageCatalog.TARGET_PACKAGES.map { packageName ->
            inspectPackage(packageName, actionsByPackage[packageName].orEmpty())
        }
    }

    override fun checkPermission(packageName: String, permission: String): GrantState = try {
        when (packageManager.checkPermission(permission, packageName)) {
            PackageManager.PERMISSION_GRANTED -> GrantState.GRANTED
            PackageManager.PERMISSION_DENIED -> GrantState.DENIED
            else -> GrantState.UNKNOWN
        }
    } catch (t: Throwable) {
        GrantState.UNKNOWN
    }

    override fun buildFacts(): BuildFacts = AndroidBuildFacts.read()

    private fun inspectPackage(
        packageName: String,
        actions: Map<String, Set<String>>,
    ): PackageFacts {
        val info = try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, PACKAGE_INFO_FLAGS)
        } catch (e: PackageManager.NameNotFoundException) {
            return PackageFacts(packageName = packageName, visibility = PackageVisibility.NOT_VISIBLE)
        } catch (t: Throwable) {
            return PackageFacts(packageName = packageName, visibility = PackageVisibility.NOT_VISIBLE)
        }

        val requestedPermissions = info.requestedPermissions?.toList().orEmpty()
        return PackageFacts(
            packageName = packageName,
            visibility = PackageVisibility.VISIBLE,
            versionName = info.versionName,
            versionCode = versionCode(info),
            installerPackage = installer(packageName),
            signingSha256 = signingDigests(info),
            targetSdk = info.applicationInfo?.targetSdkVersion,
            minSdk = if (Build.VERSION.SDK_INT >= 24) info.applicationInfo?.minSdkVersion else null,
            enabled = info.applicationInfo?.enabled,
            requestedPermissions = requestedPermissions,
            permissionGrants = PackageCatalog.NAMED_PERMISSIONS.map { permission ->
                PermissionGrantFacts(
                    permission = permission,
                    state = checkPermission(packageName, permission),
                    requested = requestedPermissions.contains(permission),
                )
            },
            components = buildList {
                addAll(componentFacts(ComponentKind.ACTIVITY, info.activities, actions))
                addAll(componentFacts(ComponentKind.SERVICE, info.services, actions))
                addAll(componentFacts(ComponentKind.RECEIVER, info.receivers, actions))
                addAll(componentFacts(ComponentKind.PROVIDER, info.providers, actions))
            },
            metadata = metadataFacts(info.applicationInfo?.metaData),
        )
    }

    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    private fun installer(packageName: String): String? = try {
        if (Build.VERSION.SDK_INT >= 30) {
            packageManager.getInstallSourceInfo(packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstallerPackageName(packageName)
        }
    } catch (t: Throwable) {
        null
    }

    private fun signingDigests(info: PackageInfo): List<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }.orEmpty()
        return signatures.map { signature -> sha256Hex(signature.toByteArray()) }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(":") { String.format(Locale.US, "%02X", it) }
    }

    private fun componentFacts(
        kind: ComponentKind,
        entries: Array<out ComponentInfo>?,
        actions: Map<String, Set<String>>,
    ): List<ComponentFacts> = entries.orEmpty().map { component ->
        ComponentFacts(
            kind = kind,
            className = component.name,
            exported = component.exported,
            requiredPermission = requiredPermission(component),
            enabled = component.enabled,
            intentActions = actions["${kind.name}:${component.name}"].orEmpty().sorted(),
            authorities = (component as? ProviderInfo)?.authority?.let { listOf(it) }.orEmpty(),
            metaDataKeys = component.metaData?.keySet()?.sorted().orEmpty(),
        )
    }

    private fun requiredPermission(component: ComponentInfo): String? = when (component) {
        is ActivityInfo -> component.permission
        is ServiceInfo -> component.permission
        is ProviderInfo -> listOfNotNull(component.readPermission, component.writePermission)
            .distinct()
            .joinToString(" / ")
            .ifBlank { null }

        else -> null
    }

    private fun metadataFacts(bundle: Bundle?): List<MetadataFacts> {
        if (bundle == null) return emptyList()
        return bundle.keySet().sorted().map { key ->
            val value = bundle.get(key)
            MetadataFacts(
                key = key,
                value = scalarValue(value),
                type = value?.javaClass?.simpleName ?: "null",
            )
        }
    }

    private fun scalarValue(value: Any?): String? = when (value) {
        null -> null
        is String, is Int, is Long, is Boolean, is Float, is Double -> value.toString()
        is Array<*> -> "${value.size} items"
        else -> value.javaClass.simpleName
    }

    /**
     * Builds package -> "KIND:class" -> actions using public intent queries. Android does
     * not expose other packages' intent-filter declarations directly, so this probes a
     * fixed action set and records the filter actions of every matching component.
     */
    private fun collectIntentActions(): Map<String, Map<String, Set<String>>> {
        val result = mutableMapOf<String, MutableMap<String, MutableSet<String>>>()
        PackageCatalog.PROBED_INTENT_ACTIONS.forEach { action ->
            val intent = Intent(action)
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, PackageManager.GET_RESOLVED_FILTER)
                .forEach { resolveInfo ->
                    val activity = resolveInfo.activityInfo ?: return@forEach
                    recordAction(
                        result,
                        ComponentKind.ACTIVITY,
                        activity.packageName,
                        activity.name,
                        resolveInfo.filter,
                        action,
                    )
                }
            @Suppress("DEPRECATION")
            packageManager.queryIntentServices(intent, PackageManager.GET_RESOLVED_FILTER)
                .forEach { resolveInfo ->
                    val service = resolveInfo.serviceInfo ?: return@forEach
                    recordAction(
                        result,
                        ComponentKind.SERVICE,
                        service.packageName,
                        service.name,
                        resolveInfo.filter,
                        action,
                    )
                }
            @Suppress("DEPRECATION")
            packageManager.queryBroadcastReceivers(intent, PackageManager.GET_RESOLVED_FILTER)
                .forEach { resolveInfo ->
                    val receiver = resolveInfo.activityInfo ?: return@forEach
                    recordAction(
                        result,
                        ComponentKind.RECEIVER,
                        receiver.packageName,
                        receiver.name,
                        resolveInfo.filter,
                        action,
                    )
                }
        }
        return result
    }

    private fun recordAction(
        result: MutableMap<String, MutableMap<String, MutableSet<String>>>,
        kind: ComponentKind,
        packageName: String?,
        className: String?,
        filter: IntentFilter?,
        queriedAction: String,
    ) {
        if (packageName == null || className == null) return
        val key = "${kind.name}:$className"
        val actions = result.getOrPut(packageName) { mutableMapOf() }
            .getOrPut(key) { mutableSetOf() }
        actions += queriedAction
        filter?.actionsIterator()?.forEach { actions += it }
    }

    private companion object {
        const val PACKAGE_INFO_FLAGS = PackageManager.GET_PERMISSIONS or
            PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS or
            PackageManager.GET_META_DATA or
            PackageManager.GET_SIGNING_CERTIFICATES
    }
}
