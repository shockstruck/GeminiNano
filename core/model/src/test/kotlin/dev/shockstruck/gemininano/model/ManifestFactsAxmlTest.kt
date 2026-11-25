package dev.shockstruck.gemininano.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

class ManifestFactsAxmlTest {

    @Test
    fun decodesARealAapt2Manifest() {
        val facts = parse(fixtureBytes())

        assertEquals("dev.shockstruck.manifestfixture", facts.declaredPackage)
        assertEquals("dev.shockstruck.shared", facts.sharedUserId)
        assertEquals("1.0", facts.versionName)

        val permission = facts.permissions.single()
        assertEquals("dev.shockstruck.manifestfixture.PRIVILEGED", permission.name)
        assertEquals("signature", permission.protectionLevelBase)
        assertEquals(listOf("privileged"), permission.protectionLevelFlags)

        assertEquals(
            listOf("android.permission.INTERNET", "android.permission.READ_LOGS"),
            facts.usesPermissions.map { it.name },
        )
        assertEquals("28", facts.usesPermissions[1].maxSdkVersion)
        assertEquals("26", facts.usesPermissions[1].minSdkVersion)

        val service = facts.components.single { it.kind == "service" }
        assertEquals("com.google.android.apps.miphone.astrea.services.AstreaService", service.name)
        assertEquals(true, service.exported)
        assertEquals("dev.shockstruck.manifestfixture.PRIVILEGED", service.permission)
        assertEquals(":fixture", service.process)
        assertEquals(ManifestBooleanValue.TRUE, service.privateComputeCore)
        assertEquals(
            setOf("process", "isolatedProcess", "privateComputeCore"),
            service.matchingAttributes.map { it.name }.toSet(),
        )
        assertEquals(
            listOf("com.google.android.as.oss.action.START"),
            service.intentFilters.single().actions,
        )
        assertEquals(
            listOf("android.intent.category.DEFAULT"),
            service.intentFilters.single().categories,
        )

        val activity = facts.components.single { it.kind == "activity" }
        assertEquals(ManifestBooleanValue.FALSE, activity.privateComputeCore)
        assertEquals(
            listOf("com.google.android.aicore.action.PROBE"),
            activity.intentFilters.single().actions,
        )

        val alias = facts.components.single { it.kind == "activity-alias" }
        assertEquals(".FixtureAlias", alias.name)
        assertEquals(".FixtureActivity", alias.targetActivity)
        assertEquals(ManifestBooleanValue.TRUE, alias.privateComputeCore)
        assertEquals(
            listOf("com.google.android.as.oss.action.ALIAS"),
            alias.intentFilters.single().actions,
        )

        assertEquals(
            setOf("com.google.android.aicore", "com.google.android.as.oss"),
            facts.queries.packages.toSet(),
        )

        val access = facts.pccElements.single()
        assertEquals("allow-component-access", access.name)
        assertEquals(listOf(FIXTURE_DIGEST), access.certificateDigests)
        val packageEntry = access.packages.single()
        assertEquals("com.example.target", packageEntry.name)
        assertEquals(listOf(FIXTURE_DIGEST), packageEntry.certificateDigests)
    }

    @Test
    fun decodesAUtf8StringPoolAndKeepsUnknownAttributeIds() {
        val packageName = "com.example.utf8"
        val facts = parse(buildUtf8Fixture(packageName))

        assertEquals(packageName, facts.declaredPackage)
        val access = facts.pccElements.single()
        assertEquals("allow-component-access", access.name)
        val attributes = access.attributes.associateBy { it.name }
        val unknown = attributes.getValue("customFlag")
        assertNull(unknown.resourceIdHex)
        assertEquals("hello", unknown.rawValue)
        assertEquals("hello", unknown.typedValue)
        assertEquals("string", unknown.dataType)
        assertEquals(UTF8_NON_ASCII, attributes.getValue("note").rawValue)
        assertEquals(UTF8_LONG_VALUE, attributes.getValue("longValue").rawValue)
        assertEquals(UTF8_LONG_VALUE.length, attributes.getValue("longValue").rawValue?.length)
        assertEquals(listOf("the-digest"), access.certificateDigests)
    }

    @Test(expected = ManifestFormatException::class)
    fun rejectsTruncatedInput() {
        val bytes = fixtureBytes()
        parse(bytes.copyOf(bytes.size - 32))
    }

    @Test(expected = ManifestFormatException::class)
    fun rejectsANonBinaryXmlRootChunk() {
        val bytes = fixtureBytes()
        bytes[0] = 0x04
        parse(bytes)
    }

    @Test(expected = ManifestSizeLimitException::class)
    fun rejectsInputAboveTheByteLimit() {
        ManifestFactsAxmlDecoder(
            fixtureBytes(),
            ManifestReadLimits(maxBytesPerManifest = 64),
        )
    }

    @Test(expected = ManifestFormatException::class)
    fun rejectsAnElementThatNeverCloses() {
        val bytes = buildUtf8Fixture("com.example.unclosed")
        parse(bytes.copyOf(bytes.size - 24))
    }

    private fun parse(bytes: ByteArray): ManifestSourceFacts =
        ManifestFactsParser().parse(
            ManifestSourceId("base", ManifestApkKind.BASE),
            ManifestFactsAxmlDecoder(bytes),
        )

    private fun fixtureBytes(): ByteArray =
        Base64.getDecoder().decode(FIXTURE_AXML_BASE64)

    private fun buildUtf8Fixture(packageName: String): ByteArray {
        val strings = listOf(
            "name",
            "certDigest",
            "customFlag",
            "manifest",
            "package",
            packageName,
            "allow-component-access",
            "com.example.target",
            "the-digest",
            "hello",
            "note",
            UTF8_NON_ASCII,
            "longValue",
            UTF8_LONG_VALUE,
        )
        val chunks = listOf(
            utf8StringPool(strings),
            resourceMap(listOf(0x01010003, 0x02000000)),
            startElement(3, listOf(Attribute(4, 5))),
            startElement(6, listOf(Attribute(2, 9), Attribute(10, 11), Attribute(12, 13))),
            startElement(4, listOf(Attribute(0, 7), Attribute(1, 8))),
            endElement(4),
            endElement(6),
            endElement(3),
        )
        return document(chunks)
    }

    private fun document(chunks: List<ByteArray>): ByteArray {
        val total = MIN_XML_HEADER + chunks.sumOf { it.size }
        return ByteArrayOutputStream().apply {
            write(le16(RES_XML_TYPE))
            write(le16(MIN_XML_HEADER))
            write(le32(total))
            chunks.forEach { write(it) }
        }.toByteArray()
    }

    private fun utf8StringPool(strings: List<String>): ByteArray {
        val offsets = mutableListOf<Int>()
        val data = ByteArrayOutputStream()
        for (value in strings) {
            offsets += data.size()
            val encoded = value.toByteArray(Charsets.UTF_8)
            writeUtf8Length(data, value.length)
            writeUtf8Length(data, encoded.size)
            data.write(encoded)
            data.write(0)
        }
        while (data.size() % 4 != 0) data.write(0)

        val headerSize = STRING_POOL_HEADER
        val stringsStart = headerSize + strings.size * 4
        val size = stringsStart + data.size()
        return ByteArrayOutputStream().apply {
            write(le16(RES_STRING_POOL_TYPE))
            write(le16(headerSize))
            write(le32(size))
            write(le32(strings.size))
            write(le32(0))
            write(le32(UTF8_FLAG))
            write(le32(stringsStart))
            write(le32(0))
            offsets.forEach { write(le32(it)) }
            write(data.toByteArray())
        }.toByteArray()
    }

    private fun writeUtf8Length(out: ByteArrayOutputStream, length: Int) {
        require(length in 0..0x7FFF) { "fixture string too long" }
        if (length > 0x7F) {
            out.write(0x80 or ((length ushr 8) and 0x7F))
            out.write(length and 0xFF)
        } else {
            out.write(length)
        }
    }

    private fun resourceMap(ids: List<Int>): ByteArray = ByteArrayOutputStream().apply {
        write(le16(RES_XML_RESOURCE_MAP_TYPE))
        write(le16(8))
        write(le32(8 + ids.size * 4))
        ids.forEach { write(le32(it)) }
    }.toByteArray()

    private fun startElement(nameIndex: Int, attributes: List<Attribute>): ByteArray {
        val size = START_ELEMENT_HEADER + ATTRIBUTE_EXTENSION_SIZE + attributes.size * ATTRIBUTE_SIZE
        return ByteArrayOutputStream().apply {
            write(le16(RES_XML_START_ELEMENT_TYPE))
            write(le16(START_ELEMENT_HEADER))
            write(le32(size))
            write(le32(0))
            write(le32(NO_ENTRY))
            write(le32(NO_ENTRY))
            write(le32(nameIndex))
            write(le16(ATTRIBUTE_EXTENSION_SIZE))
            write(le16(ATTRIBUTE_SIZE))
            write(le16(attributes.size))
            write(le16(0))
            write(le16(0))
            write(le16(0))
            attributes.forEach { attribute ->
                write(le32(NO_ENTRY))
                write(le32(attribute.nameIndex))
                write(le32(attribute.valueIndex))
                write(le16(8))
                write(0)
                write(TYPE_STRING)
                write(le32(attribute.valueIndex))
            }
        }.toByteArray()
    }

    private fun endElement(nameIndex: Int): ByteArray = ByteArrayOutputStream().apply {
        write(le16(RES_XML_END_ELEMENT_TYPE))
        write(le16(END_ELEMENT_HEADER))
        write(le32(END_ELEMENT_HEADER + 8))
        write(le32(0))
        write(le32(NO_ENTRY))
        write(le32(NO_ENTRY))
        write(le32(nameIndex))
    }.toByteArray()

    private fun le16(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
    )

    private fun le32(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte(),
    )

    private data class Attribute(
        val nameIndex: Int,
        val valueIndex: Int,
    )

    private companion object {
        const val RES_STRING_POOL_TYPE = 0x0001
        const val RES_XML_TYPE = 0x0003
        const val RES_XML_START_ELEMENT_TYPE = 0x0102
        const val RES_XML_END_ELEMENT_TYPE = 0x0103
        const val RES_XML_RESOURCE_MAP_TYPE = 0x0180
        const val UTF8_FLAG = 0x100
        const val NO_ENTRY = -1
        const val MIN_XML_HEADER = 8
        const val STRING_POOL_HEADER = 28
        const val START_ELEMENT_HEADER = 16
        const val END_ELEMENT_HEADER = 16
        const val ATTRIBUTE_EXTENSION_SIZE = 20
        const val ATTRIBUTE_SIZE = 20
        const val TYPE_STRING = 0x03

        const val UTF8_NON_ASCII = "priv\u00e9 \u2713 \u8a08\u7b97"
        val UTF8_LONG_VALUE = "x".repeat(150)

        const val FIXTURE_DIGEST =
            "AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:" +
                "AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89"

        val FIXTURE_AXML_BASE64 = listOf(
            "AwAIAJwRAAABABwAxAkAADYAAAAAAAAAAAAAAPQAAAAAAAAAAAAAAA4AAAAaAAAAMgAAAFQAAABwAAAAhAAAAJYAAAC2AAAA1AAA",
            "AO4AAAAMAQAALgEAAEYBAABsAQAAogEAAMoBAADuAQAADAIAABYCAAAeAgAAMgIAAPQCAAAEAwAAGAMAADgDAABoAwAAegMAALwD",
            "AAD2AwAAMgQAAEwEAABgBAAAiAQAAL4EAAAOBQAAjAUAAMIFAAASBgAAYgYAAKQGAAD8BgAALAcAAD4HAACWBwAAtAcAAMgHAADa",
            "BwAA8gcAACYIAABaCAAAbAgAAH4IAACgCAAABQBsAGEAYgBlAGwAAAAEAG4AYQBtAGUAAAAKAHAAZQByAG0AaQBzAHMAaQBvAG4A",
            "AAAPAHAAcgBvAHQAZQBjAHQAaQBvAG4ATABlAHYAZQBsAAAADABzAGgAYQByAGUAZABVAHMAZQByAEkAZAAAAAgAZQB4AHAAbwBy",
            "AHQAZQBkAAAABwBwAHIAbwBjAGUAcwBzAAAADgB0AGEAcgBnAGUAdABBAGMAdABpAHYAaQB0AHkAAAANAG0AaQBuAFMAZABrAFYA",
            "ZQByAHMAaQBvAG4AAAALAHYAZQByAHMAaQBvAG4ATgBhAG0AZQAAAA0AbQBhAHgAUwBkAGsAVgBlAHIAcwBpAG8AbgAAAA8AaQBz",
            "AG8AbABhAHQAZQBkAFAAcgBvAGMAZQBzAHMAAAAKAGMAZQByAHQARABpAGcAZQBzAHQAAAARAGMAbwBtAHAAaQBsAGUAUwBkAGsA",
            "VgBlAHIAcwBpAG8AbgAAABkAYwBvAG0AcABpAGwAZQBTAGQAawBWAGUAcgBzAGkAbwBuAEMAbwBkAGUAbgBhAG0AZQAAABIAcABy",
            "AGkAdgBhAHQAZQBDAG8AbQBwAHUAdABlAEMAbwByAGUAAAAQAC4ARgBpAHgAdAB1AHIAZQBBAGMAdABpAHYAaQB0AHkAAAANAC4A",
            "RgBpAHgAdAB1AHIAZQBBAGwAaQBhAHMAAAADADEALgAwAAAAAgAxADcAAAAIADoAZgBpAHgAdAB1AHIAZQAAAF8AQQBCADoAQwBE",
            "ADoARQBGADoAMAAxADoAMgAzADoANAA1ADoANgA3ADoAOAA5ADoAQQBCADoAQwBEADoARQBGADoAMAAxADoAMgAzADoANAA1ADoA",
            "NgA3ADoAOAA5ADoAQQBCADoAQwBEADoARQBGADoAMAAxADoAMgAzADoANAA1ADoANgA3ADoAOAA5ADoAQQBCADoAQwBEADoARQBG",
            "ADoAMAAxADoAMgAzADoANAA1ADoANgA3ADoAOAA5AAAABgBhAGMAdABpAG8AbgAAAAgAYQBjAHQAaQB2AGkAdAB5AAAADgBhAGMA",
            "dABpAHYAaQB0AHkALQBhAGwAaQBhAHMAAAAWAGEAbABsAG8AdwAtAGMAbwBtAHAAbwBuAGUAbgB0AC0AYQBjAGMAZQBzAHMAAAAH",
            "AGEAbgBkAHIAbwBpAGQAAAAfAGEAbgBkAHIAbwBpAGQALgBpAG4AdABlAG4AdAAuAGMAYQB0AGUAZwBvAHIAeQAuAEQARQBGAEEA",
            "VQBMAFQAAAAbAGEAbgBkAHIAbwBpAGQALgBwAGUAcgBtAGkAcwBzAGkAbwBuAC4ASQBOAFQARQBSAE4ARQBUAAAAHABhAG4AZABy",
            "AG8AaQBkAC4AcABlAHIAbQBpAHMAcwBpAG8AbgAuAFIARQBBAEQAXwBMAE8ARwBTAAAACwBhAHAAcABsAGkAYwBhAHQAaQBvAG4A",
            "AAAIAGMAYQB0AGUAZwBvAHIAeQAAABIAYwBvAG0ALgBlAHgAYQBtAHAAbABlAC4AdABhAHIAZwBlAHQAAAAZAGMAbwBtAC4AZwBv",
            "AG8AZwBsAGUALgBhAG4AZAByAG8AaQBkAC4AYQBpAGMAbwByAGUAAAAmAGMAbwBtAC4AZwBvAG8AZwBsAGUALgBhAG4AZAByAG8A",
            "aQBkAC4AYQBpAGMAbwByAGUALgBhAGMAdABpAG8AbgAuAFAAUgBPAEIARQAAAD0AYwBvAG0ALgBnAG8AbwBnAGwAZQAuAGEAbgBk",
            "AHIAbwBpAGQALgBhAHAAcABzAC4AbQBpAHAAaABvAG4AZQAuAGEAcwB0AHIAZQBhAC4AcwBlAHIAdgBpAGMAZQBzAC4AQQBzAHQA",
            "cgBlAGEAUwBlAHIAdgBpAGMAZQAAABkAYwBvAG0ALgBnAG8AbwBnAGwAZQAuAGEAbgBkAHIAbwBpAGQALgBhAHMALgBvAHMAcwAA",
            "ACYAYwBvAG0ALgBnAG8AbwBnAGwAZQAuAGEAbgBkAHIAbwBpAGQALgBhAHMALgBvAHMAcwAuAGEAYwB0AGkAbwBuAC4AQQBMAEkA",
            "QQBTAAAAJgBjAG8AbQAuAGcAbwBvAGcAbABlAC4AYQBuAGQAcgBvAGkAZAAuAGEAcwAuAG8AcwBzAC4AYQBjAHQAaQBvAG4ALgBT",
            "AFQAQQBSAFQAAAAfAGQAZQB2AC4AcwBoAG8AYwBrAHMAdAByAHUAYwBrAC4AbQBhAG4AaQBmAGUAcwB0AGYAaQB4AHQAdQByAGUA",
            "AAAqAGQAZQB2AC4AcwBoAG8AYwBrAHMAdAByAHUAYwBrAC4AbQBhAG4AaQBmAGUAcwB0AGYAaQB4AHQAdQByAGUALgBQAFIASQBW",
            "AEkATABFAEcARQBEAAAAFgBkAGUAdgAuAHMAaABvAGMAawBzAHQAcgB1AGMAawAuAHMAaABhAHIAZQBkAAAABwBmAGkAeAB0AHUA",
            "cgBlAAAAKgBoAHQAdABwADoALwAvAHMAYwBoAGUAbQBhAHMALgBhAG4AZAByAG8AaQBkAC4AYwBvAG0ALwBhAHAAawAvAHIAZQBz",
            "AC8AYQBuAGQAcgBvAGkAZAAAAA0AaQBuAHQAZQBuAHQALQBmAGkAbAB0AGUAcgAAAAgAbQBhAG4AaQBmAGUAcwB0AAAABwBwAGEA",
            "YwBrAGEAZwBlAAAACgBwAGUAcgBtAGkAcwBzAGkAbwBuAAAAGABwAGwAYQB0AGYAbwByAG0AQgB1AGkAbABkAFYAZQByAHMAaQBv",
            "AG4AQwBvAGQAZQAAABgAcABsAGEAdABmAG8AcgBtAEIAdQBpAGwAZABWAGUAcgBzAGkAbwBuAE4AYQBtAGUAAAAHAHEAdQBlAHIA",
            "aQBlAHMAAAAHAHMAZQByAHYAaQBjAGUAAAAPAHUAcwBlAHMALQBwAGUAcgBtAGkAcwBzAGkAbwBuAAAAFgB1AHMAZQBzAC0AcABl",
            "AHIAbQBpAHMAcwBpAG8AbgAtAHMAZABrAC0AMgAzAAAAgAEIAEgAAAABAAEBAwABAQYAAQEJAAEBCwABARAAAQERAAEBAgIBAQwC",
            "AQEcAgEBcQIBAakDAQFIBQEBcgUBAXMFAQG1BgEBAAEQABgAAAACAAAA/////xoAAAArAAAAAgEQALAAAAACAAAA//////////8t",
            "AAAAFAAUAAcAAAAAAAAAKwAAAAQAAAApAAAACAAAAykAAAArAAAACQAAABIAAAAIAAADEgAAACsAAAANAAAA/////wgAABAlAAAA",
            "KwAAAA4AAAATAAAACAAAAxMAAAD/////LgAAACcAAAAIAAADJwAAAP////8wAAAA/////wgAABAlAAAA/////zEAAAD/////CAAA",
            "EBEAAAACARAATAAAAAcAAAD//////////y8AAAAUABQAAgAAAAAAAAArAAAAAQAAACgAAAAIAAADKAAAACsAAAADAAAA/////wgA",
            "ABESAAAAAwEQABgAAAAHAAAA//////////8vAAAAAgEQADgAAAALAAAA//////////80AAAAFAAUAAEAAAAAAAAAKwAAAAEAAAAc",
            "AAAACAAAAxwAAAADARAAGAAAAAsAAAD//////////zQAAAACARAAYAAAAAwAAAD//////////zUAAAAUABQAAwAAAAAAAAArAAAA",
            "AQAAAB0AAAAIAAADHQAAACsAAAAIAAAA/////wgAABAaAAAAKwAAAAoAAAD/////CAAAEBwAAAADARAAGAAAAAwAAAD/////////",
            "/zUAAAACARAAJAAAABEAAAD//////////zIAAAAUABQAAAAAAAAAAAACARAAOAAAABIAAAD//////////y4AAAAUABQAAQAAAAAA",
            "AAArAAAAAQAAACEAAAAIAAADIQAAAAMBEAAYAAAAEgAAAP//////////LgAAAAIBEAA4AAAAEwAAAP//////////LgAAABQAFAAB",
            "AAAAAAAAACsAAAABAAAAJAAAAAgAAAMkAAAAAwEQABgAAAATAAAA//////////8uAAAAAwEQABgAAAARAAAA//////////8yAAAA",
            "AgEQACQAAAAWAAAA//////////8ZAAAAFAAUAAAAAAAAAAAAAgEQAEwAAAAXAAAA//////////8uAAAAFAAUAAIAAAAAAAAAKwAA",
            "AAEAAAAgAAAACAAAAyAAAAArAAAADAAAABUAAAAIAAADFQAAAAMBEAAYAAAAFwAAAP//////////LgAAAAMBEAAYAAAAFgAAAP//",
            "////////GQAAAAIBEAA4AAAAHAAAAP//////////HgAAABQAFAABAAAAAAAAACsAAAAAAAAAKgAAAAgAAAMqAAAAAgEQAJwAAAAd",
            "AAAA//////////8zAAAAFAAUAAYAAAAAAAAAKwAAAAEAAAAjAAAACAAAAyMAAAArAAAAAgAAACgAAAAIAAADKAAAACsAAAAFAAAA",
            "/////wgAABL/////KwAAAAYAAAAUAAAACAAAAxQAAAArAAAACwAAAP////8IAAAS/////ysAAAAPAAAA/////wgAABL/////AgEQ",
            "ACQAAAAkAAAA//////////8sAAAAFAAUAAAAAAAAAAAAAgEQADgAAAAlAAAA//////////8WAAAAFAAUAAEAAAAAAAAAKwAAAAEA",
            "AAAmAAAACAAAAyYAAAADARAAGAAAACUAAAD//////////xYAAAACARAAOAAAACYAAAD//////////x8AAAAUABQAAQAAAAAAAAAr",
            "AAAAAQAAABsAAAAIAAADGwAAAAMBEAAYAAAAJgAAAP//////////HwAAAAMBEAAYAAAAJAAAAP//////////LAAAAAMBEAAYAAAA",
            "HQAAAP//////////MwAAAAIBEABMAAAAKQAAAP//////////FwAAABQAFAACAAAAAAAAACsAAAABAAAAEAAAAAgAAAMQAAAAKwAA",
            "AA8AAAD/////CAAAEgAAAAACARAAJAAAACwAAAD//////////ywAAAAUABQAAAAAAAAAAAACARAAOAAAAC0AAAD//////////xYA",
            "AAAUABQAAQAAAAAAAAArAAAAAQAAACIAAAAIAAADIgAAAAMBEAAYAAAALQAAAP//////////FgAAAAMBEAAYAAAALAAAAP//////",
            "////LAAAAAMBEAAYAAAAKQAAAP//////////FwAAAAIBEAB0AAAAMAAAAP//////////GAAAABQAFAAEAAAAAAAAACsAAAABAAAA",
            "EQAAAAgAAAMRAAAAKwAAAAUAAAD/////CAAAEv////8rAAAABwAAABAAAAAIAAADEAAAACsAAAAPAAAA/////wgAABL/////AgEQ",
            "ACQAAAA1AAAA//////////8sAAAAFAAUAAAAAAAAAAAAAgEQADgAAAA2AAAA//////////8WAAAAFAAUAAEAAAAAAAAAKwAAAAEA",
            "AAAlAAAACAAAAyUAAAADARAAGAAAADYAAAD//////////xYAAAADARAAGAAAADUAAAD//////////ywAAAADARAAGAAAADAAAAD/",
            "/////////xgAAAADARAAGAAAABwAAAD//////////x4AAAADARAAGAAAAAIAAAD//////////y0AAAABARAAGAAAAAIAAAD/////",
            "GgAAACsAAAA=",
        ).joinToString("")

        init {
            require(FIXTURE_AXML_BASE64.isNotBlank())
        }
    }
}
