package dev.shockstruck.gemininano.model

/** The input is not a DEX file this reader understands, or it is structurally invalid. */
class InstalledCodeFormatException(message: String) : Exception(message)

/** One method reference from the method table. */
data class InstalledCodeMethodRef(
    val className: String,
    val name: String,
    val parameterTypes: List<String>,
    val returnType: String,
) {
    val descriptor: String
        get() = "$className->$name(${parameterTypes.joinToString("")})$returnType"
}

/** One method encoded in a class definition. */
data class InstalledCodeEncodedMethod(
    val methodIndex: Int,
    val accessFlags: Int,
    val codeOffset: Int,
) {
    val isStatic: Boolean get() = accessFlags and ACCESS_FLAG_STATIC != 0

    private companion object {
        const val ACCESS_FLAG_STATIC = 0x0008
    }
}

/** One class definition with the methods it encodes. */
data class InstalledCodeClassDef(
    val className: String,
    val methods: List<InstalledCodeEncodedMethod>,
)

/** One `code_item`: metadata only; instructions are read through [InstalledCodeDex.instructions]. */
data class InstalledCodeCodeItem(
    val offset: Int,
    val registersSize: Int,
    val insSize: Int,
    val instructionUnits: Int,
    val triesSize: Int,
)

/** What a constant instruction produced. */
enum class InstalledCodeConstKind {
    NONE,

    /** A 32-bit integer constant (`const/4`, `const/16`, `const`, `const/high16`). */
    INT,

    /** A wide (64-bit) constant; never a boolean, int or string default. */
    WIDE,

    /** A string constant (`const-string`, `const-string/jumbo`). */
    STRING,

    /** A constant of another kind (class, method handle, method type). */
    OTHER,
}

/** One decoded instruction. Instances are transient; none is retained by a result. */
class InstalledCodeInstruction internal constructor(
    val addressUnits: Int,
    val opcode: Int,
    val sizeUnits: Int,
    val destinationRegister: Int,
    val destinationIsWide: Boolean,
    val isBranch: Boolean,
    val isInvoke: Boolean,
    val isStaticInvoke: Boolean,
    val invokeMethodIndex: Int,
    val invokeRegisters: IntArray,
    val isMoveObject: Boolean,
    val moveSourceRegister: Int,
    val constKind: InstalledCodeConstKind,
    val constIntValue: Int,
    val constStringIndex: Int,
    val branchOffsetUnits: Int,
    val switchPayloadUnits: Int,
    val isPayload: Boolean,
)

/**
 * A pure reader for the DEX tables and code items of one dex file.
 *
 * Versions 035 through 041 are accepted; any other version is a
 * [InstalledCodeFormatException], as is any structure whose offsets, sizes or indices
 * fall outside the file. The checksum and signature fields are not verified, because
 * `classes*.dex` entries copied from an installed APK are read, not executed.
 *
 * Alignment follows the DEX format specification and ART's verifier: the string, type,
 * proto, field, method and class definition sections, the link section and the map list
 * are four-byte offset-aligned (constraints G7); `type_list` (interfaces and proto
 * parameters), `annotations_directory_item` and `code_item` are four-byte aligned;
 * `class_data_item`, `encoded_array_item` (static values) and the data section are
 * byte-aligned, and the data section is only range-checked because ART accepts it
 * unaligned (`dex_file_verifier.cc`, the data check with `alignment = 0`).
 *
 * Strings are decoded on demand. Nothing outside the tables, decoded strings and code
 * items is exposed, and no bytecode is retained after the caller drops this object.
 */
class InstalledCodeDex private constructor(
    private val bytes: ByteArray,
    private val header: Header,
) {

    private val methodCache = HashMap<Int, InstalledCodeMethodRef>()

    private val typeCache = HashMap<Int, String>()

    val version: Int get() = header.version

    val versionName: String get() = header.versionName

    val stringCount: Int get() = header.stringIdsSize

    val typeCount: Int get() = header.typeIdsSize

    val protoCount: Int get() = header.protoIdsSize

    val methodCount: Int get() = header.methodIdsSize

    val classCount: Int get() = header.classDefsSize

    /** Decodes string table entry [index]. */
    fun string(index: Int): String {
        requireIndex(index, header.stringIdsSize, "string")
        val dataOffset = readInt(header.stringIdsOffset + index * STRING_ID_SIZE)
        val cursor = Cursor(dataOffset)
        val utf16Length = uleb128(cursor)
        val start = cursor.offset
        var end = start
        while (end < header.limit && bytes[end] != 0.toByte()) end++
        if (end >= header.limit) {
            throw format("string $index has no terminator inside the file")
        }
        val value = decodeMutf8(start, end)
        if (value.length != utf16Length) {
            throw format("string $index declares $utf16Length UTF-16 units but decodes to ${value.length}")
        }
        return value
    }

    /** The type descriptor for type table entry [index]. */
    fun typeDescriptor(index: Int): String {
        requireIndex(index, header.typeIdsSize, "type")
        typeCache[index]?.let { return it }
        val stringIndex = readInt(header.typeIdsOffset + index * TYPE_ID_SIZE)
        return string(stringIndex).also { typeCache[index] = it }
    }

    /** The method reference for method table entry [index]. */
    fun method(index: Int): InstalledCodeMethodRef {
        requireIndex(index, header.methodIdsSize, "method")
        methodCache[index]?.let { return it }
        val base = header.methodIdsOffset + index * METHOD_ID_SIZE
        val classIndex = readShort(base)
        val protoIndex = readShort(base + 2)
        val nameIndex = readInt(base + 4)
        val proto = proto(protoIndex)
        return InstalledCodeMethodRef(
            className = typeDescriptor(classIndex),
            name = string(nameIndex),
            parameterTypes = proto.parameterTypes,
            returnType = proto.returnType,
        ).also { methodCache[index] = it }
    }

    /** The class definition for class table entry [index]. */
    fun classDef(index: Int): InstalledCodeClassDef {
        requireIndex(index, header.classDefsSize, "class definition")
        val base = header.classDefsOffset + index * CLASS_DEF_SIZE
        val classIndex = readInt(base)
        val classDataOffset = readInt(base + 24)
        val methods = if (classDataOffset == 0) {
            emptyList()
        } else {
            readClassData(classDataOffset)
        }
        return InstalledCodeClassDef(typeDescriptor(classIndex), methods)
    }

    /** Parses one `code_item` header at [offset]. */
    fun codeItem(offset: Int): InstalledCodeCodeItem {
        if (offset % 4 != 0) throw format("code item at $offset is not four-byte aligned")
        requireRange(offset.toLong(), CODE_ITEM_HEADER_SIZE.toLong(), "code item")
        val registersSize = readShort(offset)
        val insSize = readShort(offset + 2)
        val triesSize = readShort(offset + 6)
        val instructionUnits = readInt(offset + 12)
        val instructionsStart = offset.toLong() + CODE_ITEM_HEADER_SIZE
        requireRange(
            instructionsStart,
            instructionUnits.toLong() * 2,
            "code item instructions",
        )
        if (triesSize > 0) {
            val tryItemsStart = align4(instructionsStart + instructionUnits.toLong() * 2)
            requireRange(tryItemsStart, triesSize.toLong() * TRY_ITEM_SIZE, "try items")
        }
        return InstalledCodeCodeItem(
            offset = offset,
            registersSize = registersSize,
            insSize = insSize,
            instructionUnits = instructionUnits,
            triesSize = triesSize,
        )
    }

    /** Iterates the instructions of [codeItem] in address order. */
    fun instructions(codeItem: InstalledCodeCodeItem): Iterator<InstalledCodeInstruction> =
        InstructionIterator(
            bytes = bytes,
            start = codeItem.offset + CODE_ITEM_HEADER_SIZE,
            unitCount = codeItem.instructionUnits,
            limit = header.limit,
        )

    /** Every instruction address a branch or switch in [codeItem] can target. */
    fun branchTargets(codeItem: InstalledCodeCodeItem): IntArray {
        val targets = sortedSetOf<Int>()
        val iterator = instructions(codeItem)
        while (iterator.hasNext()) {
            val instruction = iterator.next()
            if (!instruction.isBranch || instruction.isPayload) continue
            when (instruction.opcode) {
                0x28, 0x29, 0x2a, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37,
                0x38, 0x39, 0x3a, 0x3b, 0x3c, 0x3d,
                -> {
                    val target = instruction.addressUnits + instruction.branchOffsetUnits
                    requireCodeAddress(target, codeItem)
                    targets += target
                }

                0x2b, 0x2c -> switchTargets(codeItem, instruction).forEach { target ->
                    requireCodeAddress(target, codeItem)
                    targets += target
                }
            }
        }
        return targets.toIntArray()
    }

    /** Every exception handler address declared by [codeItem]'s try items. */
    fun handlerAddresses(codeItem: InstalledCodeCodeItem): IntArray {
        if (codeItem.triesSize == 0) return IntArray(0)
        val instructionsEnd = codeItem.offset.toLong() + CODE_ITEM_HEADER_SIZE +
            codeItem.instructionUnits.toLong() * 2
        val triesStart = align4(instructionsEnd)
        val triesLength = codeItem.triesSize.toLong() * TRY_ITEM_SIZE
        requireRange(triesStart, triesLength, "try items")
        val handlersStart = triesStart + triesLength
        val offsets = sortedSetOf<Int>()
        for (index in 0 until codeItem.triesSize) {
            offsets += readShort((triesStart + index.toLong() * TRY_ITEM_SIZE + 6).toInt())
        }
        val addresses = sortedSetOf<Int>()
        for (offset in offsets) {
            val cursor = Cursor(handlersStart.toInt() + offset)
            val size = sleb128(cursor)
            val pairs = if (size <= 0) -size else size
            repeat(pairs) {
                uleb128(cursor)
                addresses += uleb128(cursor)
            }
            if (size <= 0) addresses += uleb128(cursor)
        }
        for (address in addresses) requireCodeAddress(address, codeItem)
        return addresses.toIntArray()
    }

    private fun switchTargets(
        codeItem: InstalledCodeCodeItem,
        instruction: InstalledCodeInstruction,
    ): List<Int> {
        val payload = instruction.addressUnits + instruction.switchPayloadUnits
        val identity = codeUnit(codeItem, payload)
        val size = codeUnit(codeItem, payload + 1)
        val targets = ArrayList<Int>(size)
        when (identity) {
            0x0100 -> repeat(size) { index ->
                val low = codeUnit(codeItem, payload + 4 + index * 2)
                val high = codeUnit(codeItem, payload + 5 + index * 2)
                targets += instruction.addressUnits + (low or (high shl 16))
            }

            0x0200 -> {
                val targetsStart = payload + 2 + size * 2
                repeat(size) { index ->
                    val low = codeUnit(codeItem, targetsStart + index * 2)
                    val high = codeUnit(codeItem, targetsStart + index * 2 + 1)
                    targets += instruction.addressUnits + (low or (high shl 16))
                }
            }

            else -> throw format(
                "switch at ${instruction.addressUnits} points at payload identity 0x${identity.toString(16)}",
            )
        }
        return targets
    }

    private fun codeUnit(codeItem: InstalledCodeCodeItem, address: Int): Int {
        requireCodeAddress(address, codeItem)
        return readShort(codeItem.offset + CODE_ITEM_HEADER_SIZE + address * 2)
    }

    private fun requireCodeAddress(address: Int, codeItem: InstalledCodeCodeItem) {
        if (address < 0 || address >= codeItem.instructionUnits) {
            throw format("code address $address is outside a code item of ${codeItem.instructionUnits} units")
        }
    }

    private data class Proto(
        val returnType: String,
        val parameterTypes: List<String>,
    )

    private fun proto(index: Int): Proto {
        requireIndex(index, header.protoIdsSize, "proto")
        val base = header.protoIdsOffset + index * PROTO_ID_SIZE
        val returnTypeIndex = readInt(base + 4)
        val parametersOffset = readInt(base + 8)
        val parameters = if (parametersOffset == 0) {
            emptyList()
        } else {
            requireRange(parametersOffset.toLong(), 4, "proto parameters")
            val size = readInt(parametersOffset)
            requireRange(
                parametersOffset.toLong() + 4,
                size.toLong() * TYPE_LIST_ENTRY_SIZE,
                "proto parameter list",
            )
            List(size) { position ->
                val typeIndex = readShort(parametersOffset + 4 + position * TYPE_LIST_ENTRY_SIZE)
                typeDescriptor(typeIndex)
            }
        }
        return Proto(typeDescriptor(returnTypeIndex), parameters)
    }

    private fun readClassData(offset: Int): List<InstalledCodeEncodedMethod> {
        val cursor = Cursor(offset)
        val staticFields = count(uleb128(cursor), "static fields")
        val instanceFields = count(uleb128(cursor), "instance fields")
        val directMethods = count(uleb128(cursor), "direct methods")
        val virtualMethods = count(uleb128(cursor), "virtual methods")
        val fieldTotal = staticFields.toLong() + instanceFields
        if (fieldTotal > Int.MAX_VALUE.toLong()) throw format("class data declares too many fields")
        val methodTotal = directMethods.toLong() + virtualMethods
        if (methodTotal > header.methodIdsSize.toLong()) {
            throw format("class data declares $methodTotal methods, above the method table")
        }
        repeat(fieldTotal.toInt()) {
            uleb128(cursor)
            uleb128(cursor)
        }
        val methods = ArrayList<InstalledCodeEncodedMethod>(methodTotal.toInt())
        for (listSize in listOf(directMethods, virtualMethods)) {
            var methodIndex = 0
            repeat(listSize) {
                methodIndex += count(uleb128(cursor), "method index difference")
                val accessFlags = uleb128(cursor)
                val codeOffset = count(uleb128(cursor), "code offset")
                if (methodIndex < 0 || methodIndex >= header.methodIdsSize) {
                    throw format("encoded method index $methodIndex is outside the method table")
                }
                if (codeOffset != 0 && codeOffset % 4 != 0) {
                    throw format("encoded method code at $codeOffset is not four-byte aligned")
                }
                if (codeOffset != 0) {
                    requireRange(codeOffset.toLong(), CODE_ITEM_HEADER_SIZE.toLong(), "encoded method code")
                }
                methods += InstalledCodeEncodedMethod(methodIndex, accessFlags, codeOffset)
            }
        }
        return methods
    }

    private fun count(value: Int, what: String): Int {
        if (value < 0) throw format("$what has a negative count")
        return value
    }

    private fun requireIndex(index: Int, size: Int, what: String) {
        if (index < 0 || index >= size) {
            throw format("$what index $index is outside a table of $size entries")
        }
    }

    private fun requireRange(offset: Long, length: Long, what: String) {
        if (offset < 0 || length < 0 || offset > header.limit.toLong() || length > header.limit - offset) {
            throw format("$what at $offset of $length bytes is outside the file of ${header.limit} bytes")
        }
    }

    private fun readInt(offset: Int): Int {
        requireRange(offset.toLong(), 4, "int")
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readShort(offset: Int): Int {
        requireRange(offset.toLong(), 2, "short")
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private inner class Cursor(var offset: Int)

    private fun uleb128(cursor: Cursor): Int {
        var result = 0
        var shift = 0
        while (true) {
            if (cursor.offset >= header.limit) throw format("truncated uleb128")
            val byte = bytes[cursor.offset++].toInt() and 0xFF
            result = result or ((byte and 0x7F) shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
            if (shift > 28) throw format("uleb128 longer than five bytes")
        }
    }

    private fun sleb128(cursor: Cursor): Int {
        var result = 0
        var shift = 0
        while (true) {
            if (cursor.offset >= header.limit) throw format("truncated sleb128")
            val byte = bytes[cursor.offset++].toInt() and 0xFF
            result = result or ((byte and 0x7F) shl shift)
            shift += 7
            if (byte and 0x80 == 0) {
                if (shift < 32 && byte and 0x40 != 0) result = result or (-1 shl shift)
                return result
            }
            if (shift > 28) throw format("sleb128 longer than five bytes")
        }
    }

    private fun decodeMutf8(start: Int, end: Int): String {
        val builder = StringBuilder(end - start)
        var index = start
        while (index < end) {
            val first = bytes[index].toInt() and 0xFF
            when {
                first < 0x80 -> {
                    builder.append(first.toChar())
                    index += 1
                }

                first and 0xE0 == 0xC0 -> {
                    if (index + 1 >= end) throw format("truncated two-byte MUTF-8 sequence")
                    val second = bytes[index + 1].toInt() and 0xFF
                    if (second and 0xC0 != 0x80) throw format("invalid MUTF-8 continuation byte")
                    builder.append((((first and 0x1F) shl 6) or (second and 0x3F)).toChar())
                    index += 2
                }

                first and 0xF0 == 0xE0 -> {
                    if (index + 2 >= end) throw format("truncated three-byte MUTF-8 sequence")
                    val second = bytes[index + 1].toInt() and 0xFF
                    val third = bytes[index + 2].toInt() and 0xFF
                    if (second and 0xC0 != 0x80 || third and 0xC0 != 0x80) {
                        throw format("invalid MUTF-8 continuation byte")
                    }
                    val codeUnit = ((first and 0x0F) shl 12) or
                        ((second and 0x3F) shl 6) or
                        (third and 0x3F)
                    builder.append(codeUnit.toChar())
                    index += 3
                }

                else -> throw format("invalid MUTF-8 lead byte 0x${first.toString(16)}")
            }
        }
        return builder.toString()
    }

    private fun format(message: String): InstalledCodeFormatException =
        InstalledCodeFormatException(message)

    private fun align4(value: Long): Long = (value + 3) and 3L.inv()

    private data class Header(
        val version: Int,
        val versionName: String,
        val fileSize: Int,
        val headerSize: Int,
        val limit: Int,
        val stringIdsOffset: Int,
        val stringIdsSize: Int,
        val typeIdsOffset: Int,
        val typeIdsSize: Int,
        val protoIdsOffset: Int,
        val protoIdsSize: Int,
        val fieldIdsOffset: Int,
        val fieldIdsSize: Int,
        val methodIdsOffset: Int,
        val methodIdsSize: Int,
        val classDefsOffset: Int,
        val classDefsSize: Int,
    )

    companion object {
        private const val STRING_ID_SIZE = 4
        private const val TYPE_ID_SIZE = 4
        private const val PROTO_ID_SIZE = 12
        private const val FIELD_ID_SIZE = 8
        private const val METHOD_ID_SIZE = 8
        private const val CLASS_DEF_SIZE = 32
        private const val TYPE_LIST_ENTRY_SIZE = 2
        private const val CODE_ITEM_HEADER_SIZE = 16
        private const val TRY_ITEM_SIZE = 8
        private const val NO_INDEX = 0xFFFFFFFFL
        private const val ENDIAN_CONSTANT = 0x12345678L
        private const val HEADER_SIZE_V40 = 0x70
        private const val HEADER_SIZE_V41 = 0x78
        private const val MIN_VERSION = 35
        private const val MAX_VERSION = 41

        fun parse(bytes: ByteArray): InstalledCodeDex {
            val header = readHeader(bytes)
            return InstalledCodeDex(bytes, header)
        }

        private fun readHeader(bytes: ByteArray): Header {
            if (bytes.size < HEADER_SIZE_V40) {
                throw InstalledCodeFormatException("a DEX header needs at least $HEADER_SIZE_V40 bytes")
            }
            if (bytes[0] != 'd'.code.toByte() || bytes[1] != 'e'.code.toByte() ||
                bytes[2] != 'x'.code.toByte() || bytes[3] != 0x0a.toByte() ||
                bytes[7] != 0.toByte()
            ) {
                throw InstalledCodeFormatException("bad DEX magic")
            }
            val versionName = buildString(3) {
                for (position in 4..6) {
                    val digit = (bytes[position].toInt() and 0xFF) - '0'.code
                    if (digit !in 0..9) throw InstalledCodeFormatException("bad DEX version digit")
                    append('0' + digit)
                }
            }
            val version = versionName.toInt()
            if (version < MIN_VERSION || version > MAX_VERSION) {
                throw InstalledCodeFormatException(
                    "DEX version $versionName is outside the supported 035..041 range",
                )
            }

            fun intAt(offset: Int): Long {
                if (offset < 0 || offset.toLong() + 4 > bytes.size) {
                    throw InstalledCodeFormatException("header field at $offset is outside the file")
                }
                return ((bytes[offset].toInt() and 0xFF).toLong()) or
                    ((bytes[offset + 1].toInt() and 0xFF).toLong() shl 8) or
                    ((bytes[offset + 2].toInt() and 0xFF).toLong() shl 16) or
                    ((bytes[offset + 3].toInt() and 0xFF).toLong() shl 24)
            }

            val fileSize = intAt(0x20)
            val headerSize = intAt(0x24)
            val endianTag = intAt(0x28)
            if (endianTag != ENDIAN_CONSTANT) {
                throw InstalledCodeFormatException("unsupported endian tag 0x${endianTag.toString(16)}")
            }
            val expectedHeaderSize = if (version >= 41) HEADER_SIZE_V41.toLong() else HEADER_SIZE_V40.toLong()
            if (headerSize != expectedHeaderSize) {
                throw InstalledCodeFormatException(
                    "header size $headerSize does not match the $versionName header size $expectedHeaderSize",
                )
            }
            val limit: Long
            if (version >= 41) {
                if (bytes.size < HEADER_SIZE_V41) {
                    throw InstalledCodeFormatException("a version 041 DEX header needs $HEADER_SIZE_V41 bytes")
                }
                val containerSize = intAt(0x70)
                val headerOffset = intAt(0x74)
                if (headerOffset != 0L) {
                    throw InstalledCodeFormatException("a multi-dex container is not supported (header offset $headerOffset)")
                }
                if (containerSize != bytes.size.toLong()) {
                    throw InstalledCodeFormatException(
                        "container size $containerSize does not match the ${bytes.size} byte entry",
                    )
                }
                if (fileSize != containerSize - headerOffset) {
                    throw InstalledCodeFormatException(
                        "a container with more than one logical dex is not supported (file size $fileSize, container $containerSize)",
                    )
                }
                limit = containerSize
            } else {
                if (fileSize != bytes.size.toLong()) {
                    throw InstalledCodeFormatException(
                        "file size $fileSize does not match the ${bytes.size} byte entry",
                    )
                }
                limit = fileSize
            }

            val linkSize = intAt(0x2c)
            val linkOffset = intAt(0x30)
            if ((linkSize == 0L) != (linkOffset == 0L)) {
                throw InstalledCodeFormatException("link section has a zero size or offset but not both")
            }
            if (linkSize > 0) {
                requireSection(linkOffset, linkSize, 1, headerSize, limit, "link")
            }
            val mapOffset = intAt(0x34)
            if (mapOffset != 0L) {
                requireSection(mapOffset, 4, 1, headerSize, limit, "map list")
            }

            val stringIdsSize = intAt(0x38)
            val stringIdsOffset = intAt(0x3c)
            val typeIdsSize = intAt(0x40)
            val typeIdsOffset = intAt(0x44)
            val protoIdsSize = intAt(0x48)
            val protoIdsOffset = intAt(0x4c)
            val fieldIdsSize = intAt(0x50)
            val fieldIdsOffset = intAt(0x54)
            val methodIdsSize = intAt(0x58)
            val methodIdsOffset = intAt(0x5c)
            val classDefsSize = intAt(0x60)
            val classDefsOffset = intAt(0x64)

            requireSection(stringIdsOffset, stringIdsSize, STRING_ID_SIZE.toLong(), headerSize, limit, "string ids")
            requireSection(typeIdsOffset, typeIdsSize, TYPE_ID_SIZE.toLong(), headerSize, limit, "type ids")
            requireSection(protoIdsOffset, protoIdsSize, PROTO_ID_SIZE.toLong(), headerSize, limit, "proto ids")
            requireSection(fieldIdsOffset, fieldIdsSize, FIELD_ID_SIZE.toLong(), headerSize, limit, "field ids")
            requireSection(methodIdsOffset, methodIdsSize, METHOD_ID_SIZE.toLong(), headerSize, limit, "method ids")
            requireSection(classDefsOffset, classDefsSize, CLASS_DEF_SIZE.toLong(), headerSize, limit, "class defs")

            if (version < 41) {
                val dataSize = intAt(0x68)
                val dataOffset = intAt(0x6c)
                if (dataSize > 0 && (dataOffset < headerSize || dataOffset > limit || dataSize > limit - dataOffset)) {
                    throw InstalledCodeFormatException(
                        "data section at $dataOffset of $dataSize bytes is outside the file",
                    )
                }
            }

            return Header(
                version = version,
                versionName = versionName,
                fileSize = fileSize.toInt(),
                headerSize = headerSize.toInt(),
                limit = limit.toInt(),
                stringIdsOffset = stringIdsOffset.toInt(),
                stringIdsSize = stringIdsSize.toInt(),
                typeIdsOffset = typeIdsOffset.toInt(),
                typeIdsSize = typeIdsSize.toInt(),
                protoIdsOffset = protoIdsOffset.toInt(),
                protoIdsSize = protoIdsSize.toInt(),
                fieldIdsOffset = fieldIdsOffset.toInt(),
                fieldIdsSize = fieldIdsSize.toInt(),
                methodIdsOffset = methodIdsOffset.toInt(),
                methodIdsSize = methodIdsSize.toInt(),
                classDefsOffset = classDefsOffset.toInt(),
                classDefsSize = classDefsSize.toInt(),
            ).also { header ->
                validateContents(bytes, header)
            }
        }

        private fun requireSection(
            offset: Long,
            size: Long,
            itemSize: Long,
            headerSize: Long,
            limit: Long,
            what: String,
        ) {
            if (size == 0L) {
                if (offset != 0L) {
                    throw InstalledCodeFormatException("$what has no entries but a non-zero offset")
                }
                return
            }
            if (offset == 0L) {
                throw InstalledCodeFormatException("$what has entries but a zero offset")
            }
            if (size < 0 || offset < 0) throw InstalledCodeFormatException("$what has a negative size or offset")
            if (offset % 4 != 0L) throw InstalledCodeFormatException("$what is not four-byte aligned")
            if (offset < headerSize) throw InstalledCodeFormatException("$what overlaps the header")
            val length = if (itemSize == 1L) size else size * itemSize
            if (length < 0 || offset > limit || length > limit - offset) {
                throw InstalledCodeFormatException("$what runs past the end of the file")
            }
        }

        private fun validateContents(bytes: ByteArray, header: Header) {
            fun intAt(offset: Long): Long {
                val position = offset.toInt()
                if (position < 0 || offset + 4 > header.limit.toLong()) {
                    throw InstalledCodeFormatException("entry at $offset is outside the file")
                }
                return ((bytes[position].toInt() and 0xFF).toLong()) or
                    ((bytes[position + 1].toInt() and 0xFF).toLong() shl 8) or
                    ((bytes[position + 2].toInt() and 0xFF).toLong() shl 16) or
                    ((bytes[position + 3].toInt() and 0xFF).toLong() shl 24)
            }
            fun shortAt(offset: Long): Int {
                val position = offset.toInt()
                if (position < 0 || offset + 2 > header.limit.toLong()) {
                    throw InstalledCodeFormatException("entry at $offset is outside the file")
                }
                return (bytes[position].toInt() and 0xFF) or ((bytes[position + 1].toInt() and 0xFF) shl 8)
            }

            for (index in 0 until header.stringIdsSize) {
                val dataOffset = intAt(header.stringIdsOffset.toLong() + index.toLong() * STRING_ID_SIZE)
                if (dataOffset < header.headerSize || dataOffset >= header.limit) {
                    throw InstalledCodeFormatException("string $index data offset $dataOffset is outside the data area")
                }
            }
            for (index in 0 until header.typeIdsSize) {
                val stringIndex = intAt(header.typeIdsOffset.toLong() + index.toLong() * TYPE_ID_SIZE)
                if (stringIndex < 0 || stringIndex >= header.stringIdsSize) {
                    throw InstalledCodeFormatException("type $index points at string $stringIndex")
                }
            }
            for (index in 0 until header.protoIdsSize) {
                val base = header.protoIdsOffset.toLong() + index.toLong() * PROTO_ID_SIZE
                val shorty = intAt(base)
                val returnType = intAt(base + 4)
                val parametersOffset = intAt(base + 8)
                if (shorty < 0 || shorty >= header.stringIdsSize) {
                    throw InstalledCodeFormatException("proto $index points at string $shorty")
                }
                if (returnType < 0 || returnType >= header.typeIdsSize) {
                    throw InstalledCodeFormatException("proto $index points at type $returnType")
                }
                if (parametersOffset != 0L) {
                    if (parametersOffset % 4 != 0L) {
                        throw InstalledCodeFormatException("proto $index parameter list is not aligned")
                    }
                    if (parametersOffset < header.headerSize || parametersOffset + 4 > header.limit) {
                        throw InstalledCodeFormatException("proto $index parameter list is outside the file")
                    }
                    val size = intAt(parametersOffset)
                    val bytesNeeded = 4 + size * TYPE_LIST_ENTRY_SIZE
                    if (size < 0 || parametersOffset + bytesNeeded > header.limit) {
                        throw InstalledCodeFormatException("proto $index parameter list runs past the file")
                    }
                }
            }
            for (index in 0 until header.fieldIdsSize) {
                val base = header.fieldIdsOffset.toLong() + index.toLong() * FIELD_ID_SIZE
                val classIndex = shortAt(base)
                val typeIndex = shortAt(base + 2)
                val nameIndex = intAt(base + 4)
                if (classIndex >= header.typeIdsSize || typeIndex >= header.typeIdsSize) {
                    throw InstalledCodeFormatException("field $index names a type outside the type table")
                }
                if (nameIndex < 0 || nameIndex >= header.stringIdsSize) {
                    throw InstalledCodeFormatException("field $index names string $nameIndex")
                }
            }
            for (index in 0 until header.methodIdsSize) {
                val base = header.methodIdsOffset.toLong() + index.toLong() * METHOD_ID_SIZE
                val classIndex = shortAt(base)
                val protoIndex = shortAt(base + 2)
                val nameIndex = intAt(base + 4)
                if (classIndex >= header.typeIdsSize) {
                    throw InstalledCodeFormatException("method $index names a class outside the type table")
                }
                if (protoIndex >= header.protoIdsSize) {
                    throw InstalledCodeFormatException("method $index points at proto $protoIndex")
                }
                if (nameIndex < 0 || nameIndex >= header.stringIdsSize) {
                    throw InstalledCodeFormatException("method $index names string $nameIndex")
                }
            }
            for (index in 0 until header.classDefsSize) {
                val base = header.classDefsOffset.toLong() + index.toLong() * CLASS_DEF_SIZE
                val classIndex = intAt(base)
                val superclassIndex = intAt(base + 8)
                val sourceFileIndex = intAt(base + 16)
                val interfacesOffset = intAt(base + 12)
                val annotationsOffset = intAt(base + 20)
                val classDataOffset = intAt(base + 24)
                val staticValuesOffset = intAt(base + 28)
                if (classIndex < 0 || classIndex >= header.typeIdsSize) {
                    throw InstalledCodeFormatException("class definition $index names type $classIndex")
                }
                if (superclassIndex != NO_INDEX && (superclassIndex < 0 || superclassIndex >= header.typeIdsSize)) {
                    throw InstalledCodeFormatException("class definition $index names superclass $superclassIndex")
                }
                if (sourceFileIndex != NO_INDEX && (sourceFileIndex < 0 || sourceFileIndex >= header.stringIdsSize)) {
                    throw InstalledCodeFormatException("class definition $index names source file $sourceFileIndex")
                }
                for ((label, offset) in listOf(
                    "interfaces" to interfacesOffset,
                    "annotations" to annotationsOffset,
                )) {
                    if (offset == 0L) continue
                    if (offset % 4 != 0L) {
                        throw InstalledCodeFormatException("class definition $index $label is not aligned")
                    }
                    if (offset < header.headerSize || offset >= header.limit) {
                        throw InstalledCodeFormatException("class definition $index $label is outside the file")
                    }
                }
                for ((label, offset) in listOf(
                    "class data" to classDataOffset,
                    "static values" to staticValuesOffset,
                )) {
                    if (offset == 0L) continue
                    if (offset < header.headerSize || offset >= header.limit) {
                        throw InstalledCodeFormatException("class definition $index $label is outside the file")
                    }
                }
            }
        }
    }

    private class InstructionIterator(
        private val bytes: ByteArray,
        private val start: Int,
        private val unitCount: Int,
        private val limit: Int,
    ) : Iterator<InstalledCodeInstruction> {
        private var cursor = 0

        override fun hasNext(): Boolean = cursor < unitCount

        override fun next(): InstalledCodeInstruction {
            if (cursor >= unitCount) throw NoSuchElementException()
            val address = cursor
            val unit0 = unitAt(address)
            val opcode = unit0 and 0xFF
            if (opcode == 0) return payloadOrNop(address, unit0)
            val size = InstructionSizes.sizeOf(opcode)
            if (size <= 0) throw InstalledCodeFormatException("undefined opcode 0x${opcode.toString(16)}")
            requireUnits(address, size)
            val instruction = decode(address, unit0, opcode, size)
            cursor = address + size
            return instruction
        }

        private fun payloadOrNop(address: Int, unit0: Int): InstalledCodeInstruction {
            val size = when (unit0) {
                0x0000 -> 1
                0x0100 -> {
                    requireUnits(address, 2)
                    4 + unitAt(address + 1) * 2
                }

                0x0200 -> {
                    requireUnits(address, 2)
                    2 + unitAt(address + 1) * 4
                }

                0x0300 -> {
                    requireUnits(address, 4)
                    val width = unitAt(address + 1).toLong()
                    val count = unitAt(address + 2).toLong() or (unitAt(address + 3).toLong() shl 16)
                    4L + (width * count + 1) / 2
                }

                else -> throw InstalledCodeFormatException(
                    "undefined instruction 0x${unit0.toString(16)}",
                )
            }.toInt()
            requireUnits(address, size)
            cursor = address + size
            return InstalledCodeInstruction(
                addressUnits = address,
                opcode = 0,
                sizeUnits = size,
                destinationRegister = -1,
                destinationIsWide = false,
                isBranch = false,
                isInvoke = false,
                isStaticInvoke = false,
                invokeMethodIndex = -1,
                invokeRegisters = IntArray(0),
                isMoveObject = false,
                moveSourceRegister = -1,
                constKind = InstalledCodeConstKind.NONE,
                constIntValue = 0,
                constStringIndex = -1,
                branchOffsetUnits = 0,
                switchPayloadUnits = -1,
                isPayload = unit0 != 0x0000,
            )
        }

        private fun decode(
            address: Int,
            unit0: Int,
            opcode: Int,
            size: Int,
        ): InstalledCodeInstruction {
            var destination = -1
            var destinationWide = false
            var isBranch = false
            var isInvoke = false
            var isStaticInvoke = false
            var methodIndex = -1
            var registers = IntArray(0)
            var isMoveObject = false
            var moveSource = -1
            var constKind = InstalledCodeConstKind.NONE
            var constInt = 0
            var constString = -1
            var branchOffset = 0
            var switchPayload = -1
            val unit1 = if (size >= 2) unitAt(address + 1) else 0
            val unit2 = if (size >= 3) unitAt(address + 2) else 0

            when (opcode) {
                0x01, 0x04, 0x07 -> {
                    destination = (unit0 shr 8) and 0xF
                    moveSource = (unit0 shr 12) and 0xF
                    destinationWide = opcode == 0x04
                    isMoveObject = opcode == 0x07
                }

                0x02, 0x05, 0x08 -> {
                    destination = (unit0 shr 8) and 0xFF
                    moveSource = unit1
                    destinationWide = opcode == 0x05
                    isMoveObject = opcode == 0x08
                }

                0x03, 0x06, 0x09 -> {
                    destination = unit1 and 0xFFFF
                    moveSource = unit2
                    destinationWide = opcode == 0x06
                    isMoveObject = opcode == 0x09
                }

                0x0a, 0x0b, 0x0c, 0x0d -> {
                    destination = (unit0 shr 8) and 0xFF
                    destinationWide = opcode == 0x0b
                    constKind = InstalledCodeConstKind.OTHER
                }

                0x12 -> {
                    destination = (unit0 shr 8) and 0xF
                    var value = (unit0 shr 12) and 0xF
                    if (value and 0x8 != 0) value = value or -0x10
                    constKind = InstalledCodeConstKind.INT
                    constInt = value
                }

                0x13 -> {
                    destination = (unit0 shr 8) and 0xFF
                    constKind = InstalledCodeConstKind.INT
                    constInt = unit1.toShort().toInt()
                }

                0x14 -> {
                    destination = (unit0 shr 8) and 0xFF
                    constKind = InstalledCodeConstKind.INT
                    constInt = unit1 or (unit2 shl 16)
                }

                0x15 -> {
                    destination = (unit0 shr 8) and 0xFF
                    constKind = InstalledCodeConstKind.INT
                    constInt = unit1 shl 16
                }

                0x16, 0x17, 0x18, 0x19 -> {
                    destination = (unit0 shr 8) and 0xFF
                    destinationWide = true
                    constKind = InstalledCodeConstKind.WIDE
                }

                0x1a -> {
                    destination = (unit0 shr 8) and 0xFF
                    constKind = InstalledCodeConstKind.STRING
                    constString = unit1
                }

                0x1b -> {
                    destination = (unit0 shr 8) and 0xFF
                    constKind = InstalledCodeConstKind.STRING
                    constString = unit1 or (unit2 shl 16)
                }

                0x1c, 0xfe, 0xff -> {
                    destination = (unit0 shr 8) and 0xFF
                    constKind = InstalledCodeConstKind.OTHER
                }

                0x20, 0x23, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58,
                0x7b, 0x7c, 0x7d, 0x7e, 0x7f, 0x80, 0x81, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
                0x88, 0x89, 0x8a, 0x8b, 0x8c, 0x8d, 0x8e, 0x8f,
                0xb0, 0xb1, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xbb,
                0xbc, 0xbd, 0xbe, 0xbf, 0xc0, 0xc1, 0xc2, 0xc3, 0xc4, 0xc5, 0xc6, 0xc7,
                0xc8, 0xc9, 0xca, 0xcb, 0xcc, 0xcd, 0xce, 0xcf,
                0xd0, 0xd1, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7,
                -> destination = (unit0 shr 8) and 0xF

                0x21 -> destination = (unit0 shr 8) and 0xF

                0x22 -> destination = (unit0 shr 8) and 0xFF

                0x2d, 0x2e, 0x2f, 0x30, 0x31,
                0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4a,
                0x90, 0x91, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0x9b,
                0x9c, 0x9d, 0x9e, 0x9f, 0xa0, 0xa1, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7,
                0xa8, 0xa9, 0xaa, 0xab, 0xac, 0xad, 0xae, 0xaf,
                0xd8, 0xd9, 0xda, 0xdb, 0xdc, 0xdd, 0xde, 0xdf, 0xe0, 0xe1, 0xe2,
                -> destination = (unit0 shr 8) and 0xFF

                0x60, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66 -> destination = (unit0 shr 8) and 0xFF

                0x6e, 0x6f, 0x70, 0x71, 0x72 -> {
                    isInvoke = true
                    isStaticInvoke = opcode == 0x71
                    methodIndex = unit1
                    registers = packedRegisters(unit0, unit2)
                }

                0x74, 0x75, 0x76, 0x77, 0x78 -> {
                    isInvoke = true
                    isStaticInvoke = opcode == 0x77
                    methodIndex = unit1
                    registers = rangeRegisters(unit0, unit2)
                }

                0xfa, 0xfb -> {
                    isInvoke = true
                    registers = if (opcode == 0xfa) packedRegisters(unit0, unit2) else rangeRegisters(unit0, unit2)
                }

                0xfc, 0xfd -> {
                    isInvoke = true
                    registers = if (opcode == 0xfc) packedRegisters(unit0, unit2) else rangeRegisters(unit0, unit2)
                }

                0x28 -> {
                    isBranch = true
                    branchOffset = ((unit0 shr 8) and 0xFF).toByte().toInt()
                }

                0x29 -> {
                    isBranch = true
                    branchOffset = unit1.toShort().toInt()
                }

                0x2a -> {
                    isBranch = true
                    branchOffset = unit1 or (unit2 shl 16)
                }

                0x2b, 0x2c -> {
                    isBranch = true
                    switchPayload = unit1 or (unit2 shl 16)
                }

                0x32, 0x33, 0x34, 0x35, 0x36, 0x37,
                0x38, 0x39, 0x3a, 0x3b, 0x3c, 0x3d,
                -> {
                    isBranch = true
                    branchOffset = unit1.toShort().toInt()
                }
            }

            return InstalledCodeInstruction(
                addressUnits = address,
                opcode = opcode,
                sizeUnits = size,
                destinationRegister = destination,
                destinationIsWide = destinationWide,
                isBranch = isBranch,
                isInvoke = isInvoke,
                isStaticInvoke = isStaticInvoke,
                invokeMethodIndex = methodIndex,
                invokeRegisters = registers,
                isMoveObject = isMoveObject,
                moveSourceRegister = moveSource,
                constKind = constKind,
                constIntValue = constInt,
                constStringIndex = constString,
                branchOffsetUnits = branchOffset,
                switchPayloadUnits = switchPayload,
                isPayload = false,
            )
        }

        private fun packedRegisters(unit0: Int, unit2: Int): IntArray {
            val count = (unit0 shr 12) and 0xF
            if (count == 0) return IntArray(0)
            val registers = IntArray(count)
            registers[0] = unit2 and 0xF
            if (count > 1) registers[1] = (unit2 shr 4) and 0xF
            if (count > 2) registers[2] = (unit2 shr 8) and 0xF
            if (count > 3) registers[3] = (unit2 shr 12) and 0xF
            if (count > 4) registers[4] = (unit0 shr 8) and 0xF
            return registers
        }

        private fun rangeRegisters(unit0: Int, unit2: Int): IntArray {
            val count = (unit0 shr 8) and 0xFF
            return IntArray(count) { unit2 + it }
        }

        private fun requireUnits(address: Int, size: Int) {
            if (size <= 0 || address.toLong() + size > unitCount) {
                throw InstalledCodeFormatException(
                    "instruction at unit $address with size $size runs past the code item",
                )
            }
        }

        private fun unitAt(address: Int): Int {
            val offset = start + address * 2
            if (offset < 0 || offset.toLong() + 2 > limit) {
                throw InstalledCodeFormatException("instruction unit $address is outside the file")
            }
            return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
        }
    }

    private object InstructionSizes {
        private val SIZES = IntArray(256)

        init {
            fun set(range: IntRange, size: Int) = range.forEach { SIZES[it] = size }
            set(0x00..0x00, 1)
            SIZES[0x01] = 1; SIZES[0x02] = 2; SIZES[0x03] = 3
            SIZES[0x04] = 1; SIZES[0x05] = 2; SIZES[0x06] = 3
            SIZES[0x07] = 1; SIZES[0x08] = 2; SIZES[0x09] = 3
            set(0x0a..0x0d, 1)
            set(0x0e..0x11, 1)
            SIZES[0x12] = 1; SIZES[0x13] = 2; SIZES[0x14] = 3; SIZES[0x15] = 2
            SIZES[0x16] = 2; SIZES[0x17] = 3; SIZES[0x18] = 5; SIZES[0x19] = 2
            SIZES[0x1a] = 2; SIZES[0x1b] = 3; SIZES[0x1c] = 2
            SIZES[0x1d] = 1; SIZES[0x1e] = 1; SIZES[0x1f] = 2
            SIZES[0x20] = 2; SIZES[0x21] = 1; SIZES[0x22] = 2; SIZES[0x23] = 2
            SIZES[0x24] = 3; SIZES[0x25] = 3; SIZES[0x26] = 3; SIZES[0x27] = 1
            SIZES[0x28] = 1; SIZES[0x29] = 2; SIZES[0x2a] = 3; SIZES[0x2b] = 3; SIZES[0x2c] = 3
            set(0x2d..0x31, 2)
            set(0x32..0x37, 2)
            set(0x38..0x3d, 2)
            set(0x44..0x51, 2)
            set(0x52..0x5f, 2)
            set(0x60..0x6d, 2)
            set(0x6e..0x72, 3)
            set(0x74..0x78, 3)
            set(0x7b..0x8f, 1)
            set(0x90..0xaf, 2)
            set(0xb0..0xcf, 1)
            set(0xd0..0xd7, 2)
            set(0xd8..0xe2, 2)
            SIZES[0xfa] = 4
            SIZES[0xfb] = 4
            SIZES[0xfc] = 3
            SIZES[0xfd] = 3
            SIZES[0xfe] = 2
            SIZES[0xff] = 2
        }

        fun sizeOf(opcode: Int): Int = SIZES[opcode]
    }
}
