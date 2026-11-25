package dev.shockstruck.gemininano.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

class InstalledCodeReaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun reportsPresentMarkerWithFalseDefaultAndAbsentMarker() {
        val dex = singleMethodDex { builder ->
            val marker = builder.string("ProtectedDownload__enabled")
            val flag = builder.method(FLAG_MANAGER, "getBoolean", "Z", listOf("Ljava/lang/String;", "Z"))
            SyntheticInsns.units(
                SyntheticInsns.constString(0, marker),
                SyntheticInsns.const4(1, 0),
                SyntheticInsns.invokeStatic(intArrayOf(0, 1), flag),
                SyntheticInsns.returnVoid(),
            )
        }

        val outcome = read(apkFile(entries = mapOf("classes.dex" to dex)), versionCode = 42L)
        assertTrue(outcome is InstalledCodeOutcome.Read)
        val facts = (outcome as InstalledCodeOutcome.Read).facts

        assertEquals(PACKAGE, facts.packageName)
        assertEquals(42L, facts.versionCode)
        val marker = facts.marker("ProtectedDownload__enabled")
        assertNotNull(marker)
        assertEquals(InstalledCodePresence.PRESENT, marker!!.presence)
        assertEquals(InstalledCodeMarkerKind.FLAG, marker.kind)
        assertEquals(1, marker.references.size)
        val reference = marker.references.single()
        assertEquals("base", reference.apk.label)
        assertEquals(InstalledCodeApkKind.BASE, reference.apk.kind)
        assertEquals("classes.dex", reference.dexEntry)
        assertEquals("ProtectedDownload__enabled", reference.matchedString)
        assertEquals(1, reference.methodCount)
        assertEquals(
            listOf<InstalledCodeDefaultValue>(InstalledCodeDefaultValue.BooleanValue(false)),
            reference.defaults,
        )

        val absent = facts.marker("feature disabled")
        assertNotNull(absent)
        assertEquals(InstalledCodePresence.ABSENT, absent!!.presence)
        assertTrue(absent.references.isEmpty())
    }

    @Test
    fun reportsTrueIntAndStringDefaults() {
        val dex = multiMethodDex(
            listOf(
                "trueFlag" to { builder ->
                    val marker = builder.string("PccSecurity__enable_security_check")
                    val flag = builder.method(FLAG_MANAGER, "getBoolean", "Z", listOf("Ljava/lang/String;", "Z"))
                    SyntheticInsns.units(
                        SyntheticInsns.constString(0, marker),
                        SyntheticInsns.const4(1, 1),
                        SyntheticInsns.invokeStatic(intArrayOf(0, 1), flag),
                        SyntheticInsns.returnVoid(),
                    )
                },
                "intFlag" to { builder ->
                    val marker = builder.string("AicDataRelease__build_labels")
                    val flag = builder.method(FLAG_MANAGER, "getInt", "I", listOf("Ljava/lang/String;", "I"))
                    SyntheticInsns.units(
                        SyntheticInsns.constString(0, marker),
                        SyntheticInsns.const16(1, 0x1234),
                        SyntheticInsns.invokeStatic(intArrayOf(0, 1), flag),
                        SyntheticInsns.returnVoid(),
                    )
                },
                "stringFlag" to { builder ->
                    val marker = builder.string("AicModels__preloaded_manifest_file_suffix")
                    val flag = builder.method(
                        FLAG_MANAGER,
                        "getString",
                        "Ljava/lang/String;",
                        listOf("Ljava/lang/String;", "Ljava/lang/String;"),
                    )
                    val defaultValue = builder.string("manifest.binarypb")
                    SyntheticInsns.units(
                        SyntheticInsns.constString(0, marker),
                        SyntheticInsns.constString(1, defaultValue),
                        SyntheticInsns.invokeStatic(intArrayOf(0, 1), flag),
                        SyntheticInsns.returnVoid(),
                    )
                },
            ),
        )

        val facts = (read(apkFile(entries = mapOf("classes.dex" to dex))) as InstalledCodeOutcome.Read).facts

        assertEquals(
            listOf<InstalledCodeDefaultValue>(InstalledCodeDefaultValue.BooleanValue(true)),
            facts.marker("PccSecurity__enable_security_check")!!.references.single().defaults,
        )
        assertEquals(
            listOf<InstalledCodeDefaultValue>(InstalledCodeDefaultValue.IntValue(0x1234)),
            facts.marker("AicDataRelease__build_labels")!!.references.single().defaults,
        )
        assertEquals(
            listOf<InstalledCodeDefaultValue>(InstalledCodeDefaultValue.StringValue("manifest.binarypb")),
            facts.marker("AicModels__preloaded_manifest_file_suffix")!!.references.single().defaults,
        )
    }

    @Test
    fun reportsNoFollowingArgumentWhenTheFlagIsTheLastStaticArgument() {
        val dex = singleMethodDex { builder ->
            val marker = builder.string("ProtectedDownload__enabled")
            val flag = builder.method(FLAG_MANAGER, "getBoolean", "Z", listOf("Ljava/lang/String;"))
            SyntheticInsns.units(
                SyntheticInsns.constString(0, marker),
                SyntheticInsns.invokeStatic(intArrayOf(0), flag),
                SyntheticInsns.returnVoid(),
            )
        }

        val facts = (read(apkFile(entries = mapOf("classes.dex" to dex))) as InstalledCodeOutcome.Read).facts
        assertEquals(
            listOf<InstalledCodeDefaultValue>(InstalledCodeDefaultValue.NoFollowingArgument),
            facts.marker("ProtectedDownload__enabled")!!.references.single().defaults,
        )
    }

    @Test
    fun reportsNoFollowingArgumentWhenTheFlagIsTheLastVirtualArgument() {
        val dex = singleMethodDex { builder ->
            val marker = builder.string("ProtectedDownload__enabled")
            val flag = builder.method(FLAG_MANAGER, "record", "V", listOf("Ljava/lang/String;"))
            SyntheticInsns.units(
                SyntheticInsns.constString(1, marker),
                SyntheticInsns.invokeVirtual(intArrayOf(0, 1), flag),
                SyntheticInsns.returnVoid(),
            )
        }

        val facts = (read(apkFile(entries = mapOf("classes.dex" to dex))) as InstalledCodeOutcome.Read).facts
        assertEquals(
            listOf<InstalledCodeDefaultValue>(InstalledCodeDefaultValue.NoFollowingArgument),
            facts.marker("ProtectedDownload__enabled")!!.references.single().defaults,
        )
    }

    @Test
    fun reportsNoFollowingArgumentWhenTheFlagIsTheLastRangeArgument() {
        val dex = singleMethodDex { builder ->
            val marker = builder.string("ProtectedDownload__enabled")
            val flag = builder.method(FLAG_MANAGER, "getBoolean", "Z", listOf("Ljava/lang/String;"))
            SyntheticInsns.units(
                SyntheticInsns.constString(0, marker),
                SyntheticInsns.invokeStaticRange(1, 0, flag),
                SyntheticInsns.returnVoid(),
            )
        }

        val facts = (read(apkFile(entries = mapOf("classes.dex" to dex))) as InstalledCodeOutcome.Read).facts
        assertEquals(
            listOf<InstalledCodeDefaultValue>(InstalledCodeDefaultValue.NoFollowingArgument),
            facts.marker("ProtectedDownload__enabled")!!.references.single().defaults,
        )
    }

    @Test
    fun reportsNoFollowingArgumentWhenTheFlagIsTheLastConstructorArgument() {
        val dex = singleMethodDex { builder ->
            val marker = builder.string("ProtectedDownload__enabled")
            val constructor = builder.method(
                "Ltest/FlagHolder;",
                "<init>",
                "V",
                listOf("Ljava/lang/String;"),
            )
            SyntheticInsns.units(
                SyntheticInsns.constString(1, marker),
                SyntheticInsns.invokeDirect(intArrayOf(0, 1), constructor),
                SyntheticInsns.returnVoid(),
            )
        }

        val facts = (read(apkFile(entries = mapOf("classes.dex" to dex))) as InstalledCodeOutcome.Read).facts
        assertEquals(
            listOf<InstalledCodeDefaultValue>(InstalledCodeDefaultValue.NoFollowingArgument),
            facts.marker("ProtectedDownload__enabled")!!.references.single().defaults,
        )
    }

    @Test
    fun reportsUnresolvedWhenThePrefixIsPassedToConcatWithAnUnresolvedArgument() {
        val dex = singleMethodDex { builder ->
            val prefix = builder.string("AicDataRelease__build_id_")
            val concat = builder.method(
                "Ljava/lang/String;",
                "concat",
                "Ljava/lang/String;",
                listOf("Ljava/lang/String;"),
            )
            SyntheticInsns.units(
                SyntheticInsns.constString(0, prefix),
                SyntheticInsns.invokeVirtual(intArrayOf(0, 1), concat),
                SyntheticInsns.returnVoid(),
            )
        }

        val facts = (read(apkFile(entries = mapOf("classes.dex" to dex))) as InstalledCodeOutcome.Read).facts
        assertEquals(
            listOf<InstalledCodeDefaultValue>(InstalledCodeDefaultValue.Unresolved),
            facts.marker("AicDataRelease__build_id_")!!.references.single().defaults,
        )
    }

    @Test
    fun resolvesABoxedIntegerDefaultInAStaticCall() {
        val dex = singleMethodDex { builder ->
            val marker = builder.string("ProtectedDownload__enabled")
            val valueOf = builder.method(
                "Ljava/lang/Integer;",
                "valueOf",
                "Ljava/lang/Integer;",
                listOf("I"),
            )
            val gate = builder.method(
                "Ltest/IntFlag;",
                "set",
                "V",
                listOf("Ljava/lang/String;", "Ljava/lang/Integer;"),
            )
            SyntheticInsns.units(
                SyntheticInsns.constString(1, marker),
                SyntheticInsns.const4(2, 0),
                SyntheticInsns.invokeStatic(intArrayOf(2), valueOf),
                SyntheticInsns.moveResultObject(2),
                SyntheticInsns.invokeStatic(intArrayOf(1, 2), gate),
                SyntheticInsns.returnVoid(),
            )
        }

        val facts = (read(apkFile(entries = mapOf("classes.dex" to dex))) as InstalledCodeOutcome.Read).facts
        assertEquals(
            listOf<InstalledCodeDefaultValue>(InstalledCodeDefaultValue.IntValue(0)),
            facts.marker("ProtectedDownload__enabled")!!.references.single().defaults,
        )
    }

    @Test
    fun resolvesABoxedIntegerDefaultInAConstructorCall() {
        val dex = singleMethodDex { builder ->
            val marker = builder.string("ProtectedDownload__enabled")
            val valueOf = builder.method(
                "Ljava/lang/Integer;",
                "valueOf",
                "Ljava/lang/Integer;",
                listOf("I"),
            )
            val constructor = builder.method(
                "Ltest/IntFlag;",
                "<init>",
                "V",
                listOf("Ljava/lang/String;", "Ljava/lang/Integer;"),
            )
            SyntheticInsns.units(
                SyntheticInsns.constString(1, marker),
                SyntheticInsns.const4(2, 0),
                SyntheticInsns.invokeStatic(intArrayOf(2), valueOf),
                SyntheticInsns.moveResultObject(2),
                SyntheticInsns.invokeDirect(intArrayOf(0, 1, 2), constructor),
                SyntheticInsns.returnVoid(),
            )
        }

        val facts = (read(apkFile(entries = mapOf("classes.dex" to dex))) as InstalledCodeOutcome.Read).facts
        assertEquals(
            listOf<InstalledCodeDefaultValue>(InstalledCodeDefaultValue.IntValue(0)),
            facts.marker("ProtectedDownload__enabled")!!.references.single().defaults,
        )
    }

    @Test
    fun resolvesBooleanTrueAndFalseFromStaticFields() {
        val dex = singleMethodDex { builder ->
            val enabled = builder.string("ProtectedDownload__enabled")
            val security = builder.string("PccSecurity__enable_security_check")
            val trueField = builder.field("Ljava/lang/Boolean;", "TRUE", "Ljava/lang/Boolean;")
            val falseField = builder.field("Ljava/lang/Boolean;", "FALSE", "Ljava/lang/Boolean;")
            val gate = builder.method(
                "Ltest/BoolFlag;",
                "set",
                "V",
                listOf("Ljava/lang/String;", "Ljava/lang/Boolean;"),
            )
            SyntheticInsns.units(
                SyntheticInsns.constString(1, enabled),
                SyntheticInsns.sgetObject(2, trueField),
                SyntheticInsns.invokeStatic(intArrayOf(1, 2), gate),
                SyntheticInsns.constString(3, security),
                SyntheticInsns.sgetObject(4, falseField),
                SyntheticInsns.invokeStatic(intArrayOf(3, 4), gate),
                SyntheticInsns.returnVoid(),
            )
        }

        val facts = (read(apkFile(entries = mapOf("classes.dex" to dex))) as InstalledCodeOutcome.Read).facts
        assertEquals(
            listOf<InstalledCodeDefaultValue>(InstalledCodeDefaultValue.BooleanValue(true)),
            facts.marker("ProtectedDownload__enabled")!!.references.single().defaults,
        )
        assertEquals(
            listOf<InstalledCodeDefaultValue>(InstalledCodeDefaultValue.BooleanValue(false)),
            facts.marker("PccSecurity__enable_security_check")!!.references.single().defaults,
        )
    }

    @Test
    fun resolvesABoxMadeBeforeTheFlagString() {
        val dex = singleMethodDex { builder ->
            val marker = builder.string("ProtectedDownload__enabled")
            val valueOf = builder.method(
                "Ljava/lang/Integer;",
                "valueOf",
                "Ljava/lang/Integer;",
                listOf("I"),
            )
            val gate = builder.method(
                "Ltest/IntFlag;",
                "set",
                "V",
                listOf("Ljava/lang/String;", "Ljava/lang/Integer;"),
            )
            SyntheticInsns.units(
                SyntheticInsns.const4(2, 7),
                SyntheticInsns.invokeStatic(intArrayOf(2), valueOf),
                SyntheticInsns.moveResultObject(2),
                SyntheticInsns.constString(1, marker),
                SyntheticInsns.invokeStatic(intArrayOf(1, 2), gate),
                SyntheticInsns.returnVoid(),
            )
        }

        val facts = (read(apkFile(entries = mapOf("classes.dex" to dex))) as InstalledCodeOutcome.Read).facts
        assertEquals(
            listOf<InstalledCodeDefaultValue>(InstalledCodeDefaultValue.IntValue(7)),
            facts.marker("ProtectedDownload__enabled")!!.references.single().defaults,
        )
    }

    @Test
    fun reportsUnresolvedForABoxPassedToTheWrongParameterType() {
        val dex = singleMethodDex { builder ->
            val marker = builder.string("ProtectedDownload__enabled")
            val valueOf = builder.method(
                "Ljava/lang/Integer;",
                "valueOf",
                "Ljava/lang/Integer;",
                listOf("I"),
            )
            val gate = builder.method(
                "Ltest/BoolFlag;",
                "set",
                "V",
                listOf("Ljava/lang/String;", "Ljava/lang/Boolean;"),
            )
            SyntheticInsns.units(
                SyntheticInsns.constString(1, marker),
                SyntheticInsns.const4(2, 0),
                SyntheticInsns.invokeStatic(intArrayOf(2), valueOf),
                SyntheticInsns.moveResultObject(2),
                SyntheticInsns.invokeStatic(intArrayOf(1, 2), gate),
                SyntheticInsns.returnVoid(),
            )
        }

        val facts = (read(apkFile(entries = mapOf("classes.dex" to dex))) as InstalledCodeOutcome.Read).facts
        assertEquals(
            listOf<InstalledCodeDefaultValue>(InstalledCodeDefaultValue.Unresolved),
            facts.marker("ProtectedDownload__enabled")!!.references.single().defaults,
        )
    }

    @Test
    fun reportsUnresolvedWhenABranchSitsBetweenTheBoxAndTheCall() {
        val dex = singleMethodDex { builder ->
            val marker = builder.string("ProtectedDownload__enabled")
            val valueOf = builder.method(
                "Ljava/lang/Integer;",
                "valueOf",
                "Ljava/lang/Integer;",
                listOf("I"),
            )
            val gate = builder.method(
                "Ltest/IntFlag;",
                "set",
                "V",
                listOf("Ljava/lang/String;", "Ljava/lang/Integer;"),
            )
            SyntheticInsns.units(
                SyntheticInsns.constString(1, marker),
                SyntheticInsns.const4(2, 0),
                SyntheticInsns.invokeStatic(intArrayOf(2), valueOf),
                SyntheticInsns.moveResultObject(2),
                SyntheticInsns.ifEqz(3, 3),
                SyntheticInsns.invokeStatic(intArrayOf(1, 2), gate),
                SyntheticInsns.returnVoid(),
            )
        }

        val facts = (read(apkFile(entries = mapOf("classes.dex" to dex))) as InstalledCodeOutcome.Read).facts
        assertEquals(
            listOf<InstalledCodeDefaultValue>(InstalledCodeDefaultValue.Unresolved),
            facts.marker("ProtectedDownload__enabled")!!.references.single().defaults,
        )
    }

    @Test
    fun resolvesABoxedDefaultThroughConcatThenAConstructor() {
        val dex = singleMethodDex { builder ->
            val prefix = builder.string("AicDataRelease__build_id_")
            val valueOfInt = builder.method(
                "Ljava/lang/Integer;",
                "valueOf",
                "Ljava/lang/Integer;",
                listOf("I"),
            )
            val valueOfString = builder.method(
                "Ljava/lang/String;",
                "valueOf",
                "Ljava/lang/String;",
                listOf("I"),
            )
            val concat = builder.method(
                "Ljava/lang/String;",
                "concat",
                "Ljava/lang/String;",
                listOf("Ljava/lang/String;"),
            )
            val constructor = builder.method(
                "Ltest/IntFlag;",
                "<init>",
                "V",
                listOf("Ljava/lang/String;", "Ljava/lang/Integer;"),
            )
            SyntheticInsns.units(
                SyntheticInsns.constString(1, prefix),
                SyntheticInsns.const4(2, 42),
                SyntheticInsns.invokeStatic(intArrayOf(2), valueOfString),
                SyntheticInsns.moveResultObject(2),
                SyntheticInsns.invokeVirtual(intArrayOf(1, 2), concat),
                SyntheticInsns.moveResultObject(1),
                SyntheticInsns.const4(3, 0),
                SyntheticInsns.invokeStatic(intArrayOf(3), valueOfInt),
                SyntheticInsns.moveResultObject(3),
                SyntheticInsns.invokeDirect(intArrayOf(0, 1, 3), constructor),
                SyntheticInsns.returnVoid(),
            )
        }

        val facts = (read(apkFile(entries = mapOf("classes.dex" to dex))) as InstalledCodeOutcome.Read).facts
        assertEquals(
            listOf<InstalledCodeDefaultValue>(InstalledCodeDefaultValue.IntValue(0)),
            facts.marker("AicDataRelease__build_id_")!!.references.single().defaults,
        )
    }

    @Test
    fun reportsUnresolvedWhenTheFlagIsConcatArgument() {
        val dex = singleMethodDex { builder ->
            val marker = builder.string("AicDataRelease__build_id_")
            val prefixText = builder.string("prefix")
            val concat = builder.method(
                "Ljava/lang/String;",
                "concat",
                "Ljava/lang/String;",
                listOf("Ljava/lang/String;"),
            )
            SyntheticInsns.units(
                SyntheticInsns.constString(1, marker),
                SyntheticInsns.constString(2, prefixText),
                SyntheticInsns.invokeVirtual(intArrayOf(2, 1), concat),
                SyntheticInsns.moveResultObject(2),
                SyntheticInsns.returnVoid(),
            )
        }

        val facts = (read(apkFile(entries = mapOf("classes.dex" to dex))) as InstalledCodeOutcome.Read).facts
        assertEquals(
            listOf<InstalledCodeDefaultValue>(InstalledCodeDefaultValue.Unresolved),
            facts.marker("AicDataRelease__build_id_")!!.references.single().defaults,
        )
    }

    @Test
    fun reportsUnresolvedWhenConcatHasNoMoveResultObject() {
        val dex = singleMethodDex { builder ->
            val marker = builder.string("AicDataRelease__build_id_")
            val suffixText = builder.string("suffix")
            val concat = builder.method(
                "Ljava/lang/String;",
                "concat",
                "Ljava/lang/String;",
                listOf("Ljava/lang/String;"),
            )
            val gate = builder.method(
                "Ltest/IntFlag;",
                "set",
                "V",
                listOf("Ljava/lang/String;", "I"),
            )
            SyntheticInsns.units(
                SyntheticInsns.constString(1, marker),
                SyntheticInsns.constString(2, suffixText),
                SyntheticInsns.invokeVirtual(intArrayOf(1, 2), concat),
                SyntheticInsns.const4(3, 0),
                SyntheticInsns.invokeStatic(intArrayOf(1, 3), gate),
                SyntheticInsns.returnVoid(),
            )
        }

        val facts = (read(apkFile(entries = mapOf("classes.dex" to dex))) as InstalledCodeOutcome.Read).facts
        assertEquals(
            listOf<InstalledCodeDefaultValue>(InstalledCodeDefaultValue.Unresolved),
            facts.marker("AicDataRelease__build_id_")!!.references.single().defaults,
        )
    }

    @Test
    fun reportsUnresolvedWhenABranchSitsBetweenTheStringAndTheCall() {
        val dex = singleMethodDex { builder ->
            val marker = builder.string("ProtectedDownload__enabled")
            val flag = builder.method(FLAG_MANAGER, "getBoolean", "Z", listOf("Ljava/lang/String;", "Z"))
            SyntheticInsns.units(
                SyntheticInsns.constString(0, marker),
                SyntheticInsns.const4(1, 0),
                SyntheticInsns.ifEqz(2, 3),
                SyntheticInsns.invokeStatic(intArrayOf(0, 1), flag),
                SyntheticInsns.returnVoid(),
            )
        }

        val facts = (read(apkFile(entries = mapOf("classes.dex" to dex))) as InstalledCodeOutcome.Read).facts
        assertEquals(
            listOf<InstalledCodeDefaultValue>(InstalledCodeDefaultValue.Unresolved),
            facts.marker("ProtectedDownload__enabled")!!.references.single().defaults,
        )
    }

    @Test
    fun reportsUnresolvedWhenTheDefaultComesFromAnotherMethod() {
        val dex = singleMethodDex { builder ->
            val marker = builder.string("ProtectedDownload__enabled")
            val flag = builder.method(FLAG_MANAGER, "getBoolean", "Z", listOf("Ljava/lang/String;", "Z"))
            val other = builder.method("Ltest/Other;", "getDefault", "Z", emptyList())
            SyntheticInsns.units(
                SyntheticInsns.constString(0, marker),
                SyntheticInsns.invokeStatic(intArrayOf(), other),
                SyntheticInsns.moveResult(1),
                SyntheticInsns.invokeStatic(intArrayOf(0, 1), flag),
                SyntheticInsns.returnVoid(),
            )
        }

        val facts = (read(apkFile(entries = mapOf("classes.dex" to dex))) as InstalledCodeOutcome.Read).facts
        assertEquals(
            listOf<InstalledCodeDefaultValue>(InstalledCodeDefaultValue.Unresolved),
            facts.marker("ProtectedDownload__enabled")!!.references.single().defaults,
        )
    }

    @Test
    fun reportsUnresolvedWhenTheDefaultRegisterIsWide() {
        val dex = singleMethodDex { builder ->
            val marker = builder.string("ProtectedDownload__enabled")
            val flag = builder.method(FLAG_MANAGER, "getBoolean", "Z", listOf("Ljava/lang/String;", "Z"))
            SyntheticInsns.units(
                SyntheticInsns.constString(0, marker),
                SyntheticInsns.constWide16(1, 0),
                SyntheticInsns.invokeStatic(intArrayOf(0, 1), flag),
                SyntheticInsns.returnVoid(),
            )
        }

        val facts = (read(apkFile(entries = mapOf("classes.dex" to dex))) as InstalledCodeOutcome.Read).facts
        assertEquals(
            listOf<InstalledCodeDefaultValue>(InstalledCodeDefaultValue.Unresolved),
            facts.marker("ProtectedDownload__enabled")!!.references.single().defaults,
        )
    }

    @Test
    fun reportsEveryPrefixMatchWithItsDefault() {
        val dex = multiMethodDex(
            listOf(
                "aicore" to { builder ->
                    val marker = builder.string("AicDataRelease__build_id_aicore")
                    val flag = builder.method(FLAG_MANAGER, "getInt", "I", listOf("Ljava/lang/String;", "I"))
                    SyntheticInsns.units(
                        SyntheticInsns.constString(0, marker),
                        SyntheticInsns.const4(1, 0),
                        SyntheticInsns.invokeStatic(intArrayOf(0, 1), flag),
                        SyntheticInsns.returnVoid(),
                    )
                },
                "pcs" to { builder ->
                    val marker = builder.string("AicDataRelease__build_id_pcs")
                    val flag = builder.method(FLAG_MANAGER, "getInt", "I", listOf("Ljava/lang/String;", "I"))
                    SyntheticInsns.units(
                        SyntheticInsns.constString(0, marker),
                        SyntheticInsns.const16(1, 7),
                        SyntheticInsns.invokeStatic(intArrayOf(0, 1), flag),
                        SyntheticInsns.returnVoid(),
                    )
                },
                "phenotype" to { builder ->
                    val value = builder.string("com.google.android.gms.phenotype.Flag")
                    SyntheticInsns.units(
                        SyntheticInsns.constString(0, value),
                        SyntheticInsns.returnVoid(),
                    )
                },
                "gservices" to { builder ->
                    val value = builder.string("content://com.google.android.gsf.gservices/settings")
                    SyntheticInsns.units(
                        SyntheticInsns.constString(0, value),
                        SyntheticInsns.returnVoid(),
                    )
                },
            ),
        )

        val facts = (read(apkFile(entries = mapOf("classes.dex" to dex))) as InstalledCodeOutcome.Read).facts

        val buildId = facts.marker("AicDataRelease__build_id_")!!
        assertEquals(InstalledCodePresence.PRESENT, buildId.presence)
        assertEquals(InstalledCodeMarkerMatch.PREFIX, buildId.match)
        assertEquals(
            setOf("AicDataRelease__build_id_aicore", "AicDataRelease__build_id_pcs"),
            buildId.references.map { it.matchedString }.toSet(),
        )
        assertEquals(
            InstalledCodeDefaultValue.IntValue(0),
            buildId.references.first { it.matchedString == "AicDataRelease__build_id_aicore" }.defaults.single(),
        )
        assertEquals(
            InstalledCodeDefaultValue.IntValue(7),
            buildId.references.first { it.matchedString == "AicDataRelease__build_id_pcs" }.defaults.single(),
        )
        assertEquals(
            setOf("com.google.android.gms.phenotype.Flag"),
            facts.marker("com.google.android.gms.phenotype")!!.references.map { it.matchedString }.toSet(),
        )
        assertEquals(
            setOf("content://com.google.android.gsf.gservices/settings"),
            facts.marker("com.google.android.gsf.gservices")!!.references.map { it.matchedString }.toSet(),
        )
    }

    @Test
    fun recordsPermissionCheckAndDeviceConfigCalls() {
        val dex = multiMethodDex(
            listOf(
                "checks" to { builder ->
                    val marker = builder.string(InstalledCodeMarkers.READ_DEVICE_CONFIG)
                    val check = builder.method(
                        "Landroid/content/Context;",
                        "checkSelfPermission",
                        "I",
                        listOf("Ljava/lang/String;"),
                    )
                    SyntheticInsns.units(
                        SyntheticInsns.constString(0, marker),
                        SyntheticInsns.invokeStatic(intArrayOf(0), check),
                        SyntheticInsns.returnVoid(),
                    )
                },
                "plain" to { builder ->
                    val marker = builder.string(InstalledCodeMarkers.READ_DEVICE_CONFIG)
                    SyntheticInsns.units(
                        SyntheticInsns.constString(0, marker),
                        SyntheticInsns.returnVoid(),
                    )
                },
                "deviceConfig" to { builder ->
                    val flag = builder.method(FLAG_MANAGER, "getBoolean", "Z", listOf("Ljava/lang/String;", "Z"))
                    val getBoolean = builder.method(
                        "Landroid/provider/DeviceConfig;",
                        "getBoolean",
                        "Z",
                        listOf("Ljava/lang/String;", "Ljava/lang/String;", "Z"),
                    )
                    val getString = builder.method(
                        "Landroid/provider/DeviceConfig;",
                        "getString",
                        "Ljava/lang/String;",
                        listOf("Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/String;"),
                    )
                    val flagName = builder.string("ProtectedDownload__enabled")
                    SyntheticInsns.units(
                        SyntheticInsns.constString(0, flagName),
                        SyntheticInsns.const4(1, 0),
                        SyntheticInsns.invokeStatic(intArrayOf(0, 1), flag),
                        SyntheticInsns.invokeStatic(intArrayOf(0, 1), getBoolean),
                        SyntheticInsns.invokeStatic(intArrayOf(0, 1), getString),
                        SyntheticInsns.returnVoid(),
                    )
                },
            ),
        )

        val facts = (read(apkFile(entries = mapOf("classes.dex" to dex))) as InstalledCodeOutcome.Read).facts

        val permission = facts.readDeviceConfig
        assertEquals(InstalledCodeMarkers.READ_DEVICE_CONFIG, permission.marker)
        assertEquals(1, permission.references.size)
        val reference = permission.references.single()
        assertEquals(2, reference.referencingMethods)
        assertEquals(1, reference.referencingMethodsWithPermissionCheck)
        assertEquals(listOf("checkSelfPermission"), reference.permissionCheckMethodNames)
        assertTrue(permission.anyReferencingMethodChecksPermission)
        assertEquals(2, facts.marker(InstalledCodeMarkers.READ_DEVICE_CONFIG)!!.methodCount)

        assertEquals(listOf("getBoolean", "getString"), facts.deviceConfig.methodNames)
    }

    @Test
    fun recordsAssetEntryNamesAndSizesWithoutContent() {
        val dex = singleMethodDex { builder ->
            SyntheticInsns.units(SyntheticInsns.returnVoid())
        }
        val entries = mapOf(
            "classes.dex" to dex,
            "assets/manifest.binarypb" to ByteArray(10) { 7 },
            "assets/models/model.tflite" to ByteArray(5) { 1 },
            "assets/preload/model.bin" to ByteArray(3) { 2 },
            "assets/other.txt" to ByteArray(4) { 3 },
            "assets/upper/MODEL.TFLITE" to ByteArray(6) { 4 },
            "assets/dir/" to ByteArray(0),
        )

        val facts = (read(apkFile(entries = entries)) as InstalledCodeOutcome.Read).facts

        assertEquals(5, facts.assets.totalAssetCount)
        assertEquals(listOf(5), facts.assets.apkAssetCounts.map { it.count })
        assertEquals(
            setOf(
                "assets/manifest.binarypb",
                "assets/models/model.tflite",
                "assets/preload/model.bin",
                "assets/upper/MODEL.TFLITE",
            ),
            facts.assets.entries.map { it.name }.toSet(),
        )
        assertEquals(10L, facts.assets.entries.first { it.name == "assets/manifest.binarypb" }.sizeBytes)
        assertFalse(facts.assets.entries.any { it.name == "assets/other.txt" })
        assertFalse(facts.assets.truncated)
    }

    @Test
    fun readsEveryDexOfBaseAndSplitApks() {
        val first = singleMethodDex { builder ->
            val marker = builder.string("feature disabled")
            SyntheticInsns.units(SyntheticInsns.constString(0, marker), SyntheticInsns.returnVoid())
        }
        val second = singleMethodDex { builder ->
            val marker = builder.string("AicModels__protected_download_v2_enabled")
            val flag = builder.method(FLAG_MANAGER, "getBoolean", "Z", listOf("Ljava/lang/String;", "Z"))
            SyntheticInsns.units(
                SyntheticInsns.constString(0, marker),
                SyntheticInsns.const4(1, 1),
                SyntheticInsns.invokeStatic(intArrayOf(0, 1), flag),
                SyntheticInsns.returnVoid(),
            )
        }
        val third = singleMethodDex { builder ->
            val marker = builder.string("manifest.binarypb")
            SyntheticInsns.units(SyntheticInsns.constString(0, marker), SyntheticInsns.returnVoid())
        }

        val base = apkFile(entries = mapOf("classes.dex" to first, "classes2.dex" to second))
        val split = apkFile(
            label = "split:config.arm64",
            kind = InstalledCodeApkKind.SPLIT,
            fileName = "split.apk",
            entries = mapOf("classes.dex" to third),
        )

        val outcome = read(base, split)
        assertTrue(outcome is InstalledCodeOutcome.Read)
        val facts = (outcome as InstalledCodeOutcome.Read).facts

        assertEquals(2, facts.apks.size)
        assertEquals(listOf("classes.dex", "classes2.dex"), facts.apks[0].dexEntries)
        assertEquals("classes.dex", facts.marker("feature disabled")!!.references.single().dexEntry)
        assertEquals("classes2.dex", facts.marker("AicModels__protected_download_v2_enabled")!!.references.single().dexEntry)
        assertEquals(
            InstalledCodeApkKind.SPLIT,
            facts.marker("manifest.binarypb")!!.references.single().apk.kind,
        )
        assertEquals("split:config.arm64", facts.marker("manifest.binarypb")!!.references.single().apk.label)
    }

    @Test
    fun reportsTooLargeWhenADexEntryExceedsTheEntryBound() {
        val oversized = ByteArray(64) { 1 }
        val outcome = read(
            apkFile(entries = mapOf("classes.dex" to oversized)),
            limits = InstalledCodeReadLimits(maxBytesPerDexEntry = 16),
        )

        assertTrue(outcome is InstalledCodeOutcome.TooLarge)
        val tooLarge = outcome as InstalledCodeOutcome.TooLarge
        assertTrue(tooLarge.detail.contains("classes.dex"))
        assertEquals(1L, tooLarge.versionCode)
    }

    @Test
    fun reportsTooLargeWhenTheTotalExceedsTheBound() {
        val first = singleMethodDex { SyntheticInsns.units(SyntheticInsns.returnVoid()) }
        val second = singleMethodDex { SyntheticInsns.units(SyntheticInsns.returnVoid()) }
        val outcome = read(
            apkFile(entries = mapOf("classes.dex" to first, "classes2.dex" to second)),
            limits = InstalledCodeReadLimits(maxTotalDexBytes = 4),
        )

        assertTrue(outcome is InstalledCodeOutcome.TooLarge)
        assertTrue((outcome as InstalledCodeOutcome.TooLarge).detail.contains("total"))
    }

    @Test
    fun partialReadNeverReportsAbsent() {
        val good = singleMethodDex { builder ->
            val marker = builder.string("feature disabled")
            SyntheticInsns.units(SyntheticInsns.constString(0, marker), SyntheticInsns.returnVoid())
        }
        val outcome = read(
            apkFile(entries = mapOf("classes.dex" to good, "classes2.dex" to "not a dex".toByteArray())),
        )

        assertTrue(outcome is InstalledCodeOutcome.Partial)
        val facts = (outcome as InstalledCodeOutcome.Partial).facts
        assertFalse(facts.complete)
        assertEquals(1, facts.failures.size)
        val failure = facts.failures.single()
        assertEquals("classes2.dex", failure.dexEntry)
        assertEquals(InstalledCodeFormatException::class.java.name, failure.errorClass)
        assertTrue(failure.message.isNotBlank())

        assertEquals(InstalledCodePresence.PRESENT, facts.marker("feature disabled")!!.presence)
        assertEquals(
            InstalledCodePresence.UNKNOWN,
            facts.marker("ProtectedDownload__enabled")!!.presence,
        )
    }

    @Test
    fun reportsNotVisibleForAnUnknownPackage() {
        val outcome = InstalledCodeReader().read(PACKAGE, FakeInstalledCodeInput(emptyMap()))
        assertTrue(outcome is InstalledCodeOutcome.NotVisible)
    }

    @Test
    fun reportsReadErrorWithClassAndMessage() {
        val input = object : InstalledCodeInput {
            override fun packageFor(packageName: String): InstalledCodePackageInput {
                throw IllegalStateException("boom")
            }
        }
        val outcome = InstalledCodeReader().read(PACKAGE, input)
        assertTrue(outcome is InstalledCodeOutcome.ReadError)
        val error = outcome as InstalledCodeOutcome.ReadError
        assertEquals(IllegalStateException::class.java.name, error.errorClass)
        assertEquals("boom", error.message)
    }

    @Test
    fun recordsPackageVersionAndApkDigest() {
        val dex = singleMethodDex { SyntheticInsns.units(SyntheticInsns.returnVoid()) }
        val file = folder.newFile("base.apk")
        writeApk(file, mapOf("classes.dex" to dex))
        val apk = InstalledCodeApkFile(InstalledCodeApkId("base", InstalledCodeApkKind.BASE), file)
        val facts = (read(apk, versionCode = 545_573L) as InstalledCodeOutcome.Read).facts

        assertEquals(545_573L, facts.versionCode)
        assertEquals(1, facts.apkSha256.size)
        val digest = facts.apkSha256.single()
        assertEquals(InstalledCodeApkKind.BASE, digest.apk.kind)
        assertEquals(sha256(file), digest.sha256)
        assertEquals(64, digest.sha256.length)
        assertEquals(digest.sha256.lowercase(Locale.US), digest.sha256)
    }

    @Test
    fun allowlistContainsEveryRequiredMarker() {
        assertEquals(
            listOf(
                "ProtectedDownload__enabled",
                "feature disabled",
                "Rejecting request since the feature is disabled",
                "Device Config not available: relying on defaults",
                "android.permission.READ_DEVICE_CONFIG",
                "device_personalization_services",
                "PccSecurity__enable_security_check",
                "PcsAi__enable_genai_inference_service",
                "AstreaNetworkUsageLog__enable_network_usage_log",
                "AicDataRelease__build_labels",
                "AicModels__protected_download_v2_enabled",
                "AicModels__preloaded_manifest_file_suffix",
                "manifest.binarypb",
                "EMBEDDED_ASSET_MANIFEST_POPULATOR",
                "AicDataRelease__build_id_",
                "Disable populator for client role",
                "com.google.android.gms.phenotype",
                "com.google.android.gsf.gservices",
            ),
            InstalledCodeMarkers.MARKERS.map { it.name },
        )
        assertEquals(
            setOf(
                "EMBEDDED_ASSET_MANIFEST_POPULATOR",
                "AicDataRelease__build_id_",
                "Disable populator for client role",
                "com.google.android.gms.phenotype",
            ),
            InstalledCodeMarkers.MARKERS.filter { it.match == InstalledCodeMarkerMatch.PREFIX }.map { it.name }.toSet(),
        )
        assertEquals(
            setOf("com.google.android.gsf.gservices"),
            InstalledCodeMarkers.MARKERS.filter { it.match == InstalledCodeMarkerMatch.SUBSTRING }.map { it.name }.toSet(),
        )
    }

    @Test
    fun presenceUsesTheStringTableWhenNoCodeReferencesTheMarker() {
        val dex = singleMethodDex { builder ->
            builder.string("device_personalization_services")
            builder.string("com.google.android.gms.phenotype.TabOnly")
            builder.string("content://com.google.android.gsf.gservices/table")
            SyntheticInsns.units(SyntheticInsns.returnVoid())
        }

        val facts = (read(apkFile(entries = mapOf("classes.dex" to dex))) as InstalledCodeOutcome.Read).facts

        val namespace = facts.marker("device_personalization_services")!!
        assertEquals(InstalledCodePresence.PRESENT, namespace.presence)
        assertEquals(1, namespace.references.size)
        assertEquals(0, namespace.references.single().methodCount)
        assertEquals(InstalledCodePresence.PRESENT, facts.marker("com.google.android.gms.phenotype")!!.presence)
        assertEquals(InstalledCodePresence.PRESENT, facts.marker("com.google.android.gsf.gservices")!!.presence)
        assertEquals(InstalledCodePresence.ABSENT, facts.marker("ProtectedDownload__enabled")!!.presence)
    }

    @Test
    fun resolvesADefaultSetBeforeTheConstString() {
        val dex = singleMethodDex { builder ->
            val marker = builder.string("ProtectedDownload__enabled")
            val flag = builder.method(FLAG_MANAGER, "getBoolean", "Z", listOf("Ljava/lang/String;", "Z"))
            SyntheticInsns.units(
                SyntheticInsns.const4(1, 0),
                SyntheticInsns.constString(0, marker),
                SyntheticInsns.invokeStatic(intArrayOf(0, 1), flag),
                SyntheticInsns.returnVoid(),
            )
        }

        val facts = (read(apkFile(entries = mapOf("classes.dex" to dex))) as InstalledCodeOutcome.Read).facts
        assertEquals(
            listOf<InstalledCodeDefaultValue>(InstalledCodeDefaultValue.BooleanValue(false)),
            facts.marker("ProtectedDownload__enabled")!!.references.single().defaults,
        )
    }

    @Test
    fun resolvesOneLiteralSharedByThreeFlags() {
        val markers = listOf(
            "ProtectedDownload__enabled",
            "PccSecurity__enable_security_check",
            "PcsAi__enable_genai_inference_service",
        )
        val dex = singleMethodDex { builder ->
            val flag = builder.method(FLAG_MANAGER, "getBoolean", "Z", listOf("Ljava/lang/String;", "Z"))
            val first = builder.string(markers[0])
            val second = builder.string(markers[1])
            val third = builder.string(markers[2])
            SyntheticInsns.units(
                SyntheticInsns.const4(1, 0),
                SyntheticInsns.constString(0, first),
                SyntheticInsns.invokeStatic(intArrayOf(0, 1), flag),
                SyntheticInsns.constString(2, second),
                SyntheticInsns.invokeStatic(intArrayOf(2, 1), flag),
                SyntheticInsns.constString(3, third),
                SyntheticInsns.invokeStatic(intArrayOf(3, 1), flag),
                SyntheticInsns.returnVoid(),
            )
        }

        val facts = (read(apkFile(entries = mapOf("classes.dex" to dex))) as InstalledCodeOutcome.Read).facts
        for (marker in markers) {
            assertEquals(
                marker,
                listOf<InstalledCodeDefaultValue>(InstalledCodeDefaultValue.BooleanValue(false)),
                facts.marker(marker)!!.references.single().defaults,
            )
        }
    }

    @Test
    fun reportsUnresolvedForAReachableLabelBetweenLiteralAndInvoke() {
        val dex = singleMethodDex { builder ->
            val marker = builder.string("ProtectedDownload__enabled")
            val flag = builder.method(FLAG_MANAGER, "getBoolean", "Z", listOf("Ljava/lang/String;", "Z"))
            SyntheticInsns.units(
                SyntheticInsns.ifEqz(2, 5),
                SyntheticInsns.const4(1, 0),
                SyntheticInsns.constString(0, marker),
                SyntheticInsns.nop(),
                SyntheticInsns.invokeStatic(intArrayOf(0, 1), flag),
                SyntheticInsns.returnVoid(),
            )
        }

        val facts = (read(apkFile(entries = mapOf("classes.dex" to dex))) as InstalledCodeOutcome.Read).facts
        assertEquals(
            listOf<InstalledCodeDefaultValue>(InstalledCodeDefaultValue.Unresolved),
            facts.marker("ProtectedDownload__enabled")!!.references.single().defaults,
        )
    }

    @Test
    fun reportsUnresolvedForAnExceptionHandlerBetweenLiteralAndInvoke() {
        val bytes = singleMethodDexWithTries(
            tries = listOf(TrySpec(startAddress = 0, insnCount = 3, handlerAddress = 3)),
        ) { builder ->
            val marker = builder.string("ProtectedDownload__enabled")
            val flag = builder.method(FLAG_MANAGER, "getBoolean", "Z", listOf("Ljava/lang/String;", "Z"))
            SyntheticInsns.units(
                SyntheticInsns.const4(1, 0),
                SyntheticInsns.constString(0, marker),
                SyntheticInsns.nop(),
                SyntheticInsns.invokeStatic(intArrayOf(0, 1), flag),
                SyntheticInsns.returnVoid(),
            )
        }
        val dex = InstalledCodeDex.parse(bytes)
        val code = dex.codeItem(dex.classDef(0).methods[0].codeOffset)
        assertArrayEquals(intArrayOf(3), dex.handlerAddresses(code))

        val facts = (read(apkFile(entries = mapOf("classes.dex" to bytes))) as InstalledCodeOutcome.Read).facts
        assertEquals(
            listOf<InstalledCodeDefaultValue>(InstalledCodeDefaultValue.Unresolved),
            facts.marker("ProtectedDownload__enabled")!!.references.single().defaults,
        )
    }

    @Test
    fun reportsUnresolvedWhenTheDefaultRegisterIsOverwritten() {
        val dex = singleMethodDex { builder ->
            val marker = builder.string("ProtectedDownload__enabled")
            val flag = builder.method(FLAG_MANAGER, "getBoolean", "Z", listOf("Ljava/lang/String;", "Z"))
            SyntheticInsns.units(
                SyntheticInsns.constString(0, marker),
                SyntheticInsns.const4(1, 0),
                SyntheticInsns.moveObject(1, 0),
                SyntheticInsns.invokeStatic(intArrayOf(0, 1), flag),
                SyntheticInsns.returnVoid(),
            )
        }

        val facts = (read(apkFile(entries = mapOf("classes.dex" to dex))) as InstalledCodeOutcome.Read).facts
        assertEquals(
            listOf<InstalledCodeDefaultValue>(InstalledCodeDefaultValue.Unresolved),
            facts.marker("ProtectedDownload__enabled")!!.references.single().defaults,
        )
    }

    @Test
    fun resolvesDefaultMovedByMove16() {
        val dex = singleMethodDex { builder ->
            val marker = builder.string("ProtectedDownload__enabled")
            val flag = builder.method(FLAG_MANAGER, "getBoolean", "Z", listOf("Ljava/lang/String;", "Z"))
            SyntheticInsns.units(
                SyntheticInsns.const4(0, 1),
                SyntheticInsns.const4(2, 0),
                SyntheticInsns.move16(3, 2),
                SyntheticInsns.constString(1, marker),
                SyntheticInsns.invokeStatic(intArrayOf(1, 3), flag),
                SyntheticInsns.returnVoid(),
            )
        }

        val facts = (read(apkFile(entries = mapOf("classes.dex" to dex))) as InstalledCodeOutcome.Read).facts
        assertEquals(
            listOf<InstalledCodeDefaultValue>(InstalledCodeDefaultValue.BooleanValue(false)),
            facts.marker("ProtectedDownload__enabled")!!.references.single().defaults,
        )
    }

    @Test
    fun resolvesStringDefaultMovedByMoveObject16() {
        val dex = singleMethodDex { builder ->
            val marker = builder.string("ProtectedDownload__enabled")
            val wrong = builder.string("wrong")
            val right = builder.string("right")
            val flag = builder.method(
                FLAG_MANAGER,
                "getString",
                "Ljava/lang/String;",
                listOf("Ljava/lang/String;", "Ljava/lang/String;"),
            )
            SyntheticInsns.units(
                SyntheticInsns.constString(0, wrong),
                SyntheticInsns.constString(2, right),
                SyntheticInsns.moveObject16(3, 2),
                SyntheticInsns.constString(1, marker),
                SyntheticInsns.invokeStatic(intArrayOf(1, 3), flag),
                SyntheticInsns.returnVoid(),
            )
        }

        val facts = (read(apkFile(entries = mapOf("classes.dex" to dex))) as InstalledCodeOutcome.Read).facts
        assertEquals(
            listOf<InstalledCodeDefaultValue>(InstalledCodeDefaultValue.StringValue("right")),
            facts.marker("ProtectedDownload__enabled")!!.references.single().defaults,
        )
    }

    @Test
    fun usesTheFlagStringWhenItsRegisterIsAlsoTheDefault() {
        val dex = singleMethodDex { builder ->
            val marker = builder.string("ProtectedDownload__enabled")
            val older = builder.string("other")
            val flag = builder.method(
                FLAG_MANAGER,
                "getString",
                "Ljava/lang/String;",
                listOf("Ljava/lang/String;", "Ljava/lang/String;"),
            )
            SyntheticInsns.units(
                SyntheticInsns.constString(0, older),
                SyntheticInsns.constString(0, marker),
                SyntheticInsns.invokeStatic(intArrayOf(0, 0), flag),
                SyntheticInsns.returnVoid(),
            )
        }

        val facts = (read(apkFile(entries = mapOf("classes.dex" to dex))) as InstalledCodeOutcome.Read).facts
        assertEquals(
            listOf<InstalledCodeDefaultValue>(
                InstalledCodeDefaultValue.StringValue("ProtectedDownload__enabled"),
            ),
            facts.marker("ProtectedDownload__enabled")!!.references.single().defaults,
        )
    }

    @Test
    fun keepsTheFlagStringWhenItIsMovedWithMoveObject() {
        val dex = singleMethodDex { builder ->
            val marker = builder.string("ProtectedDownload__enabled")
            val older = builder.string("other")
            val flag = builder.method(
                FLAG_MANAGER,
                "getString",
                "Ljava/lang/String;",
                listOf("Ljava/lang/String;", "Ljava/lang/String;"),
            )
            SyntheticInsns.units(
                SyntheticInsns.constString(0, older),
                SyntheticInsns.constString(0, marker),
                SyntheticInsns.moveObject(1, 0),
                SyntheticInsns.invokeStatic(intArrayOf(1, 1), flag),
                SyntheticInsns.returnVoid(),
            )
        }

        val facts = (read(apkFile(entries = mapOf("classes.dex" to dex))) as InstalledCodeOutcome.Read).facts
        assertEquals(
            listOf<InstalledCodeDefaultValue>(
                InstalledCodeDefaultValue.StringValue("ProtectedDownload__enabled"),
            ),
            facts.marker("ProtectedDownload__enabled")!!.references.single().defaults,
        )
    }

    @Test
    fun reportsUnresolvedWhenInvokeCustomTakesTheFlag() {
        val dex = singleMethodDex { builder ->
            val marker = builder.string("ProtectedDownload__enabled")
            val flag = builder.method(FLAG_MANAGER, "getBoolean", "Z", listOf("Ljava/lang/String;", "Z"))
            SyntheticInsns.units(
                SyntheticInsns.constString(0, marker),
                SyntheticInsns.const4(1, 1),
                SyntheticInsns.invokeCustom(intArrayOf(0), 0),
                SyntheticInsns.invokeStatic(intArrayOf(0, 1), flag),
                SyntheticInsns.returnVoid(),
            )
        }

        val facts = (read(apkFile(entries = mapOf("classes.dex" to dex))) as InstalledCodeOutcome.Read).facts
        assertEquals(
            listOf<InstalledCodeDefaultValue>(InstalledCodeDefaultValue.Unresolved),
            facts.marker("ProtectedDownload__enabled")!!.references.single().defaults,
        )
    }

    @Test
    fun reportsUnresolvedWhenInvokeCustomRangeTakesTheFlag() {
        val dex = singleMethodDex { builder ->
            val marker = builder.string("ProtectedDownload__enabled")
            val flag = builder.method(FLAG_MANAGER, "getBoolean", "Z", listOf("Ljava/lang/String;", "Z"))
            SyntheticInsns.units(
                SyntheticInsns.constString(0, marker),
                SyntheticInsns.const4(1, 1),
                SyntheticInsns.invokeCustomRange(1, 0, 0),
                SyntheticInsns.invokeStatic(intArrayOf(0, 1), flag),
                SyntheticInsns.returnVoid(),
            )
        }

        val facts = (read(apkFile(entries = mapOf("classes.dex" to dex))) as InstalledCodeOutcome.Read).facts
        assertEquals(
            listOf<InstalledCodeDefaultValue>(InstalledCodeDefaultValue.Unresolved),
            facts.marker("ProtectedDownload__enabled")!!.references.single().defaults,
        )
    }

    @Test
    fun reportsUnresolvedWhenInvokePolymorphicTakesTheFlag() {
        val dex = singleMethodDex { builder ->
            val marker = builder.string("ProtectedDownload__enabled")
            val flag = builder.method(FLAG_MANAGER, "getBoolean", "Z", listOf("Ljava/lang/String;", "Z"))
            SyntheticInsns.units(
                SyntheticInsns.constString(0, marker),
                SyntheticInsns.const4(1, 1),
                SyntheticInsns.invokePolymorphic(intArrayOf(0), 0, 0),
                SyntheticInsns.invokeStatic(intArrayOf(0, 1), flag),
                SyntheticInsns.returnVoid(),
            )
        }

        val facts = (read(apkFile(entries = mapOf("classes.dex" to dex))) as InstalledCodeOutcome.Read).facts
        assertEquals(
            listOf<InstalledCodeDefaultValue>(InstalledCodeDefaultValue.Unresolved),
            facts.marker("ProtectedDownload__enabled")!!.references.single().defaults,
        )
    }

    @Test
    fun reportsUnresolvedWhenInvokePolymorphicRangeTakesTheFlag() {
        val dex = singleMethodDex { builder ->
            val marker = builder.string("ProtectedDownload__enabled")
            val flag = builder.method(FLAG_MANAGER, "getBoolean", "Z", listOf("Ljava/lang/String;", "Z"))
            SyntheticInsns.units(
                SyntheticInsns.constString(0, marker),
                SyntheticInsns.const4(1, 1),
                SyntheticInsns.invokePolymorphicRange(1, 0, 0, 0),
                SyntheticInsns.invokeStatic(intArrayOf(0, 1), flag),
                SyntheticInsns.returnVoid(),
            )
        }

        val facts = (read(apkFile(entries = mapOf("classes.dex" to dex))) as InstalledCodeOutcome.Read).facts
        assertEquals(
            listOf<InstalledCodeDefaultValue>(InstalledCodeDefaultValue.Unresolved),
            facts.marker("ProtectedDownload__enabled")!!.references.single().defaults,
        )
    }

    @Test
    fun skipsInvokeCustomThatDoesNotTakeTheFlag() {
        val dex = singleMethodDex { builder ->
            val marker = builder.string("ProtectedDownload__enabled")
            val flag = builder.method(FLAG_MANAGER, "getBoolean", "Z", listOf("Ljava/lang/String;", "Z"))
            SyntheticInsns.units(
                SyntheticInsns.constString(0, marker),
                SyntheticInsns.const4(1, 1),
                SyntheticInsns.constString(2, builder.string("other")),
                SyntheticInsns.invokeCustom(intArrayOf(2), 0),
                SyntheticInsns.invokeStatic(intArrayOf(0, 1), flag),
                SyntheticInsns.returnVoid(),
            )
        }

        val facts = (read(apkFile(entries = mapOf("classes.dex" to dex))) as InstalledCodeOutcome.Read).facts
        assertEquals(
            listOf<InstalledCodeDefaultValue>(InstalledCodeDefaultValue.BooleanValue(true)),
            facts.marker("ProtectedDownload__enabled")!!.references.single().defaults,
        )
    }

    @Test
    fun recordsOutOfMemoryErrorAsADexFailure() {
        val good = singleMethodDex { builder ->
            val marker = builder.string("feature disabled")
            SyntheticInsns.units(SyntheticInsns.constString(0, marker), SyntheticInsns.returnVoid())
        }

        val outcome = readApk(failingApk(good, OutOfMemoryError("synthetic oom")))
        assertTrue(outcome is InstalledCodeOutcome.Partial)
        val facts = (outcome as InstalledCodeOutcome.Partial).facts
        assertEquals(1, facts.failures.size)
        val failure = facts.failures.single()
        assertEquals("classes2.dex", failure.dexEntry)
        assertEquals(OutOfMemoryError::class.java.name, failure.errorClass)
        assertEquals("synthetic oom", failure.message)
        assertEquals(InstalledCodePresence.PRESENT, facts.marker("feature disabled")!!.presence)
        assertEquals(InstalledCodePresence.UNKNOWN, facts.marker("ProtectedDownload__enabled")!!.presence)
    }

    @Test
    fun recordsStackOverflowErrorAsADexFailure() {
        val good = singleMethodDex { builder ->
            val marker = builder.string("feature disabled")
            SyntheticInsns.units(SyntheticInsns.constString(0, marker), SyntheticInsns.returnVoid())
        }

        val outcome = readApk(failingApk(good, StackOverflowError("synthetic stack")))
        assertTrue(outcome is InstalledCodeOutcome.Partial)
        val facts = (outcome as InstalledCodeOutcome.Partial).facts
        assertEquals(1, facts.failures.size)
        assertEquals(StackOverflowError::class.java.name, facts.failures.single().errorClass)
        assertEquals(InstalledCodePresence.PRESENT, facts.marker("feature disabled")!!.presence)
    }

    private fun readApk(vararg apks: InstalledCodeApk): InstalledCodeOutcome =
        InstalledCodeReader().read(
            PACKAGE,
            FakeInstalledCodeInput(
                mapOf(PACKAGE to InstalledCodePackageInput.Visible(1L, apks.toList())),
            ),
        )

    private fun failingApk(goodDex: ByteArray, error: Throwable): InstalledCodeApk =
        object : InstalledCodeApk {
            override val id = InstalledCodeApkId("base", InstalledCodeApkKind.BASE)

            override fun forEachEntry(visitor: (InstalledCodeZipEntry) -> Unit) {
                visitor(InstalledCodeZipEntry("classes.dex", goodDex.size.toLong(), false))
                visitor(InstalledCodeZipEntry("classes2.dex", 1L, false))
            }

            override fun openEntry(name: String): InputStream =
                if (name == "classes2.dex") throw error else ByteArrayInputStream(goodDex)

            override fun sha256(): String = "0".repeat(64)
        }

    private fun singleMethodDexWithTries(
        tries: List<TrySpec>,
        instructions: (SyntheticDexBuilder) -> IntArray,
    ): ByteArray {
        val builder = SyntheticDexBuilder(35)
        val index = builder.method(PROBE, "probe", "V", emptyList())
        builder.clazz(
            PROBE,
            listOf(
                MethodSpec(
                    name = "probe",
                    returnType = "V",
                    parameters = emptyList(),
                    methodIndex = index,
                    instructions = instructions(builder),
                    tries = tries,
                ),
            ),
        )
        return builder.build()
    }

    private fun read(
        vararg apks: InstalledCodeApkFile,
        versionCode: Long = 1L,
        limits: InstalledCodeReadLimits = InstalledCodeReadLimits(),
    ): InstalledCodeOutcome {
        val input = FakeInstalledCodeInput(
            mapOf(PACKAGE to InstalledCodePackageInput.Visible(versionCode, apks.toList())),
        )
        return InstalledCodeReader(limits).read(PACKAGE, input)
    }

    private fun apkFile(
        label: String = "base",
        kind: InstalledCodeApkKind = InstalledCodeApkKind.BASE,
        fileName: String = "base.apk",
        entries: Map<String, ByteArray>,
    ): InstalledCodeApkFile {
        val file = folder.newFile(fileName)
        writeApk(file, entries)
        return InstalledCodeApkFile(InstalledCodeApkId(label, kind), file)
    }

    private fun singleMethodDex(instructions: (SyntheticDexBuilder) -> IntArray): ByteArray =
        multiMethodDex(listOf("probe" to instructions))

    private fun multiMethodDex(
        methods: List<Pair<String, (SyntheticDexBuilder) -> IntArray>>,
    ): ByteArray {
        val builder = SyntheticDexBuilder(35)
        val specs = methods.map { (name, instructions) ->
            val index = builder.method(PROBE, name, "V", emptyList())
            MethodSpec(
                name = name,
                returnType = "V",
                parameters = emptyList(),
                methodIndex = index,
                instructions = instructions(builder),
            )
        }
        builder.clazz(PROBE, specs)
        return builder.build()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { String.format(Locale.US, "%02x", it) }
    }

    private companion object {
        const val PACKAGE = "com.google.android.as.oss"
        const val PROBE = "Ltest/Probe;"
        const val FLAG_MANAGER = "Ltest/FlagManager;"
    }
}
