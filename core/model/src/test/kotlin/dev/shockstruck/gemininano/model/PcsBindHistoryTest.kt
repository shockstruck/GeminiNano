package dev.shockstruck.gemininano.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcsBindHistoryTest {

    private val grpc = PcsBindCatalog.ASTREA_GRPC
    private val genai = PcsBindCatalog.ASTREA_GENAI_INFERENCE
    private val now = 1_800_000_000_000L

    private fun result(
        component: String = grpc,
        outcome: PcsBindOutcome = PcsBindOutcome.BINDER_RETURNED,
        role: String? = "grpc",
        descriptor: String? = "com.example.IPcs",
        exceptionClass: String? = null,
        exceptionMessage: String? = null,
    ) = PcsBindResult(
        componentName = component,
        outcome = outcome,
        role = role,
        declaredName = component,
        requiredPermission = null,
        bindFlag = "BIND_AUTO_CREATE",
        interfaceDescriptor = descriptor,
        binderAlive = if (outcome == PcsBindOutcome.BINDER_RETURNED) true else null,
        descriptorReadMillis = if (outcome == PcsBindOutcome.BINDER_RETURNED) 3L else null,
        descriptorTimedOut = false,
        exceptionClass = exceptionClass,
        exceptionMessage = exceptionMessage,
        elapsedMillis = 42L,
        unbindCalls = if (outcome.attempted) 1 else 0,
    )

    private fun entry(
        result: PcsBindResult = result(),
        at: Long = now,
        pcsVersionCode: Long? = 152_997L,
    ) = PcsBindHistoryEntry(
        result = result,
        observedAtEpochMillis = at,
        appVersionName = "0.6.1",
        appVersionCode = 7L,
        pcsVersionCode = pcsVersionCode,
    )

    private fun plan(vararg components: String) = PcsBindPlan(
        componentsRead = true,
        candidates = components.map { name ->
            PcsBindCandidate(
                definition = null,
                className = name,
                declaredName = name,
                requiredPermission = null,
                eligibility = PcsBindEligibility.ELIGIBLE,
            )
        },
    )

    @Test
    fun consentOffAddsNoBindStep() {
        assertEquals(
            listOf(FullDiagnosticsStep.COLLECT_EVIDENCE, FullDiagnosticsStep.BUILD_REPORT),
            PcsBindRunPolicy.steps(includeBindProbe = false, eligibleCount = 3),
        )
        assertFalse(PcsBindRunPolicy.shouldBind(includeBindProbe = false, eligibleCount = 3))
    }

    @Test
    fun consentOnRunsTheBindBeforeTheReport() {
        val steps = PcsBindRunPolicy.steps(includeBindProbe = true, eligibleCount = 3)
        assertEquals(
            listOf(
                FullDiagnosticsStep.COLLECT_EVIDENCE,
                FullDiagnosticsStep.RUN_BIND_PROBE,
                FullDiagnosticsStep.BUILD_REPORT,
            ),
            steps,
        )
        assertTrue(
            steps.indexOf(FullDiagnosticsStep.RUN_BIND_PROBE) <
                steps.indexOf(FullDiagnosticsStep.BUILD_REPORT),
        )
        assertEquals(FullDiagnosticsStep.BUILD_REPORT, steps.last())
        assertTrue(PcsBindRunPolicy.shouldBind(includeBindProbe = true, eligibleCount = 3))
    }

    @Test
    fun consentWithNoEligibleServiceDoesNotBind() {
        assertEquals(
            listOf(FullDiagnosticsStep.COLLECT_EVIDENCE, FullDiagnosticsStep.BUILD_REPORT),
            PcsBindRunPolicy.steps(includeBindProbe = true, eligibleCount = 0),
        )
        assertFalse(PcsBindRunPolicy.shouldBind(includeBindProbe = true, eligibleCount = 0))
    }

    @Test
    fun historyRoundTripsEveryStoredField() {
        val hostileMessage = "a|b~c%d\nline\rtab\tend \\ \"quoted\" 100%"
        val history = PcsBindHistory(
            listOf(
                entry(
                    result = result(
                        outcome = PcsBindOutcome.SECURITY_EXCEPTION,
                        descriptor = null,
                        exceptionClass = "java.lang.SecurityException",
                        exceptionMessage = hostileMessage,
                    ),
                    pcsVersionCode = null,
                ),
                entry(
                    result = result(outcome = PcsBindOutcome.TIMEOUT, descriptor = null),
                ),
            ),
        )

        val encoded = PcsBindHistoryCodec.encode(history)
        assertTrue(encoded.startsWith(PcsBindHistoryCodec.HEADER))
        val decoded = PcsBindHistoryCodec.decode(encoded)
        assertTrue(decoded is PcsBindHistoryDecode.Decoded)
        assertEquals(history, (decoded as PcsBindHistoryDecode.Decoded).history)
    }

    @Test
    fun corruptOrMissingTextIsReportedAndNeverThrows() {
        val cases = listOf(
            "",
            "not a history file",
            "unexpected-header\n",
            PcsBindHistoryCodec.HEADER + "\nonly|three|fields",
            PcsBindHistoryCodec.HEADER + "\n" + PcsBindHistoryCodec.encode(PcsBindHistory(listOf(entry())))
                .lineSequence().drop(1).first().replace("BINDER_RETURNED", "NOT_A_REAL_OUTCOME"),
            PcsBindHistoryCodec.HEADER + "\n" + PcsBindHistoryCodec.encode(PcsBindHistory(listOf(entry())))
                .lineSequence().drop(1).first().replace("42", "not-a-number"),
            PcsBindHistoryCodec.HEADER + "\n" + PcsBindHistoryCodec.encode(PcsBindHistory(listOf(entry())))
                .lineSequence().drop(1).first() + "%",
        )

        cases.forEach { text ->
            val decoded = PcsBindHistoryCodec.decode(text)
            assertTrue("expected Corrupt for: $text", decoded is PcsBindHistoryDecode.Corrupt)
        }

        val oversized = PcsBindHistoryCodec.HEADER + "\n" + "x".repeat(300 * 1024)
        assertTrue(PcsBindHistoryCodec.decode(oversized) is PcsBindHistoryDecode.Corrupt)

        val headerOnly = PcsBindHistoryCodec.decode(PcsBindHistoryCodec.HEADER + "\n")
        assertEquals(
            PcsBindHistory(),
            (headerOnly as PcsBindHistoryDecode.Decoded).history,
        )
    }

    @Test
    fun fiveResultsPerComponentAreKept() {
        var history = PcsBindHistory()
        repeat(7) { index ->
            history = PcsBindHistoryPolicy.record(
                history,
                entry(result = result(component = grpc), at = now + index),
            )
            history = PcsBindHistoryPolicy.record(
                history,
                entry(result = result(component = genai, role = "genai_inference"), at = now + index),
            )
        }

        val grpcTimes = history.entries
            .filter { it.result.componentName == grpc }
            .map { it.observedAtEpochMillis }
        val genaiTimes = history.entries
            .filter { it.result.componentName == genai }
            .map { it.observedAtEpochMillis }
        assertEquals(5, grpcTimes.size)
        assertEquals((now + 2..now + 6).toList(), grpcTimes)
        assertEquals((now + 2..now + 6).toList(), genaiTimes)
        assertEquals(10, history.entries.size)
    }

    @Test
    fun aNotRunResultNeverOverwritesAnEarlierObservation() {
        val stored = PcsBindHistoryPolicy.record(
            PcsBindHistory(),
            entry(result = result(outcome = PcsBindOutcome.BINDER_RETURNED), at = now),
        )
        val afterNotRun = PcsBindHistoryPolicy.record(
            stored,
            entry(result = result(outcome = PcsBindOutcome.NOT_RUN, descriptor = null), at = now + 1),
        )
        val afterNotDeclared = PcsBindHistoryPolicy.record(
            afterNotRun,
            entry(
                result = result(outcome = PcsBindOutcome.NOT_DECLARED, descriptor = null),
                at = now + 2,
            ),
        )

        assertEquals(1, afterNotDeclared.entries.size)
        assertEquals(PcsBindOutcome.BINDER_RETURNED, afterNotDeclared.entries.single().result.outcome)
        assertNull(afterNotDeclared.latestFor(genai))
    }

    @Test
    fun rowsMarkCurrentEarlierAndNotRun() {
        val earlier = entry(
            result = result(component = "svc.earlier", outcome = PcsBindOutcome.TIMEOUT),
            at = now - 60_000,
        )
        val rows = PcsBindReport.rows(
            plan = plan("svc.current", "svc.earlier", "svc.notrun"),
            currentResults = listOf(result(component = "svc.current")),
            history = PcsBindHistory(listOf(earlier)),
            currentPcsVersionCode = 152_997L,
            currentRunAtEpochMillis = now,
            appVersionName = "0.7.0",
            appVersionCode = 8L,
        )

        assertEquals(3, rows.size)
        assertEquals(PcsBindProvenance.CURRENT_RUN, rows[0].provenance)
        assertEquals(now, rows[0].observedAtEpochMillis)
        assertEquals(PcsBindProvenance.EARLIER_RUN, rows[1].provenance)
        assertEquals(now - 60_000, rows[1].observedAtEpochMillis)
        assertEquals("0.6.1", rows[1].appVersionName)
        assertEquals(PcsBindProvenance.NOT_RUN, rows[2].provenance)
        assertNull(rows[2].observedAtEpochMillis)
        assertEquals("not run", rows[2].bindingFact)
    }

    @Test
    fun aStoredResultFromADifferentPcsVersionIsLabelledAndExcluded() {
        val mismatchRows = PcsBindReport.rows(
            plan = plan(grpc),
            history = PcsBindHistory(listOf(entry(result = result(component = grpc), pcsVersionCode = 100L))),
            currentPcsVersionCode = 200L,
        )
        val mismatch = mismatchRows.single()
        assertTrue(mismatch.pcsVersionMismatch)
        assertTrue(mismatch.pcsVersionLabel.orEmpty().contains("not used as classifier input"))
        assertFalse(mismatch.classifierEligible)
        assertTrue(PcsBindReport.classifierResults(mismatchRows).isEmpty())

        val matchingRows = PcsBindReport.rows(
            plan = plan(grpc),
            history = PcsBindHistory(listOf(entry(result = result(component = grpc), pcsVersionCode = 200L))),
            currentPcsVersionCode = 200L,
        )
        val matching = matchingRows.single()
        assertFalse(matching.pcsVersionMismatch)
        assertTrue(matching.classifierEligible)
        assertEquals(1, PcsBindReport.classifierResults(matchingRows).size)

        val unknownVersionRows = PcsBindReport.rows(
            plan = plan(grpc),
            history = PcsBindHistory(listOf(entry(result = result(component = grpc), pcsVersionCode = null))),
            currentPcsVersionCode = 200L,
        )
        assertFalse(
            "a stored result whose version cannot be matched is not classifier input",
            unknownVersionRows.single().classifierEligible,
        )

        val currentRows = PcsBindReport.rows(
            plan = plan(grpc),
            currentResults = listOf(result(component = grpc)),
            currentPcsVersionCode = 200L,
        )
        assertTrue(currentRows.single().classifierEligible)
    }

    @Test
    fun everyRowSeparatesBindingFromRpcAuthorizationAndProvisioning() {
        val rows = PcsBindReport.rows(
            plan = plan(grpc, "svc.notrun"),
            currentResults = listOf(result(component = grpc)),
            currentPcsVersionCode = 152_997L,
        )
        val observed = rows[0]
        val notRun = rows[1]

        assertEquals("binder returned (descriptor \"com.example.IPcs\")", observed.bindingFact)
        assertEquals("not tested: no transaction is sent", observed.rpcAuthorizationFact)
        assertEquals("not tested", observed.provisioningFact)

        assertEquals("not run", notRun.bindingFact)
        assertEquals("not tested: no transaction is sent", notRun.rpcAuthorizationFact)
        assertEquals("not tested", notRun.provisioningFact)
    }

    @Test
    fun historyEntriesKeepOnlyAttemptedResults() {
        val entries = PcsBindReport.historyEntries(
            results = listOf(
                result(component = grpc, outcome = PcsBindOutcome.BINDER_RETURNED),
                result(component = genai, outcome = PcsBindOutcome.NULL_BINDING, descriptor = null),
                result(component = "svc.notrun", outcome = PcsBindOutcome.NOT_RUN, descriptor = null),
            ),
            observedAtEpochMillis = now,
            appVersionName = "0.6.2",
            appVersionCode = 8L,
            pcsVersionCode = 152_997L,
        )

        assertEquals(2, entries.size)
        assertTrue(entries.all { it.observedAtEpochMillis == now })
        assertTrue(entries.all { it.appVersionName == "0.6.2" })
        assertTrue(entries.all { it.appVersionCode == 8L })
        assertTrue(entries.all { it.pcsVersionCode == 152_997L })
        assertFalse(entries.any { it.result.outcome == PcsBindOutcome.NOT_RUN })
    }

    @Test
    fun aStoredComponentOutsideTheCurrentPlanIsStillShownAsEarlier() {
        val rows = PcsBindReport.rows(
            plan = plan(grpc),
            history = PcsBindHistory(
                listOf(
                    entry(result = result(component = "svc.removed"), at = now - 1_000),
                    entry(result = result(component = grpc), at = now - 500),
                ),
            ),
            currentPcsVersionCode = 152_997L,
        )

        assertEquals(2, rows.size)
        assertEquals("svc.removed", rows.last().componentName)
        assertEquals(PcsBindProvenance.EARLIER_RUN, rows.last().provenance)
    }
}
