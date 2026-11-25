package dev.shockstruck.gemininano.model

import java.util.Locale

/**
 * Turns one manifest's event stream into [ManifestSourceFacts].
 *
 * The parser records only what the manifest declares. It never guesses a value: an
 * attribute the encoder left unnamed stays in the record with its hex resource id and
 * raw value, and a missing protection level or export flag stays null.
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
        private val openPccElements = mutableListOf<PccElementBuilder>()
        private val permissions = mutableListOf<ManifestPermissionFacts>()
        private val usesPermissions = mutableListOf<ManifestUsesPermissionFacts>()
        private val components = mutableListOf<ManifestComponentFacts>()
        private val pccElements = mutableListOf<ManifestElementFacts>()

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
            if (openPccElements.isNotEmpty()) {
                throw ManifestFormatException("manifest ended inside a matching element")
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

                event.name in COMPONENT_NAMES -> components.record(componentFacts(event.name, attributes))
            }

            if (event.name.manifestHasAnyToken(MANIFEST_PCC_ELEMENT_TOKENS)) {
                openPccElements += PccElementBuilder(depth, event.name, attributes.map(::toFacts))
            }
            for (builder in openPccElements) {
                builder.captureAttributes(attributes, limits.maxTextValuesPerElement)
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
            while (openPccElements.isNotEmpty() && openPccElements.last().depth == stack.size) {
                pccElements.record(openPccElements.removeAt(openPccElements.lastIndex).build())
            }
        }

        private fun text(event: ManifestEvent.Text) {
            if (event.value.isBlank()) return
            for (builder in openPccElements) {
                builder.addText(event.value, limits.maxTextValuesPerElement)
            }
        }

        private fun permissionFacts(attributes: List<ManifestAttribute>): ManifestPermissionFacts {
            val level = decodeProtectionLevel(attributeValue(attributes, "protectionLevel"))
            return ManifestPermissionFacts(
                name = attributeValue(attributes, "name") ?: "",
                protectionLevelRaw = level.raw,
                protectionLevelBase = level.base,
                protectionLevelFlags = level.flags,
                attributes = attributes.map(::toFacts),
            )
        }

        private fun usesPermissionFacts(attributes: List<ManifestAttribute>): ManifestUsesPermissionFacts =
            ManifestUsesPermissionFacts(
                name = attributeValue(attributes, "name") ?: "",
                maxSdkVersion = attributeValue(attributes, "maxSdkVersion"),
                attributes = attributes.map(::toFacts),
            )

        private fun componentFacts(
            kind: String,
            attributes: List<ManifestAttribute>,
        ): ManifestComponentFacts = ManifestComponentFacts(
            kind = kind,
            name = attributeValue(attributes, "name"),
            exported = attributeValue(attributes, "exported")?.toBooleanOrNull(),
            permission = attributeValue(attributes, "permission"),
            process = attributeValue(attributes, "process"),
            matchingAttributes = attributes
                .filter { it.name.manifestHasAnyToken(MANIFEST_COMPONENT_ATTRIBUTE_TOKENS) }
                .map(::toFacts),
        )

        private fun <T> MutableList<T>.record(item: T) {
            if (size >= limits.maxRecordedItems) {
                throw ManifestSizeLimitException(
                    "manifest records more than ${limits.maxRecordedItems} items",
                )
            }
            add(item)
        }
    }

    private class PccElementBuilder(
        val depth: Int,
        private val name: String,
        private val attributes: List<ManifestAttributeFacts>,
    ) {
        private val textValues = mutableListOf<String>()
        private val digests = mutableListOf<String>()

        fun captureAttributes(attributes: List<ManifestAttribute>, maxValues: Int) {
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
        )
    }

    private companion object {
        val COMPONENT_NAMES = setOf("activity", "service", "receiver", "provider")

        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"

        val PROTECTION_BASE_LEVELS = mapOf(
            0 to "normal",
            1 to "dangerous",
            2 to "signature",
            3 to "signatureOrSystem",
        )

        val PROTECTION_BASE_SYMBOLS = setOf("normal", "dangerous", "signature", "signatureorsystem")

        val PROTECTION_FLAGS = listOf(
            0x00000010 to "privileged",
            0x00000020 to "system",
            0x00000040 to "development",
            0x00000080 to "appop",
            0x00000100 to "pre23",
            0x00000200 to "installer",
            0x00000400 to "verifier",
            0x00000800 to "preinstalled",
            0x00001000 to "setup",
            0x00002000 to "instant",
            0x00004000 to "ephemeral",
            0x00008000 to "runtime",
            0x00010000 to "oem",
            0x00020000 to "vendorPrivileged",
            0x00040000 to "module",
            0x00080000 to "companion",
            0x00100000 to "retailDemo",
            0x00200000 to "recents",
            0x00400000 to "role",
        )

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

        fun toFacts(attribute: ManifestAttribute): ManifestAttributeFacts = ManifestAttributeFacts(
            name = attribute.name,
            namespace = attribute.namespace,
            resourceIdHex = attribute.resourceId?.let { String.format(Locale.US, "0x%08x", it) },
            rawValue = attribute.rawValue,
            typedValue = attribute.typedValue,
            dataType = attribute.dataType,
        )

        fun decodeProtectionLevel(raw: String?): ProtectionLevel {
            if (raw.isNullOrBlank()) return ProtectionLevel(null, null, emptyList())
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
                return ProtectionLevel(trimmed, PROTECTION_BASE_LEVELS[(numeric and 0xfL).toInt()], flags)
            }
            val symbols = trimmed.split('|').map { it.trim() }.filter { it.isNotEmpty() }
            val base = symbols.firstOrNull { it.lowercase(Locale.US) in PROTECTION_BASE_SYMBOLS }
            return ProtectionLevel(trimmed, base, symbols.filterNot { it == base })
        }
    }

    private data class ProtectionLevel(
        val raw: String?,
        val base: String?,
        val flags: List<String>,
    )
}
