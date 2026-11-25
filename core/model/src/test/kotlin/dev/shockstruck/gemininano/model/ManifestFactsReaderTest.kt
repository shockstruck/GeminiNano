package dev.shockstruck.gemininano.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestFactsReaderTest {

    private val reader = ManifestFactsReader()

    private fun read(
        sources: ManifestPackageSources,
        packageName: String = PACKAGE,
        reader: ManifestFactsReader = this.reader,
    ): ManifestReadOutcome = reader.read(packageName, FakeReadInput(sources))

    @Test
    fun readsBaseAndSplitManifestsWhenPackagesMatch() {
        val baseEvents = manifestEvents(
            listOf(
                start(
                    "permission",
                    attribute("name", "$PACKAGE.PRIVILEGED"),
                    attribute("protectionLevel", "0x12", raw = null, dataType = "int-hex"),
                ),
                end("permission"),
                start("uses-permission", attribute("name", "android.permission.INTERNET")),
                end("uses-permission"),
            ),
        )
        val splitEvents = manifestEvents(
            listOf(
                start(
                    "service",
                    attribute("name", ".SplitService"),
                    attribute("exported", "true", raw = null, dataType = "boolean"),
                ),
                end("service"),
            ),
        )

        val outcome = read(visible(base(baseEvents), split("config.arm64", splitEvents)))
        val delivered = outcome as ManifestReadOutcome.Read

        assertTrue(delivered.facts.complete)
        assertEquals(listOf("base", "split:config.arm64"), delivered.facts.sources.map { it.source.label })
        assertEquals(listOf("$PACKAGE.PRIVILEGED"), delivered.facts.permissions.map { it.name })
        assertEquals(listOf("android.permission.INTERNET"), delivered.facts.usesPermissions.map { it.name })
        assertEquals(listOf("service"), delivered.facts.components.map { it.kind })
        assertEquals(true, delivered.facts.components.single().exported)
        assertEquals(listOf(SIGNER), delivered.facts.signerCertificateSha256)
        assertEquals(SHARED_USER, delivered.facts.effectiveSharedUserId)
    }

    @Test
    fun mismatchWhenBaseDeclaresAnotherPackage() {
        val outcome = read(visible(base(manifestEvents(packageName = "com.other.app"))))

        val mismatch = outcome as ManifestReadOutcome.Mismatch
        assertEquals("com.other.app", mismatch.declaredPackage)
        assertEquals(ManifestApkKind.BASE, mismatch.source.kind)
    }

    @Test
    fun mismatchWhenSplitDeclaresAnotherPackage() {
        val outcome = read(
            visible(
                base(),
                split("config", manifestEvents(packageName = "com.other.app")),
            ),
        )

        val mismatch = outcome as ManifestReadOutcome.Mismatch
        assertEquals("com.other.app", mismatch.declaredPackage)
        assertEquals("split:config", mismatch.source.label)
    }

    @Test
    fun notVisibleWhenPackageLookupFails() {
        assertEquals(
            ManifestReadOutcome.NotVisible(PACKAGE),
            read(ManifestPackageSources.NotVisible),
        )
    }

    @Test
    fun readErrorCarriesTheObservedClassAndMessage() {
        val failing = FakeSource(
            ManifestSourceId("base", ManifestApkKind.BASE),
            openFailure = IllegalStateException("broken apk"),
        )

        val error = read(visible(failing)) as ManifestReadOutcome.ReadError
        assertEquals("java.lang.IllegalStateException", error.errorClass)
        assertEquals("broken apk", error.message)
        assertEquals("base", error.source?.label)
    }

    @Test
    fun partialWhenSplitIsUnreadable() {
        val unreadable = FakeSource(
            ManifestSourceId("split:config", ManifestApkKind.SPLIT),
            openFailure = ManifestFormatException("no AndroidManifest.xml entry"),
        )

        val partial = read(visible(base(), unreadable)) as ManifestReadOutcome.Partial
        assertFalse(partial.facts.complete)
        assertEquals(1, partial.facts.sources.size)
        assertEquals(1, partial.facts.failures.size)
        assertEquals(ManifestFormatException::class.java.name, partial.facts.failures.single().errorClass)
        assertEquals("no AndroidManifest.xml entry", partial.facts.failures.single().message)
    }

    @Test
    fun oversizedInputIsAReadError() {
        val oversized = FakeSource(
            ManifestSourceId("base", ManifestApkKind.BASE),
            openFailure = ManifestSizeLimitException("manifest entry exceeds 64 bytes"),
        )

        val error = read(visible(oversized)) as ManifestReadOutcome.ReadError
        assertEquals(ManifestSizeLimitException::class.java.name, error.errorClass)
        assertEquals("manifest entry exceeds 64 bytes", error.message)
    }

    @Test
    fun recordedItemLimitIsEnforced() {
        val limited = ManifestFactsReader(ManifestReadLimits(maxRecordedItems = 1))
        val events = manifestEvents(
            listOf(
                start("permission", attribute("name", "a")),
                end("permission"),
                start("permission", attribute("name", "b")),
                end("permission"),
            ),
        )

        val error = read(visible(base(events)), reader = limited) as ManifestReadOutcome.ReadError
        assertEquals(ManifestSizeLimitException::class.java.name, error.errorClass)
    }

    @Test
    fun missingBaseManifestIsAReadError() {
        val error = read(visible(split("config"))) as ManifestReadOutcome.ReadError
        assertEquals("MissingBaseManifest", error.errorClass)
        assertNull(error.source)
    }

    @Test
    fun unknownAttributeIdsKeepHexIdAndRawValue() {
        val events = manifestEvents(
            listOf(
                start(
                    "allow-component-access",
                    ManifestAttribute(
                        name = "mysteryFlag",
                        namespace = null,
                        resourceId = 0x7f0b0001,
                        rawValue = "raw-mystery",
                        typedValue = "raw-mystery",
                        dataType = "string",
                    ),
                ),
                end("allow-component-access"),
            ),
        )

        val delivered = read(visible(base(events))) as ManifestReadOutcome.Read
        val attribute = delivered.facts.pccElements.single().attributes.single()
        assertEquals("mysteryFlag", attribute.name)
        assertEquals("0x7f0b0001", attribute.resourceIdHex)
        assertEquals("raw-mystery", attribute.rawValue)
        assertEquals("raw-mystery", attribute.typedValue)
    }

    @Test
    fun componentAccessElementRecordsAttributesTextAndCertificateDigest() {
        val digest = "AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:" +
            "AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89"
        val events = manifestEvents(
            listOf(
                start("allow-component-access", attribute("name", "$PACKAGE", resourceId = 0x01010000)),
                start(
                    "package",
                    attribute("name", "com.example.target"),
                    attribute("certDigest", digest),
                ),
                end("package"),
                end("allow-component-access"),
                start("association"),
                text("contact"),
                text("0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF"),
                end("association"),
            ),
        )

        val delivered = read(visible(base(events))) as ManifestReadOutcome.Read
        val access = delivered.facts.pccElements[0]
        assertEquals("allow-component-access", access.name)
        assertEquals("0x01010000", access.attributes.single().resourceIdHex)
        assertEquals(listOf(digest), access.certificateDigests)

        val association = delivered.facts.pccElements[1]
        assertEquals(listOf("contact", "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF"), association.textValues)
        assertEquals(
            listOf("0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF"),
            association.certificateDigests,
        )
    }

    @Test
    fun componentsRecordExportedPermissionProcessAndMatchingAttributes() {
        val events = manifestEvents(
            listOf(
                start(
                    "service",
                    attribute("name", ".FixtureService"),
                    attribute("exported", "true", raw = null, dataType = "boolean"),
                    attribute("permission", "$PACKAGE.PRIVILEGED"),
                    attribute("process", ":fixture"),
                    attribute("isolatedProcess", "true", raw = null, dataType = "boolean"),
                ),
                end("service"),
            ),
        )

        val component = (read(visible(base(events))) as ManifestReadOutcome.Read).facts.components.single()
        assertEquals("service", component.kind)
        assertEquals(".FixtureService", component.name)
        assertEquals(true, component.exported)
        assertEquals("$PACKAGE.PRIVILEGED", component.permission)
        assertEquals(":fixture", component.process)
        assertEquals(listOf("process", "isolatedProcess"), component.matchingAttributes.map { it.name })
    }

    @Test
    fun permissionsRecordDecodedProtectionLevels() {
        val events = manifestEvents(
            listOf(
                start(
                    "permission",
                    attribute("name", "$PACKAGE.SIGNED"),
                    attribute("protectionLevel", "0x12", raw = null, dataType = "int-hex"),
                ),
                end("permission"),
                start(
                    "permission",
                    attribute("name", "$PACKAGE.SYMBOLIC"),
                    attribute("protectionLevel", "signature|privileged"),
                ),
                end("permission"),
            ),
        )

        val permissions = (read(visible(base(events))) as ManifestReadOutcome.Read).facts.permissions
        assertEquals("signature", permissions[0].protectionLevelBase)
        assertEquals(listOf("privileged"), permissions[0].protectionLevelFlags)
        assertEquals("0x12", permissions[0].protectionLevelRaw)
        assertEquals("signature", permissions[1].protectionLevelBase)
        assertEquals(listOf("privileged"), permissions[1].protectionLevelFlags)
    }

    @Test
    fun usesPermissionVariantsRecordNamesAndMaxSdk() {
        val events = manifestEvents(
            listOf(
                start("uses-permission-sdk-23", attribute("name", "android.permission.READ_LOGS"), attribute("maxSdkVersion", "28", raw = null, dataType = "int-dec")),
                end("uses-permission-sdk-23"),
            ),
        )

        val uses = (read(visible(base(events))) as ManifestReadOutcome.Read).facts.usesPermissions.single()
        assertEquals("android.permission.READ_LOGS", uses.name)
        assertEquals("28", uses.maxSdkVersion)
    }

    @Test
    fun sharedUserIdIsRecordedFromTheManifest() {
        val events = listOf(
            start(
                "manifest",
                attribute("package", PACKAGE, namespace = null),
                attribute("sharedUserId", SHARED_USER),
            ),
            end("manifest"),
        )

        val facts = (read(visible(base(events))) as ManifestReadOutcome.Read).facts
        assertEquals(listOf(SHARED_USER), facts.declaredSharedUserIds)
    }

    @Test
    fun pccEvidenceDeclaredWhenComponentAccessElementIsPresent() {
        val events = manifestEvents(
            listOf(
                start("allow-component-access"),
                start("package", attribute("name", "com.example.target")),
                end("package"),
                end("allow-component-access"),
            ),
        )

        val evidence = pccComponentEvidence(read(visible(base(events))))
        assertEquals(PccComponentEvidenceState.DECLARED, evidence.state)
        assertEquals("allow-component-access", evidence.matchedElements.single().name)
    }

    @Test
    fun pccEvidenceDeclaredForPccComponentAttribute() {
        val events = manifestEvents(
            listOf(
                start("service", attribute("name", ".PccService"), attribute("pccRole", "provider")),
                end("service"),
            ),
        )

        val evidence = pccComponentEvidence(read(visible(base(events))))
        assertEquals(PccComponentEvidenceState.DECLARED, evidence.state)
        assertEquals(".PccService", evidence.matchedComponents.single().name)
    }

    @Test
    fun pccEvidenceNotDeclaredOnlyAfterACompleteRead() {
        val evidence = pccComponentEvidence(read(visible(base())))

        assertEquals(PccComponentEvidenceState.NOT_DECLARED_IN_READ_MANIFEST, evidence.state)
        assertTrue(evidence.reason.contains("completely"))
    }

    @Test
    fun pccEvidenceUnreadableWhenASplitFailed() {
        val unreadable = FakeSource(
            ManifestSourceId("split:config", ManifestApkKind.SPLIT),
            openFailure = ManifestFormatException("no AndroidManifest.xml entry"),
        )

        val evidence = pccComponentEvidence(read(visible(base(), unreadable)))
        assertEquals(PccComponentEvidenceState.UNREADABLE, evidence.state)
        assertTrue(evidence.reason.contains("could not be read"))
    }

    @Test
    fun pccEvidenceUnreadableWhenPackageIsNotVisible() {
        val evidence = pccComponentEvidence(read(ManifestPackageSources.NotVisible))
        assertEquals(PccComponentEvidenceState.UNREADABLE, evidence.state)
        assertTrue(evidence.reason.contains("not visible"))
    }

    @Test
    fun pccEvidenceUnreadableWhenManifestReadFailed() {
        val failing = FakeSource(
            ManifestSourceId("base", ManifestApkKind.BASE),
            openFailure = IllegalStateException("broken apk"),
        )

        val evidence = pccComponentEvidence(read(visible(failing)))
        assertEquals(PccComponentEvidenceState.UNREADABLE, evidence.state)
        assertTrue(evidence.reason.contains("java.lang.IllegalStateException"))
    }

    @Test
    fun pccEvidenceUnreadableWhenDeclaredPackageMismatches() {
        val evidence = pccComponentEvidence(
            read(visible(base(manifestEvents(packageName = "com.other.app")))),
        )

        assertEquals(PccComponentEvidenceState.UNREADABLE, evidence.state)
        assertTrue(evidence.reason.contains("com.other.app"))
    }

    private fun visible(vararg sources: ManifestSourceInput): ManifestPackageSources =
        ManifestPackageSources.Visible(
            effectiveSharedUserId = SHARED_USER,
            signerCertificateSha256 = listOf(SIGNER),
            sources = sources.toList(),
        )

    private fun base(events: List<ManifestEvent> = manifestEvents()): ManifestSourceInput =
        FakeSource(ManifestSourceId("base", ManifestApkKind.BASE), events = events)

    private fun split(
        name: String,
        events: List<ManifestEvent> = manifestEvents(),
    ): ManifestSourceInput = FakeSource(ManifestSourceId("split:$name", ManifestApkKind.SPLIT), events = events)

    private fun manifestEvents(
        body: List<ManifestEvent> = emptyList(),
        packageName: String = PACKAGE,
    ): List<ManifestEvent> = listOf(
        start("manifest", attribute("package", packageName, namespace = null)),
    ) + body + end("manifest")

    private fun start(name: String, vararg attributes: ManifestAttribute): ManifestEvent =
        ManifestEvent.StartElement(name, attributes.toList())

    private fun end(name: String): ManifestEvent = ManifestEvent.EndElement(name)

    private fun text(value: String): ManifestEvent = ManifestEvent.Text(value)

    private fun attribute(
        name: String,
        value: String,
        namespace: String? = ANDROID_NAMESPACE,
        resourceId: Int? = null,
        raw: String? = value,
        dataType: String = "string",
    ): ManifestAttribute = ManifestAttribute(
        name = name,
        namespace = namespace,
        resourceId = resourceId,
        rawValue = raw,
        typedValue = value,
        dataType = dataType,
    )

    private class FakeReadInput(
        private val sources: ManifestPackageSources,
    ) : ManifestReadInput {
        override fun sourcesFor(packageName: String): ManifestPackageSources = sources
    }

    private class FakeSource(
        override val source: ManifestSourceId,
        private val events: List<ManifestEvent> = emptyList(),
        private val openFailure: Exception? = null,
    ) : ManifestSourceInput {
        override fun openEvents(): ManifestEventStream {
            openFailure?.let { throw it }
            return FakeEventStream(events)
        }
    }

    private class FakeEventStream(
        private val events: List<ManifestEvent>,
    ) : ManifestEventStream {
        private var index = 0

        override fun nextEvent(): ManifestEvent? = events.getOrNull(index++)

        override fun close() = Unit
    }

    private companion object {
        const val PACKAGE = "com.example.summarizer"
        const val SHARED_USER = "dev.example.shared"
        const val SIGNER = "AA:BB:CC"
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
