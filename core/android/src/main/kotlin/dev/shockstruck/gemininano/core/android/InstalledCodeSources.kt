package dev.shockstruck.gemininano.core.android

import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import dev.shockstruck.gemininano.model.InstalledCodeApkFile
import dev.shockstruck.gemininano.model.InstalledCodeApkId
import dev.shockstruck.gemininano.model.InstalledCodeApkKind
import dev.shockstruck.gemininano.model.InstalledCodeInput
import dev.shockstruck.gemininano.model.InstalledCodePackageInput
import java.io.File

/**
 * Resolves a visible installed package to its base and split APK files through public
 * PackageManager APIs only.
 *
 * The files are handed to `:core:model`, which opens each as a ZIP and reads only
 * `classes*.dex` entries. No APK byte, asset, model or resource content is copied,
 * cached or exported here.
 */
class InstalledCodeSources(
    private val packageManager: PackageManager,
) : InstalledCodeInput {

    override fun packageFor(packageName: String): InstalledCodePackageInput {
        val info = try {
            readPackageInfo(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            return InstalledCodePackageInput.NotVisible
        }

        val applicationInfo = info.applicationInfo
            ?: return InstalledCodePackageInput.Visible(
                versionCode = PackageInfoCompat.getLongVersionCode(info),
                apks = emptyList(),
            )

        val apks = mutableListOf<InstalledCodeApkFile>()
        applicationInfo.publicSourceDir?.takeIf { it.isNotBlank() }?.let { path ->
            apks += InstalledCodeApkFile(
                InstalledCodeApkId("base", InstalledCodeApkKind.BASE),
                File(path),
            )
        }

        val splitNames = info.splitNames.orEmpty()
        applicationInfo.splitPublicSourceDirs.orEmpty().forEachIndexed { index, path ->
            if (path.isNullOrBlank()) return@forEachIndexed
            val name = splitNames.getOrNull(index)?.takeIf { it.isNotBlank() } ?: index.toString()
            apks += InstalledCodeApkFile(
                InstalledCodeApkId("split:$name", InstalledCodeApkKind.SPLIT),
                File(path),
            )
        }

        return InstalledCodePackageInput.Visible(
            versionCode = PackageInfoCompat.getLongVersionCode(info),
            apks = apks,
        )
    }

    private fun readPackageInfo(packageName: String) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
}
