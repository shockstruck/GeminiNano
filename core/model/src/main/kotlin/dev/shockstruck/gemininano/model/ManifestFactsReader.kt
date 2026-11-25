package dev.shockstruck.gemininano.model

import java.io.Closeable

/** Bounds applied to every manifest the reader touches. */
data class ManifestReadLimits(
    /** Maximum bytes read from one APK's `AndroidManifest.xml` entry. */
    val maxBytesPerManifest: Int = 4 * 1024 * 1024,
    /** Maximum recorded permissions, components and matching elements per manifest. */
    val maxRecordedItems: Int = 8192,
    /** Maximum text values kept for one matching element. */
    val maxTextValuesPerElement: Int = 1024,
) {
    init {
        require(maxBytesPerManifest > 0) { "maxBytesPerManifest must be positive" }
        require(maxRecordedItems > 0) { "maxRecordedItems must be positive" }
        require(maxTextValuesPerElement > 0) { "maxTextValuesPerElement must be positive" }
    }
}

/** A sequence of manifest events, closed exactly once by the reader. */
interface ManifestEventStream : Closeable {
    /** The next event, or null when the manifest ended normally. */
    fun nextEvent(): ManifestEvent?
}

/** One APK manifest that can be opened once. */
interface ManifestSourceInput {
    val source: ManifestSourceId

    /** Opens the manifest. Exceptions are reported as a read failure for this source. */
    fun openEvents(): ManifestEventStream
}

/** What a package lookup in the platform boundary produced. */
sealed interface ManifestPackageSources {
    /** The package is absent or hidden by Android's package visibility rules. */
    data object NotVisible : ManifestPackageSources

    data class Visible(
        val effectiveSharedUserId: String? = null,
        val signerCertificateSha256: List<String> = emptyList(),
        val sources: List<ManifestSourceInput> = emptyList(),
    ) : ManifestPackageSources
}

/**
 * Platform boundary for installed-manifest reads. `:core:android` implements this over
 * PackageManager and APK ZIP files; tests supply a fake.
 */
interface ManifestReadInput {
    fun sourcesFor(packageName: String): ManifestPackageSources
}

/**
 * Reads the base and split APK manifests of one installed package as plain facts.
 *
 * Only the requested package's `AndroidManifest.xml` entries are decoded; no APK bytes,
 * dex or model content is copied, cached or exported. The base manifest must read to the
 * end for any facts to be returned. A readable base with an unreadable or oversized
 * split is [ManifestReadOutcome.Partial], never a claim that the split declared nothing.
 */
class ManifestFactsReader(
    private val limits: ManifestReadLimits = ManifestReadLimits(),
) {

    fun read(packageName: String, input: ManifestReadInput): ManifestReadOutcome {
        val sources = try {
            input.sourcesFor(packageName)
        } catch (e: Exception) {
            return ManifestReadOutcome.ReadError(packageName, e.javaClass.name, messageOf(e))
        }

        if (sources !is ManifestPackageSources.Visible) {
            return ManifestReadOutcome.NotVisible(packageName)
        }

        val base = sources.sources.firstOrNull { it.source.kind == ManifestApkKind.BASE }
            ?: return ManifestReadOutcome.ReadError(
                packageName = packageName,
                errorClass = "MissingBaseManifest",
                message = "the package reported no base APK manifest",
            )

        val baseResult = readSource(base)
        if (baseResult.facts == null) {
            val failure = baseResult.failure
            return ManifestReadOutcome.ReadError(
                packageName = packageName,
                errorClass = failure?.errorClass ?: "MissingBaseManifest",
                message = failure?.message ?: "the base APK manifest could not be read",
                source = base.source,
            )
        }
        if (baseResult.facts.declaredPackage != packageName) {
            return ManifestReadOutcome.Mismatch(packageName, baseResult.facts.declaredPackage, base.source)
        }

        val readSources = mutableListOf(baseResult.facts)
        val failures = mutableListOf<ManifestSourceFailure>()
        for (split in sources.sources.filter { it.source.kind == ManifestApkKind.SPLIT }) {
            val result = readSource(split)
            if (result.facts == null) {
                result.failure?.let(failures::add)
                continue
            }
            if (result.facts.declaredPackage != packageName) {
                return ManifestReadOutcome.Mismatch(packageName, result.facts.declaredPackage, split.source)
            }
            readSources += result.facts
        }

        val facts = ManifestPackageFacts(
            packageName = packageName,
            effectiveSharedUserId = sources.effectiveSharedUserId,
            signerCertificateSha256 = sources.signerCertificateSha256,
            sources = readSources,
            failures = failures,
        )
        return if (failures.isEmpty()) {
            ManifestReadOutcome.Read(packageName, facts)
        } else {
            ManifestReadOutcome.Partial(packageName, facts)
        }
    }

    private fun readSource(input: ManifestSourceInput): SourceResult = try {
        val events = input.openEvents()
        try {
            SourceResult(input.source, ManifestFactsParser(limits).parse(input.source, events), null)
        } finally {
            try {
                events.close()
            } catch (_: Exception) {
                // A close failure cannot change facts already parsed from this source.
            }
        }
    } catch (e: Exception) {
        SourceResult(
            source = input.source,
            facts = null,
            failure = ManifestSourceFailure(input.source, e.javaClass.name, messageOf(e)),
        )
    }

    private fun messageOf(e: Exception): String =
        e.message?.takeIf { it.isNotBlank() } ?: "no message"

    private data class SourceResult(
        val source: ManifestSourceId,
        val facts: ManifestSourceFacts?,
        val failure: ManifestSourceFailure?,
    )
}
