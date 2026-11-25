package dev.shockstruck.gemininano.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.shockstruck.gemininano.model.Severity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
}

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val logVerbosity: Severity = Severity.VERBOSE,
    val exportDirectoryUri: String? = null,
)

/**
 * Small, local preferences store. No account, no sync, no network: a plain
 * SharedPreferences file in the app's private storage.
 */
class SettingsRepository(context: Context) {

    private val appContext = context.applicationContext
    private val preferences =
        appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    private fun load(): AppSettings = AppSettings(
        theme = ThemeMode.entries.firstOrNull {
            it.name == preferences.getString(KEY_THEME, null)
        } ?: ThemeMode.SYSTEM,
        logVerbosity = Severity.fromLabel(
            preferences.getString(KEY_VERBOSITY, null).orEmpty()
        ) ?: Severity.VERBOSE,
        exportDirectoryUri = preferences.getString(KEY_EXPORT_DIRECTORY, null),
    )

    fun setTheme(mode: ThemeMode) {
        preferences.edit().putString(KEY_THEME, mode.name).apply()
        _state.value = _state.value.copy(theme = mode)
    }

    fun setLogVerbosity(severity: Severity) {
        preferences.edit().putString(KEY_VERBOSITY, severity.label).apply()
        _state.value = _state.value.copy(logVerbosity = severity)
    }

    fun setExportDirectory(uri: Uri?) {
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching {
                appContext.contentResolver.takePersistableUriPermission(uri, flags)
            }
            preferences.edit().putString(KEY_EXPORT_DIRECTORY, uri.toString()).apply()
            _state.value = _state.value.copy(exportDirectoryUri = uri.toString())
        } else {
            _state.value.exportDirectoryUri?.let { previous ->
                runCatching {
                    appContext.contentResolver.releasePersistableUriPermission(
                        Uri.parse(previous),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
            }
            preferences.edit().remove(KEY_EXPORT_DIRECTORY).apply()
            _state.value = _state.value.copy(exportDirectoryUri = null)
        }
    }

    fun hasWritePermission(uri: Uri): Boolean =
        appContext.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }

    companion object {
        private const val PREFERENCES_NAME = "gemini_nano_manager_settings"
        private const val KEY_THEME = "theme"
        private const val KEY_VERBOSITY = "log_verbosity"
        private const val KEY_EXPORT_DIRECTORY = "export_directory"
    }
}
