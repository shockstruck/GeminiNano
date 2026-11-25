package dev.shockstruck.gemininano.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dev.shockstruck.gemininano.diagnostics.ExportMetadata
import dev.shockstruck.gemininano.diagnostics.JsonExporter
import dev.shockstruck.gemininano.diagnostics.TextExporter
import dev.shockstruck.gemininano.model.DiagnosticEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Writes diagnostic exports only where the user pointed Storage Access Framework at:
 * either a persisted directory tree or a single document the user picked.
 */
class LogExportManager(
    private val context: Context,
    private val eventsProvider: () -> List<DiagnosticEvent>,
    private val metadataProvider: () -> ExportMetadata,
) {

    private val jsonExporter = JsonExporter()
    private val textExporter = TextExporter()

    fun json(): String = jsonExporter.export(eventsProvider(), metadataProvider())

    fun text(): String = textExporter.export(eventsProvider(), metadataProvider())

    fun suggestedFileName(json: Boolean): String {
        val extension = if (json) "json" else "txt"
        return "gemini-nano-diagnostics-${fileTimestamp()}.$extension"
    }

    fun fullReportFileName(json: Boolean): String {
        val extension = if (json) "json" else "txt"
        return "gemini-nano-full-diagnostics-${fileTimestamp()}.$extension"
    }

    fun mimeType(json: Boolean): String =
        if (json) "application/json" else "text/plain"

    /** Writes to a document URI returned by CreateDocument. */
    fun writeToDocument(uri: Uri, json: Boolean): Result<Unit> {
        val payload = if (json) json() else text()
        return writePayloadToDocument(uri, payload)
    }

    /** Writes an already-built payload to a document URI returned by CreateDocument. */
    fun writePayloadToDocument(uri: Uri, payload: String): Result<Unit> = runCatching {
        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
            output.write(payload.toByteArray(Charsets.UTF_8))
        } ?: error("The chosen document could not be opened for writing.")
    }

    /** Writes a new file into a persisted SAF directory tree. */
    fun writeToDirectory(treeUri: Uri, json: Boolean): Result<Uri> =
        writePayloadToDirectory(
            treeUri = treeUri,
            fileName = suggestedFileName(json),
            mimeType = mimeType(json),
            payload = if (json) json() else text(),
        )

    fun writePayloadToDirectory(
        treeUri: Uri,
        fileName: String,
        mimeType: String,
        payload: String,
    ): Result<Uri> = runCatching {
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val document = DocumentsContract.createDocument(
            resolver,
            parent,
            mimeType,
            fileName,
        ) ?: error("The chosen directory did not accept a new document.")
        resolver.openOutputStream(document, "wt")?.use { output ->
            output.write(payload.toByteArray(Charsets.UTF_8))
        } ?: error("The new document could not be opened for writing.")
        document
    }

    private fun fileTimestamp(): String {
        val formatter = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date())
    }
}
