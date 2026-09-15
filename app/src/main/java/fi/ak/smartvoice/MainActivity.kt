package fi.ak.smartvoice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fi.ak.smartvoice.theme.SmartVoiceTheme
import fi.ak.smartvoice.ui.DictationViewModel
import fi.ak.smartvoice.ui.MainScreen
import fi.ak.smartvoice.ui.onboarding.OnboardingScreen

class MainActivity : ComponentActivity() {
    private val viewModel: DictationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            // Asked once, at first composition: the wizard is the root until it is finished or
            // skipped. It must not vanish mid-flow when the last permission is granted.
            var showWizard by rememberSaveable { mutableStateOf(viewModel.shouldShowOnboarding()) }
            SmartVoiceTheme(themeMode = themeMode) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (showWizard) {
                        OnboardingScreen(
                            viewModel = viewModel,
                            onFinished = {
                                viewModel.completeOnboarding()
                                showWizard = false
                            },
                        )
                    } else {
                        MainScreen(viewModel = viewModel)
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        viewModel.onAppBackgrounded()
    }
}