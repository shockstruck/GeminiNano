package dev.shockstruck.gemininano.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcsBindCatalogTest {

    private fun pcs(vararg components: ComponentFacts, coverage: PackageReadCoverage = PackageReadCoverage.FULL) =
        PackageFacts(
            packageName = PlatformPrincipals.PCS,
            visibility = PackageVisibility.VISIBLE,
            coverage = coverage,
            components = components.toList(),
        )

    private fun service(
        name: String,
        exported: Boolean = true,
        effectivelyEnabled: Boolean? = true,
        requiredPermission: String? = null,
    ) = ComponentFacts(
        kind = ComponentKind.SERVICE,
        className = name,
        exported = exported,
        requiredPermission = requiredPermission,
        enabled = effectivelyEnabled != false,
        manifestEffectiveEnabled = effectivelyEnabled != false,
        runtimeEnabledSetting = ComponentEnabledSetting.DEFAULT,
        effectivelyEnabled = effectivelyEnabled,
    )

    @Test
    fun runtimeAstreaNamesAreTheFirstTwoCandidatesInOrder() {
        val plan = PcsBindCatalog.plan(
            pcs(
                service(PcsBindCatalog.ASTREA_GRPC),
                service(PcsBindCatalog.ASTREA_GENAI_INFERENCE),
                service(PcsBindCatalog.ASTREA_SUPER_ICON),
                service(PcsBindCatalog.ASTREA_CONVERSATION_UPDATE),
                service(PcsBindCatalog.ASTREA_CONVERSATION_LISTENER),
                service(PcsBindCatalog.ASTREA_FEEDBACK_HTTP_CLIENT),
            ),
        )
        assertEquals(
            listOf(
                PcsBindRole.GRPC,
                PcsBindRole.GENAI_INFERENCE,
                PcsBindRole.SUPER_ICON,
                PcsBindRole.CONVERSATION_UPDATE,
                PcsBindRole.CONVERSATION_LISTENER,
                PcsBindRole.FEEDBACK_HTTP_CLIENT,
            ),
            plan.candidates.mapNotNull { it.definition?.role },
        )
        assertEquals(6, plan.eligibleCount)
        assertTrue(plan.projectedResults().all { it.outcome == PcsBindOutcome.NOT_RUN })
    }

    @Test
    fun openSourceNamesAreRecognisedAsAliasesOfTheSameRole() {
        val plan = PcsBindCatalog.plan(
            pcs(
                service(PcsBindCatalog.OPEN_SOURCE_GRPC),
                service(PcsBindCatalog.OPEN_SOURCE_GENAI_INFERENCE),
            ),
        )
        val grpc = plan.candidates.first { it.definition?.role == PcsBindRole.GRPC }
        val inference = plan.candidates.first { it.definition?.role == PcsBindRole.GENAI_INFERENCE }
        assertEquals(PcsBindCatalog.OPEN_SOURCE_GRPC, grpc.declaredName)
        assertEquals(PcsBindCatalog.OPEN_SOURCE_GENAI_INFERENCE, inference.declaredName)
        assertEquals(PcsBindCatalog.ASTREA_GRPC, grpc.className)
        assertEquals(PcsBindEligibility.ELIGIBLE, grpc.eligibility)
    }

    @Test
    fun aKnownServiceThePackageDoesNotDeclareSaysNotDeclared() {
        val plan = PcsBindCatalog.plan(pcs(service(PcsBindCatalog.ASTREA_GRPC)))
        val genai = plan.candidates.first { it.definition?.role == PcsBindRole.GENAI_INFERENCE }
        assertEquals(PcsBindEligibility.NOT_DECLARED, genai.eligibility)
        assertEquals(PcsBindOutcome.NOT_DECLARED, genai.projectedResult().outcome)
        assertEquals(PcsBindCatalog.ASTREA_GENAI_INFERENCE, genai.projectedResult().componentName)
        assertNull(genai.declaredName)
    }

    @Test
    fun declaredButNotExportedIsDistinctFromNotDeclared() {
        val plan = PcsBindCatalog.plan(
            pcs(service(PcsBindCatalog.ASTREA_GRPC, exported = false)),
        )
        val grpc = plan.candidates.first { it.definition?.role == PcsBindRole.GRPC }
        assertEquals(PcsBindEligibility.NOT_EXPORTED, grpc.eligibility)
        assertEquals(PcsBindOutcome.NOT_EXPORTED, grpc.projectedResult().outcome)
        assertEquals(PcsBindCatalog.ASTREA_GRPC, grpc.declaredName)
        assertFalse(grpc.projectedResult().outcome.attempted)
    }

    @Test
    fun theEligibilityPredicatesAreReportedPrecisely() {
        assertEquals(
            PcsBindEligibility.NOT_ENABLED,
            PcsBindCatalog.eligibilityOf(service("s", effectivelyEnabled = false)),
        )
        assertEquals(
            PcsBindEligibility.ENABLED_UNKNOWN,
            PcsBindCatalog.eligibilityOf(service("s", effectivelyEnabled = null)),
        )
        assertEquals(
            PcsBindEligibility.REQUIRES_PERMISSION,
            PcsBindCatalog.eligibilityOf(
                service("s", requiredPermission = "android.permission.BIND_JOB_SERVICE"),
            ),
        )
        assertEquals(
            PcsBindEligibility.ELIGIBLE,
            PcsBindCatalog.eligibilityOf(service("s")),
        )
        assertEquals(PcsBindEligibility.NOT_DECLARED, PcsBindCatalog.eligibilityOf(null))
    }

    @Test
    fun unreadComponentsSayNotReadAndInventNoDeclarations() {
        val plan = PcsBindCatalog.plan(
            PackageFacts(
                packageName = PlatformPrincipals.PCS,
                visibility = PackageVisibility.VISIBLE,
                coverage = PackageReadCoverage(components = false),
            ),
        )
        assertFalse(plan.componentsRead)
        assertEquals(PcsBindCatalog.KNOWN.size, plan.candidates.size)
        assertTrue(plan.candidates.all { it.eligibility == PcsBindEligibility.NOT_READ })
        assertTrue(plan.projectedResults().all { it.outcome == PcsBindOutcome.NOT_READ })
        assertEquals(0, plan.eligibleCount)
    }

    @Test
    fun otherEligibleServicesFollowTheKnownRolesAndIneligibleOnesAreExcluded() {
        val plan = PcsBindCatalog.plan(
            pcs(
                service("com.aaa.NowPlayingRelayService"),
                service(PcsBindCatalog.ASTREA_GRPC),
                service("com.zzz.JobService", requiredPermission = "android.permission.BIND_JOB_SERVICE"),
            ),
        )
        assertEquals(2, plan.eligibleCount)
        assertEquals(
            listOf(PcsBindCatalog.ASTREA_GRPC, "com.aaa.NowPlayingRelayService"),
            plan.candidates
                .filter { it.eligibility == PcsBindEligibility.ELIGIBLE }
                .map { it.className },
        )
        assertEquals(PcsBindRole.GRPC, plan.candidates.first().definition?.role)
        assertNull(plan.candidates.last().definition)
    }

    @Test
    fun componentsThatAreNotServicesNeverEnterThePlan() {
        val activity = ComponentFacts(
            kind = ComponentKind.ACTIVITY,
            className = PcsBindCatalog.ASTREA_GRPC,
            exported = true,
            effectivelyEnabled = true,
        )
        val plan = PcsBindCatalog.plan(pcs(activity))
        val grpc = plan.candidates.first { it.definition?.role == PcsBindRole.GRPC }
        assertEquals(PcsBindEligibility.NOT_DECLARED, grpc.eligibility)
    }
}
