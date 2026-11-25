package dev.shockstruck.gemininano.model

import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile

/**
 * One installed APK opened as a ZIP archive.
 *
 * Only the central directory and the requested `classes*.dex` entries are read. No APK
 * byte, asset, model or resource content is copied, cached or exported by this library.
 */
interface InstalledCodeApk {
    val id: InstalledCodeApkId

    /** Lists every central-directory entry in one pass. */
    fun forEachEntry(visitor: (InstalledCodeZipEntry) -> Unit)

    /** Opens one entry. The caller closes the stream and bounds the bytes read. */
    fun openEntry(name: String): InputStream

    /** Lowercase hex SHA-256 of the whole APK file. */
    fun sha256(): String
}

/** An [InstalledCodeApk] backed by an APK file on disk. */
class InstalledCodeApkFile(
    override val id: InstalledCodeApkId,
    private val file: File,
) : InstalledCodeApk {

    override fun forEachEntry(visitor: (InstalledCodeZipEntry) -> Unit) {
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                visitor(
                    InstalledCodeZipEntry(
                        name = entry.name,
                        sizeBytes = entry.size,
                        isDirectory = entry.isDirectory,
                    ),
                )
            }
        }
    }

    override fun openEntry(name: String): InputStream {
        val zip = ZipFile(file)
        val entry = zip.getEntry(name)
        if (entry == null) {
            zip.close()
            throw InstalledCodeFormatException("${file.name} has no $name entry")
        }
        return object : FilterInputStream(zip.getInputStream(entry)) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    zip.close()
                }
            }
        }
    }

    override fun sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(HASH_BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> String.format(Locale.US, "%02x", byte) }
    }

    private companion object {
        const val HASH_BUFFER = 64 * 1024
    }
}

/** What a package lookup in the platform boundary produced. */
sealed interface InstalledCodePackageInput {
    /** The package is absent or hidden by Android's package visibility rules. */
    data object NotVisible : InstalledCodePackageInput

    data class Visible(
        val versionCode: Long,
        val apks: List<InstalledCodeApk>,
    ) : InstalledCodePackageInput
}

/**
 * Platform boundary for installed-code reads. `:core:android` implements this over
 * PackageManager and APK files; tests supply a fake or real files.
 */
interface InstalledCodeInput {
    fun packageFor(packageName: String): InstalledCodePackageInput
}
