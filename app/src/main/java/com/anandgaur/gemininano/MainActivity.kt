package com.anandgaur.gemininano

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.anandgaur.gemininano.ui.PrivateNotesScreen
import com.anandgaur.gemininano.ui.theme.GeminiNanoTheme

/**
 * Single-activity, single-screen app.
 *
 * There is nothing AI-specific here — MainActivity just hosts the Compose UI. All the
 * on-device Gemini Nano logic lives in the ViewModel + NotesRepository, and all the
 * on-device model work happens through Android AICore, entirely on this phone.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GeminiNanoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    PrivateNotesScreen()
                }
            }
        }
    }
}
