package dev.shockstruck.gemininano.model

/** Which APK inside an installed package a dex entry was read from. */
enum class InstalledCodeApkKind {
    BASE,
    SPLIT,
}

/** Identifies one APK of an installed package. */
data class InstalledCodeApkId(
    val label: String,
    val kind: InstalledCodeApkKind,
)

/** One ZIP entry as listed from an APK's central directory. */
data class InstalledCodeZipEntry(
    val name: String,
    val sizeBytes: Long,
    val isDirectory: Boolean,
)

/** SHA-256 of one APK file, as lowercase hex. */
data class InstalledCodeApkDigest(
    val apk: InstalledCodeApkId,
    val sha256: String,
)

/** Bounds applied to every installed-code read. */
data class InstalledCodeReadLimits(
    val maxBytesPerDexEntry: Int = 32 * 1024 * 1024,
    val maxTotalDexBytes: Long = 64L * 1024 * 1024,
    val maxRecordedAssetEntries: Int = 200,
    val maxRecordedReferences: Int = 8192,
    val maxDefaultsPerReference: Int = 16,
) {
    init {
        require(maxBytesPerDexEntry > 0) { "maxBytesPerDexEntry must be positive" }
        require(maxTotalDexBytes > 0) { "maxTotalDexBytes must be positive" }
        require(maxRecordedAssetEntries > 0) { "maxRecordedAssetEntries must be positive" }
        require(maxRecordedReferences > 0) { "maxRecordedReferences must be positive" }
        require(maxDefaultsPerReference > 0) { "maxDefaultsPerReference must be positive" }
    }
}

/** How a marker string is matched against a string constant. */
enum class InstalledCodeMarkerMatch {
    EXACT,
    PREFIX,
    SUBSTRING,
}

/** What kind of fact a marker records. */
enum class InstalledCodeMarkerKind {
    /** A DeviceConfig/Phenotype boolean, int or string flag. */
    FLAG,

    /** A log or error message. */
    MESSAGE,

    /** An Android permission name. */
    PERMISSION,

    /** A DeviceConfig namespace. */
    NAMESPACE,

    /** An asset or model file name. */
    ASSET,

    /** A tag, class name or populator identifier. */
    TAG,

    /** A package or package-prefix reference. */
    PACKAGE,
}

/**
 * One entry of the fixed marker allowlist.
 *
 * [resolveDefault] is true only for flag names, where the value passed beside the flag
 * name in the same constructor or factory call is the flag's default.
 */
data class InstalledCodeMarkerSpec(
    val name: String,
    val match: InstalledCodeMarkerMatch,
    val kind: InstalledCodeMarkerKind,
    val resolveDefault: Boolean = false,
) {
    fun matchedText(value: String): String? = when (match) {
        InstalledCodeMarkerMatch.EXACT -> value.takeIf { it == name }
        InstalledCodeMarkerMatch.PREFIX -> value.takeIf { it.startsWith(name) }
        InstalledCodeMarkerMatch.SUBSTRING -> value.takeIf { it.contains(name) }
    }
}

/** Whether a marker was referenced, or whether the read was too incomplete to say. */
enum class InstalledCodePresence {
    PRESENT,

    /** Every dex of every APK was read and none referenced the marker. */
    ABSENT,

    /** At least one dex could not be read, so absence cannot be claimed. */
    UNKNOWN,
}

/** A literal value passed beside a flag name, or the fact that it could not be resolved. */
sealed interface InstalledCodeDefaultValue {
    data class BooleanValue(val value: Boolean) : InstalledCodeDefaultValue

    data class IntValue(val value: Int) : InstalledCodeDefaultValue

    data class StringValue(val value: String) : InstalledCodeDefaultValue

    data object Unresolved : InstalledCodeDefaultValue
}

/**
 * One matched string in one dex.
 *
 * [methodCount] is the number of methods whose code references [matchedString].
 * [defaults] holds distinct literal values passed beside the flag name, or
 * [InstalledCodeDefaultValue.Unresolved]; it is empty for markers that are not flags.
 */
data class InstalledCodeMarkerReference(
    val marker: String,
    val apk: InstalledCodeApkId,
    val dexEntry: String,
    val matchedString: String,
    val methodCount: Int,
    val defaults: List<InstalledCodeDefaultValue> = emptyList(),
)

/**
 * The facts recorded for one allowlisted marker.
 *
 * [methodCount] sums the method counts of the matched strings; a method that references
 * two different prefixed names is counted once under each name.
 */
data class InstalledCodeMarkerFacts(
    val marker: String,
    val match: InstalledCodeMarkerMatch,
    val kind: InstalledCodeMarkerKind,
    val presence: InstalledCodePresence,
    val references: List<InstalledCodeMarkerReference> = emptyList(),
) {
    val methodCount: Int get() = references.sumOf { it.methodCount }
    val presentIn: List<Pair<InstalledCodeApkId, String>>
        get() = references.map { it.apk to it.dexEntry }.distinct()
}

/** One dex's evidence for the `android.permission.READ_DEVICE_CONFIG` marker. */
data class InstalledCodePermissionCheckReference(
    val apk: InstalledCodeApkId,
    val dexEntry: String,
    val referencingMethods: Int,
    val referencingMethodsWithPermissionCheck: Int,
    val permissionCheckMethodNames: List<String>,
)

/** Whether methods referencing the permission also invoke a permission check. */
data class InstalledCodePermissionCheckFacts(
    val marker: String,
    val references: List<InstalledCodePermissionCheckReference> = emptyList(),
) {
    val anyReferencingMethodChecksPermission: Boolean
        get() = references.any { it.referencingMethodsWithPermissionCheck > 0 }
}

/** Method names invoked on `Landroid/provider/DeviceConfig;`, names only. */
data class InstalledCodeDeviceConfigFacts(
    val methodNames: List<String> = emptyList(),
)

/** One `assets/` entry whose name matched a configured token. */
data class InstalledCodeAssetEntryFacts(
    val apk: InstalledCodeApkId,
    val name: String,
    val sizeBytes: Long,
)

/** One dex entry exceeded a configured input bound. */
class InstalledCodeSizeLimitException(message: String) : Exception(message)

/** The number of non-directory entries under `assets/` in one APK. */
data class InstalledCodeApkAssetCount(
    val apk: InstalledCodeApkId,
    val count: Int,
)

/** Asset entry facts; no asset content is read. */
data class InstalledCodeAssetFacts(
    val entries: List<InstalledCodeAssetEntryFacts> = emptyList(),
    val apkAssetCounts: List<InstalledCodeApkAssetCount> = emptyList(),
    val totalAssetCount: Int = 0,
    val truncated: Boolean = false,
)

/** One APK of the package that was opened. */
data class InstalledCodeApkFacts(
    val id: InstalledCodeApkId,
    val sha256: String,
    val dexEntries: List<String> = emptyList(),
)

/** One dex or APK that could not be read; class and message are the observed values. */
data class InstalledCodeReadFailure(
    val apk: InstalledCodeApkId?,
    val dexEntry: String?,
    val errorClass: String,
    val message: String,
)

/** Every fact a complete installed-code read records. */
data class InstalledCodePackageFacts(
    val packageName: String,
    val versionCode: Long,
    val apkSha256: List<InstalledCodeApkDigest>,
    val apks: List<InstalledCodeApkFacts>,
    val markers: List<InstalledCodeMarkerFacts>,
    val readDeviceConfig: InstalledCodePermissionCheckFacts,
    val deviceConfig: InstalledCodeDeviceConfigFacts,
    val assets: InstalledCodeAssetFacts,
    val failures: List<InstalledCodeReadFailure> = emptyList(),
    val referencesTruncated: Boolean = false,
) {
    val complete: Boolean get() = failures.isEmpty()

    fun marker(name: String): InstalledCodeMarkerFacts? = markers.firstOrNull { it.marker == name }
}

/** The result of one installed-code read. Never collapsed into a boolean. */
sealed interface InstalledCodeOutcome {
    val packageName: String

    /** Every `classes*.dex` entry of every APK was read and analysed. */
    data class Read(
        override val packageName: String,
        val facts: InstalledCodePackageFacts,
    ) : InstalledCodeOutcome

    /** At least one dex was read and at least one could not be. */
    data class Partial(
        override val packageName: String,
        val facts: InstalledCodePackageFacts,
    ) : InstalledCodeOutcome

    /** PackageManager did not expose the package (absent or hidden by visibility). */
    data class NotVisible(
        override val packageName: String,
    ) : InstalledCodeOutcome

    /** A fatal failure; the class and message are the observed values. */
    data class ReadError(
        override val packageName: String,
        val errorClass: String,
        val message: String,
    ) : InstalledCodeOutcome

    /** A dex entry or the package total exceeds a configured bound; nothing was parsed. */
    data class TooLarge(
        override val packageName: String,
        val detail: String,
        val versionCode: Long? = null,
        val apkSha256: List<InstalledCodeApkDigest> = emptyList(),
    ) : InstalledCodeOutcome
}

/**
 * One package's installed-code read with the elapsed time it took.
 *
 * The outcome is kept whole, never collapsed into a boolean: [InstalledCodeOutcome.Read]
 * and [InstalledCodeOutcome.Partial] carry the only facts this type can export, and the
 * other outcomes carry the class, message or detail that stopped the read.
 */
data class InstalledCodePackageEvidence(
    val requestedPackage: String,
    val outcome: InstalledCodeOutcome,
    val elapsedMillis: Long,
) {
    val outcomeLabel: String
        get() = when (outcome) {
            is InstalledCodeOutcome.Read -> "read"
            is InstalledCodeOutcome.Partial -> "partial"
            is InstalledCodeOutcome.NotVisible -> "not visible"
            is InstalledCodeOutcome.ReadError -> "read error"
            is InstalledCodeOutcome.TooLarge -> "too large"
        }

    /** The facts the read returned, or null when it returned none. */
    val facts: InstalledCodePackageFacts?
        get() = when (outcome) {
            is InstalledCodeOutcome.Read -> outcome.facts
            is InstalledCodeOutcome.Partial -> outcome.facts
            else -> null
        }
}
