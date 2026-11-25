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
    fun componentAccessElementRecordsAttributesTextPackagesAndCertificateDigest() {
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
        val packageEntry = access.packages.single()
        assertEquals("com.example.target", packageEntry.name)
        assertEquals(listOf(digest), packageEntry.certificateDigests)
        assertEquals(2, packageEntry.attributes.size)

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
                    attribute("isolatedProcess", "false", raw = null, dataType = "boolean"),
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
        assertEquals(
            setOf("process", "isolatedProcess"),
            component.matchingAttributes.map { it.name }.toSet(),
        )
        assertEquals(ManifestBooleanValue.ABSENT, component.privateComputeCore)
    }

    @Test
    fun intentFiltersAreRecordedOnServicesAndActivities() {
        val astreaService = "com.google.android.apps.miphone.astrea.services.AstreaService"
        val events = manifestEvents(
            listOf(
                start("service", attribute("name", astreaService)),
                start("intent-filter", attribute("priority", "10", raw = null, dataType = "int-dec")),
                start("action", attribute("name", "com.google.android.as.oss.action.START")),
                end("action"),
                start("category", attribute("name", "android.intent.category.DEFAULT")),
                end("category"),
                start("data", attribute("scheme", "content"), attribute("host", "aicore")),
                end("data"),
                end("intent-filter"),
                end("service"),
                start("activity", attribute("name", ".MainActivity")),
                start("intent-filter"),
                start("action", attribute("name", "com.google.android.aicore.action.PROBE")),
                end("action"),
                end("intent-filter"),
                end("activity"),
            ),
        )

        val components = (read(visible(base(events))) as ManifestReadOutcome.Read).facts.components
        val service = components.single { it.kind == "service" }
        assertEquals(astreaService, service.name)
        assertTrue(service.declaresIntentFilter)
        val serviceFilter = service.intentFilters.single()
        assertEquals(listOf("com.google.android.as.oss.action.START"), serviceFilter.actions)
        assertEquals(listOf("android.intent.category.DEFAULT"), serviceFilter.categories)
        assertEquals(listOf("content"), serviceFilter.dataSchemes)
        assertEquals(listOf("aicore"), serviceFilter.dataHosts)

        val activity = components.single { it.kind == "activity" }
        assertEquals(listOf("com.google.android.aicore.action.PROBE"), activity.intentFilters.single().actions)
    }

    @Test
    fun activityAliasWithIntentFilterIsRecordedAndDoesNotFailTheRead() {
        val events = manifestEvents(
            listOf(
                start("activity", attribute("name", ".Target")),
                end("activity"),
                start(
                    "activity-alias",
                    attribute("name", ".Alias"),
                    attribute("targetActivity", ".Target"),
                    attribute("privateComputeCore", "true", raw = null, dataType = "boolean"),
                ),
                start("intent-filter"),
                start("action", attribute("name", "com.google.android.as.oss.action.ALIAS")),
                end("action"),
                end("intent-filter"),
                end("activity-alias"),
            ),
        )

        val delivered = read(visible(base(events))) as ManifestReadOutcome.Read
        val alias = delivered.facts.components.single { it.kind == "activity-alias" }
        assertEquals(".Alias", alias.name)
        assertEquals(".Target", alias.targetActivity)
        assertEquals(listOf("com.google.android.as.oss.action.ALIAS"), alias.intentFilters.single().actions)
        assertEquals(ManifestBooleanValue.TRUE, alias.privateComputeCore)
    }

    @Test
    fun intentFilterOutsideAComponentDoesNotFailTheRead() {
        val events = manifestEvents(
            listOf(
                start("intent-filter"),
                start("action", attribute("name", "com.example.ORPHAN")),
                end("action"),
                end("intent-filter"),
            ),
        )

        val delivered = read(visible(base(events))) as ManifestReadOutcome.Read
        assertTrue(delivered.facts.components.isEmpty())
        assertTrue(delivered.facts.complete)
    }

    @Test
    fun activityAliasPrivateComputeCoreTrueIsNeverPccComponentDeclared() {
        val events = manifestEvents(
            listOf(
                start(
                    "activity-alias",
                    attribute("name", ".Alias"),
                    attribute("targetActivity", ".Target"),
                    attribute("privateComputeCore", "true", raw = null, dataType = "boolean"),
                ),
                end("activity-alias"),
            ),
        )

        val evidence = pccComponentEvidence(read(visible(base(events))))
        assertEquals(PccComponentEvidenceState.UNREADABLE, evidence.pccComponents.state)
        assertTrue(evidence.pccComponents.reason.contains("activity-alias"))
    }

    @Test
    fun activityAliasPrivateComputeCoreReferenceIsUnreadable() {
        val events = manifestEvents(
            listOf(
                start(
                    "activity-alias",
                    attribute("name", ".Alias"),
                    attribute("targetActivity", ".Target"),
                    attribute("privateComputeCore", "@0x7f010001", raw = null, dataType = "reference"),
                ),
                end("activity-alias"),
            ),
        )

        val evidence = pccComponentEvidence(read(visible(base(events))))
        assertEquals(PccComponentEvidenceState.UNREADABLE, evidence.pccComponents.state)
        assertEquals(ManifestBooleanValue.UNRESOLVED, evidence.pccComponents.matched.firstOrNull()?.privateComputeCore)
    }

    @Test
    fun activityAliasUnnamedAttributeIsUnreadable() {
        val events = manifestEvents(
            listOf(
                start(
                    "activity-alias",
                    attribute("name", ".Alias"),
                    attribute("targetActivity", ".Target"),
                    ManifestAttribute("", null, 0x01010010, "true", "true", "boolean"),
                ),
                end("activity-alias"),
            ),
        )

        val evidence = pccComponentEvidence(read(visible(base(events))))
        assertEquals(PccComponentEvidenceState.UNREADABLE, evidence.pccComponents.state)
        assertEquals(PccComponentEvidenceState.UNREADABLE, evidence.otherSandboxAttributes.state)
    }

    @Test
    fun activityAliasPrivateComputeCoreFalseDoesNotBlockNotDeclared() {
        val events = manifestEvents(
            listOf(
                start(
                    "activity-alias",
                    attribute("name", ".Alias"),
                    attribute("targetActivity", ".Target"),
                    attribute("privateComputeCore", "false", raw = null, dataType = "boolean"),
                ),
                end("activity-alias"),
            ),
        )

        val evidence = pccComponentEvidence(read(visible(base(events))))
        assertEquals(PccComponentEvidenceState.NOT_DECLARED_IN_READ_MANIFEST, evidence.pccComponents.state)
    }

    @Test
    fun privateComputeCoreInAForeignNamespaceIsIgnoredForEvidence() {
        val events = manifestEvents(
            listOf(
                start(
                    "service",
                    attribute("name", ".ForeignNamespaceService"),
                    attribute(
                        "privateComputeCore",
                        "true",
                        namespace = "http://example.com/other",
                        raw = null,
                        dataType = "boolean",
                    ),
                ),
                end("service"),
            ),
        )

        val delivered = read(visible(base(events))) as ManifestReadOutcome.Read
        assertEquals(ManifestBooleanValue.ABSENT, delivered.facts.components.single().privateComputeCore)
        val evidence = pccComponentEvidence(delivered)
        assertEquals(PccComponentEvidenceState.NOT_DECLARED_IN_READ_MANIFEST, evidence.pccComponents.state)
    }

    @Test
    fun privateComputeCoreWithoutANamespaceIsUnresolved() {
        val events = manifestEvents(
            listOf(
                start(
                    "service",
                    attribute("name", ".UnqualifiedService"),
                    attribute("privateComputeCore", "true", namespace = null, raw = null, dataType = "boolean"),
                ),
                end("service"),
            ),
        )

        val delivered = read(visible(base(events))) as ManifestReadOutcome.Read
        assertEquals(ManifestBooleanValue.UNRESOLVED, delivered.facts.components.single().privateComputeCore)
        val evidence = pccComponentEvidence(delivered)
        assertEquals(PccComponentEvidenceState.UNREADABLE, evidence.pccComponents.state)
    }

    @Test
    fun queriesPackagesAndIntentActionsAreRecorded() {
        val events = listOf(
            start("manifest", attribute("package", PACKAGE, namespace = null)),
            start("queries"),
            start("package", attribute("name", "com.google.android.aicore")),
            end("package"),
            start("package", attribute("name", "com.google.android.as.oss")),
            end("package"),
            start("intent"),
            start("action", attribute("name", "android.intent.action.PROBE")),
            end("action"),
            end("intent"),
            start("provider", attribute("authorities", "dev.shockstruck.provider")),
            end("provider"),
            end("queries"),
            end("manifest"),
        )

        val delivered = read(visible(base(events))) as ManifestReadOutcome.Read
        val queries = delivered.facts.queries.single()
        assertEquals(
            setOf("com.google.android.aicore", "com.google.android.as.oss"),
            queries.packages.toSet(),
        )
        assertEquals(listOf("android.intent.action.PROBE"), queries.intentActions)
        assertEquals(listOf("dev.shockstruck.provider"), queries.providerAuthorities)
        assertTrue("queries provider must not be a component", delivered.facts.components.isEmpty())
    }

    @Test
    fun usesPermissionVariantsRecordNamesAndSdkBounds() {
        val events = manifestEvents(
            listOf(
                start(
                    "uses-permission-sdk-23",
                    attribute("name", "android.permission.READ_LOGS"),
                    attribute("maxSdkVersion", "28", raw = null, dataType = "int-dec"),
                    attribute("minSdkVersion", "26", raw = null, dataType = "int-dec"),
                ),
                end("uses-permission-sdk-23"),
            ),
        )

        val uses = (read(visible(base(events))) as ManifestReadOutcome.Read).facts.usesPermissions.single()
        assertEquals("android.permission.READ_LOGS", uses.name)
        assertEquals("28", uses.maxSdkVersion)
        assertEquals("26", uses.minSdkVersion)
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
    fun protectionLevelFlagsMatchPermissionInfoTable() {
        PERMISSION_INFO_FLAGS.forEach { (bit, name) ->
            val protected = 0x2 or bit
            val events = manifestEvents(
                listOf(
                    start(
                        "permission",
                        attribute("name", "flag.$name"),
                        attribute(
                            "protectionLevel",
                            "0x%x".format(protected),
                            raw = null,
                            dataType = "int-hex",
                        ),
                    ),
                    end("permission"),
                ),
            )

            val permission = (read(visible(base(events))) as ManifestReadOutcome.Read).facts.permissions.single()
            assertEquals("base for 0x${bit.toString(16)}", "signature", permission.protectionLevelBase)
            assertEquals("flags for 0x${bit.toString(16)}", listOf(name), permission.protectionLevelFlags)
            assertNull("unknown bits for 0x${bit.toString(16)}", permission.protectionLevelUnknownBitsHex)
        }
    }

    @Test
    fun protectionLevelInternalIsABaseLevelNotAFlag() {
        val events = manifestEvents(
            listOf(
                start(
                    "permission",
                    attribute("name", "internal.permission"),
                    attribute("protectionLevel", "4", raw = null, dataType = "int-dec"),
                ),
                end("permission"),
            ),
        )

        val permission = (read(visible(base(events))) as ManifestReadOutcome.Read).facts.permissions.single()
        assertEquals("internal", permission.protectionLevelBase)
        assertTrue(permission.protectionLevelFlags.isEmpty())
        assertNull(permission.protectionLevelUnknownBitsHex)
    }

    @Test
    fun protectionLevelUnknownBitsAreKeptAsHex() {
        val events = manifestEvents(
            listOf(
                start(
                    "permission",
                    attribute("name", "unknown.bits"),
                    attribute("protectionLevel", "0x10000002", raw = null, dataType = "int-hex"),
                ),
                end("permission"),
            ),
        )

        val permission = (read(visible(base(events))) as ManifestReadOutcome.Read).facts.permissions.single()
        assertEquals("signature", permission.protectionLevelBase)
        assertEquals("0x10000000", permission.protectionLevelUnknownBitsHex)
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
    fun privateComputeCoreTrueDeclaresAPccComponentForEveryKind() {
        COMPONENT_KINDS.forEach { kind ->
            val events = manifestEvents(
                listOf(
                    start(
                        kind,
                        attribute("name", ".$kind"),
                        attribute("privateComputeCore", "true", raw = null, dataType = "boolean"),
                    ),
                    end(kind),
                ),
            )

            val evidence = pccComponentEvidence(read(visible(base(events))))
            assertEquals("$kind true", PccComponentEvidenceState.DECLARED, evidence.pccComponents.state)
            assertEquals(kind, evidence.pccComponents.matched.single().kind)
        }
    }

    @Test
    fun privateComputeCoreFalseIsNotADeclarationForEveryKind() {
        COMPONENT_KINDS.forEach { kind ->
            val events = manifestEvents(
                listOf(
                    start(
                        kind,
                        attribute("name", ".$kind"),
                        attribute("privateComputeCore", "false", raw = null, dataType = "boolean"),
                    ),
                    end(kind),
                ),
            )

            val evidence = pccComponentEvidence(read(visible(base(events))))
            assertEquals(
                "$kind false",
                PccComponentEvidenceState.NOT_DECLARED_IN_READ_MANIFEST,
                evidence.pccComponents.state,
            )
        }
    }

    @Test
    fun privateComputeCoreReferenceIsUnresolvedForEveryKind() {
        COMPONENT_KINDS.forEach { kind ->
            val events = manifestEvents(
                listOf(
                    start(
                        kind,
                        attribute("name", ".$kind"),
                        attribute(
                            "privateComputeCore",
                            "@0x7f010001",
                            raw = null,
                            dataType = "reference",
                        ),
                    ),
                    end(kind),
                ),
            )

            val evidence = pccComponentEvidence(read(visible(base(events))))
            assertEquals("$kind reference", PccComponentEvidenceState.UNREADABLE, evidence.pccComponents.state)
            assertEquals(ManifestBooleanValue.UNRESOLVED, evidence.pccComponents.matched.firstOrNull()?.privateComputeCore)
            assertTrue(evidence.pccComponents.reason.contains("unresolved"))
        }
    }

    @Test
    fun componentAccessOnlyManifestIsAssociationEvidenceNotPccComponentEvidence() {
        val events = manifestEvents(
            listOf(
                start("allow-component-access"),
                start("package", attribute("name", "com.example.target")),
                end("package"),
                end("allow-component-access"),
            ),
        )

        val evidence = pccComponentEvidence(read(visible(base(events))))
        assertEquals(PccComponentEvidenceState.NOT_DECLARED_IN_READ_MANIFEST, evidence.pccComponents.state)
        assertEquals(PccComponentEvidenceState.DECLARED, evidence.associationPolicy.state)
        assertEquals("allow-component-access", evidence.associationPolicy.matched.single().name)
        assertEquals(PccComponentEvidenceState.NOT_DECLARED_IN_READ_MANIFEST, evidence.otherSandboxAttributes.state)
    }

    @Test
    fun isolatedProcessFalseDoesNotDeclareAPccComponent() {
        val events = manifestEvents(
            listOf(
                start(
                    "service",
                    attribute("name", ".IsolatedService"),
                    attribute("isolatedProcess", "false", raw = null, dataType = "boolean"),
                ),
                end("service"),
            ),
        )

        val evidence = pccComponentEvidence(read(visible(base(events))))
        assertEquals(PccComponentEvidenceState.NOT_DECLARED_IN_READ_MANIFEST, evidence.pccComponents.state)
        assertEquals(PccComponentEvidenceState.DECLARED, evidence.otherSandboxAttributes.state)
        assertEquals("isolatedProcess", evidence.otherSandboxAttributes.matched.single().attribute.name)
    }

    @Test
    fun processAttributeIsInformationalSandboxEvidenceOnly() {
        val events = manifestEvents(
            listOf(
                start(
                    "service",
                    attribute("name", ".ProcessService"),
                    attribute("process", ":sandbox"),
                ),
                end("service"),
            ),
        )

        val evidence = pccComponentEvidence(read(visible(base(events))))
        assertEquals(PccComponentEvidenceState.NOT_DECLARED_IN_READ_MANIFEST, evidence.pccComponents.state)
        assertEquals(PccComponentEvidenceState.DECLARED, evidence.otherSandboxAttributes.state)
        assertEquals("process", evidence.otherSandboxAttributes.matched.single().attribute.name)
        assertTrue(evidence.otherSandboxAttributes.reason.contains("informational"))
    }

    @Test
    fun unnamedComponentAttributeBlocksNotDeclared() {
        val unnamed = ManifestAttribute(
            name = "",
            namespace = null,
            resourceId = 0x01010010,
            rawValue = "true",
            typedValue = "true",
            dataType = "boolean",
        )
        val events = manifestEvents(
            listOf(
                start("service", attribute("name", ".UnnamedAttributeService"), unnamed),
                end("service"),
            ),
        )

        val delivered = read(visible(base(events))) as ManifestReadOutcome.Read
        val component = delivered.facts.components.single()
        assertTrue(component.hasUnnamedAttributes)
        assertEquals("0x01010010", component.unnamedAttributes.single().resourceIdHex)
        assertEquals("true", component.unnamedAttributes.single().rawValue)

        val evidence = pccComponentEvidence(delivered)
        assertEquals(PccComponentEvidenceState.UNREADABLE, evidence.pccComponents.state)
        assertEquals(PccComponentEvidenceState.UNREADABLE, evidence.otherSandboxAttributes.state)
        assertTrue(evidence.pccComponents.reason.contains("readable name"))
    }

    @Test
    fun pccEvidenceNotDeclaredOnlyAfterACompleteRead() {
        val evidence = pccComponentEvidence(read(visible(base())))

        assertEquals(PccComponentEvidenceState.NOT_DECLARED_IN_READ_MANIFEST, evidence.pccComponents.state)
        assertEquals(PccComponentEvidenceState.NOT_DECLARED_IN_READ_MANIFEST, evidence.associationPolicy.state)
        assertEquals(PccComponentEvidenceState.NOT_DECLARED_IN_READ_MANIFEST, evidence.otherSandboxAttributes.state)
    }

    @Test
    fun everyEvidenceValueIsUnreadableWhenASplitFailed() {
        val unreadable = FakeSource(
            ManifestSourceId("split:config", ManifestApkKind.SPLIT),
            openFailure = ManifestFormatException("no AndroidManifest.xml entry"),
        )

        val evidence = pccComponentEvidence(read(visible(base(), unreadable)))
        assertEquals(PccComponentEvidenceState.UNREADABLE, evidence.pccComponents.state)
        assertEquals(PccComponentEvidenceState.UNREADABLE, evidence.associationPolicy.state)
        assertEquals(PccComponentEvidenceState.UNREADABLE, evidence.otherSandboxAttributes.state)
        assertTrue(evidence.pccComponents.reason.contains("could not be read"))
    }

    @Test
    fun everyEvidenceValueIsUnreadableWhenPackageIsNotVisible() {
        val evidence = pccComponentEvidence(read(ManifestPackageSources.NotVisible))
        assertEquals(PccComponentEvidenceState.UNREADABLE, evidence.pccComponents.state)
        assertEquals(PccComponentEvidenceState.UNREADABLE, evidence.associationPolicy.state)
        assertEquals(PccComponentEvidenceState.UNREADABLE, evidence.otherSandboxAttributes.state)
        assertTrue(evidence.pccComponents.reason.contains("not visible"))
    }

    @Test
    fun everyEvidenceValueIsUnreadableWhenManifestReadFailed() {
        val failing = FakeSource(
            ManifestSourceId("base", ManifestApkKind.BASE),
            openFailure = IllegalStateException("broken apk"),
        )

        val evidence = pccComponentEvidence(read(visible(failing)))
        assertEquals(PccComponentEvidenceState.UNREADABLE, evidence.pccComponents.state)
        assertEquals(PccComponentEvidenceState.UNREADABLE, evidence.associationPolicy.state)
        assertEquals(PccComponentEvidenceState.UNREADABLE, evidence.otherSandboxAttributes.state)
        assertTrue(evidence.pccComponents.reason.contains("java.lang.IllegalStateException"))
    }

    @Test
    fun everyEvidenceValueIsUnreadableWhenDeclaredPackageMismatches() {
        val evidence = pccComponentEvidence(
            read(visible(base(manifestEvents(packageName = "com.other.app")))),
        )

        assertEquals(PccComponentEvidenceState.UNREADABLE, evidence.pccComponents.state)
        assertEquals(PccComponentEvidenceState.UNREADABLE, evidence.associationPolicy.state)
        assertEquals(PccComponentEvidenceState.UNREADABLE, evidence.otherSandboxAttributes.state)
        assertTrue(evidence.pccComponents.reason.contains("com.other.app"))
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

        val COMPONENT_KINDS = listOf("activity", "service", "receiver", "provider")

        /** `PermissionInfo` flag values, GrapheneOS platform_frameworks_base@17. */
        val PERMISSION_INFO_FLAGS = mapOf(
            0x10 to "privileged",
            0x20 to "development",
            0x40 to "appop",
            0x80 to "pre23",
            0x100 to "installer",
            0x200 to "verifier",
            0x400 to "preinstalled",
            0x800 to "setup",
            0x1000 to "instant",
            0x2000 to "runtimeOnly",
            0x4000 to "oem",
            0x8000 to "vendorPrivileged",
            0x10000 to "systemTextClassifier",
            0x20000 to "wellbeing",
            0x40000 to "documenter",
            0x80000 to "configurator",
            0x100000 to "incidentReportApprover",
            0x200000 to "appPredictor",
            0x400000 to "module",
            0x800000 to "companion",
            0x1000000 to "retailDemo",
            0x2000000 to "recents",
            0x4000000 to "role",
            0x8000000 to "knownSigner",
        )
    }
}
