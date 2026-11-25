package dev.shockstruck.gemininano.model

import java.util.Locale

/**
 * Decodes Android's binary XML format (`ResXMLTree`, the format of an APK's
 * `AndroidManifest.xml`) into [ManifestEvent]s.
 *
 * This reads the manifest entry only and never keeps or returns the raw bytes: the
 * input is one bounded manifest entry, decoded in memory, and the events carry declared
 * attribute values only. Malformed or oversized input raises [ManifestFormatException]
 * or [ManifestSizeLimitException] instead of producing partial facts.
 */
class ManifestFactsAxmlDecoder(
    private val bytes: ByteArray,
    private val limits: ManifestReadLimits = ManifestReadLimits(),
) : ManifestEventStream {

    private val pending = ArrayDeque<ManifestEvent>()
    private var strings: List<String?> = emptyList()
    private var resourceMap: IntArray = IntArray(0)
    private var offset = 0
    private var end = 0

    init {
        end = bytes.size
        if (bytes.size < MIN_XML_HEADER) {
            throw ManifestFormatException("manifest is shorter than the binary XML header")
        }
        if (bytes.size > limits.maxBytesPerManifest) {
            throw ManifestSizeLimitException(
                "manifest is ${bytes.size} bytes, above the ${limits.maxBytesPerManifest} byte limit",
            )
        }
        if (u16(0) != RES_XML_TYPE) {
            throw ManifestFormatException("not Android binary XML (root chunk type ${u16(0)})")
        }
        val headerSize = u16(2)
        val declaredSize = u32(4)
        if (headerSize < MIN_XML_HEADER) {
            throw ManifestFormatException("binary XML header size $headerSize is too small")
        }
        if (declaredSize < headerSize || declaredSize > bytes.size) {
            throw ManifestFormatException("binary XML declares $declaredSize bytes in a ${bytes.size} byte buffer")
        }
        offset = headerSize
        end = declaredSize
    }

    override fun nextEvent(): ManifestEvent? {
        while (pending.isEmpty()) {
            if (offset >= end) return null
            parseChunk()
        }
        return pending.removeFirst()
    }

    override fun close() {
        // No external resource is held; the bounded byte array is released with the decoder.
    }

    private fun parseChunk() {
        requireRange(offset, MIN_CHUNK_HEADER)
        val type = u16(offset)
        val headerSize = u16(offset + 2)
        val size = u32(offset + 4)
        if (headerSize < MIN_CHUNK_HEADER || size < headerSize) {
            throw ManifestFormatException("chunk at $offset has header $headerSize and size $size")
        }
        if (size > end - offset) {
            throw ManifestFormatException("chunk at $offset of $size bytes exceeds the manifest")
        }
        when (type) {
            RES_STRING_POOL_TYPE -> parseStringPool(offset, headerSize, size)
            RES_XML_RESOURCE_MAP_TYPE -> parseResourceMap(offset, headerSize, size)
            RES_XML_START_ELEMENT_TYPE -> parseStartElement(offset, headerSize, size)
            RES_XML_END_ELEMENT_TYPE -> parseEndElement(offset, headerSize, size)
            RES_XML_CDATA_TYPE -> parseText(offset, headerSize, size)
            else -> Unit
        }
        offset += size
    }

    private fun parseStringPool(chunkStart: Int, headerSize: Int, size: Int) {
        if (headerSize < STRING_POOL_HEADER) {
            throw ManifestFormatException("string pool header is $headerSize bytes")
        }
        val chunkEnd = chunkStart + size
        val stringCount = u32(chunkStart + 8)
        val flags = u32(chunkStart + 16)
        val stringsStart = u32(chunkStart + 20)
        if (stringCount < 0 || stringCount > MAX_STRINGS) {
            throw ManifestSizeLimitException("string pool declares $stringCount strings")
        }
        val offsetsAt = chunkStart + headerSize
        if (offsetsAt > chunkEnd - stringCount * 4) {
            throw ManifestFormatException("string pool offsets exceed the chunk")
        }
        val dataAt = chunkStart + stringsStart
        if (dataAt < chunkStart || dataAt > chunkEnd) {
            throw ManifestFormatException("string pool data starts outside the chunk")
        }
        val utf8 = (flags and UTF8_FLAG) != 0
        strings = List(stringCount) { index ->
            val relative = u32(offsetsAt + index * 4)
            if (relative < 0) throw ManifestFormatException("string pool offset is negative")
            val at = dataAt + relative
            if (at < dataAt || at >= chunkEnd) {
                throw ManifestFormatException("string pool entry $index points outside the chunk")
            }
            readPoolString(at, chunkEnd, utf8)
        }
    }

    private fun readPoolString(at: Int, limit: Int, utf8: Boolean): String {
        if (utf8) {
            // A UTF-8 entry carries the UTF-16 length first, then the byte length;
            // each prefix is one byte, or two when its high bit is set.
            val afterUtf16Length = skipUtf8Length(at, limit)
            val (byteLength, dataAt) = readUtf8Length(afterUtf16Length, limit)
            if (byteLength < 0 || dataAt > limit - byteLength) {
                throw ManifestFormatException("UTF-8 string exceeds the string pool")
            }
            return String(bytes, dataAt, byteLength, Charsets.UTF_8)
        }
        var cursor = at
        var length = u16(cursor)
        cursor += 2
        if (length and 0x8000 != 0) {
            length = ((length and 0x7fff) shl 16) or u16(cursor)
            cursor += 2
        }
        if (length < 0 || cursor > limit - length * 2) {
            throw ManifestFormatException("UTF-16 string exceeds the string pool")
        }
        return String(bytes, cursor, length * 2, Charsets.UTF_16LE)
    }

    /** Skips one UTF-8 length prefix (one byte, or two when the high bit is set). */
    private fun skipUtf8Length(at: Int, limit: Int): Int {
        requireStringByte(at, limit)
        return if (u8(at) and 0x80 != 0) {
            requireStringByte(at + 1, limit)
            at + 2
        } else {
            at + 1
        }
    }

    /** Decodes one UTF-8 length prefix and returns it with the position after it. */
    private fun readUtf8Length(at: Int, limit: Int): Pair<Int, Int> {
        requireStringByte(at, limit)
        val first = u8(at)
        return if (first and 0x80 != 0) {
            requireStringByte(at + 1, limit)
            (((first and 0x7f) shl 8) or u8(at + 1)) to (at + 2)
        } else {
            first to (at + 1)
        }
    }

    private fun requireStringByte(at: Int, limit: Int) {
        if (at < 0 || at >= limit) {
            throw ManifestFormatException("string length prefix exceeds the string pool")
        }
    }

    private fun parseResourceMap(chunkStart: Int, headerSize: Int, size: Int) {
        val count = (size - headerSize) / 4
        if (count < 0) throw ManifestFormatException("resource map chunk is too small")
        resourceMap = IntArray(count) { index -> u32(chunkStart + headerSize + index * 4) }
    }

    private fun parseStartElement(chunkStart: Int, headerSize: Int, size: Int) {
        val chunkEnd = chunkStart + size
        val extensionAt = chunkStart + headerSize
        if (headerSize < START_ELEMENT_HEADER || extensionAt > chunkEnd - ATTRIBUTE_EXTENSION_SIZE) {
            throw ManifestFormatException("start element chunk at $chunkStart is too small")
        }
        val nameIndex = u32(extensionAt + 4)
        val attributeStart = u16(extensionAt + 8)
        val attributeSize = u16(extensionAt + 10)
        val attributeCount = u16(extensionAt + 12)
        if (attributeSize < ATTRIBUTE_SIZE) {
            throw ManifestFormatException("attribute size $attributeSize is below $ATTRIBUTE_SIZE")
        }
        if (attributeCount > MAX_ATTRIBUTES) {
            throw ManifestSizeLimitException("element declares $attributeCount attributes")
        }
        val attributesAt = extensionAt + attributeStart
        if (attributesAt < extensionAt || attributesAt > chunkEnd - attributeCount * attributeSize) {
            throw ManifestFormatException("attributes exceed the start element chunk")
        }
        val name = stringOrNull(nameIndex)
            ?: throw ManifestFormatException("element name index $nameIndex is missing")
        val attributes = List(attributeCount) { index ->
            readAttribute(attributesAt + index * attributeSize, chunkEnd)
        }
        pending.addLast(ManifestEvent.StartElement(name, attributes))
    }

    private fun readAttribute(at: Int, limit: Int): ManifestAttribute {
        if (at < 0 || at > limit - ATTRIBUTE_SIZE) {
            throw ManifestFormatException("attribute at $at exceeds the chunk")
        }
        val nameIndex = u32(at + 4)
        val rawIndex = u32(at + 8)
        val dataType = u8(at + 15)
        val data = u32(at + 16)
        val name = stringOrNull(nameIndex) ?: ""
        val raw = stringOrNull(rawIndex)
        return ManifestAttribute(
            name = name,
            namespace = stringOrNull(u32(at)),
            resourceId = resourceIdFor(nameIndex),
            rawValue = raw,
            typedValue = decodeValue(dataType, data, raw),
            dataType = dataTypeName(dataType),
        )
    }

    private fun parseEndElement(chunkStart: Int, headerSize: Int, size: Int) {
        val extensionAt = chunkStart + headerSize
        if (headerSize < END_ELEMENT_HEADER || extensionAt > chunkStart + size - 8) {
            throw ManifestFormatException("end element chunk at $chunkStart is too small")
        }
        val name = stringOrNull(u32(extensionAt + 4))
            ?: throw ManifestFormatException("end element name index is missing")
        pending.addLast(ManifestEvent.EndElement(name))
    }

    private fun parseText(chunkStart: Int, headerSize: Int, size: Int) {
        val extensionAt = chunkStart + headerSize
        if (headerSize < CDATA_HEADER || extensionAt > chunkStart + size - 4) {
            throw ManifestFormatException("CDATA chunk at $chunkStart is too small")
        }
        val value = stringOrNull(u32(extensionAt)) ?: return
        pending.addLast(ManifestEvent.Text(value))
    }

    private fun stringOrNull(index: Int): String? = when {
        index == NO_ENTRY -> null
        index < 0 || index >= strings.size ->
            throw ManifestFormatException("string index $index is outside the string pool")
        else -> strings[index]
    }

    private fun resourceIdFor(nameIndex: Int): Int? =
        resourceMap.getOrNull(nameIndex)?.takeIf { it != 0 }

    private fun decodeValue(dataType: Int, data: Int, raw: String?): String = when (dataType) {
        TYPE_NULL -> "null"
        TYPE_REFERENCE -> "@0x%08x".format(Locale.US, data)
        TYPE_ATTRIBUTE -> "?0x%08x".format(Locale.US, data)
        TYPE_STRING -> raw ?: stringOrNull(data).orEmpty()
        TYPE_FLOAT -> Float.fromBits(data).toString()
        TYPE_INT_DEC -> data.toString()
        TYPE_INT_HEX -> "0x%08x".format(Locale.US, data)
        TYPE_INT_BOOLEAN -> (data != 0).toString()
        else -> "0x%08x".format(Locale.US, data)
    }

    private fun dataTypeName(dataType: Int): String = when (dataType) {
        TYPE_NULL -> "null"
        TYPE_REFERENCE -> "reference"
        TYPE_ATTRIBUTE -> "attribute"
        TYPE_STRING -> "string"
        TYPE_FLOAT -> "float"
        TYPE_DIMENSION -> "dimension"
        TYPE_FRACTION -> "fraction"
        TYPE_INT_DEC -> "int-dec"
        TYPE_INT_HEX -> "int-hex"
        TYPE_INT_BOOLEAN -> "boolean"
        in TYPE_COLOR_RANGE -> "color"
        else -> "unknown:0x%02x".format(Locale.US, dataType)
    }

    private fun u8(at: Int): Int {
        requireRange(at, 1)
        return bytes[at].toInt() and 0xFF
    }

    private fun u16(at: Int): Int {
        requireRange(at, 2)
        return (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
    }

    private fun u32(at: Int): Int {
        requireRange(at, 4)
        return (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)
    }

    private fun requireRange(at: Int, length: Int) {
        if (at < 0 || length < 0 || at > end - length) {
            throw ManifestFormatException("read of $length byte(s) at $at is outside the manifest")
        }
    }

    private companion object {
        const val RES_STRING_POOL_TYPE = 0x0001
        const val RES_XML_TYPE = 0x0003
        const val RES_XML_START_ELEMENT_TYPE = 0x0102
        const val RES_XML_END_ELEMENT_TYPE = 0x0103
        const val RES_XML_CDATA_TYPE = 0x0104
        const val RES_XML_RESOURCE_MAP_TYPE = 0x0180

        const val UTF8_FLAG = 0x100
        const val NO_ENTRY = -1

        const val MIN_XML_HEADER = 8
        const val MIN_CHUNK_HEADER = 8
        const val STRING_POOL_HEADER = 28
        const val START_ELEMENT_HEADER = 16
        const val END_ELEMENT_HEADER = 16
        const val CDATA_HEADER = 16
        const val ATTRIBUTE_EXTENSION_SIZE = 20
        const val ATTRIBUTE_SIZE = 20
        const val MAX_ATTRIBUTES = 4096
        const val MAX_STRINGS = 65536

        const val TYPE_NULL = 0x00
        const val TYPE_REFERENCE = 0x01
        const val TYPE_ATTRIBUTE = 0x02
        const val TYPE_STRING = 0x03
        const val TYPE_FLOAT = 0x04
        const val TYPE_DIMENSION = 0x05
        const val TYPE_FRACTION = 0x06
        const val TYPE_INT_DEC = 0x10
        const val TYPE_INT_HEX = 0x11
        const val TYPE_INT_BOOLEAN = 0x12
        val TYPE_COLOR_RANGE = 0x1C..0x1F
    }
}
