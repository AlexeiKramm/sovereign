package fi.ak.smartvoice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import fi.ak.smartvoice.theme.SmartVoiceTheme
import fi.ak.smartvoice.ui.DictationScreen

/**
 * Chunk 0 (PLAN §14): the settings/diagnostics screen the IME cannot provide for itself.
 * It requests `RECORD_AUDIO` — which an InputMethodService cannot do — and proves the transport
 * against the server's frozen `/v1` contract before any keyboard code exists.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SmartVoiceTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    DictationScreen()
                }
            }
        }
    }
}
