package dev.shockstruck.gemininano.data

import android.content.Context
import androidx.core.content.edit
import dev.shockstruck.gemininano.model.PcsBindHistory
import dev.shockstruck.gemininano.model.PcsBindHistoryCodec
import dev.shockstruck.gemininano.model.PcsBindHistoryDecode
import dev.shockstruck.gemininano.model.PcsBindHistoryEntry
import dev.shockstruck.gemininano.model.PcsBindHistoryNotes
import dev.shockstruck.gemininano.model.PcsBindHistoryPolicy
import dev.shockstruck.gemininano.model.PcsBindHistorySnapshot

/**
 * Bind outcomes in the app's own private `SharedPreferences` file. No account, no sync
 * and no network: the encoded text lives beside the other local settings.
 *
 * A missing value is reported as an empty history with an explicit note, and unreadable
 * text is reported the same way instead of throwing; recording a new observation then
 * replaces the unreadable text with a valid history.
 */
class PcsBindHistoryStore(context: Context) {

    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): PcsBindHistorySnapshot {
        val stored = try {
            preferences.getString(KEY_HISTORY, null)
        } catch (t: RuntimeException) {
            return unreadable("${t.javaClass.name}: ${t.message ?: "no detail"}")
        }
        if (stored == null) {
            return PcsBindHistorySnapshot(note = PcsBindHistoryNotes.MISSING)
        }
        return when (val decoded = PcsBindHistoryCodec.decode(stored)) {
            is PcsBindHistoryDecode.Decoded -> PcsBindHistorySnapshot(decoded.history)
            is PcsBindHistoryDecode.Corrupt -> unreadable(decoded.reason)
        }
    }

    /** Records the attempted observations and returns the history now stored. */
    fun record(entries: List<PcsBindHistoryEntry>): PcsBindHistorySnapshot {
        val current = load()
        if (entries.isEmpty()) return current
        val updated = PcsBindHistoryPolicy.recordAll(current.history, entries)
        if (updated == current.history) return current
        return try {
            preferences.edit { putString(KEY_HISTORY, PcsBindHistoryCodec.encode(updated)) }
            PcsBindHistorySnapshot(updated)
        } catch (t: RuntimeException) {
            unreadable(
                "writing the updated history failed (${t.javaClass.name}: " +
                    "${t.message ?: "no detail"})",
            )
        }
    }

    fun clear() {
        runCatching { preferences.edit { remove(KEY_HISTORY) } }
    }

    private fun unreadable(reason: String) =
        PcsBindHistorySnapshot(
            history = PcsBindHistory(),
            note = PcsBindHistoryNotes.corrupt(reason),
        )

    private companion object {
        const val PREFERENCES_NAME = "gemini_nano_manager_pcs_bind_history"
        const val KEY_HISTORY = "history"
    }
}
