package dev.shockstruck.gemininano.model

import java.util.Locale

/**
 * Turns one manifest's event stream into [ManifestSourceFacts].
 *
 * The parser records only what the manifest declares. It never guesses a value: an
 * attribute the encoder left unnamed stays in the record with its hex resource id and
 * raw value, a missing protection level or export flag stays null, and a
 * `privateComputeCore` reference stays unresolved rather than becoming true or false.
 */
class ManifestFactsParser(
    private val limits: ManifestReadLimits = ManifestReadLimits(),
) {

    fun parse(source: ManifestSourceId, events: ManifestEventStream): ManifestSourceFacts {
        val state = ParserState(limits)
        while (true) {
            val event = events.nextEvent() ?: break
            state.accept(event)
        }
        return state.finish(source)
    }

    private class ParserState(private val limits: ManifestReadLimits) {

        private val stack = mutableListOf<String>()
        private val openComponents = mutableListOf<ComponentBuilder>()
        private val openFilters = mutableListOf<FilterBuilder>()
        private val openPccElements = mutableListOf<PccElementBuilder>()
        private val permissions = mutableListOf<ManifestPermissionFacts>()
        private val usesPermissions = mutableListOf<ManifestUsesPermissionFacts>()
        private val components = mutableListOf<ManifestComponentFacts>()
        private val pccElements = mutableListOf<ManifestElementFacts>()
        private val queriesPackages = mutableListOf<String>()
        private val queriesActions = mutableListOf<String>()
        private val queriesAuthorities = mutableListOf<String>()

        private var queriesDepth: Int? = null
        private var declaredPackage: String? = null
        private var sharedUserId: String? = null
        private var versionName: String? = null

        fun accept(event: ManifestEvent) {
            when (event) {
                is ManifestEvent.StartElement -> start(event)
                is ManifestEvent.EndElement -> end(event)
                is ManifestEvent.Text -> text(event)
            }
        }

        fun finish(source: ManifestSourceId): ManifestSourceFacts {
            if (stack.isNotEmpty()) {
                throw ManifestFormatException("manifest ended with ${stack.size} unclosed element(s)")
            }
            if (openComponents.isNotEmpty() || openFilters.isNotEmpty() || openPccElements.isNotEmpty()) {
                throw ManifestFormatException("manifest ended inside an open element")
            }
            val declared = declaredPackage
                ?: throw ManifestFormatException("<manifest> did not declare a package attribute")
            return ManifestSourceFacts(
                source = source,
                declaredPackage = declared,
                sharedUserId = sharedUserId,
                versionName = versionName,
                permissions = permissions.toList(),
                usesPermissions = usesPermissions.toList(),
                pccElements = pccElements.toList(),
                components = components.toList(),
                queries = ManifestQueriesFacts(
                    packages = queriesPackages.toList(),
                    intentActions = queriesActions.toList(),
                    providerAuthorities = queriesAuthorities.toList(),
                ),
            )
        }

        private fun start(event: ManifestEvent.StartElement) {
            val depth = stack.size
            val attributes = event.attributes

            when {
                event.name == "manifest" -> {
                    declaredPackage = attributeValue(attributes, "package")
                    sharedUserId = attributeValue(attributes, "sharedUserId")
                    versionName = attributeValue(attributes, "versionName")
                }

                event.name == "permission" -> permissions.record(permissionFacts(attributes))
                event.name.startsWith("uses-permission") ->
                    usesPermissions.record(usesPermissionFacts(attributes))

                event.name in COMPONENT_NAMES && queriesDepth == null ->
                    openComponents += ComponentBuilder(depth, componentFacts(event.name, attributes))

                event.name == "intent-filter" -> {
                    openComponents.lastOrNull()?.let { component ->
                        openFilters += FilterBuilder(depth, component)
                    }
                }

                event.name == "action" && openFilters.isNotEmpty() ->
                    openFilters.last().actions.record(attributeValue(attributes, "name") ?: "")

                event.name == "category" && openFilters.isNotEmpty() ->
                    openFilters.last().categories.record(attributeValue(attributes, "name") ?: "")

                event.name == "data" && openFilters.isNotEmpty() -> {
                    val filter = openFilters.last()
                    attributeValue(attributes, "scheme")?.let { filter.schemes.record(it) }
                    attributeValue(attributes, "host")?.let { filter.hosts.record(it) }
                }
            }

            if (event.name == "queries") queriesDepth = depth
            val queries = queriesDepth
            if (queries != null && depth > queries) {
                when (event.name) {
                    "package" -> attributeValue(attributes, "name")?.let { queriesPackages.record(it) }
                    "action" -> if (stack.lastOrNull() == "intent") {
                        attributeValue(attributes, "name")?.let { queriesActions.record(it) }
                    }
                    "provider" -> attributeValue(attributes, "authorities")?.let { queriesAuthorities.record(it) }
                }
            }

            if (event.name.manifestHasAnyToken(MANIFEST_PCC_ELEMENT_TOKENS)) {
                openPccElements += PccElementBuilder(depth, event.name, attributes.map(::toFacts))
            }
            for (builder in openPccElements) {
                builder.captureDescendant(event.name, attributes, limits.maxRecordedItems)
            }

            stack += event.name
        }

        private fun end(event: ManifestEvent.EndElement) {
            if (stack.isEmpty()) {
                throw ManifestFormatException("unexpected </${event.name}>")
            }
            val open = stack.removeAt(stack.lastIndex)
            if (open != event.name) {
                throw ManifestFormatException("</${event.name}> closes <$open>")
            }
            val depth = stack.size

            while (openFilters.isNotEmpty() && openFilters.last().depth == depth) {
                val filter = openFilters.removeAt(openFilters.lastIndex)
                filter.component.filters.record(filter.build())
            }
            while (openComponents.isNotEmpty() && openComponents.last().depth == depth) {
                components.record(openComponents.removeAt(openComponents.lastIndex).build())
            }
            while (openPccElements.isNotEmpty() && openPccElements.last().depth == depth) {
                pccElements.record(openPccElements.removeAt(openPccElements.lastIndex).build())
            }
            if (queriesDepth == depth) queriesDepth = null
        }

        private fun text(event: ManifestEvent.Text) {
            if (event.value.isBlank()) return
            for (builder in openPccElements) {
                builder.addText(event.value, limits.maxRecordedItems)
            }
        }

        private fun permissionFacts(attributes: List<ManifestAttribute>): ManifestPermissionFacts {
            val level = decodeProtectionLevel(attributeValue(attributes, "protectionLevel"))
            return ManifestPermissionFacts(
                name = attributeValue(attributes, "name") ?: "",
                protectionLevelRaw = level.raw,
                protectionLevelBase = level.base,
                protectionLevelFlags = level.flags,
                protectionLevelUnknownBitsHex = level.unknownBitsHex,
                attributes = attributes.map(::toFacts),
            )
        }

        private fun usesPermissionFacts(attributes: List<ManifestAttribute>): ManifestUsesPermissionFacts =
            ManifestUsesPermissionFacts(
                name = attributeValue(attributes, "name") ?: "",
                maxSdkVersion = attributeValue(attributes, "maxSdkVersion"),
                minSdkVersion = attributeValue(attributes, "minSdkVersion"),
                attributes = attributes.map(::toFacts),
            )

        private fun componentFacts(
            kind: String,
            attributes: List<ManifestAttribute>,
        ): ManifestComponentFacts {
            val pccAttributes = attributes.filter { it.name == PRIVATE_COMPUTE_CORE }
            val androidPcc = pccAttributes.firstOrNull { it.namespace == ANDROID_NAMESPACE }
            val unqualifiedPcc = pccAttributes.firstOrNull { it.namespace.isNullOrEmpty() }
            val privateComputeCore = when {
                androidPcc != null -> privateComputeCoreValue(androidPcc)
                unqualifiedPcc != null -> ManifestBooleanValue.UNRESOLVED
                else -> ManifestBooleanValue.ABSENT
            }
            return ManifestComponentFacts(
                kind = kind,
                name = attributeValue(attributes, "name"),
                targetActivity = attributeValue(attributes, "targetActivity"),
                exported = attributeValue(attributes, "exported")?.toBooleanOrNull(),
                permission = attributeValue(attributes, "permission"),
                process = attributeValue(attributes, "process"),
                privateComputeCore = privateComputeCore,
                matchingAttributes = attributes
                    .filter { it.name.isNotBlank() }
                    .filter { it.name.manifestHasAnyToken(MANIFEST_COMPONENT_ATTRIBUTE_TOKENS) }
                    .map(::toFacts),
                unnamedAttributes = attributes.filter { it.name.isBlank() }.map(::toFacts),
            )
        }

        private fun <T> MutableList<T>.record(item: T) {
            if (size >= limits.maxRecordedItems) {
                throw ManifestSizeLimitException(
                    "manifest records more than ${limits.maxRecordedItems} items",
                )
            }
            add(item)
        }
    }

    private class ComponentBuilder(
        val depth: Int,
        private val facts: ManifestComponentFacts,
    ) {
        val filters = mutableListOf<ManifestIntentFilterFacts>()

        fun build(): ManifestComponentFacts = facts.copy(intentFilters = filters.toList())
    }

    private class FilterBuilder(
        val depth: Int,
        val component: ComponentBuilder,
    ) {
        val actions = mutableListOf<String>()
        val categories = mutableListOf<String>()
        val schemes = mutableListOf<String>()
        val hosts = mutableListOf<String>()

        fun build(): ManifestIntentFilterFacts = ManifestIntentFilterFacts(
            actions = actions.toList(),
            categories = categories.toList(),
            dataSchemes = schemes.toList(),
            dataHosts = hosts.toList(),
        )
    }

    private class PccElementBuilder(
        val depth: Int,
        private val name: String,
        private val attributes: List<ManifestAttributeFacts>,
    ) {
        private val textValues = mutableListOf<String>()
        private val digests = mutableListOf<String>()
        private val packages = mutableListOf<ManifestAssociationPackageFacts>()

        fun captureDescendant(
            elementName: String,
            attributes: List<ManifestAttribute>,
            maxValues: Int,
        ) {
            if (elementName == "package") {
                val packageDigests = attributes.mapNotNull { attribute ->
                    val value = attribute.rawValue ?: attribute.typedValue
                    when {
                        value.isBlank() -> null
                        attribute.name.equals("certDigest", ignoreCase = true) -> value
                        looksLikeDigest(value) -> value
                        else -> null
                    }
                }
                if (packages.size >= maxValues) {
                    throw ManifestSizeLimitException("association element has more than $maxValues package entries")
                }
                packages += ManifestAssociationPackageFacts(
                    name = attributeValue(attributes, "name"),
                    certificateDigests = packageDigests,
                    attributes = attributes.map(::toFacts),
                )
            }
            for (attribute in attributes) {
                val value = attribute.rawValue ?: attribute.typedValue
                if (value.isBlank()) continue
                if (attribute.name.equals("certDigest", ignoreCase = true) || looksLikeDigest(value)) {
                    addDigest(value, maxValues)
                }
            }
        }

        fun addText(value: String, maxValues: Int) {
            if (textValues.size >= maxValues) {
                throw ManifestSizeLimitException("matching element has more than $maxValues text values")
            }
            textValues += value
            if (looksLikeDigest(value)) addDigest(value, maxValues)
        }

        private fun addDigest(value: String, maxValues: Int) {
            if (digests.size >= maxValues) {
                throw ManifestSizeLimitException("matching element has more than $maxValues digest values")
            }
            if (value !in digests) digests += value
        }

        fun build(): ManifestElementFacts = ManifestElementFacts(
            name = name,
            attributes = attributes,
            textValues = textValues.toList(),
            certificateDigests = digests.toList(),
            packages = packages.toList(),
        )
    }

    private companion object {
        val COMPONENT_NAMES = setOf("activity", "service", "receiver", "provider", "activity-alias")

        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        const val PRIVATE_COMPUTE_CORE = "privateComputeCore"

        val PROTECTION_BASE_LEVELS = mapOf(
            0 to "normal",
            1 to "dangerous",
            2 to "signature",
            3 to "signatureOrSystem",
            4 to "internal",
        )

        val PROTECTION_BASE_SYMBOLS = setOf(
            "normal",
            "dangerous",
            "signature",
            "signatureorsystem",
            "internal",
        )

        /**
         * `PermissionInfo` flag constants, GrapheneOS `platform_frameworks_base@17`,
         * `core/java/android/content/pm/PermissionInfo.java`. `PROTECTION_FLAG_SYSTEM`
         * is the deprecated alias of PRIVILEGED (`0x10`) and is not listed twice.
         */
        val PROTECTION_FLAGS = listOf(
            0x00000010 to "privileged",
            0x00000020 to "development",
            0x00000040 to "appop",
            0x00000080 to "pre23",
            0x00000100 to "installer",
            0x00000200 to "verifier",
            0x00000400 to "preinstalled",
            0x00000800 to "setup",
            0x00001000 to "instant",
            0x00002000 to "runtimeOnly",
            0x00004000 to "oem",
            0x00008000 to "vendorPrivileged",
            0x00010000 to "systemTextClassifier",
            0x00020000 to "wellbeing",
            0x00040000 to "documenter",
            0x00080000 to "configurator",
            0x00100000 to "incidentReportApprover",
            0x00200000 to "appPredictor",
            0x00400000 to "module",
            0x00800000 to "companion",
            0x01000000 to "retailDemo",
            0x02000000 to "recents",
            0x04000000 to "role",
            0x08000000 to "knownSigner",
        )

        val KNOWN_FLAG_MASK = PROTECTION_FLAGS.fold(0L) { mask, flag -> mask or flag.first.toLong() }
        const val BASE_LEVEL_MASK = 0x0FL

        val COLON_SEPARATED_HEX_DIGEST = Regex("^(?:[0-9A-Fa-f]{2}:){15,}[0-9A-Fa-f]{2}$")
        val PLAIN_HEX_DIGEST = Regex("^[0-9A-Fa-f]{40,128}$")
        val BASE64_DIGEST = Regex("^[A-Za-z0-9+/]{43}=?$")

        fun looksLikeDigest(value: String): Boolean =
            COLON_SEPARATED_HEX_DIGEST.matches(value) ||
                PLAIN_HEX_DIGEST.matches(value) ||
                BASE64_DIGEST.matches(value)

        fun attributeValue(attributes: List<ManifestAttribute>, name: String): String? {
            val namespaced = attributes.firstOrNull {
                it.name == name && (it.namespace == ANDROID_NAMESPACE || it.namespace == null)
            }
            return (namespaced ?: attributes.firstOrNull { it.name == name })
                ?.let { it.rawValue ?: it.typedValue }
        }

        fun String.toBooleanOrNull(): Boolean? = when (lowercase(Locale.US)) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }

        fun privateComputeCoreValue(attribute: ManifestAttribute): ManifestBooleanValue = when {
            attribute.dataType == "reference" || attribute.dataType == "attribute" ->
                ManifestBooleanValue.UNRESOLVED

            attribute.typedValue.equals("true", ignoreCase = true) ||
                attribute.typedValue == "1" ||
                attribute.rawValue.equals("true", ignoreCase = true) ->
                ManifestBooleanValue.TRUE

            attribute.typedValue.equals("false", ignoreCase = true) ||
                attribute.typedValue == "0" ||
                attribute.rawValue.equals("false", ignoreCase = true) ->
                ManifestBooleanValue.FALSE

            else -> ManifestBooleanValue.UNRESOLVED
        }

        fun toFacts(attribute: ManifestAttribute): ManifestAttributeFacts = ManifestAttributeFacts(
            name = attribute.name,
            namespace = attribute.namespace,
            resourceIdHex = attribute.resourceId?.let { String.format(Locale.US, "0x%08x", it) },
            rawValue = attribute.rawValue,
            typedValue = attribute.typedValue,
            dataType = attribute.dataType,
        )

        fun decodeProtectionLevel(raw: String?): ProtectionLevel {
            if (raw.isNullOrBlank()) return ProtectionLevel(null, null, emptyList(), null)
            val trimmed = raw.trim()
            val numeric = when {
                trimmed.startsWith("0x", ignoreCase = true) -> trimmed.substring(2).toLongOrNull(16)
                trimmed.all(Char::isDigit) -> trimmed.toLongOrNull()
                else -> null
            }
            if (numeric != null) {
                val flags = PROTECTION_FLAGS
                    .filter { (numeric and it.first.toLong()) != 0L }
                    .map { it.second }
                val unknownBits = numeric and (KNOWN_FLAG_MASK or BASE_LEVEL_MASK).inv()
                val unknownHex = if (unknownBits != 0L) {
                    "0x" + unknownBits.toString(16)
                } else {
                    null
                }
                return ProtectionLevel(
                    raw = trimmed,
                    base = PROTECTION_BASE_LEVELS[(numeric and BASE_LEVEL_MASK).toInt()],
                    flags = flags,
                    unknownBitsHex = unknownHex,
                )
            }
            val symbols = trimmed.split('|').map { it.trim() }.filter { it.isNotEmpty() }
            val base = symbols.firstOrNull { it.lowercase(Locale.US) in PROTECTION_BASE_SYMBOLS }
            return ProtectionLevel(
                raw = trimmed,
                base = base,
                flags = symbols.filterNot { it == base },
                unknownBitsHex = null,
            )
        }
    }

    private data class ProtectionLevel(
        val raw: String?,
        val base: String?,
        val flags: List<String>,
        val unknownBitsHex: String?,
    )
}
