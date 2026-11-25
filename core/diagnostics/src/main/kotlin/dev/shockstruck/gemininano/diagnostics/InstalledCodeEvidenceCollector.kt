package dev.shockstruck.gemininano.diagnostics

import dev.shockstruck.gemininano.model.InstalledCodeOutcome
import dev.shockstruck.gemininano.model.InstalledCodePackageEvidence

/**
 * Reads several packages' installed code, one at a time, and turns any failure into that
 * package's own [InstalledCodeOutcome.ReadError].
 *
 * One package's failure — including an unexpected `Throwable` from the reader — never
 * removes another package's read, and never propagates to the caller. The elapsed time of
 * every read is recorded beside its outcome.
 */
class InstalledCodeEvidenceCollector(
    private val read: (String) -> InstalledCodeOutcome,
    private val elapsedClock: () -> Long,
) {

    fun collect(packages: List<String>): List<InstalledCodePackageEvidence> =
        packages.map { packageName -> collectOne(packageName) }

    private fun collectOne(packageName: String): InstalledCodePackageEvidence {
        val startedAt = elapsedClock()
        val outcome = try {
            read(packageName)
        } catch (t: Throwable) {
            InstalledCodeOutcome.ReadError(
                packageName = packageName,
                errorClass = t.javaClass.name,
                message = t.message ?: "(no message)",
            )
        }
        return InstalledCodePackageEvidence(
            requestedPackage = packageName,
            outcome = outcome,
            elapsedMillis = (elapsedClock() - startedAt).coerceAtLeast(0L),
        )
    }
}
