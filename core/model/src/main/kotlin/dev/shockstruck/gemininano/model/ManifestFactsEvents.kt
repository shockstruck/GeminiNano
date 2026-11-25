package dev.shockstruck.gemininano.model

/**
 * One event from one manifest. A binary XML decoder produces these; the facts parser
 * consumes them. Tests supply them directly, so the parsing rules never need an APK.
 */
sealed interface ManifestEvent {
    data class StartElement(
        val name: String,
        val attributes: List<ManifestAttribute> = emptyList(),
    ) : ManifestEvent

    data class EndElement(
        val name: String,
    ) : ManifestEvent

    data class Text(
        val value: String,
    ) : ManifestEvent
}

/**
 * One raw attribute as decoded from the binary XML, before any interpretation.
 *
 * [resourceId] is the attribute's resource id from the resource map, when the encoder
 * recorded one; the hex form is produced in [ManifestAttributeFacts].
 */
data class ManifestAttribute(
    val name: String,
    val namespace: String? = null,
    val resourceId: Int? = null,
    val rawValue: String? = null,
    val typedValue: String = rawValue.orEmpty(),
    val dataType: String = if (rawValue != null) "string" else "unknown",
)
