package dev.shockstruck.gemininano.core.android

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import dev.shockstruck.gemininano.model.ManifestApkKind
import dev.shockstruck.gemininano.model.ManifestEventStream
import dev.shockstruck.gemininano.model.ManifestFactsAxmlDecoder
import dev.shockstruck.gemininano.model.ManifestFormatException
import dev.shockstruck.gemininano.model.ManifestPackageSources
import dev.shockstruck.gemininano.model.ManifestReadInput
import dev.shockstruck.gemininano.model.ManifestReadLimits
import dev.shockstruck.gemininano.model.ManifestSizeLimitException
import dev.shockstruck.gemininano.model.ManifestSourceId
import dev.shockstruck.gemininano.model.ManifestSourceInput
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Resolves a visible installed package to its base and split APK manifests through
 * public PackageManager APIs only.
 *
 * Each APK is opened as a ZIP file and exactly one bounded entry,
 * `AndroidManifest.xml`, is decoded. No other entry is read and no APK bytes, dex or
 * model content is copied, cached or exported. A package the app cannot see is reported
 * as [ManifestPackageSources.NotVisible] instead of as an empty manifest.
 */
class InstalledManifestSources(
    private val packageManager: PackageManager,
    private val limits: ManifestReadLimits = ManifestReadLimits(),
) : ManifestReadInput {

    override fun sourcesFor(packageName: String): ManifestPackageSources {
        val info = try {
            readPackageInfo(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            return ManifestPackageSources.NotVisible
        }

        val signerCertificateSha256 = signerDigests(info)
        val effectiveSharedUserId = sharedUserId(info)
        val applicationInfo = info.applicationInfo
            ?: return ManifestPackageSources.Visible(
                effectiveSharedUserId = effectiveSharedUserId,
                signerCertificateSha256 = signerCertificateSha256,
            )

        val sources = mutableListOf<ManifestSourceInput>()
        applicationInfo.publicSourceDir?.takeIf { it.isNotBlank() }?.let { path ->
            sources += InstalledManifestSource(
                source = ManifestSourceId("base", ManifestApkKind.BASE),
                apk = File(path),
                limits = limits,
            )
        }

        val splitPaths = applicationInfo.splitPublicSourceDirs.orEmpty()
        val splitNames = info.splitNames.orEmpty()
        splitPaths.forEachIndexed { index, path ->
            if (path.isNullOrBlank()) return@forEachIndexed
            val name = splitNames.getOrNull(index)?.takeIf { it.isNotBlank() } ?: index.toString()
            sources += InstalledManifestSource(
                source = ManifestSourceId("split:$name", ManifestApkKind.SPLIT),
                apk = File(path),
                limits = limits,
            )
        }

        return ManifestPackageSources.Visible(
            effectiveSharedUserId = effectiveSharedUserId,
            signerCertificateSha256 = signerCertificateSha256,
            sources = sources,
        )
    }

    private fun readPackageInfo(packageName: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(packageInfoFlags().toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, packageInfoFlags())
        }

    private fun packageInfoFlags(): Int =
        PackageManager.GET_PERMISSIONS or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                0
            }

    private fun sharedUserId(info: PackageInfo): String? =
        @Suppress("DEPRECATION")
        info.sharedUserId

    private fun signerDigests(info: PackageInfo): List<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        return signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString(":") { byte -> String.format(Locale.US, "%02X", byte) }
        }
    }
}

/** One APK file whose `AndroidManifest.xml` entry is decoded on open. */
private class InstalledManifestSource(
    override val source: ManifestSourceId,
    private val apk: File,
    private val limits: ManifestReadLimits,
) : ManifestSourceInput {

    override fun openEvents(): ManifestEventStream {
        val bytes = ZipFile(apk).use { zip ->
            val entry = zip.getEntry(MANIFEST_ENTRY)
                ?: throw ManifestFormatException("${apk.name} has no $MANIFEST_ENTRY entry")
            if (entry.size > limits.maxBytesPerManifest.toLong()) {
                throw ManifestSizeLimitException(
                    "${apk.name} manifest entry is ${entry.size} bytes, above the ${limits.maxBytesPerManifest} byte limit",
                )
            }
            zip.getInputStream(entry).use { input -> readBounded(input) }
        }
        return ManifestFactsAxmlDecoder(bytes, limits)
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limits.maxBytesPerManifest) {
                throw ManifestSizeLimitException(
                    "${apk.name} manifest entry exceeds ${limits.maxBytesPerManifest} bytes",
                )
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private companion object {
        const val MANIFEST_ENTRY = "AndroidManifest.xml"
        const val READ_BUFFER = 8 * 1024
    }
}
