package dev.shockstruck.gemininano.diagnostics

import dev.shockstruck.gemininano.model.AppUserFacts
import dev.shockstruck.gemininano.model.GrantState
import dev.shockstruck.gemininano.model.InstalledCodeApkId
import dev.shockstruck.gemininano.model.InstalledCodeApkKind
import dev.shockstruck.gemininano.model.InstalledCodeAssetFacts
import dev.shockstruck.gemininano.model.InstalledCodeDefaultValue
import dev.shockstruck.gemininano.model.InstalledCodeDeviceConfigFacts
import dev.shockstruck.gemininano.model.InstalledCodeMarkerFacts
import dev.shockstruck.gemininano.model.InstalledCodeMarkerKind
import dev.shockstruck.gemininano.model.InstalledCodeMarkerMatch
import dev.shockstruck.gemininano.model.InstalledCodeMarkerReference
import dev.shockstruck.gemininano.model.InstalledCodeMarkers
import dev.shockstruck.gemininano.model.InstalledCodeOutcome
import dev.shockstruck.gemininano.model.InstalledCodePackageEvidence
import dev.shockstruck.gemininano.model.InstalledCodePackageFacts
import dev.shockstruck.gemininano.model.InstalledCodePermissionCheckFacts
import dev.shockstruck.gemininano.model.InstalledCodePermissionCheckReference
import dev.shockstruck.gemininano.model.InstalledCodePresence
import dev.shockstruck.gemininano.model.InstalledCodeReadFailure
import dev.shockstruck.gemininano.model.ManifestAssociationPackageFacts
import dev.shockstruck.gemininano.model.ManifestElementFacts
import dev.shockstruck.gemininano.model.ManifestReadOutcome
import dev.shockstruck.gemininano.model.PackageFacts
import dev.shockstruck.gemininano.model.PackageReadCoverage
import dev.shockstruck.gemininano.model.PackageVisibility
import dev.shockstruck.gemininano.model.PlatformPrincipals
import dev.shockstruck.gemininano.testing.TestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisioningDependencyEvaluatorTest {

    private val evaluator = ProvisioningDependencyEvaluator()

    private val baseApk = InstalledCodeApkId("base", InstalledCodeApkKind.BASE)

    private fun facts(
        packageName: String,
        markers: List<InstalledCodeMarkerFacts> = emptyList(),
        anyCheck: Boolean = true,
        failures: List<InstalledCodeReadFailure> = emptyList(),
    ) = InstalledCodePackageFacts(
        packageName = packageName,
        versionCode = 545_573L,
        apkSha256 = emptyList(),
        apks = emptyList(),
        markers = markers,
        readDeviceConfig = InstalledCodePermissionCheckFacts(
            marker = InstalledCodeMarkers.READ_DEVICE_CONFIG,
            references = if (anyCheck) {
                listOf(
                    InstalledCodePermissionCheckReference(
                        apk = baseApk,
                        dexEntry = "classes.dex",
                        referencingMethods = 1,
                        referencingMethodsWithPermissionCheck = 1,
                        permissionCheckMethodNames = listOf("checkSelfPermission"),
                    ),
                )
            } else {
                emptyList()
            },
        ),
        deviceConfig = InstalledCodeDeviceConfigFacts(),
        assets = InstalledCodeAssetFacts(),
        failures = failures,
    )

    private fun read(
        packageName: String,
        markers: List<InstalledCodeMarkerFacts> = emptyList(),
        anyCheck: Boolean = true,
        failures: List<InstalledCodeReadFailure> = emptyList(),
    ) = InstalledCodePackageEvidence(
        requestedPackage = packageName,
        outcome = InstalledCodeOutcome.Read(
            packageName,
            facts(packageName, markers, anyCheck, failures),
        ),
        elapsedMillis = 25L,
    )

    private fun flagWithSites(
        name: String,
        sitesByMatchedName: Map<String, List<InstalledCodeDefaultValue>>,
        methodCount: Int = 1,
        presence: InstalledCodePresence = InstalledCodePresence.PRESENT,
    ): InstalledCodeMarkerFacts = InstalledCodeMarkerFacts(
        marker = name,
        match = InstalledCodeMarkerMatch.PREFIX,
        kind = InstalledCodeMarkerKind.FLAG,
        presence = presence,
        references = if (presence == InstalledCodePresence.ABSENT) {
            emptyList()
        } else {
            sitesByMatchedName.map { (matched, defaults) ->
                InstalledCodeMarkerReference(
                    marker = name,
                    apk = baseApk,
                    dexEntry = "classes.dex",
                    matchedString = matched,
                    methodCount = methodCount,
                    defaults = defaults,
                )
            }
        },
    )

    private fun flag(
        name: String,
        defaults: List<InstalledCodeDefaultValue>,
        matchedStrings: List<String> = listOf(name),
        methodCount: Int = 1,
        presence: InstalledCodePresence = InstalledCodePresence.PRESENT,
    ): InstalledCodeMarkerFacts = flagWithSites(
        name = name,
        sitesByMatchedName = matchedStrings.associateWith { defaults },
        methodCount = methodCount,
        presence = presence,
    )

    private fun buildId(
        defaults: List<InstalledCodeDefaultValue>,
        name: String = "AicDataRelease__build_id_aicore",
    ) = flagWithSites(
        name = ProvisioningDependencyEvaluator.AICORE_BUILD_ID_MARKER,
        sitesByMatchedName = mapOf(name to defaults),
    )

    private fun protectedDownload(defaults: List<InstalledCodeDefaultValue>) = flag(
        name = ProvisioningDependencyEvaluator.PROTECTED_DOWNLOAD_MARKER,
        defaults = defaults,
        methodCount = 1,
    )

    private fun packages(
        aicoreSigning: List<String> = listOf("AB:CD:EF"),
        aicoreSignersRead: Boolean = true,
        pcsSigning: List<String> = listOf("BB:CC:DD"),
    ) = listOf(
        PackageFacts(
            packageName = PlatformPrincipals.AICORE,
            visibility = PackageVisibility.VISIBLE,
            versionCode = 545_573L,
            signingSha256 = aicoreSigning,
            coverage = PackageReadCoverage(signers = aicoreSignersRead),
        ),
        PackageFacts(
            packageName = PlatformPrincipals.PCS,
            visibility = PackageVisibility.VISIBLE,
            versionCode = 152_997L,
            signingSha256 = pcsSigning,
        ),
    )

    private fun policyEntry(
        name: String?,
        digests: List<String>,
    ) = ManifestAssociationPackageFacts(name = name, certificateDigests = digests)

    private fun policyElement(
        vararg entries: ManifestAssociationPackageFacts,
    ) = ManifestElementFacts(
        name = "component-access",
        packages = entries.toList(),
    )

    private fun associationManifests(
        pcsElements: List<ManifestElementFacts> = listOf(
            policyElement(policyEntry(PlatformPrincipals.AICORE, listOf("AB:CD:EF"))),
        ),
        aicoreElements: List<ManifestElementFacts> = emptyList(),
        aicoreComplete: Boolean = true,
        aicorePackage: String = PlatformPrincipals.AICORE,
    ): List<ManifestEvidenceSnapshot> {
        val pcsRead = TestFixtures.manifestRead(
            packageName = PlatformPrincipals.PCS,
            pccElements = pcsElements,
        )
        val aicoreRead = TestFixtures.manifestRead(
            packageName = aicorePackage,
            pccElements = aicoreElements,
        )
        return listOf(
            ManifestEvidenceSnapshot(PlatformPrincipals.PCS, pcsRead),
            ManifestEvidenceSnapshot(
                aicorePackage,
                if (aicoreComplete) {
                    aicoreRead
                } else {
                    ManifestReadOutcome.Partial(aicorePackage, aicoreRead.facts)
                },
            ),
        )
    }

    private fun input(
        appUser: AppUserFacts? = AppUserFacts(isSystemUser = true),
        packages: List<PackageFacts> = packages(),
        manifests: List<ManifestEvidenceSnapshot> = associationManifests(),
        installedCode: List<InstalledCodePackageEvidence> = listOf(
            read(
                PlatformPrincipals.AICORE,
                markers = listOf(buildId(listOf(InstalledCodeDefaultValue.IntValue(0)))),
            ),
            read(
                PlatformPrincipals.PCS,
                markers = listOf(
                    protectedDownload(listOf(InstalledCodeDefaultValue.BooleanValue(false))),
                ),
            ),
        ),
        aicoreReadDeviceConfig: GrantState? = GrantState.DENIED,
        pcsReadDeviceConfig: GrantState? = GrantState.DENIED,
    ) = DependencyEvaluationInput(
        appUser = appUser,
        packages = packages,
        matrix = TestFixtures.permissionMatrix(
            aicoreReadDeviceConfig = aicoreReadDeviceConfig,
            pcsReadDeviceConfig = pcsReadDeviceConfig,
        ),
        manifests = manifests,
        installedCode = installedCode,
    )

    private fun ProvisioningDependencyEvaluation.step(id: DependencyStepId): DependencyStep =
        steps.first { it.id == id }

    // -- System (owner) user ---------------------------------------------------------

    @Test
    fun aSecondaryUserIsACandidateNotAFailedStep() {
        val evaluation = evaluator.evaluate(input(appUser = AppUserFacts(isSystemUser = false)))

        val step = evaluation.step(DependencyStepId.SYSTEM_USER)
        assertEquals(DependencyStepOutcome.NOT_OBSERVABLE, step.outcome)
        assertTrue(step.summary.contains("candidate cause"))
        assertTrue(step.summary.contains("owner profile"))
        assertEquals(DependencyStepId.AICORE_BUILD_ID, evaluation.firstFailingStep?.id)
    }

    @Test
    fun theOwnerUserPassesWhenAicoreIsInTheSameProfile() {
        val evaluation = evaluator.evaluate(input())

        assertEquals(DependencyStepOutcome.PASSED, evaluation.step(DependencyStepId.SYSTEM_USER).outcome)
    }

    @Test
    fun aicoresUserIsNotInferredWhenThePackageIsNotVisible() {
        val evaluation = evaluator.evaluate(
            input(
                packages = listOf(packages().first { it.packageName == PlatformPrincipals.PCS }),
                manifests = emptyList(),
            ),
        )

        val step = evaluation.step(DependencyStepId.SYSTEM_USER)
        assertEquals(DependencyStepOutcome.NOT_OBSERVABLE, step.outcome)
        assertTrue(step.summary.contains("not visible"))
    }

    @Test
    fun anUnreadUserIsNotObservable() {
        val evaluation = evaluator.evaluate(input(appUser = null))

        assertEquals(
            DependencyStepOutcome.NOT_OBSERVABLE,
            evaluation.step(DependencyStepId.SYSTEM_USER).outcome,
        )
    }

    @Test
    fun thePopulatorGateIsNotObservable() {
        val evaluation = evaluator.evaluate(input())

        assertEquals(
            DependencyStepOutcome.NOT_OBSERVABLE,
            evaluation.step(DependencyStepId.AICORE_POPULATOR_GATE).outcome,
        )
    }

    // -- AICore build-id gate -------------------------------------------------------

    @Test
    fun theBuildIdGateFailsFromTheInstalledDefaultAndADeniedPermission() {
        val evaluation = evaluator.evaluate(input())

        val step = evaluation.step(DependencyStepId.AICORE_BUILD_ID)
        assertEquals(DependencyStepOutcome.FAILED, step.outcome)
        assertEquals(
            "First step shown to fail: ${DependencyStepId.AICORE_BUILD_ID.label}",
            evaluation.resultLabel,
        )
        assertEquals(
            listOf(
                DependencyStepId.CLIENT_STATUS_CHECK to DependencyStepOutcome.NOT_TESTED,
                DependencyStepId.AICORE_POPULATOR_GATE to DependencyStepOutcome.NOT_OBSERVABLE,
            ),
            evaluation.earlierStepsNotPassed.map { it.id to it.outcome },
        )
        assertTrue(evaluation.summary.contains("Earlier steps not passed:"))
        assertTrue(
            step.evidence.joinToString(" ") { it.statement }
                .contains(ProvisioningDependencyEvaluator.DEFAULTS_LABEL),
        )
        assertTrue(step.evidence.any { it.strength == DependencyEvidenceStrength.INFERRED })
        assertTrue(step.summary.contains("inferred"))
    }

    @Test
    fun theBuildIdGatePassesWhenTheInstalledDefaultEnablesIt() {
        val evaluation = evaluator.evaluate(
            input(
                installedCode = listOf(
                    read(
                        PlatformPrincipals.AICORE,
                        markers = listOf(buildId(listOf(InstalledCodeDefaultValue.IntValue(1)))),
                    ),
                ),
            ),
        )

        val step = evaluation.step(DependencyStepId.AICORE_BUILD_ID)
        assertEquals(DependencyStepOutcome.PASSED, step.outcome)
        assertTrue(step.summary.contains("inferred"))
    }

    @Test
    fun aNameWithADefaultSiteAndANoArgumentSiteKeepsItsDefault() {
        val evaluation = evaluator.evaluate(
            input(
                installedCode = listOf(
                    read(
                        PlatformPrincipals.AICORE,
                        markers = listOf(
                            buildId(
                                listOf(
                                    InstalledCodeDefaultValue.IntValue(0),
                                    InstalledCodeDefaultValue.NoFollowingArgument,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            DependencyStepOutcome.FAILED,
            evaluation.step(DependencyStepId.AICORE_BUILD_ID).outcome,
        )
    }

    @Test
    fun aPrefixPassedToConcatIsNotEstablished() {
        val evaluation = evaluator.evaluate(
            input(
                installedCode = listOf(
                    read(
                        PlatformPrincipals.AICORE,
                        markers = listOf(
                            buildId(
                                defaults = listOf(InstalledCodeDefaultValue.Unresolved),
                                name = ProvisioningDependencyEvaluator.AICORE_BUILD_ID_MARKER,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val step = evaluation.step(DependencyStepId.AICORE_BUILD_ID)
        assertEquals(DependencyStepOutcome.NOT_OBSERVABLE, step.outcome)
        assertTrue(step.summary.contains("unresolved or mixed"))
    }

    @Test
    fun twoEstablishedNamesBothZeroFail() {
        val evaluation = evaluator.evaluate(
            input(
                installedCode = listOf(
                    read(
                        PlatformPrincipals.AICORE,
                        markers = listOf(
                            flagWithSites(
                                ProvisioningDependencyEvaluator.AICORE_BUILD_ID_MARKER,
                                mapOf(
                                    "AicDataRelease__build_id_aicore" to
                                        listOf(InstalledCodeDefaultValue.IntValue(0)),
                                    "AicDataRelease__build_id_asi" to
                                        listOf(InstalledCodeDefaultValue.IntValue(0)),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val step = evaluation.step(DependencyStepId.AICORE_BUILD_ID)
        assertEquals(DependencyStepOutcome.FAILED, step.outcome)
        assertTrue(step.summary.contains("AicDataRelease__build_id_aicore"))
        assertTrue(step.summary.contains("AicDataRelease__build_id_asi"))
    }

    @Test
    fun twoEstablishedNamesZeroAndNonZeroIsNotObservable() {
        val evaluation = evaluator.evaluate(
            input(
                installedCode = listOf(
                    read(
                        PlatformPrincipals.AICORE,
                        markers = listOf(
                            flagWithSites(
                                ProvisioningDependencyEvaluator.AICORE_BUILD_ID_MARKER,
                                mapOf(
                                    "AicDataRelease__build_id_aicore" to
                                        listOf(InstalledCodeDefaultValue.IntValue(0)),
                                    "AicDataRelease__build_id_asi" to
                                        listOf(InstalledCodeDefaultValue.IntValue(1)),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val step = evaluation.step(DependencyStepId.AICORE_BUILD_ID)
        assertEquals(DependencyStepOutcome.NOT_OBSERVABLE, step.outcome)
        assertTrue(step.summary.contains("disagree"))
    }

    @Test
    fun zeroAndUnresolvedIsNotObservable() {
        val evaluation = evaluator.evaluate(
            input(
                installedCode = listOf(
                    read(
                        PlatformPrincipals.AICORE,
                        markers = listOf(
                            flagWithSites(
                                ProvisioningDependencyEvaluator.AICORE_BUILD_ID_MARKER,
                                mapOf(
                                    "AicDataRelease__build_id_aicore" to
                                        listOf(InstalledCodeDefaultValue.IntValue(0)),
                                    "AicDataRelease__build_id_asi" to
                                        listOf(InstalledCodeDefaultValue.Unresolved),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val step = evaluation.step(DependencyStepId.AICORE_BUILD_ID)
        assertEquals(DependencyStepOutcome.NOT_OBSERVABLE, step.outcome)
        assertTrue(step.summary.contains("unresolved or mixed"))
    }

    @Test
    fun onlyNoArgumentSitesIsNotObservable() {
        val evaluation = evaluator.evaluate(
            input(
                installedCode = listOf(
                    read(
                        PlatformPrincipals.AICORE,
                        markers = listOf(
                            buildId(listOf(InstalledCodeDefaultValue.NoFollowingArgument)),
                        ),
                    ),
                ),
            ),
        )

        val step = evaluation.step(DependencyStepId.AICORE_BUILD_ID)
        assertEquals(DependencyStepOutcome.NOT_OBSERVABLE, step.outcome)
        assertTrue(step.summary.contains("no exact flag name has a default site"))
    }

    @Test
    fun theNotEstablishedListIsCappedAtSixtyNamesWithACount() {
        val sites = (0 until 62).associate { index ->
            "AicDataRelease__build_id_n%02d".format(index) to
                listOf<InstalledCodeDefaultValue>(InstalledCodeDefaultValue.Unresolved)
        }
        val evaluation = evaluator.evaluate(
            input(
                installedCode = listOf(
                    read(
                        PlatformPrincipals.AICORE,
                        markers = listOf(
                            flagWithSites(
                                ProvisioningDependencyEvaluator.AICORE_BUILD_ID_MARKER,
                                sites,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val step = evaluation.step(DependencyStepId.AICORE_BUILD_ID)
        assertEquals(DependencyStepOutcome.NOT_OBSERVABLE, step.outcome)
        assertTrue(step.summary.contains("2 more (62 exact names total)"))
        assertTrue(step.summary.contains("AicDataRelease__build_id_n59"))
        assertTrue(!step.summary.contains("AicDataRelease__build_id_n61"))
    }

    @Test
    fun aMarkerAbsentFromTheInstalledCodeContradictsTheDumpAndNeverDecides() {
        val evaluation = evaluator.evaluate(
            input(
                installedCode = listOf(
                    read(
                        PlatformPrincipals.AICORE,
                        markers = listOf(
                            flag(
                                ProvisioningDependencyEvaluator.AICORE_BUILD_ID_MARKER,
                                defaults = emptyList(),
                                presence = InstalledCodePresence.ABSENT,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val step = evaluation.step(DependencyStepId.AICORE_BUILD_ID)
        assertEquals(DependencyStepOutcome.NOT_OBSERVABLE, step.outcome)
        assertTrue(step.summary.contains("Contradicts the July 2025 public dump"))
        assertTrue(step.evidence.any { it.statement.contains("absent") })
    }

    @Test
    fun anUnresolvedDefaultIsNotEstablished() {
        val evaluation = evaluator.evaluate(
            input(
                installedCode = listOf(
                    read(
                        PlatformPrincipals.AICORE,
                        markers = listOf(
                            buildId(listOf(InstalledCodeDefaultValue.Unresolved)),
                        ),
                    ),
                ),
            ),
        )

        val step = evaluation.step(DependencyStepId.AICORE_BUILD_ID)
        assertEquals(DependencyStepOutcome.NOT_OBSERVABLE, step.outcome)
        assertTrue(step.summary.contains("unresolved or mixed"))
    }

    @Test
    fun aDefaultOfTheWrongTypeIsShownAndDecidesNothing() {
        val evaluation = evaluator.evaluate(
            input(
                installedCode = listOf(
                    read(
                        PlatformPrincipals.PCS,
                        markers = listOf(
                            protectedDownload(
                                listOf(InstalledCodeDefaultValue.StringValue("not-a-boolean")),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val step = evaluation.step(DependencyStepId.PCS_PROTECTED_DOWNLOAD)
        assertEquals(DependencyStepOutcome.NOT_OBSERVABLE, step.outcome)
        assertTrue(step.summary.contains("does not fit"))
    }

    @Test
    fun presenceWithoutCodeNeverDecides() {
        val evaluation = evaluator.evaluate(
            input(
                installedCode = listOf(
                    read(
                        PlatformPrincipals.AICORE,
                        markers = listOf(
                            buildId(
                                listOf(InstalledCodeDefaultValue.IntValue(0)),
                            ).copy(
                                references = listOf(
                                    InstalledCodeMarkerReference(
                                        marker = ProvisioningDependencyEvaluator.AICORE_BUILD_ID_MARKER,
                                        apk = baseApk,
                                        dexEntry = "classes.dex",
                                        matchedString = "AicDataRelease__build_id_aicore",
                                        methodCount = 0,
                                        defaults = listOf(InstalledCodeDefaultValue.IntValue(0)),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val step = evaluation.step(DependencyStepId.AICORE_BUILD_ID)
        assertEquals(DependencyStepOutcome.NOT_OBSERVABLE, step.outcome)
        assertTrue(step.summary.contains("loaded by no instruction"))
    }

    @Test
    fun aPermissionCheckNotShownIsNeverNoCheck() {
        val evaluation = evaluator.evaluate(
            input(
                installedCode = listOf(
                    read(
                        PlatformPrincipals.AICORE,
                        markers = listOf(
                            buildId(listOf(InstalledCodeDefaultValue.IntValue(0))),
                        ),
                        anyCheck = false,
                    ),
                ),
            ),
        )

        val step = evaluation.step(DependencyStepId.AICORE_BUILD_ID)
        assertEquals(DependencyStepOutcome.NOT_OBSERVABLE, step.outcome)
        assertTrue(step.summary.contains("not shown"))
        assertTrue(step.evidence.any { it.statement.contains("never \"no check\"") })
    }

    @Test
    fun aGrantedPermissionLeavesTheDeviceConfigValueUnobservable() {
        val evaluation = evaluator.evaluate(
            input(aicoreReadDeviceConfig = GrantState.GRANTED),
        )

        val step = evaluation.step(DependencyStepId.AICORE_BUILD_ID)
        assertEquals(DependencyStepOutcome.NOT_OBSERVABLE, step.outcome)
        assertTrue(step.summary.contains("granted"))
    }

    @Test
    fun anUnknownGrantStateIsNotDenied() {
        val evaluation = evaluator.evaluate(
            input(aicoreReadDeviceConfig = null),
        )

        val step = evaluation.step(DependencyStepId.AICORE_BUILD_ID)
        assertEquals(DependencyStepOutcome.NOT_OBSERVABLE, step.outcome)
        assertTrue(step.summary.contains("unknown is not denied"))
    }

    @Test
    fun aPartialReadMakesTheDependentStepNotObservable() {
        val evaluation = evaluator.evaluate(
            input(
                installedCode = listOf(
                    InstalledCodePackageEvidence(
                        requestedPackage = PlatformPrincipals.AICORE,
                        outcome = InstalledCodeOutcome.Partial(
                            PlatformPrincipals.AICORE,
                            facts(
                                PlatformPrincipals.AICORE,
                                markers = listOf(
                                    buildId(listOf(InstalledCodeDefaultValue.IntValue(0))),
                                ),
                                failures = listOf(
                                    InstalledCodeReadFailure(
                                        apk = baseApk,
                                        dexEntry = "classes2.dex",
                                        errorClass = "java.lang.OutOfMemoryError",
                                        message = "Java heap space",
                                    ),
                                ),
                            ),
                        ),
                        elapsedMillis = 9L,
                    ),
                ),
            ),
        )

        val step = evaluation.step(DependencyStepId.AICORE_BUILD_ID)
        assertEquals(DependencyStepOutcome.NOT_OBSERVABLE, step.outcome)
        assertTrue(step.summary.contains("partial"))
    }

    @Test
    fun aReadErrorExportsItsClassAndMessage() {
        val evaluation = evaluator.evaluate(
            input(
                installedCode = listOf(
                    InstalledCodePackageEvidence(
                        requestedPackage = PlatformPrincipals.AICORE,
                        outcome = InstalledCodeOutcome.ReadError(
                            PlatformPrincipals.AICORE,
                            "java.io.IOException",
                            "the APK could not be opened",
                        ),
                        elapsedMillis = 2L,
                    ),
                ),
            ),
        )

        val step = evaluation.step(DependencyStepId.AICORE_BUILD_ID)
        assertEquals(DependencyStepOutcome.NOT_OBSERVABLE, step.outcome)
        assertTrue(step.summary.contains("java.io.IOException"))
        assertTrue(step.summary.contains("the APK could not be opened"))
    }

    @Test
    fun aTooLargeReadShowsItsDetail() {
        val evaluation = evaluator.evaluate(
            input(
                installedCode = listOf(
                    InstalledCodePackageEvidence(
                        requestedPackage = PlatformPrincipals.AICORE,
                        outcome = InstalledCodeOutcome.TooLarge(
                            PlatformPrincipals.AICORE,
                            "classes.dex is 40000000 bytes, above the 33554432 byte limit",
                        ),
                        elapsedMillis = 4L,
                    ),
                ),
            ),
        )

        val step = evaluation.step(DependencyStepId.AICORE_BUILD_ID)
        assertEquals(DependencyStepOutcome.NOT_OBSERVABLE, step.outcome)
        assertTrue(step.summary.contains("40000000 bytes"))
    }

    @Test
    fun pcsProtectedDownloadFailsFromTheBooleanDefaultAndADeniedPermission() {
        val evaluation = evaluator.evaluate(input())

        val step = evaluation.step(DependencyStepId.PCS_PROTECTED_DOWNLOAD)
        assertEquals(DependencyStepOutcome.FAILED, step.outcome)
        assertTrue(step.evidence.any { it.strength == DependencyEvidenceStrength.INFERRED })
    }

    // -- Association ----------------------------------------------------------------

    @Test
    fun theAssociationPassesOnAMatchingSignerDigest() {
        val evaluation = evaluator.evaluate(input())

        assertEquals(
            DependencyStepOutcome.PASSED,
            evaluation.step(DependencyStepId.PCS_ASSOCIATION).outcome,
        )
    }

    @Test
    fun theAssociationFailsWhenNoPolicyDigestMatches() {
        val evaluation = evaluator.evaluate(
            input(
                packages = packages(aicoreSigning = listOf("AA:BB:CC")),
                installedCode = emptyList(),
            ),
        )

        val step = evaluation.step(DependencyStepId.PCS_ASSOCIATION)
        assertEquals(DependencyStepOutcome.FAILED, step.outcome)
        assertTrue(step.summary.contains("if this build enforces the policy"))
        assertTrue(step.evidence.any { it.strength == DependencyEvidenceStrength.INFERRED })
        assertEquals(DependencyStepId.PCS_ASSOCIATION, evaluation.firstFailingStep?.id)
    }

    @Test
    fun theAssociationIsNotObservableWhenSignersWereNotRead() {
        val evaluation = evaluator.evaluate(
            input(
                packages = packages(aicoreSigning = emptyList(), aicoreSignersRead = false),
            ),
        )

        val step = evaluation.step(DependencyStepId.PCS_ASSOCIATION)
        assertEquals(DependencyStepOutcome.NOT_OBSERVABLE, step.outcome)
        assertTrue(step.summary.contains("signer was not read"))
    }

    @Test
    fun anUnnamedPolicyEntryIsNotObservable() {
        val evaluation = evaluator.evaluate(
            input(
                manifests = associationManifests(
                    pcsElements = listOf(
                        policyElement(policyEntry(name = null, digests = listOf("AB:CD:EF"))),
                    ),
                ),
            ),
        )

        assertEquals(
            DependencyStepOutcome.NOT_OBSERVABLE,
            evaluation.step(DependencyStepId.PCS_ASSOCIATION).outcome,
        )
    }

    @Test
    fun aicoresDigestUnderAnotherPackageIsNotAMatch() {
        val evaluation = evaluator.evaluate(
            input(
                manifests = associationManifests(
                    pcsElements = listOf(
                        policyElement(
                            policyEntry(
                                PlatformPrincipals.AICORE_LEGACY,
                                listOf("AB:CD:EF"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val step = evaluation.step(DependencyStepId.PCS_ASSOCIATION)
        assertEquals(DependencyStepOutcome.FAILED, step.outcome)
        assertTrue(step.summary.contains("no entry named"))
    }

    @Test
    fun anEntryWithoutDigestIsNotObservable() {
        val evaluation = evaluator.evaluate(
            input(
                manifests = associationManifests(
                    pcsElements = listOf(
                        policyElement(policyEntry(PlatformPrincipals.AICORE, emptyList())),
                    ),
                ),
            ),
        )

        val step = evaluation.step(DependencyStepId.PCS_ASSOCIATION)
        assertEquals(DependencyStepOutcome.NOT_OBSERVABLE, step.outcome)
        assertTrue(step.summary.contains("no certificate digest"))
    }

    @Test
    fun theAssociationIsNotObservableWhenAicoresOwnManifestWasNotRead() {
        val evaluation = evaluator.evaluate(
            input(manifests = associationManifests(aicoreComplete = false)),
        )

        val step = evaluation.step(DependencyStepId.PCS_ASSOCIATION)
        assertEquals(DependencyStepOutcome.NOT_OBSERVABLE, step.outcome)
        assertTrue(step.summary.contains("AICore's own manifest"))
    }

    @Test
    fun theAssociationPassesWhenAicoresOwnPolicyNamesPcsWithAMatchingDigest() {
        val evaluation = evaluator.evaluate(
            input(
                packages = packages(pcsSigning = listOf("AB:CD:EF")),
                manifests = associationManifests(
                    aicoreElements = listOf(
                        policyElement(policyEntry(PlatformPrincipals.PCS, listOf("AB:CD:EF"))),
                    ),
                ),
            ),
        )

        val step = evaluation.step(DependencyStepId.PCS_ASSOCIATION)
        assertEquals(DependencyStepOutcome.PASSED, step.outcome)
        assertTrue(step.summary.contains("names PCS"))
    }

    @Test
    fun theAssociationIsNotObservableWhenAicoresPolicyPcsEntryDoesNotMatch() {
        val evaluation = evaluator.evaluate(
            input(
                packages = packages(pcsSigning = listOf("AB:CD:EF")),
                manifests = associationManifests(
                    aicoreElements = listOf(
                        policyElement(policyEntry(PlatformPrincipals.PCS, listOf("99:99:99"))),
                    ),
                ),
            ),
        )

        val step = evaluation.step(DependencyStepId.PCS_ASSOCIATION)
        assertEquals(DependencyStepOutcome.NOT_OBSERVABLE, step.outcome)
        assertTrue(step.summary.contains("reverse check is not shown to pass"))
    }

    // -- Order and unobservable steps ----------------------------------------------

    @Test
    fun theFirstFailingStepIsTheEarliestInChainOrder() {
        val evaluation = evaluator.evaluate(
            input(packages = packages(aicoreSigning = listOf("AA:BB:CC"))),
        )

        assertEquals(DependencyStepId.AICORE_BUILD_ID, evaluation.firstFailingStep?.id)
        assertEquals(
            DependencyStepOutcome.FAILED,
            evaluation.step(DependencyStepId.PCS_ASSOCIATION).outcome,
        )
    }

    @Test
    fun theClientStatusCheckAndLaterStepsAreNotTestedOrNotObservable() {
        val evaluation = evaluator.evaluate(input())

        assertEquals(
            DependencyStepOutcome.NOT_TESTED,
            evaluation.step(DependencyStepId.CLIENT_STATUS_CHECK).outcome,
        )
        listOf(
            DependencyStepId.PCS_MANIFEST_RPC,
            DependencyStepId.MANIFEST_TO_MODEL,
            DependencyStepId.ISOLATED_INFERENCE,
        ).forEach { id ->
            assertEquals(DependencyStepOutcome.NOT_OBSERVABLE, evaluation.step(id).outcome)
        }
    }

    @Test
    fun noFailedStepSaysItCannotBeDetermined() {
        val evaluation = evaluator.evaluate(DependencyEvaluationInput())

        assertNull(evaluation.firstFailingStep)
        assertEquals("Cannot be determined: no step is shown to fail", evaluation.resultLabel)
        assertTrue(evaluation.earlierStepsNotPassed.isEmpty())
        assertEquals(DependencyStepId.SYSTEM_USER, evaluation.firstUnsettledStep?.id)
    }

    // -- Reader failure isolation --------------------------------------------------

    @Test
    fun anUnexpectedThrowableInOnePackageLeavesTheOtherPackageIntact() {
        var clock = 0L
        val collector = InstalledCodeEvidenceCollector(
            read = { packageName ->
                if (packageName == PlatformPrincipals.AICORE) {
                    throw OutOfMemoryError("Java heap space")
                }
                InstalledCodeOutcome.Read(
                    packageName,
                    facts(
                        packageName,
                        markers = listOf(
                            protectedDownload(listOf(InstalledCodeDefaultValue.BooleanValue(false))),
                        ),
                    ),
                )
            },
            elapsedClock = {
                clock += 7L
                clock
            },
        )

        val collected = collector.collect(
            listOf(PlatformPrincipals.AICORE, PlatformPrincipals.PCS),
        )

        assertEquals(2, collected.size)
        val aicore = collected.first { it.requestedPackage == PlatformPrincipals.AICORE }
        assertEquals("java.lang.OutOfMemoryError", (aicore.outcome as InstalledCodeOutcome.ReadError).errorClass)
        assertEquals("Java heap space", (aicore.outcome as InstalledCodeOutcome.ReadError).message)
        assertEquals("read", collected.first { it.requestedPackage == PlatformPrincipals.PCS }.outcomeLabel)
        assertTrue(collected.all { it.elapsedMillis == 7L })

        val evaluation = evaluator.evaluate(input(installedCode = collected))
        assertEquals(
            DependencyStepOutcome.NOT_OBSERVABLE,
            evaluation.step(DependencyStepId.AICORE_BUILD_ID).outcome,
        )
        assertEquals(
            DependencyStepOutcome.FAILED,
            evaluation.step(DependencyStepId.PCS_PROTECTED_DOWNLOAD).outcome,
        )

        val report = FullDiagnosticsReportBuilder().build(
            FullDiagnosticsInput(
                generatedAtEpochMillis = TestFixtures.NOW,
                metadata = ExportMetadata(
                    applicationId = PlatformPrincipals.APP,
                    versionName = "0.7.0",
                    versionCode = 8L,
                    exportedAtEpochMillis = TestFixtures.NOW,
                ),
                matrix = null,
                features = emptyList(),
                prompt = emptyList(),
                summarization = null,
                surface = null,
                bindResults = emptyList(),
                offline = emptyList(),
                events = emptyList(),
                installedCode = collected,
                dependencies = evaluation,
            ),
        )
        assertTrue(report.json.contains("java.lang.OutOfMemoryError"))
        assertTrue(report.text.contains("Java heap space"))
    }
}
