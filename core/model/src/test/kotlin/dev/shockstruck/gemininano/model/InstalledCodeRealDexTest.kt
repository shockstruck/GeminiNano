package dev.shockstruck.gemininano.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Proof over a DEX produced by the real Android toolchain from this project's own
 * source, not synthetic bytes. The fixture and its build command are in
 * `src/test/resources/installed-code-fixture/`.
 */
class InstalledCodeRealDexTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun parsesRealDexWithUnalignedClassDataAndStaticValues() {
        val bytes = fixtureDex()
        val dex = InstalledCodeDex.parse(bytes)
        assertEquals(37, dex.version)
        assertEquals("037", dex.versionName)
        assertTrue(dex.classCount >= 2)

        val classDefsOffset = u32(bytes, 0x64)
        var unalignedClassData = false
        var unalignedStaticValues = false
        var sawBranch = false
        var sawSwitch = false
        for (index in 0 until dex.classCount) {
            val base = classDefsOffset + index * 32
            val classDataOffset = u32(bytes, base + 24)
            val staticValuesOffset = u32(bytes, base + 28)
            if (classDataOffset != 0 && classDataOffset % 4 != 0) unalignedClassData = true
            if (staticValuesOffset != 0 && staticValuesOffset % 4 != 0) unalignedStaticValues = true
            for (method in dex.classDef(index).methods) {
                if (method.codeOffset == 0) continue
                val code = dex.codeItem(method.codeOffset)
                val instructions = dex.instructions(code).asSequence().toList()
                if (instructions.any { it.opcode == 0x2b || it.opcode == 0x2c }) sawSwitch = true
                if (instructions.any { it.isBranch && it.opcode != 0x2b && it.opcode != 0x2c }) sawBranch = true
                dex.branchTargets(code)
                dex.handlerAddresses(code)
            }
        }
        assertTrue("the fixture must contain unaligned class data", unalignedClassData)
        assertTrue("the fixture must contain an unaligned static value", unalignedStaticValues)
        assertTrue("the fixture must contain a branch", sawBranch)
        assertTrue("the fixture must contain a switch", sawSwitch)
    }

    @Test
    fun readsMarkersAndDefaultsFromTheRealDex() {
        val file = folder.newFile("fixture.apk")
        writeApk(file, mapOf("classes.dex" to fixtureDex()))
        val apk = InstalledCodeApkFile(InstalledCodeApkId("base", InstalledCodeApkKind.BASE), file)
        val outcome = InstalledCodeReader().read(
            "fixture",
            FakeInstalledCodeInput(
                mapOf(
                    "fixture" to InstalledCodePackageInput.Visible(1L, listOf(apk)),
                ),
            ),
        )

        assertTrue(outcome is InstalledCodeOutcome.Read)
        val facts = (outcome as InstalledCodeOutcome.Read).facts

        val protectedFlag = facts.marker("ProtectedDownload__enabled")
        assertNotNull(protectedFlag)
        assertEquals(InstalledCodePresence.PRESENT, protectedFlag!!.presence)
        assertTrue(protectedFlag.methodCount >= 1)
        assertEquals(
            listOf<InstalledCodeDefaultValue>(InstalledCodeDefaultValue.BooleanValue(false)),
            protectedFlag.references.flatMap { it.defaults }.distinct(),
        )

        val buildId = facts.marker("AicDataRelease__build_id_")
        assertNotNull(buildId)
        assertEquals(
            setOf("AicDataRelease__build_id_aicore", "AicDataRelease__build_id_"),
            buildId!!.references.map { it.matchedString }.toSet(),
        )
        buildId.references.forEach { reference ->
            assertEquals(
                "every build-id site must resolve its boxed zero",
                listOf<InstalledCodeDefaultValue>(InstalledCodeDefaultValue.IntValue(0)),
                reference.defaults,
            )
        }
        val runtimeKey = buildId.references.first {
            it.matchedString == "AicDataRelease__build_id_"
        }
        assertEquals(
            listOf<InstalledCodeDefaultValue>(InstalledCodeDefaultValue.IntValue(0)),
            runtimeKey.defaults,
        )

        val staticFlag = facts.marker("PccSecurity__enable_security_check")
        assertNotNull(staticFlag)
        assertEquals(
            listOf<InstalledCodeDefaultValue>(InstalledCodeDefaultValue.BooleanValue(false)),
            staticFlag!!.references.flatMap { it.defaults }.distinct(),
        )

        val pcsFlag = facts.marker("PcsAi__enable_genai_inference_service")
        assertNotNull(pcsFlag)
        assertEquals(
            listOf<InstalledCodeDefaultValue>(InstalledCodeDefaultValue.BooleanValue(true)),
            pcsFlag!!.references.flatMap { it.defaults }.distinct(),
        )
    }

    private fun fixtureDex(): ByteArray {
        val stream = javaClass.getResourceAsStream("/installed-code-fixture/classes.dex")
        assertNotNull("the real dex fixture is missing", stream)
        return stream!!.use { it.readBytes() }
    }

    private fun u32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
