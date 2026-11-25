package dev.shockstruck.gemininano.model

import java.io.ByteArrayOutputStream

/**
 * Reads the installed code of one package as marker, flag-default and asset facts.
 *
 * Every `classes*.dex` entry of every base and split APK is read once, bounded per entry
 * and in total. Bytes are parsed by [InstalledCodeDex] and dropped after the dex is
 * analysed; the result keeps only marker strings, flag names, literal defaults, method
 * names on `DeviceConfig`, asset entry names and sizes, digests and counts.
 *
 * A marker is reported [InstalledCodePresence.ABSENT] only when every dex of every APK
 * was read. Any unread dex makes every unmentioned marker
 * [InstalledCodePresence.UNKNOWN].
 */
class InstalledCodeReader(
    private val limits: InstalledCodeReadLimits = InstalledCodeReadLimits(),
) {

    fun read(packageName: String, input: InstalledCodeInput): InstalledCodeOutcome {
        val packageInput = try {
            input.packageFor(packageName)
        } catch (e: Throwable) {
            return InstalledCodeOutcome.ReadError(packageName, e.javaClass.name, messageOf(e))
        }
        if (packageInput !is InstalledCodePackageInput.Visible) {
            return InstalledCodeOutcome.NotVisible(packageName)
        }
        if (packageInput.apks.isEmpty()) {
            return InstalledCodeOutcome.ReadError(
                packageName,
                "MissingApk",
                "the package reported no APK paths",
            )
        }

        val digests = mutableListOf<InstalledCodeApkDigest>()
        val apkFacts = mutableListOf<InstalledCodeApkFacts>()
        val failures = mutableListOf<InstalledCodeReadFailure>()
        val inventories = mutableListOf<ApkInventory>()
        for (apk in packageInput.apks) {
            if (apk.id.kind == InstalledCodeApkKind.BASE && inventories.any { it.apk.id.kind == InstalledCodeApkKind.BASE }) {
                failures += InstalledCodeReadFailure(
                    apk.id,
                    null,
                    "DuplicateBaseApk",
                    "the package reported more than one base APK",
                )
                continue
            }
            val digest = try {
                apk.sha256()
            } catch (e: Throwable) {
                val failure = InstalledCodeReadFailure(apk.id, null, e.javaClass.name, messageOf(e))
                if (apk.id.kind == InstalledCodeApkKind.BASE) {
                    return InstalledCodeOutcome.ReadError(packageName, failure.errorClass, failure.message)
                }
                failures += failure
                continue
            }
            val inventory = try {
                inventoryOf(apk)
            } catch (e: Throwable) {
                val failure = InstalledCodeReadFailure(apk.id, null, e.javaClass.name, messageOf(e))
                if (apk.id.kind == InstalledCodeApkKind.BASE) {
                    return InstalledCodeOutcome.ReadError(packageName, failure.errorClass, failure.message)
                }
                failures += failure
                continue
            }
            digests += InstalledCodeApkDigest(apk.id, digest)
            inventories += inventory
            apkFacts += InstalledCodeApkFacts(apk.id, digest, inventory.dexEntries.map { it.name })
        }

        if (inventories.none { it.apk.id.kind == InstalledCodeApkKind.BASE }) {
            val baseFailure = failures.firstOrNull { it.apk?.kind == InstalledCodeApkKind.BASE }
            return if (baseFailure != null) {
                InstalledCodeOutcome.ReadError(packageName, baseFailure.errorClass, baseFailure.message)
            } else {
                InstalledCodeOutcome.ReadError(packageName, "MissingBaseApk", "no base APK could be opened")
            }
        }

        val totalDexBytes = inventories.sumOf { inventory ->
            inventory.dexEntries.sumOf { entry -> entry.sizeBytes.coerceAtLeast(0L) }
        }
        if (totalDexBytes > limits.maxTotalDexBytes) {
            return InstalledCodeOutcome.TooLarge(
                packageName,
                "the package's dex entries total $totalDexBytes bytes, above the ${limits.maxTotalDexBytes} byte limit",
                packageInput.versionCode,
                digests.toList(),
            )
        }
        for (inventory in inventories) {
            for (entry in inventory.dexEntries) {
                if (entry.sizeBytes > limits.maxBytesPerDexEntry.toLong()) {
                    return InstalledCodeOutcome.TooLarge(
                        packageName,
                        "${inventory.apk.id.label} ${entry.name} is ${entry.sizeBytes} bytes, above the ${limits.maxBytesPerDexEntry} byte limit",
                        packageInput.versionCode,
                        digests.toList(),
                    )
                }
            }
        }

        val references = mutableListOf<InstalledCodeMarkerReference>()
        val permissionChecks = mutableListOf<InstalledCodePermissionCheckReference>()
        val deviceConfigNames = sortedSetOf<String>()
        var referencesTruncated = false
        var totalRead = 0L
        for (inventory in inventories) {
            for (entry in inventory.dexEntries) {
                try {
                    val bytes = readBounded(inventory.apk, entry.name)
                    totalRead += bytes.size
                    if (totalRead > limits.maxTotalDexBytes) {
                        return InstalledCodeOutcome.TooLarge(
                            packageName,
                            "the read dex entries total more than the ${limits.maxTotalDexBytes} byte limit",
                            packageInput.versionCode,
                            digests.toList(),
                        )
                    }
                    val dex = InstalledCodeDex.parse(bytes)
                    val analysis = analyseInstalledCodeDex(dex, inventory.apk.id, entry.name, limits)
                    references += analysis.references
                    analysis.permissionCheck?.let(permissionChecks::add)
                    deviceConfigNames += analysis.deviceConfigMethodNames
                    referencesTruncated = referencesTruncated || analysis.referencesTruncated
                } catch (e: InstalledCodeSizeLimitException) {
                    return InstalledCodeOutcome.TooLarge(
                        packageName,
                        "${inventory.apk.id.label} ${entry.name}: ${messageOf(e)}",
                        packageInput.versionCode,
                        digests.toList(),
                    )
                } catch (e: Throwable) {
                    failures += InstalledCodeReadFailure(
                        inventory.apk.id,
                        entry.name,
                        e.javaClass.name,
                        messageOf(e),
                    )
                }
            }
        }

        val assets = assetFacts(inventories)
        val facts = InstalledCodePackageFacts(
            packageName = packageName,
            versionCode = packageInput.versionCode,
            apkSha256 = digests.toList(),
            apks = apkFacts.toList(),
            markers = markerFacts(references, failures.isEmpty(), referencesTruncated),
            readDeviceConfig = InstalledCodePermissionCheckFacts(
                marker = InstalledCodeMarkers.READ_DEVICE_CONFIG,
                references = permissionChecks.toList(),
            ),
            deviceConfig = InstalledCodeDeviceConfigFacts(deviceConfigNames.toList()),
            assets = assets,
            failures = failures.toList(),
            referencesTruncated = referencesTruncated,
        )
        return if (failures.isEmpty()) {
            InstalledCodeOutcome.Read(packageName, facts)
        } else {
            InstalledCodeOutcome.Partial(packageName, facts)
        }
    }

    private fun inventoryOf(apk: InstalledCodeApk): ApkInventory {
        val dexEntries = mutableListOf<InstalledCodeZipEntry>()
        val assetEntries = mutableListOf<InstalledCodeAssetEntryFacts>()
        var assetCount = 0
        var assetsTruncated = false
        apk.forEachEntry { entry ->
            if (entry.isDirectory) return@forEachEntry
            if (DEX_ENTRY.matches(entry.name)) {
                dexEntries += entry
                return@forEachEntry
            }
            if (!entry.name.startsWith(ASSET_PREFIX)) return@forEachEntry
            assetCount += 1
            if (!matchesAssetToken(entry.name)) return@forEachEntry
            if (assetEntries.size < limits.maxRecordedAssetEntries) {
                assetEntries += InstalledCodeAssetEntryFacts(apk.id, entry.name, entry.sizeBytes)
            } else {
                assetsTruncated = true
            }
        }
        dexEntries.sortWith(compareBy({ dexOrder(it.name) }, { it.name }))
        return ApkInventory(
            apk = apk,
            dexEntries = dexEntries.toList(),
            assetCount = assetCount,
            assetEntries = assetEntries.toList(),
            assetsTruncated = assetsTruncated,
        )
    }

    private fun assetFacts(inventories: List<ApkInventory>): InstalledCodeAssetFacts {
        val recorded = mutableListOf<InstalledCodeAssetEntryFacts>()
        var truncated = false
        for (inventory in inventories) {
            if (inventory.assetsTruncated) truncated = true
            for (entry in inventory.assetEntries) {
                if (recorded.size < limits.maxRecordedAssetEntries) {
                    recorded += entry
                } else {
                    truncated = true
                }
            }
        }
        return InstalledCodeAssetFacts(
            entries = recorded,
            apkAssetCounts = inventories.map { InstalledCodeApkAssetCount(it.apk.id, it.assetCount) },
            totalAssetCount = inventories.sumOf { it.assetCount },
            truncated = truncated,
        )
    }

    private fun markerFacts(
        references: List<InstalledCodeMarkerReference>,
        complete: Boolean,
        truncated: Boolean,
    ): List<InstalledCodeMarkerFacts> = InstalledCodeMarkers.MARKERS.map { spec ->
        val matches = references.filter { it.marker == spec.name }
        val presence = when {
            matches.isNotEmpty() -> InstalledCodePresence.PRESENT
            complete && !truncated -> InstalledCodePresence.ABSENT
            else -> InstalledCodePresence.UNKNOWN
        }
        InstalledCodeMarkerFacts(
            marker = spec.name,
            match = spec.match,
            kind = spec.kind,
            presence = presence,
            references = matches,
        )
    }

    private fun readBounded(apk: InstalledCodeApk, name: String): ByteArray {
        apk.openEntry(name).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(READ_BUFFER)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > limits.maxBytesPerDexEntry) {
                    throw InstalledCodeSizeLimitException(
                        "$name exceeds the ${limits.maxBytesPerDexEntry} byte entry limit",
                    )
                }
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }

    private fun matchesAssetToken(name: String): Boolean {
        val lower = name.lowercase()
        return ASSET_TOKENS.any { token -> lower.contains(token) }
    }

    private fun dexOrder(name: String): Int {
        if (name == BASE_DEX) return 0
        val digits = name.removePrefix("classes").removeSuffix(".dex")
        return digits.toIntOrNull() ?: Int.MAX_VALUE
    }

    private data class ApkInventory(
        val apk: InstalledCodeApk,
        val dexEntries: List<InstalledCodeZipEntry>,
        val assetCount: Int,
        val assetEntries: List<InstalledCodeAssetEntryFacts>,
        val assetsTruncated: Boolean,
    )

    private companion object {
        val DEX_ENTRY = Regex("^classes\\d*\\.dex$")
        const val BASE_DEX = "classes.dex"
        const val ASSET_PREFIX = "assets/"
        const val READ_BUFFER = 64 * 1024
        val ASSET_TOKENS = listOf("manifest", ".binarypb", ".tflite", "model", "preload")

        fun messageOf(error: Throwable): String = error.message ?: "(no message)"
    }
}
