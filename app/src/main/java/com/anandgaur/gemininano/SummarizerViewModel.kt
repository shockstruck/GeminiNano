package com.anandgaur.gemininano

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anandgaur.gemininano.data.NotesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Everything the single screen needs to render, in one immutable object.
 * The UI just reacts to whichever [phase] we're in.
 */
data class NotesUiState(
    val noteText: String = "",
    val phase: Phase = Phase.Idle,
    val summary: String = "",
    val message: String = "",
    // Live download progress for the one-time model download.
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
) {
    sealed interface Phase {
        /** Nothing happening — waiting for the user to type and tap Summarize. */
        data object Idle : Phase

        /** First run: the on-device model is downloading ("Preparing on-device model..."). */
        data object PreparingModel : Phase

        /** The model is running inference on the device right now. */
        data object Summarizing : Phase

        /** We have a summary to show. */
        data object Success : Phase

        /** Something went wrong, but recoverable — [message] explains it. */
        data object Error : Phase

        /** This device can't run Gemini Nano — [message] explains why. */
        data object NotSupported : Phase
    }

    // Convenience flags for the UI.
    val isBusy: Boolean get() = phase == Phase.PreparingModel || phase == Phase.Summarizing
    val canSummarize: Boolean get() = noteText.isNotBlank() && !isBusy

    /** 0f..1f download progress, or null if the total size isn't known yet (indeterminate). */
    val downloadFraction: Float?
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null

    /** Human label like "42%  (128 MB / 305 MB)", or "Starting…" when size is unknown. */
    val downloadLabel: String
        get() {
            val frac = downloadFraction ?: return "Starting download…"
            val percent = (frac * 100).toInt()
            fun mb(bytes: Long) = "%.0f MB".format(bytes / 1_000_000.0)
            return "$percent%  (${mb(downloadedBytes)} / ${mb(totalBytes)})"
        }
}

/**
 * MVVM ViewModel. Holds UI state as a [StateFlow] and orchestrates the on-device
 * summarization through [NotesRepository]. Contains NO Android View / Compose code.
 *
 * We use AndroidViewModel because the repository (and ML Kit) needs an Application
 * Context. The Context never leaves the device — it's only used to reach AICore locally.
 */
class SummarizerViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = NotesRepository(app.applicationContext)

    private val _uiState = MutableStateFlow(NotesUiState())
    val uiState: StateFlow<NotesUiState> = _uiState.asStateFlow()

    /** Two-way binding for the text field. */
    fun onNoteChange(newText: String) {
        _uiState.update { it.copy(noteText = newText) }
    }

    /**
     * The one action in the app. Steps:
     *   1. Check whether on-device AI is available.
     *   2. If the model needs downloading, download it ("Preparing on-device model...").
     *   3. Run inference on the device and show the bullet-point summary.
     * No step ever touches the network for the AI — it's all AICore / Gemini Nano.
     */
    fun summarize() {
        val note = _uiState.value.noteText.trim()
        if (note.isBlank()) return

        viewModelScope.launch {
            when (val status = repository.checkStatus()) {
                is NotesRepository.NanoStatus.Unsupported -> {
                    _uiState.update {
                        it.copy(phase = NotesUiState.Phase.NotSupported, message = status.reason)
                    }
                }

                is NotesRepository.NanoStatus.Downloadable -> {
                    // One-time model download, then summarize.
                    _uiState.update {
                        it.copy(
                            phase = NotesUiState.Phase.PreparingModel,
                            downloadedBytes = 0,
                            totalBytes = 0,
                        )
                    }
                    // Each progress tick updates the state → the UI shows a live % / MB bar.
                    val result = repository.downloadModel { downloaded, total ->
                        _uiState.update { it.copy(downloadedBytes = downloaded, totalBytes = total) }
                    }
                    if (result.isSuccess) {
                        runInference(note)
                    } else {
                        _uiState.update {
                            it.copy(
                                phase = NotesUiState.Phase.Error,
                                message = "Couldn't prepare the on-device model: " +
                                    (result.exceptionOrNull()?.message ?: "unknown error"),
                            )
                        }
                    }
                }

                is NotesRepository.NanoStatus.Downloading -> {
                    // Model is already downloading; inference will run once it's ready.
                    _uiState.update { it.copy(phase = NotesUiState.Phase.PreparingModel) }
                    runInference(note)
                }

                is NotesRepository.NanoStatus.Available -> {
                    runInference(note)
                }
            }
        }
    }

    private suspend fun runInference(note: String) {
        _uiState.update { it.copy(phase = NotesUiState.Phase.Summarizing) }
        try {
            val summary = repository.summarize(note)
            _uiState.update {
                it.copy(phase = NotesUiState.Phase.Success, summary = summary.trim())
            }
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    phase = NotesUiState.Phase.Error,
                    message = "On-device summarization failed: ${e.message ?: "unknown error"}",
                )
            }
        }
    }

    /** Reset back to the editor so the user can summarize another note. */
    fun reset() {
        _uiState.update { it.copy(phase = NotesUiState.Phase.Idle, summary = "", message = "") }
    }

    // Release the native Gemini Nano resources when the ViewModel is destroyed.
    override fun onCleared() {
        repository.close()
        super.onCleared()
    }
}
