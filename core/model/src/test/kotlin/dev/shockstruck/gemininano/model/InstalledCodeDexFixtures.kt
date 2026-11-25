package dev.shockstruck.gemininano.model

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds a synthetic DEX file for tests. All bytes are constructed here; none is Google code. */
internal class SyntheticDexBuilder(private val version: Int = 35) {

    private val strings = mutableListOf<String>()
    private val stringLookup = mutableMapOf<String, Int>()
    private val typeStringIndices = mutableListOf<Int>()
    private val typeLookup = mutableMapOf<String, Int>()
    private val protoShorties = mutableListOf<Int>()
    private val protoReturnTypes = mutableListOf<Int>()
    private val protoParameters = mutableListOf<List<Int>>()
    private val protoLookup = mutableMapOf<String, Int>()
    private val methodClasses = mutableListOf<Int>()
    private val methodProtos = mutableListOf<Int>()
    private val methodNames = mutableListOf<Int>()
    private val methodLookup = mutableMapOf<String, Int>()
    private val classSpecs = mutableListOf<ClassSpec>()

    val stringCount: Int get() = strings.size

    val typeCount: Int get() = typeStringIndices.size

    fun string(value: String): Int = stringLookup.getOrPut(value) {
        strings += value
        strings.size - 1
    }

    fun type(descriptor: String): Int = typeLookup.getOrPut(descriptor) {
        typeStringIndices += string(descriptor)
        typeStringIndices.size - 1
    }

    fun proto(returnType: String, parameters: List<String>): Int {
        val key = returnType + "(" + parameters.joinToString("") + ")"
        return protoLookup.getOrPut(key) {
            val shorty = when (returnType) {
                "V" -> "V"
                "Z", "B", "S", "C", "I", "J", "F", "D" -> returnType
                else -> "L"
            }
            protoShorties += string(shorty)
            protoReturnTypes += type(returnType)
            protoParameters += parameters.map { type(it) }
            protoReturnTypes.size - 1
        }
    }

    fun method(className: String, name: String, returnType: String, parameters: List<String>): Int {
        val key = "$className->$name(${parameters.joinToString("")})$returnType"
        return methodLookup.getOrPut(key) {
            methodClasses += type(className)
            methodProtos += proto(returnType, parameters)
            methodNames += string(name)
            methodClasses.size - 1
        }
    }

    fun clazz(className: String, methods: List<MethodSpec>): SyntheticDexBuilder {
        classSpecs += ClassSpec(type(className), methods)
        return this
    }

    fun build(): ByteArray {
        val headerSize = if (version >= 41) 0x78 else 0x70
        var cursor = align4(headerSize)
        val stringIdsOffset = cursor
        cursor += strings.size * 4
        val typeIdsOffset = cursor
        cursor += typeStringIndices.size * 4
        val protoIdsOffset = cursor
        cursor += protoReturnTypes.size * 12
        val methodIdsOffset = cursor
        cursor += methodClasses.size * 8
        val classDefsOffset = cursor
        cursor += classSpecs.size * 32
        val dataOffset = cursor

        val data = ByteArrayOutputStream()
        val stringDataOffsets = IntArray(strings.size)
        for ((index, value) in strings.withIndex()) {
            stringDataOffsets[index] = dataOffset + data.size()
            writeUleb128(data, value.length)
            data.write(encodeMutf8(value))
            data.write(0)
        }

        val protoParameterOffsets = IntArray(protoParameters.size)
        for ((index, parameters) in protoParameters.withIndex()) {
            if (parameters.isEmpty()) continue
            pad(data, 4)
            protoParameterOffsets[index] = dataOffset + data.size()
            writeInt(data, parameters.size)
            for (parameter in parameters) writeShort(data, parameter)
            pad(data, 4)
        }

        val codeOffsets = HashMap<Pair<Int, Int>, Int>()
        for ((classIndex, spec) in classSpecs.withIndex()) {
            for ((methodIndex, method) in spec.methods.withIndex()) {
                pad(data, 4)
                codeOffsets[classIndex to methodIndex] = dataOffset + data.size()
                writeCodeItem(data, method)
            }
        }

        val classDataOffsets = IntArray(classSpecs.size)
        for ((classIndex, spec) in classSpecs.withIndex()) {
            pad(data, 4)
            classDataOffsets[classIndex] = dataOffset + data.size()
            writeUleb128(data, 0)
            writeUleb128(data, 0)
            writeUleb128(data, spec.methods.size)
            writeUleb128(data, 0)
            var previous = 0
            for ((methodIndex, method) in spec.methods.withIndex()) {
                writeUleb128(data, method.methodIndex - previous)
                previous = method.methodIndex
                writeUleb128(data, if (method.isStatic) 0x0009 else 0x0001)
                writeUleb128(data, codeOffsets.getValue(classIndex to methodIndex))
            }
        }

        val dataBytes = data.toByteArray()
        val fileSize = dataOffset + dataBytes.size
        val bytes = ByteArray(fileSize)
        "dex\n".toByteArray(Charsets.US_ASCII).copyInto(bytes, 0)
        val versionText = String.format(java.util.Locale.US, "%03d", version)
        versionText.toByteArray(Charsets.US_ASCII).copyInto(bytes, 4)
        bytes[7] = 0
        writeIntAt(bytes, 0x20, fileSize)
        writeIntAt(bytes, 0x24, headerSize)
        writeIntAt(bytes, 0x28, 0x12345678)
        writeIntAt(bytes, 0x38, strings.size)
        writeIntAt(bytes, 0x3c, stringIdsOffset)
        writeIntAt(bytes, 0x40, typeStringIndices.size)
        writeIntAt(bytes, 0x44, typeIdsOffset)
        writeIntAt(bytes, 0x48, protoReturnTypes.size)
        writeIntAt(bytes, 0x4c, protoIdsOffset)
        writeIntAt(bytes, 0x50, 0)
        writeIntAt(bytes, 0x54, 0)
        writeIntAt(bytes, 0x58, methodClasses.size)
        writeIntAt(bytes, 0x5c, methodIdsOffset)
        writeIntAt(bytes, 0x60, classSpecs.size)
        writeIntAt(bytes, 0x64, classDefsOffset)
        writeIntAt(bytes, 0x68, if (version >= 41) 0 else dataBytes.size)
        writeIntAt(bytes, 0x6c, if (version >= 41) 0 else dataOffset)
        if (version >= 41) {
            writeIntAt(bytes, 0x70, fileSize)
            writeIntAt(bytes, 0x74, 0)
        }

        for (index in strings.indices) {
            writeIntAt(bytes, stringIdsOffset + index * 4, stringDataOffsets[index])
        }
        for (index in typeStringIndices.indices) {
            writeIntAt(bytes, typeIdsOffset + index * 4, typeStringIndices[index])
        }
        for (index in protoReturnTypes.indices) {
            val base = protoIdsOffset + index * 12
            writeIntAt(bytes, base, protoShorties[index])
            writeIntAt(bytes, base + 4, protoReturnTypes[index])
            writeIntAt(bytes, base + 8, protoParameterOffsets[index])
        }
        for (index in methodClasses.indices) {
            val base = methodIdsOffset + index * 8
            writeShortAt(bytes, base, methodClasses[index])
            writeShortAt(bytes, base + 2, methodProtos[index])
            writeIntAt(bytes, base + 4, methodNames[index])
        }
        for ((index, spec) in classSpecs.withIndex()) {
            val base = classDefsOffset + index * 32
            writeIntAt(bytes, base, spec.classTypeIndex)
            writeIntAt(bytes, base + 4, 0x0001)
            writeIntAt(bytes, base + 8, 0xFFFFFFFF.toInt())
            writeIntAt(bytes, base + 12, 0)
            writeIntAt(bytes, base + 16, 0xFFFFFFFF.toInt())
            writeIntAt(bytes, base + 20, 0)
            writeIntAt(bytes, base + 24, classDataOffsets[index])
            writeIntAt(bytes, base + 28, 0)
        }
        dataBytes.copyInto(bytes, dataOffset)
        return bytes
    }

    private fun writeCodeItem(output: ByteArrayOutputStream, method: MethodSpec) {
        writeShort(output, method.registers)
        writeShort(output, 0)
        writeShort(output, 0)
        writeShort(output, method.tries.size)
        writeInt(output, 0)
        writeInt(output, method.instructions.size)
        for (unit in method.instructions) writeShort(output, unit)
        if (method.tries.isEmpty()) return
        if (method.instructions.size % 2 == 1) writeShort(output, 0)
        for (trySpec in method.tries) {
            writeInt(output, trySpec.startAddress)
            writeShort(output, trySpec.insnCount)
            writeShort(output, 0)
        }
        writeUleb128(output, 1)
        writeSleb128(output, 0)
        writeUleb128(output, method.tries.first().handlerAddress)
    }

    private data class ClassSpec(
        val classTypeIndex: Int,
        val methods: List<MethodSpec>,
    )

    companion object {
        fun align4(value: Int): Int = (value + 3) and 3.inv()

        fun pad(output: ByteArrayOutputStream, alignment: Int) {
            while (output.size() % alignment != 0) output.write(0)
        }

        fun writeSleb128(output: ByteArrayOutputStream, value: Int) {
            var remaining = value
            while (true) {
                val byte = remaining and 0x7F
                remaining = remaining shr 7
                val signBitSet = byte and 0x40 != 0
                if ((remaining == 0 && !signBitSet) || (remaining == -1 && signBitSet)) {
                    output.write(byte)
                    return
                }
                output.write(byte or 0x80)
            }
        }

        fun writeUleb128(output: ByteArrayOutputStream, value: Int) {
            var remaining = value
            do {
                var byte = remaining and 0x7F
                remaining = remaining ushr 7
                if (remaining != 0) byte = byte or 0x80
                output.write(byte)
            } while (remaining != 0)
        }

        fun writeInt(output: ByteArrayOutputStream, value: Int) {
            output.write(value and 0xFF)
            output.write((value ushr 8) and 0xFF)
            output.write((value ushr 16) and 0xFF)
            output.write((value ushr 24) and 0xFF)
        }

        fun writeShort(output: ByteArrayOutputStream, value: Int) {
            output.write(value and 0xFF)
            output.write((value ushr 8) and 0xFF)
        }

        fun writeIntAt(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = (value and 0xFF).toByte()
            bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
            bytes[offset + 2] = ((value ushr 16) and 0xFF).toByte()
            bytes[offset + 3] = ((value ushr 24) and 0xFF).toByte()
        }

        fun writeShortAt(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = (value and 0xFF).toByte()
            bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        }

        fun encodeMutf8(value: String): ByteArray {
            val output = ByteArrayOutputStream()
            for (char in value) {
                val code = char.code
                when {
                    code in 0x01..0x7F -> output.write(code)
                    code <= 0x7FF -> {
                        output.write(0xC0 or (code shr 6))
                        output.write(0x80 or (code and 0x3F))
                    }

                    else -> {
                        output.write(0xE0 or (code shr 12))
                        output.write(0x80 or ((code shr 6) and 0x3F))
                        output.write(0x80 or (code and 0x3F))
                    }
                }
            }
            return output.toByteArray()
        }
    }
}

internal data class MethodSpec(
    val name: String,
    val returnType: String,
    val parameters: List<String>,
    val methodIndex: Int,
    val isStatic: Boolean = true,
    val registers: Int = 16,
    val instructions: IntArray,
    val tries: List<TrySpec> = emptyList(),
)

internal data class TrySpec(
    val startAddress: Int,
    val insnCount: Int,
    val handlerAddress: Int,
)

/** Instruction encoders for the synthetic dex files. */
internal object SyntheticInsns {
    fun units(vararg instructions: IntArray): IntArray =
        instructions.flatMap { it.toList() }.toIntArray()

    fun constString(register: Int, stringIndex: Int): IntArray =
        intArrayOf(0x1a or (register shl 8), stringIndex)

    fun constStringJumbo(register: Int, stringIndex: Int): IntArray =
        intArrayOf(0x1b or (register shl 8), stringIndex and 0xFFFF, (stringIndex ushr 16) and 0xFFFF)

    fun const4(register: Int, value: Int): IntArray =
        intArrayOf(0x12 or (register shl 8) or ((value and 0xF) shl 12))

    fun const16(register: Int, value: Int): IntArray =
        intArrayOf(0x13 or (register shl 8), value and 0xFFFF)

    fun const32(register: Int, value: Int): IntArray =
        intArrayOf(0x14 or (register shl 8), value and 0xFFFF, (value ushr 16) and 0xFFFF)

    fun constHigh16(register: Int, value: Int): IntArray =
        intArrayOf(0x15 or (register shl 8), (value ushr 16) and 0xFFFF)

    fun constWide16(register: Int, value: Int): IntArray =
        intArrayOf(0x16 or (register shl 8), value and 0xFFFF)

    fun moveObject(destination: Int, source: Int): IntArray =
        intArrayOf(0x07 or (destination shl 8) or (source shl 12))

    fun move16(destination: Int, source: Int): IntArray =
        intArrayOf(0x03, destination and 0xFFFF, source and 0xFFFF)

    fun moveWide16(destination: Int, source: Int): IntArray =
        intArrayOf(0x06, destination and 0xFFFF, source and 0xFFFF)

    fun moveObject16(destination: Int, source: Int): IntArray =
        intArrayOf(0x09, destination and 0xFFFF, source and 0xFFFF)

    fun invokeCustom(registers: IntArray, callSiteIndex: Int): IntArray =
        invoke35c(0xfc, registers, callSiteIndex)

    fun invokeCustomRange(count: Int, firstRegister: Int, callSiteIndex: Int): IntArray =
        intArrayOf(0xfd or (count shl 8), callSiteIndex, firstRegister)

    fun invokePolymorphic(registers: IntArray, methodIndex: Int, protoIndex: Int): IntArray {
        require(registers.size <= 5)
        val padded = registers + IntArray(5 - registers.size)
        val unit0 = 0xfa or (registers.size shl 12) or ((padded[4] and 0xF) shl 8)
        val unit2 = (padded[0] and 0xF) or ((padded[1] and 0xF) shl 4) or
            ((padded[2] and 0xF) shl 8) or ((padded[3] and 0xF) shl 12)
        return intArrayOf(unit0, methodIndex, unit2, protoIndex)
    }

    fun invokePolymorphicRange(
        count: Int,
        firstRegister: Int,
        methodIndex: Int,
        protoIndex: Int,
    ): IntArray = intArrayOf(0xfb or (count shl 8), methodIndex, firstRegister, protoIndex)

    fun moveResult(register: Int): IntArray = intArrayOf(0x0a or (register shl 8))

    fun invokeStatic(registers: IntArray, methodIndex: Int): IntArray =
        invoke35c(0x71, registers, methodIndex)

    fun invokeVirtual(registers: IntArray, methodIndex: Int): IntArray =
        invoke35c(0x6e, registers, methodIndex)

    fun goto8(offset: Int): IntArray = intArrayOf(0x28 or ((offset and 0xFF) shl 8))

    fun ifEqz(register: Int, offset: Int): IntArray =
        intArrayOf(0x38 or (register shl 8), offset and 0xFFFF)

    fun returnVoid(): IntArray = intArrayOf(0x0e)

    fun nop(): IntArray = intArrayOf(0x00)

    private fun invoke35c(opcode: Int, registers: IntArray, methodIndex: Int): IntArray {
        require(registers.size <= 5)
        val padded = registers + IntArray(5 - registers.size)
        val unit0 = opcode or (registers.size shl 12) or ((padded[4] and 0xF) shl 8)
        val unit2 = (padded[0] and 0xF) or ((padded[1] and 0xF) shl 4) or
            ((padded[2] and 0xF) shl 8) or ((padded[3] and 0xF) shl 12)
        return intArrayOf(unit0, methodIndex, unit2)
    }
}

internal class FakeInstalledCodeInput(
    private val packages: Map<String, InstalledCodePackageInput>,
) : InstalledCodeInput {
    override fun packageFor(packageName: String): InstalledCodePackageInput =
        packages[packageName] ?: InstalledCodePackageInput.NotVisible
}

internal fun writeApk(file: File, entries: Map<String, ByteArray>) {
    ZipOutputStream(file.outputStream().buffered()).use { zip ->
        for ((name, bytes) in entries) {
            val entry = ZipEntry(name)
            entry.size = bytes.size.toLong()
            zip.putNextEntry(entry)
            zip.write(bytes)
            zip.closeEntry()
        }
    }
}
