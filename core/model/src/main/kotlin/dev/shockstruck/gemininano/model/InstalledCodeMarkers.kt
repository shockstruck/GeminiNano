package dev.shockstruck.gemininano.model

/**
 * The fixed marker allowlist and the read-only analysis of one parsed dex.
 *
 * Only the listed marker strings, the literal values passed beside flag names, the names
 * of `DeviceConfig` methods and the names of permission-check methods are kept. No other
 * string, class content or bytecode is exposed by the analysis.
 */
object InstalledCodeMarkers {
    const val READ_DEVICE_CONFIG = "android.permission.READ_DEVICE_CONFIG"

    const val DEVICE_CONFIG_CLASS = "Landroid/provider/DeviceConfig;"

    val PERMISSION_CHECK_METHOD_NAMES: List<String> = listOf(
        "checkSelfPermission",
        "checkCallingOrSelfPermission",
    )

    val MARKERS: List<InstalledCodeMarkerSpec> = listOf(
        InstalledCodeMarkerSpec("ProtectedDownload__enabled", InstalledCodeMarkerMatch.EXACT, InstalledCodeMarkerKind.FLAG, resolveDefault = true),
        InstalledCodeMarkerSpec("feature disabled", InstalledCodeMarkerMatch.EXACT, InstalledCodeMarkerKind.MESSAGE),
        InstalledCodeMarkerSpec("Rejecting request since the feature is disabled", InstalledCodeMarkerMatch.EXACT, InstalledCodeMarkerKind.MESSAGE),
        InstalledCodeMarkerSpec("Device Config not available: relying on defaults", InstalledCodeMarkerMatch.EXACT, InstalledCodeMarkerKind.MESSAGE),
        InstalledCodeMarkerSpec(READ_DEVICE_CONFIG, InstalledCodeMarkerMatch.EXACT, InstalledCodeMarkerKind.PERMISSION),
        InstalledCodeMarkerSpec("device_personalization_services", InstalledCodeMarkerMatch.EXACT, InstalledCodeMarkerKind.NAMESPACE),
        InstalledCodeMarkerSpec("PccSecurity__enable_security_check", InstalledCodeMarkerMatch.EXACT, InstalledCodeMarkerKind.FLAG, resolveDefault = true),
        InstalledCodeMarkerSpec("PcsAi__enable_genai_inference_service", InstalledCodeMarkerMatch.EXACT, InstalledCodeMarkerKind.FLAG, resolveDefault = true),
        InstalledCodeMarkerSpec("AstreaNetworkUsageLog__enable_network_usage_log", InstalledCodeMarkerMatch.EXACT, InstalledCodeMarkerKind.FLAG, resolveDefault = true),
        InstalledCodeMarkerSpec("AicDataRelease__build_labels", InstalledCodeMarkerMatch.EXACT, InstalledCodeMarkerKind.FLAG, resolveDefault = true),
        InstalledCodeMarkerSpec("AicModels__protected_download_v2_enabled", InstalledCodeMarkerMatch.EXACT, InstalledCodeMarkerKind.FLAG, resolveDefault = true),
        InstalledCodeMarkerSpec("AicModels__preloaded_manifest_file_suffix", InstalledCodeMarkerMatch.EXACT, InstalledCodeMarkerKind.FLAG, resolveDefault = true),
        InstalledCodeMarkerSpec("manifest.binarypb", InstalledCodeMarkerMatch.EXACT, InstalledCodeMarkerKind.ASSET),
        InstalledCodeMarkerSpec("EMBEDDED_ASSET_MANIFEST_POPULATOR", InstalledCodeMarkerMatch.PREFIX, InstalledCodeMarkerKind.TAG),
        InstalledCodeMarkerSpec("AicDataRelease__build_id_", InstalledCodeMarkerMatch.PREFIX, InstalledCodeMarkerKind.FLAG, resolveDefault = true),
        InstalledCodeMarkerSpec("Disable populator for client role", InstalledCodeMarkerMatch.PREFIX, InstalledCodeMarkerKind.MESSAGE),
        InstalledCodeMarkerSpec("com.google.android.gms.phenotype", InstalledCodeMarkerMatch.PREFIX, InstalledCodeMarkerKind.PACKAGE),
        InstalledCodeMarkerSpec("com.google.android.gsf.gservices", InstalledCodeMarkerMatch.SUBSTRING, InstalledCodeMarkerKind.PACKAGE),
    )

    fun spec(name: String): InstalledCodeMarkerSpec = MARKERS.first { it.name == name }
}

internal data class InstalledCodeDexAnalysis(
    val references: List<InstalledCodeMarkerReference>,
    val permissionCheck: InstalledCodePermissionCheckReference?,
    val deviceConfigMethodNames: List<String>,
    val referencesTruncated: Boolean,
)

internal fun analyseInstalledCodeDex(
    dex: InstalledCodeDex,
    apk: InstalledCodeApkId,
    dexEntry: String,
    limits: InstalledCodeReadLimits,
): InstalledCodeDexAnalysis {
    val perMarker = HashMap<Int, LinkedHashMap<String, ReferenceAccumulator>>()
    val deviceConfigNames = sortedSetOf<String>()
    var referencingPermissionMethods = 0
    var permissionCheckingMethods = 0
    val permissionCheckNames = sortedSetOf<String>()
    var referenceGroups = 0
    var truncated = false

    fun recordMatch(specIndex: Int, matched: String): ReferenceAccumulator? {
        val byText = perMarker.getOrPut(specIndex) { LinkedHashMap() }
        byText[matched]?.let { return it }
        if (referenceGroups >= limits.maxRecordedReferences) {
            truncated = true
            return null
        }
        referenceGroups += 1
        return ReferenceAccumulator(matched).also { byText[matched] = it }
    }

    for (stringIndex in 0 until dex.stringCount) {
        val value = dex.string(stringIndex)
        for ((specIndex, spec) in InstalledCodeMarkers.MARKERS.withIndex()) {
            val matched = spec.matchedText(value) ?: continue
            recordMatch(specIndex, matched)
        }
    }

    for (classIndex in 0 until dex.classCount) {
        val classDef = dex.classDef(classIndex)
        for (encoded in classDef.methods) {
            if (encoded.codeOffset == 0) continue
            val codeItem = dex.codeItem(encoded.codeOffset)
            val instructions = dex.instructions(codeItem).asSequence().toList()
            val barriers = (dex.branchTargets(codeItem) + dex.handlerAddresses(codeItem)).toSet()

            val methodMatches = LinkedHashSet<Pair<Int, String>>()
            val flagOccurrences = mutableListOf<FlagOccurrence>()
            var methodReferencesPermission = false
            var methodChecksPermission = false

            for ((instructionIndex, instruction) in instructions.withIndex()) {
                if (instruction.isInvoke && instruction.invokeMethodIndex >= 0) {
                    val methodRef = dex.method(instruction.invokeMethodIndex)
                    if (methodRef.className == InstalledCodeMarkers.DEVICE_CONFIG_CLASS) {
                        deviceConfigNames += methodRef.name
                    }
                    if (methodRef.name in InstalledCodeMarkers.PERMISSION_CHECK_METHOD_NAMES) {
                        methodChecksPermission = true
                        permissionCheckNames += methodRef.name
                    }
                }
                if (instruction.constKind != InstalledCodeConstKind.STRING) continue
                val value = dex.string(instruction.constStringIndex)
                for ((specIndex, spec) in InstalledCodeMarkers.MARKERS.withIndex()) {
                    val matched = spec.matchedText(value) ?: continue
                    methodMatches += specIndex to matched
                    if (spec.name == InstalledCodeMarkers.READ_DEVICE_CONFIG) {
                        methodReferencesPermission = true
                    }
                    if (spec.resolveDefault) {
                        flagOccurrences += FlagOccurrence(
                            specIndex = specIndex,
                            matched = matched,
                            instructionIndex = instructionIndex,
                            register = instruction.destinationRegister,
                        )
                    }
                }
            }

            for ((specIndex, matched) in methodMatches) {
                val accumulator = recordMatch(specIndex, matched) ?: continue
                accumulator.methodCount += 1
            }
            for (occurrence in flagOccurrences) {
                val accumulator = perMarker[occurrence.specIndex]?.get(occurrence.matched) ?: continue
                accumulator.addDefault(
                    resolveDefault(dex, instructions, occurrence, barriers),
                    limits.maxDefaultsPerReference,
                )
            }
            if (methodReferencesPermission) {
                referencingPermissionMethods += 1
                if (methodChecksPermission) permissionCheckingMethods += 1
            }
        }
    }

    val references = InstalledCodeMarkers.MARKERS.indices.flatMap { specIndex ->
        perMarker[specIndex].orEmpty().values.map { accumulator ->
            InstalledCodeMarkerReference(
                marker = InstalledCodeMarkers.MARKERS[specIndex].name,
                apk = apk,
                dexEntry = dexEntry,
                matchedString = accumulator.matched,
                methodCount = accumulator.methodCount,
                defaults = accumulator.defaults.toList(),
            )
        }
    }
    val permissionCheck = if (referencingPermissionMethods > 0) {
        InstalledCodePermissionCheckReference(
            apk = apk,
            dexEntry = dexEntry,
            referencingMethods = referencingPermissionMethods,
            referencingMethodsWithPermissionCheck = permissionCheckingMethods,
            permissionCheckMethodNames = permissionCheckNames.toList(),
        )
    } else {
        null
    }
    return InstalledCodeDexAnalysis(
        references = references,
        permissionCheck = permissionCheck,
        deviceConfigMethodNames = deviceConfigNames.toList(),
        referencesTruncated = truncated,
    )
}

private class ReferenceAccumulator(
    val matched: String,
) {
    var methodCount: Int = 0
    val defaults: MutableList<InstalledCodeDefaultValue> = mutableListOf()

    fun addDefault(value: InstalledCodeDefaultValue, limit: Int) {
        if (defaults.size >= limit) return
        if (value !in defaults) defaults += value
    }
}

private data class FlagOccurrence(
    val specIndex: Int,
    val matched: String,
    val instructionIndex: Int,
    val register: Int,
)

private sealed interface Literal {
    data class IntLiteral(val value: Int) : Literal
    data class StringLiteral(val value: String) : Literal
    data class BoxedIntLiteral(val value: Int) : Literal
    data class BoxedBooleanLiteral(val value: Boolean) : Literal
    data object WideLiteral : Literal
    data object OtherLiteral : Literal
}

private data class LiteralState(
    val literal: Literal,
    val position: Int,
)

private const val OP_MOVE_RESULT_OBJECT = 0x0c
private const val OP_SGET_OBJECT = 0x62

private fun resolveDefault(
    dex: InstalledCodeDex,
    instructions: List<InstalledCodeInstruction>,
    occurrence: FlagOccurrence,
    barriers: Set<Int>,
): InstalledCodeDefaultValue {
    if (occurrence.register < 0) return InstalledCodeDefaultValue.Unresolved
    val occurrenceAddress = instructions[occurrence.instructionIndex].addressUnits
    val blockStart = blockStartIndex(instructions, occurrence.instructionIndex)
    val literals = HashMap<Int, LiteralState>()
    trackBlockLiterals(dex, instructions, blockStart, occurrence.instructionIndex, literals)
    literals[occurrence.register] = LiteralState(
        Literal.StringLiteral(occurrence.matched),
        occurrenceAddress,
    )
    val stringRegisters = mutableSetOf(occurrence.register)
    var expectingConcatResult = false
    var previousInvoke: InstalledCodeInstruction? = null
    var index = occurrence.instructionIndex + 1
    while (index < instructions.size) {
        val instruction = instructions[index]
        if (instruction.isPayload) {
            index += 1
            continue
        }
        if (expectingConcatResult) {
            if (instruction.opcode == OP_MOVE_RESULT_OBJECT && instruction.destinationRegister >= 0) {
                stringRegisters.clear()
                stringRegisters += instruction.destinationRegister
                literals.remove(instruction.destinationRegister)
                expectingConcatResult = false
                previousInvoke = null
                index += 1
                continue
            }
            return InstalledCodeDefaultValue.Unresolved
        }
        if (instruction.isBranch) return InstalledCodeDefaultValue.Unresolved
        if (instruction.isInvoke) {
            if (instruction.invokeMethodIndex < 0) {
                if (instruction.invokeRegisters.any { it in stringRegisters }) {
                    return InstalledCodeDefaultValue.Unresolved
                }
            } else {
                val methodRef = dex.method(instruction.invokeMethodIndex)
                if (isStringConcat(methodRef)) {
                    val registers = instruction.invokeRegisters
                    val receiverIsFlag = !instruction.isStaticInvoke &&
                        registers.isNotEmpty() &&
                        registers[0] in stringRegisters
                    val argumentIsFlag =
                        (if (instruction.isStaticInvoke) registers.toList() else registers.drop(1))
                            .any { it in stringRegisters }
                    if (receiverIsFlag) {
                        // The key is composed at run time on this value; only the concat
                        // result can carry it further.
                        stringRegisters.clear()
                        expectingConcatResult = true
                        previousInvoke = null
                        index += 1
                        continue
                    }
                    if (argumentIsFlag) return InstalledCodeDefaultValue.Unresolved
                } else if (isStringBuilderCall(methodRef) &&
                    instruction.invokeRegisters.any { it in stringRegisters }
                ) {
                    return InstalledCodeDefaultValue.Unresolved
                } else {
                    val resolved = resolveFromInvoke(
                        dex = dex,
                        instruction = instruction,
                        stringRegisters = stringRegisters,
                        literals = literals,
                        barriers = barriers,
                        occurrenceAddress = occurrenceAddress,
                    )
                    if (resolved != null) return resolved
                }
            }
        }
        updateStringRegisters(instruction, stringRegisters)
        trackLiteral(dex, instruction, literals, previousInvoke)
        previousInvoke = if (instruction.isInvoke) instruction else null
        if (stringRegisters.isEmpty()) return InstalledCodeDefaultValue.Unresolved
        index += 1
    }
    return InstalledCodeDefaultValue.Unresolved
}

private fun trackBlockLiterals(
    dex: InstalledCodeDex,
    instructions: List<InstalledCodeInstruction>,
    from: Int,
    until: Int,
    literals: MutableMap<Int, LiteralState>,
) {
    var previousInvoke: InstalledCodeInstruction? = null
    for (index in from until until) {
        val instruction = instructions[index]
        if (instruction.isPayload || instruction.isBranch) {
            previousInvoke = null
            continue
        }
        trackLiteral(dex, instruction, literals, previousInvoke)
        previousInvoke = if (instruction.isInvoke) instruction else null
    }
}

private fun blockStartIndex(
    instructions: List<InstalledCodeInstruction>,
    occurrenceIndex: Int,
): Int {
    for (index in occurrenceIndex - 1 downTo 0) {
        if (instructions[index].isBranch) return index + 1
    }
    return 0
}

private fun resolveFromInvoke(
    dex: InstalledCodeDex,
    instruction: InstalledCodeInstruction,
    stringRegisters: Set<Int>,
    literals: Map<Int, LiteralState>,
    barriers: Set<Int>,
    occurrenceAddress: Int,
): InstalledCodeDefaultValue? {
    val registers = instruction.invokeRegisters
    val positions = registers.indices.filter { registers[it] in stringRegisters }
    if (positions.isEmpty()) return null
    val methodRef = dex.method(instruction.invokeMethodIndex)
    val receiverOffset = if (instruction.isStaticInvoke) 0 else 1
    val parameterStarts = parameterStartRegisters(methodRef.parameterTypes, receiverOffset)
    val parameterIndex = parameterStarts.indexOfFirst { start -> start in positions }
    if (parameterIndex < 0) return InstalledCodeDefaultValue.Unresolved
    val nextIndex = parameterIndex + 1
    if (nextIndex >= methodRef.parameterTypes.size) {
        // The flag is the last argument, so nothing beside it carries a value.
        return InstalledCodeDefaultValue.NoFollowingArgument
    }
    val word = parameterStarts[nextIndex]
    if (word >= registers.size) return InstalledCodeDefaultValue.Unresolved
    val state = literals[registers[word]] ?: return InstalledCodeDefaultValue.Unresolved
    val windowStart = minOf(state.position, occurrenceAddress)
    val invokeAddress = instruction.addressUnits
    if (barriers.any { barrier -> barrier > windowStart && barrier <= invokeAddress }) {
        return InstalledCodeDefaultValue.Unresolved
    }
    return literalValue(methodRef.parameterTypes[nextIndex], state.literal)
}

private fun parameterStartRegisters(
    parameterTypes: List<String>,
    receiverOffset: Int,
): IntArray {
    val starts = IntArray(parameterTypes.size)
    var cursor = receiverOffset
    for (index in parameterTypes.indices) {
        starts[index] = cursor
        cursor += if (parameterTypes[index] == "J" || parameterTypes[index] == "D") 2 else 1
    }
    return starts
}

private fun literalValue(parameterType: String, literal: Literal?): InstalledCodeDefaultValue =
    when {
        literal == null -> InstalledCodeDefaultValue.Unresolved
        parameterType == "Z" && literal is Literal.IntLiteral -> when (literal.value) {
            0 -> InstalledCodeDefaultValue.BooleanValue(false)
            1 -> InstalledCodeDefaultValue.BooleanValue(true)
            else -> InstalledCodeDefaultValue.Unresolved
        }

        parameterType in INT_PARAMETER_TYPES && literal is Literal.IntLiteral ->
            InstalledCodeDefaultValue.IntValue(literal.value)

        parameterType == "Ljava/lang/String;" && literal is Literal.StringLiteral ->
            InstalledCodeDefaultValue.StringValue(literal.value)

        parameterType in INTEGER_BOX_PARAMETER_TYPES && literal is Literal.BoxedIntLiteral ->
            InstalledCodeDefaultValue.IntValue(literal.value)

        parameterType in BOOLEAN_BOX_PARAMETER_TYPES && literal is Literal.BoxedBooleanLiteral ->
            InstalledCodeDefaultValue.BooleanValue(literal.value)

        else -> InstalledCodeDefaultValue.Unresolved
    }

private val INT_PARAMETER_TYPES = setOf("B", "S", "C", "I")
private val INTEGER_BOX_PARAMETER_TYPES = setOf("Ljava/lang/Integer;", "Ljava/lang/Object;")
private val BOOLEAN_BOX_PARAMETER_TYPES = setOf("Ljava/lang/Boolean;", "Ljava/lang/Object;")

private fun isStringConcat(method: InstalledCodeMethodRef): Boolean =
    method.className == "Ljava/lang/String;" &&
        method.name == "concat" &&
        method.parameterTypes == listOf("Ljava/lang/String;")

private fun isStringBuilderCall(method: InstalledCodeMethodRef): Boolean =
    method.className == "Ljava/lang/StringBuilder;"

private fun boxedLiteralFromValueOf(
    dex: InstalledCodeDex,
    previousInvoke: InstalledCodeInstruction?,
    literals: Map<Int, LiteralState>,
): LiteralState? {
    val invoke = previousInvoke ?: return null
    if (!invoke.isInvoke || !invoke.isStaticInvoke || invoke.invokeMethodIndex < 0) return null
    val method = dex.method(invoke.invokeMethodIndex)
    val registers = invoke.invokeRegisters
    if (registers.isEmpty()) return null
    val argument = literals[registers[0]] ?: return null
    val intLiteral = argument.literal as? Literal.IntLiteral ?: return null
    return when {
        method.className == "Ljava/lang/Integer;" &&
            method.name == "valueOf" &&
            method.parameterTypes == listOf("I") ->
            LiteralState(Literal.BoxedIntLiteral(intLiteral.value), argument.position)

        method.className == "Ljava/lang/Boolean;" &&
            method.name == "valueOf" &&
            method.parameterTypes == listOf("Z") &&
            intLiteral.value in 0..1 ->
            LiteralState(Literal.BoxedBooleanLiteral(intLiteral.value == 1), argument.position)

        else -> null
    }
}

private fun boxedLiteralFromStaticField(
    dex: InstalledCodeDex,
    fieldIndex: Int,
): Literal? {
    if (fieldIndex < 0 || fieldIndex >= dex.fieldCount) return null
    val field = dex.field(fieldIndex)
    return when {
        field.className == "Ljava/lang/Boolean;" && field.name == "TRUE" ->
            Literal.BoxedBooleanLiteral(true)

        field.className == "Ljava/lang/Boolean;" && field.name == "FALSE" ->
            Literal.BoxedBooleanLiteral(false)

        else -> null
    }
}

private fun updateStringRegisters(
    instruction: InstalledCodeInstruction,
    stringRegisters: MutableSet<Int>,
) {
    if (instruction.isMoveObject && instruction.moveSourceRegister in stringRegisters) {
        if (instruction.destinationRegister >= 0) stringRegisters += instruction.destinationRegister
        return
    }
    val destination = instruction.destinationRegister
    if (destination < 0) return
    stringRegisters -= destination
    if (instruction.destinationIsWide) stringRegisters -= destination + 1
}

private fun trackLiteral(
    dex: InstalledCodeDex,
    instruction: InstalledCodeInstruction,
    literals: MutableMap<Int, LiteralState>,
    previousInvoke: InstalledCodeInstruction?,
) {
    val destination = instruction.destinationRegister
    if (destination < 0) return
    val position = instruction.addressUnits
    fun put(value: Literal) {
        literals[destination] = LiteralState(value, position)
    }
    when {
        instruction.opcode == OP_MOVE_RESULT_OBJECT -> {
            val boxed = boxedLiteralFromValueOf(dex, previousInvoke, literals)
            if (boxed == null) literals.remove(destination)
            else literals[destination] = boxed
        }

        instruction.opcode == OP_SGET_OBJECT -> {
            val boxed = boxedLiteralFromStaticField(dex, instruction.staticFieldIndex)
            if (boxed == null) literals.remove(destination)
            else put(boxed)
        }

        instruction.constKind == InstalledCodeConstKind.STRING ->
            put(Literal.StringLiteral(dex.string(instruction.constStringIndex)))

        instruction.constKind == InstalledCodeConstKind.INT ->
            put(Literal.IntLiteral(instruction.constIntValue))

        instruction.constKind == InstalledCodeConstKind.WIDE -> {
            literals[destination] = LiteralState(Literal.WideLiteral, position)
            literals[destination + 1] = LiteralState(Literal.WideLiteral, position)
        }

        instruction.constKind == InstalledCodeConstKind.OTHER ->
            put(Literal.OtherLiteral)

        instruction.opcode in 0x01..0x03 -> {
            val source = literals[instruction.moveSourceRegister]
            if (source == null) literals.remove(destination)
            else literals[destination] = LiteralState(source.literal, source.position)
        }

        instruction.opcode in 0x04..0x06 -> {
            literals[destination] = LiteralState(Literal.WideLiteral, position)
            literals[destination + 1] = LiteralState(Literal.WideLiteral, position)
        }

        instruction.isMoveObject -> {
            val source = literals[instruction.moveSourceRegister]
            if (source == null) literals.remove(destination)
            else literals[destination] = LiteralState(source.literal, source.position)
        }

        else -> {
            literals.remove(destination)
            if (instruction.destinationIsWide) literals.remove(destination + 1)
        }
    }
}
