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

        val service = facts.components.single()
        assertEquals("service", service.kind)
        assertEquals(".FixtureService", service.name)
        assertEquals(true, service.exported)
        assertEquals("dev.shockstruck.manifestfixture.PRIVILEGED", service.permission)
        assertEquals(":fixture", service.process)
        assertEquals(
            setOf("process", "isolatedProcess"),
            service.matchingAttributes.map { it.name }.toSet(),
        )

        val access = facts.pccElements.single()
        assertEquals("allow-component-access", access.name)
        assertTrue(access.attributes.isEmpty())
        assertEquals(listOf(FIXTURE_DIGEST), access.certificateDigests)
    }

    @Test
    fun decodesAUtf8StringPoolAndKeepsUnknownAttributeIds() {
        val packageName = "com.example.utf8"
        val facts = parse(buildUtf8Fixture(packageName))

        assertEquals(packageName, facts.declaredPackage)
        val access = facts.pccElements.single()
        assertEquals("allow-component-access", access.name)
        val unknown = access.attributes.single()
        assertEquals("customFlag", unknown.name)
        assertNull(unknown.resourceIdHex)
        assertEquals("hello", unknown.rawValue)
        assertEquals("hello", unknown.typedValue)
        assertEquals("string", unknown.dataType)
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
        )
        val chunks = listOf(
            utf8StringPool(strings),
            resourceMap(listOf(0x01010003, 0x02000000)),
            startElement(3, listOf(Attribute(4, 5))),
            startElement(6, listOf(Attribute(2, 9))),
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
            require(encoded.size < 0x80) { "fixture string too long" }
            data.write(encoded.size)
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

        const val FIXTURE_DIGEST =
            "AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:" +
                "AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89"

        val FIXTURE_AXML_BASE64 = listOf(
            "AwAIADwKAAABABwAWAYAACUAAAAAAAAAAAAAALAAAAAAAAAAAAAAAA4AAAAaAAAAMgAAAFQAAABwAAAAhAAAAJYAAACwAAAAzgAA",
            "APAAAAAIAQAALgEAAGQBAACGAQAAkAEAAJgBAACsAQAAbgIAAJ4CAACwAgAA6gIAACYDAABAAwAAaAMAAKoDAAACBAAAMgQAAEQE",
            "AACcBAAAsAQAAMIEAADaBAAADgUAAEIFAABUBQAAdgUAAAUAbABhAGIAZQBsAAAABABuAGEAbQBlAAAACgBwAGUAcgBtAGkAcwBz",
            "AGkAbwBuAAAADwBwAHIAbwB0AGUAYwB0AGkAbwBuAEwAZQB2AGUAbAAAAAwAcwBoAGEAcgBlAGQAVQBzAGUAcgBJAGQAAAAIAGUA",
            "eABwAG8AcgB0AGUAZAAAAAcAcAByAG8AYwBlAHMAcwAAAAsAdgBlAHIAcwBpAG8AbgBOAGEAbQBlAAAADQBtAGEAeABTAGQAawBW",
            "AGUAcgBzAGkAbwBuAAAADwBpAHMAbwBsAGEAdABlAGQAUAByAG8AYwBlAHMAcwAAAAoAYwBlAHIAdABEAGkAZwBlAHMAdAAAABEA",
            "YwBvAG0AcABpAGwAZQBTAGQAawBWAGUAcgBzAGkAbwBuAAAAGQBjAG8AbQBwAGkAbABlAFMAZABrAFYAZQByAHMAaQBvAG4AQwBv",
            "AGQAZQBuAGEAbQBlAAAADwAuAEYAaQB4AHQAdQByAGUAUwBlAHIAdgBpAGMAZQAAAAMAMQAuADAAAAACADEANwAAAAgAOgBmAGkA",
            "eAB0AHUAcgBlAAAAXwBBAEIAOgBDAEQAOgBFAEYAOgAwADEAOgAyADMAOgA0ADUAOgA2ADcAOgA4ADkAOgBBAEIAOgBDAEQAOgBF",
            "AEYAOgAwADEAOgAyADMAOgA0ADUAOgA2ADcAOgA4ADkAOgBBAEIAOgBDAEQAOgBFAEYAOgAwADEAOgAyADMAOgA0ADUAOgA2ADcA",
            "OgA4ADkAOgBBAEIAOgBDAEQAOgBFAEYAOgAwADEAOgAyADMAOgA0ADUAOgA2ADcAOgA4ADkAAAAWAGEAbABsAG8AdwAtAGMAbwBt",
            "AHAAbwBuAGUAbgB0AC0AYQBjAGMAZQBzAHMAAAAHAGEAbgBkAHIAbwBpAGQAAAAbAGEAbgBkAHIAbwBpAGQALgBwAGUAcgBtAGkA",
            "cwBzAGkAbwBuAC4ASQBOAFQARQBSAE4ARQBUAAAAHABhAG4AZAByAG8AaQBkAC4AcABlAHIAbQBpAHMAcwBpAG8AbgAuAFIARQBB",
            "AEQAXwBMAE8ARwBTAAAACwBhAHAAcABsAGkAYwBhAHQAaQBvAG4AAAASAGMAbwBtAC4AZQB4AGEAbQBwAGwAZQAuAHQAYQByAGcA",
            "ZQB0AAAAHwBkAGUAdgAuAHMAaABvAGMAawBzAHQAcgB1AGMAawAuAG0AYQBuAGkAZgBlAHMAdABmAGkAeAB0AHUAcgBlAAAAKgBk",
            "AGUAdgAuAHMAaABvAGMAawBzAHQAcgB1AGMAawAuAG0AYQBuAGkAZgBlAHMAdABmAGkAeAB0AHUAcgBlAC4AUABSAEkAVgBJAEwA",
            "RQBHAEUARAAAABYAZABlAHYALgBzAGgAbwBjAGsAcwB0AHIAdQBjAGsALgBzAGgAYQByAGUAZAAAAAcAZgBpAHgAdAB1AHIAZQAA",
            "ACoAaAB0AHQAcAA6AC8ALwBzAGMAaABlAG0AYQBzAC4AYQBuAGQAcgBvAGkAZAAuAGMAbwBtAC8AYQBwAGsALwByAGUAcwAvAGEA",
            "bgBkAHIAbwBpAGQAAAAIAG0AYQBuAGkAZgBlAHMAdAAAAAcAcABhAGMAawBhAGcAZQAAAAoAcABlAHIAbQBpAHMAcwBpAG8AbgAA",
            "ABgAcABsAGEAdABmAG8AcgBtAEIAdQBpAGwAZABWAGUAcgBzAGkAbwBuAEMAbwBkAGUAAAAYAHAAbABhAHQAZgBvAHIAbQBCAHUA",
            "aQBsAGQAVgBlAHIAcwBpAG8AbgBOAGEAbQBlAAAABwBzAGUAcgB2AGkAYwBlAAAADwB1AHMAZQBzAC0AcABlAHIAbQBpAHMAcwBp",
            "AG8AbgAAABYAdQBzAGUAcwAtAHAAZQByAG0AaQBzAHMAaQBvAG4ALQBzAGQAawAtADIAMwAAAAAAgAEIADwAAAABAAEBAwABAQYA",
            "AQEJAAEBCwABARAAAQERAAEBHAIBAXECAQGpAwEBSAUBAXIFAQFzBQEBAAEQABgAAAACAAAA/////xMAAAAcAAAAAgEQALAAAAAC",
            "AAAA//////////8dAAAAFAAUAAcAAAAAAAAAHAAAAAQAAAAaAAAACAAAAxoAAAAcAAAABwAAAA4AAAAIAAADDgAAABwAAAALAAAA",
            "/////wgAABAlAAAAHAAAAAwAAAAPAAAACAAAAw8AAAD/////HgAAABgAAAAIAAADGAAAAP////8gAAAA/////wgAABAlAAAA////",
            "/yEAAAD/////CAAAEBEAAAACARAATAAAAAcAAAD//////////x8AAAAUABQAAgAAAAAAAAAcAAAAAQAAABkAAAAIAAADGQAAABwA",
            "AAADAAAA/////wgAABESAAAAAwEQABgAAAAHAAAA//////////8fAAAAAgEQADgAAAALAAAA//////////8jAAAAFAAUAAEAAAAA",
            "AAAAHAAAAAEAAAAUAAAACAAAAxQAAAADARAAGAAAAAsAAAD//////////yMAAAACARAATAAAAAwAAAD//////////yQAAAAUABQA",
            "AgAAAAAAAAAcAAAAAQAAABUAAAAIAAADFQAAABwAAAAIAAAA/////wgAABAcAAAAAwEQABgAAAAMAAAA//////////8kAAAAAgEQ",
            "ACQAAAAQAAAA//////////8SAAAAFAAUAAAAAAAAAAAAAgEQAEwAAAARAAAA//////////8eAAAAFAAUAAIAAAAAAAAAHAAAAAEA",
            "AAAXAAAACAAAAxcAAAAcAAAACgAAABEAAAAIAAADEQAAAAMBEAAYAAAAEQAAAP//////////HgAAAAMBEAAYAAAAEAAAAP//////",
            "////EgAAAAIBEAA4AAAAFgAAAP//////////FgAAABQAFAABAAAAAAAAABwAAAAAAAAAGwAAAAgAAAMbAAAAAgEQAIgAAAAXAAAA",
            "//////////8iAAAAFAAUAAUAAAAAAAAAHAAAAAEAAAANAAAACAAAAw0AAAAcAAAAAgAAABkAAAAIAAADGQAAABwAAAAFAAAA////",
            "/wgAABL/////HAAAAAYAAAAQAAAACAAAAxAAAAAcAAAACQAAAP////8IAAAS/////wMBEAAYAAAAFwAAAP//////////IgAAAAMB",
            "EAAYAAAAFgAAAP//////////FgAAAAMBEAAYAAAAAgAAAP//////////HQAAAAEBEAAYAAAAAgAAAP////8TAAAAHAAAAA==",
        ).joinToString("")

        init {
            require(FIXTURE_AXML_BASE64.isNotBlank())
        }
    }
}
