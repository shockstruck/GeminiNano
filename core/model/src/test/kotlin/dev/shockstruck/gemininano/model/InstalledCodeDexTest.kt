package dev.shockstruck.gemininano.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InstalledCodeDexTest {

    @Test
    fun parsesStringTypeMethodAndClassTables() {
        val builder = SyntheticDexBuilder(35)
        val flagIndex = builder.string("ProtectedDownload__enabled")
        val flagManager = builder.method(
            "Ltest/FlagManager;",
            "getBoolean",
            "Z",
            listOf("Ljava/lang/String;", "Z"),
        )
        val probeIndex = builder.method("Ltest/Probe;", "probe", "V", emptyList())
        builder.clazz(
            "Ltest/Probe;",
            listOf(
                MethodSpec(
                    name = "probe",
                    returnType = "V",
                    parameters = emptyList(),
                    methodIndex = probeIndex,
                    instructions = SyntheticInsns.units(
                        SyntheticInsns.constString(0, flagIndex),
                        SyntheticInsns.const4(1, 0),
                        SyntheticInsns.invokeStatic(intArrayOf(0, 1), flagManager),
                        SyntheticInsns.returnVoid(),
                    ),
                ),
            ),
        )

        val dex = InstalledCodeDex.parse(builder.build())

        assertEquals(35, dex.version)
        assertEquals("035", dex.versionName)
        assertEquals("ProtectedDownload__enabled", dex.string(flagIndex))
        assertEquals(builder.stringCount, dex.stringCount)
        assertEquals(1, dex.classCount)

        val classDef = dex.classDef(0)
        assertEquals("Ltest/Probe;", classDef.className)
        assertEquals(1, classDef.methods.size)
        assertEquals(probeIndex, classDef.methods[0].methodIndex)

        val reference = dex.method(flagManager)
        assertEquals("Ltest/FlagManager;", reference.className)
        assertEquals("getBoolean", reference.name)
        assertEquals(listOf("Ljava/lang/String;", "Z"), reference.parameterTypes)
        assertEquals("Z", reference.returnType)
        assertEquals(
            "Ltest/FlagManager;->getBoolean(Ljava/lang/String;Z)Z",
            reference.descriptor,
        )

        val code = dex.codeItem(classDef.methods[0].codeOffset)
        assertEquals(7, code.instructionUnits)
        val instructions = dex.instructions(code).asSequence().toList()
        assertEquals(listOf(0x1a, 0x12, 0x71, 0x0e), instructions.map { it.opcode })
        assertEquals(InstalledCodeConstKind.STRING, instructions[0].constKind)
        assertEquals(flagIndex, instructions[0].constStringIndex)
        assertEquals(0, instructions[1].constIntValue)
        assertEquals(flagManager, instructions[2].invokeMethodIndex)
        assertTrue(instructions[2].isStaticInvoke)
        assertEquals(listOf(0, 1), instructions[2].invokeRegisters.toList())
    }

    @Test
    fun acceptsEverySupportedVersion() {
        for (version in 35..41) {
            val dex = InstalledCodeDex.parse(minimalDex(version))
            assertEquals(version, dex.version)
            assertEquals(String.format(java.util.Locale.US, "%03d", version), dex.versionName)
        }
    }

    @Test
    fun rejectsUnsupportedVersions() {
        assertThrows(InstalledCodeFormatException::class.java) {
            InstalledCodeDex.parse(minimalDex(34))
        }
        assertThrows(InstalledCodeFormatException::class.java) {
            InstalledCodeDex.parse(minimalDex(42))
        }
    }

    @Test
    fun rejectsMalformedHeaders() {
        val valid = minimalDex(35)

        val badMagic = valid.copyOf()
        badMagic[0] = 'z'.code.toByte()
        assertThrows(InstalledCodeFormatException::class.java) {
            InstalledCodeDex.parse(badMagic)
        }

        val badEndian = valid.copyOf()
        SyntheticDexBuilder.writeIntAt(badEndian, 0x28, 0x78563412)
        assertThrows(InstalledCodeFormatException::class.java) {
            InstalledCodeDex.parse(badEndian)
        }

        val badHeaderSize = valid.copyOf()
        SyntheticDexBuilder.writeIntAt(badHeaderSize, 0x24, 0x78)
        assertThrows(InstalledCodeFormatException::class.java) {
            InstalledCodeDex.parse(badHeaderSize)
        }

        val badFileSize = valid.copyOf()
        SyntheticDexBuilder.writeIntAt(badFileSize, 0x20, valid.size + 4)
        assertThrows(InstalledCodeFormatException::class.java) {
            InstalledCodeDex.parse(badFileSize)
        }
    }

    @Test
    fun rejectsTruncatedTables() {
        val valid = minimalDex(35)

        val truncatedFile = valid.copyOf(valid.size - 8)
        assertThrows(InstalledCodeFormatException::class.java) {
            InstalledCodeDex.parse(truncatedFile)
        }

        val oversizedStringTable = valid.copyOf()
        SyntheticDexBuilder.writeIntAt(oversizedStringTable, 0x38, 0x1000)
        assertThrows(InstalledCodeFormatException::class.java) {
            InstalledCodeDex.parse(oversizedStringTable)
        }

        val oversizedClassTable = valid.copyOf()
        SyntheticDexBuilder.writeIntAt(oversizedClassTable, 0x60, 0x1000)
        assertThrows(InstalledCodeFormatException::class.java) {
            InstalledCodeDex.parse(oversizedClassTable)
        }
    }

    @Test
    fun rejectsOutOfRangeIndices() {
        val builder = minimalBuilder(35)
        val valid = builder.build()
        val typeIdsOffset = 0x70 + builder.stringCount * 4
        val corrupted = valid.copyOf()
        SyntheticDexBuilder.writeIntAt(corrupted, typeIdsOffset, 0x00FF)
        assertThrows(InstalledCodeFormatException::class.java) {
            InstalledCodeDex.parse(corrupted)
        }
    }

    @Test
    fun rejectsUndefinedOpcodeAndTruncatedInstructions() {
        val builder = SyntheticDexBuilder(35)
        val probeIndex = builder.method("Ltest/Probe;", "probe", "V", emptyList())
        builder.clazz(
            "Ltest/Probe;",
            listOf(
                MethodSpec(
                    name = "probe",
                    returnType = "V",
                    parameters = emptyList(),
                    methodIndex = probeIndex,
                    instructions = intArrayOf(0x3e),
                ),
            ),
        )
        val dex = InstalledCodeDex.parse(builder.build())
        val code = dex.codeItem(dex.classDef(0).methods[0].codeOffset)
        assertThrows(InstalledCodeFormatException::class.java) {
            dex.instructions(code).next()
        }

        val goodBuilder = SyntheticDexBuilder(35)
        val index = goodBuilder.method("Ltest/Probe;", "probe", "V", emptyList())
        goodBuilder.clazz(
            "Ltest/Probe;",
            listOf(
                MethodSpec(
                    name = "probe",
                    returnType = "V",
                    parameters = emptyList(),
                    methodIndex = index,
                    instructions = SyntheticInsns.units(SyntheticInsns.returnVoid()),
                ),
            ),
        )
        val goodBytes = goodBuilder.build()
        val parsed = InstalledCodeDex.parse(goodBytes)
        val codeOffset = parsed.classDef(0).methods[0].codeOffset
        val truncated = goodBytes.copyOf()
        SyntheticDexBuilder.writeIntAt(truncated, codeOffset + 12, 0x1000)
        val reparsed = InstalledCodeDex.parse(truncated)
        assertThrows(InstalledCodeFormatException::class.java) {
            reparsed.codeItem(codeOffset)
        }
    }

    private fun minimalDex(version: Int = 35): ByteArray = minimalBuilder(version).build()

    private fun minimalBuilder(version: Int = 35): SyntheticDexBuilder {
        val builder = SyntheticDexBuilder(version)
        val probeIndex = builder.method("Ltest/Probe;", "probe", "V", emptyList())
        builder.clazz(
            "Ltest/Probe;",
            listOf(
                MethodSpec(
                    name = "probe",
                    returnType = "V",
                    parameters = emptyList(),
                    methodIndex = probeIndex,
                    instructions = SyntheticInsns.units(SyntheticInsns.returnVoid()),
                ),
            ),
        )
        return builder
    }
}
