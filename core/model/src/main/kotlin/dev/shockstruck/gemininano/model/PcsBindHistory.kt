package dev.shockstruck.gemininano.model

/**
 * One persisted bind observation, with the context needed to judge it later.
 *
 * [result] is always an attempt that actually ran; a planned but unattempted row is never
 * stored, so a later `NOT RUN` can never overwrite an earlier observation.
 */
data class PcsBindHistoryEntry(
    val result: PcsBindResult,
    val observedAtEpochMillis: Long,
    val appVersionName: String,
    val appVersionCode: Long,
    /** `PackageInfo.versionCode` of the PCS package when the bind ran, or null if unread. */
    val pcsVersionCode: Long?,
)

/** Every stored bind observation, oldest first. */
data class PcsBindHistory(
    val entries: List<PcsBindHistoryEntry> = emptyList(),
) {
    /** The most recent stored observation for one component, or null when none exists. */
    fun latestFor(componentName: String): PcsBindHistoryEntry? =
        entries.withIndex()
            .filter { it.value.result.componentName == componentName }
            .maxWithOrNull(compareBy({ it.value.observedAtEpochMillis }, { it.index }))
            ?.value

    val componentCount: Int get() = entries.map { it.result.componentName }.distinct().size
}

/** A loaded history plus the note the UI and the report must show beside it. */
data class PcsBindHistorySnapshot(
    val history: PcsBindHistory = PcsBindHistory(),
    /** Non-null when storage was missing or unreadable; the history is then empty. */
    val note: String? = null,
)

/** The explicit text used when app-private storage holds no readable history. */
object PcsBindHistoryNotes {
    const val MISSING = "No PCS bind history is stored on this device yet."

    fun corrupt(reason: String): String =
        "Stored PCS bind history could not be read ($reason). It is ignored; no history is shown."
}

/** Bounded, append-only policy for the stored observations. */
object PcsBindHistoryPolicy {

    /** The last five results per component are kept; older ones fall away. */
    const val MAX_RESULTS_PER_COMPONENT = 5

    /**
     * Adds one observation. A result whose outcome was not attempted (`NOT RUN`, not
     * declared, not exported, …) is not an observation and changes nothing.
     */
    fun record(history: PcsBindHistory, entry: PcsBindHistoryEntry): PcsBindHistory {
        if (!entry.result.outcome.attempted) return history
        return PcsBindHistory(trim(history.entries + entry))
    }

    fun recordAll(
        history: PcsBindHistory,
        entries: List<PcsBindHistoryEntry>,
    ): PcsBindHistory = entries.fold(history) { current, entry -> record(current, entry) }

    /** Keeps the newest [MAX_RESULTS_PER_COMPONENT] entries of every component. */
    fun trim(entries: List<PcsBindHistoryEntry>): List<PcsBindHistoryEntry> =
        entries.groupBy { it.result.componentName }
            .values
            .flatMap { perComponent ->
                perComponent.sortedBy { it.observedAtEpochMillis }
                    .takeLast(MAX_RESULTS_PER_COMPONENT)
            }
            .sortedBy { it.observedAtEpochMillis }
}

/** The result of decoding stored text; decoding never throws. */
sealed interface PcsBindHistoryDecode {
    data class Decoded(val history: PcsBindHistory) : PcsBindHistoryDecode
    data class Corrupt(val reason: String) : PcsBindHistoryDecode
}

/**
 * Line-oriented codec for app-private storage. It is deliberately strict: an unknown
 * field count, an unknown escape, an unknown outcome name or an unparsable number is
 * reported as [PcsBindHistoryDecode.Corrupt] instead of being guessed at. Nothing in the
 * stored text can make decoding throw.
 */
object PcsBindHistoryCodec {

    const val HEADER = "gemininano-pcs-bind-history/1"

    private const val FIELD_SEPARATOR = '|'
    private const val FIELD_COUNT = 22
    private const val NULL = "~"
    private const val MAX_TEXT_LENGTH = 256 * 1024
    private const val MAX_ENTRIES = 500

    fun encode(history: PcsBindHistory): String {
        val out = StringBuilder()
        out.append(HEADER).append('\n')
        history.entries.forEach { entry ->
            val result = entry.result
            out.append(
                listOf(
                    encodeText(result.componentName) ?: "",
                    result.outcome.name,
                    encodeText(result.exceptionClass),
                    encodeText(result.exceptionMessage),
                    encodeText(result.interfaceDescriptor),
                    result.elapsedMillis.toString(),
                    result.serviceDisconnected.toString(),
                    result.bindingDied.toString(),
                    result.unbindCalls.toString(),
                    encodeText(result.role),
                    encodeText(result.declaredName),
                    encodeText(result.requiredPermission),
                    encodeText(result.bindFlag) ?: "",
                    result.binderAlive?.toString() ?: NULL,
                    result.descriptorReadMillis?.toString() ?: NULL,
                    encodeText(result.descriptorErrorClass),
                    encodeText(result.descriptorErrorMessage),
                    result.descriptorTimedOut.toString(),
                    entry.observedAtEpochMillis.toString(),
                    encodeText(entry.appVersionName) ?: "",
                    entry.appVersionCode.toString(),
                    entry.pcsVersionCode?.toString() ?: NULL,
                ).joinToString(FIELD_SEPARATOR.toString()),
            ).append('\n')
        }
        return out.toString()
    }

    fun decode(text: String): PcsBindHistoryDecode {
        if (text.isBlank()) {
            return PcsBindHistoryDecode.Corrupt("the stored value is empty")
        }
        if (text.length > MAX_TEXT_LENGTH) {
            return PcsBindHistoryDecode.Corrupt(
                "the stored value is larger than the ${MAX_TEXT_LENGTH / 1024} KiB bound",
            )
        }
        val lines = text.split('\n')
        if (lines.firstOrNull() != HEADER) {
            return PcsBindHistoryDecode.Corrupt("the stored value has no recognised header")
        }
        val entryLines = lines.drop(1).filter { it.isNotEmpty() }
        if (entryLines.size > MAX_ENTRIES) {
            return PcsBindHistoryDecode.Corrupt(
                "the stored value has more than $MAX_ENTRIES entries",
            )
        }
        return try {
            val entries = entryLines.mapIndexed { index, line ->
                try {
                    decodeEntry(line)
                } catch (t: RuntimeException) {
                    throw IllegalArgumentException(
                        "entry ${index + 1}: ${t.message ?: t.javaClass.simpleName}",
                        t,
                    )
                }
            }
            PcsBindHistoryDecode.Decoded(
                PcsBindHistory(PcsBindHistoryPolicy.trim(entries)),
            )
        } catch (t: RuntimeException) {
            PcsBindHistoryDecode.Corrupt(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun decodeEntry(line: String): PcsBindHistoryEntry {
        val parts = line.split(FIELD_SEPARATOR)
        if (parts.size != FIELD_COUNT) {
            throw IllegalArgumentException("expected $FIELD_COUNT fields, found ${parts.size}")
        }
        val componentName = requiredText(parts[0], "componentName")
        val outcome = PcsBindOutcome.entries.firstOrNull { it.name == parts[1] }
            ?: throw IllegalArgumentException("unknown outcome \"${parts[1]}\"")
        return PcsBindHistoryEntry(
            result = PcsBindResult(
                componentName = componentName,
                outcome = outcome,
                exceptionClass = decodeText(parts[2]),
                exceptionMessage = decodeText(parts[3]),
                interfaceDescriptor = decodeText(parts[4]),
                elapsedMillis = requiredLong(parts[5], "elapsedMillis"),
                serviceDisconnected = requiredBoolean(parts[6], "serviceDisconnected"),
                bindingDied = requiredBoolean(parts[7], "bindingDied"),
                unbindCalls = requiredInt(parts[8], "unbindCalls"),
                role = decodeText(parts[9]),
                declaredName = decodeText(parts[10]),
                requiredPermission = decodeText(parts[11]),
                bindFlag = requiredText(parts[12], "bindFlag"),
                binderAlive = nullableBoolean(parts[13], "binderAlive"),
                descriptorReadMillis = nullableLong(parts[14], "descriptorReadMillis"),
                descriptorErrorClass = decodeText(parts[15]),
                descriptorErrorMessage = decodeText(parts[16]),
                descriptorTimedOut = requiredBoolean(parts[17], "descriptorTimedOut"),
            ),
            observedAtEpochMillis = requiredLong(parts[18], "observedAtEpochMillis"),
            appVersionName = requiredText(parts[19], "appVersionName"),
            appVersionCode = requiredLong(parts[20], "appVersionCode"),
            pcsVersionCode = nullableLong(parts[21], "pcsVersionCode"),
        )
    }

    private fun encodeText(value: String?): String? {
        if (value == null) return NULL
        val out = StringBuilder(value.length)
        value.forEach { ch ->
            when (ch) {
                '%' -> out.append("%25")
                '|' -> out.append("%7C")
                '~' -> out.append("%7E")
                '\n' -> out.append("%0A")
                '\r' -> out.append("%0D")
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    private fun decodeText(value: String): String? {
        if (value == NULL) return null
        if (value.isEmpty()) return ""
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            if (ch != '%') {
                out.append(ch)
                index++
                continue
            }
            if (index + 3 > value.length) {
                throw IllegalArgumentException("a text field has a truncated escape")
            }
            val escape = value.substring(index + 1, index + 3)
            out.append(
                when (escape.lowercase()) {
                    "25" -> '%'
                    "7c" -> '|'
                    "7e" -> '~'
                    "0a" -> '\n'
                    "0d" -> '\r'
                    else -> throw IllegalArgumentException("unknown escape \"%$escape\"")
                },
            )
            index += 3
        }
        return out.toString()
    }

    private fun requiredText(value: String, name: String): String =
        decodeText(value) ?: throw IllegalArgumentException("$name is null")

    private fun requiredLong(value: String, name: String): Long =
        value.toLongOrNull() ?: throw IllegalArgumentException("$name is not a long")

    private fun nullableLong(value: String, name: String): Long? =
        if (value == NULL) null else requiredLong(value, name)

    private fun requiredInt(value: String, name: String): Int =
        value.toIntOrNull() ?: throw IllegalArgumentException("$name is not an int")

    private fun requiredBoolean(value: String, name: String): Boolean =
        when (value) {
            "true" -> true
            "false" -> false
            else -> throw IllegalArgumentException("$name is not a boolean")
        }

    private fun nullableBoolean(value: String, name: String): Boolean? =
        if (value == NULL) null else requiredBoolean(value, name)
}
