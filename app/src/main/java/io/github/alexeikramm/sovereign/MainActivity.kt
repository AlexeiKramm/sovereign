package io.github.alexeikramm.sovereign

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.alexeikramm.sovereign.theme.SovereignTheme
import io.github.alexeikramm.sovereign.theme.appThemeIsDark
import io.github.alexeikramm.sovereign.server.SovereignServerService
import io.github.alexeikramm.sovereign.ui.DictationViewModel
import io.github.alexeikramm.sovereign.ui.MainScreen
import android.os.Build
import androidx.activity.SystemBarStyle
import io.github.alexeikramm.sovereign.ui.onboarding.OnboardingScreen

class MainActivity : ComponentActivity() {
    private val viewModel: DictationViewModel by viewModels()

    /** Set by the server notification; the Server screen opens once and clears it. */
    private var openServerRequested by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openServerRequested = intent?.action == SovereignServerService.ACTION_OPEN_SERVER
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val themeSeedColor by viewModel.themeSeedColor.collectAsStateWithLifecycle()
            val themePaletteStyle by viewModel.themePaletteStyle.collectAsStateWithLifecycle()
            val themeUseSystemDynamic by viewModel.themeUseSystemDynamic.collectAsStateWithLifecycle()
            val themeDepthMode by viewModel.themeDepthMode.collectAsStateWithLifecycle()
            val themeDarkBgTone by viewModel.themeDarkBgTone.collectAsStateWithLifecycle()
            val themeDarkCardTone by viewModel.themeDarkCardTone.collectAsStateWithLifecycle()
            val themeDarkBorderTone by viewModel.themeDarkBorderTone.collectAsStateWithLifecycle()
            val themeLightBgTone by viewModel.themeLightBgTone.collectAsStateWithLifecycle()
            val themeLightBorderTone by viewModel.themeLightBorderTone.collectAsStateWithLifecycle()
            val darkTheme = appThemeIsDark(themeMode)

            // `enableEdgeToEdge` chooses the status/navigation bar icon colour from the *system*
            // theme. When the app's theme deliberately differs, that leaves dark icons on a dark
            // background (or the reverse), so match them to the theme we actually render.
            val view = LocalView.current
            if (!view.isInEditMode) {
                SideEffect {
                    val window = (view.context as Activity).window
                    window.statusBarColor = android.graphics.Color.TRANSPARENT
                    window.navigationBarColor = android.graphics.Color.TRANSPARENT
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        window.isStatusBarContrastEnforced = false
                        window.isNavigationBarContrastEnforced = false
                    }
                    WindowCompat.getInsetsController(window, view).apply {
                        isAppearanceLightStatusBars = !darkTheme
                        isAppearanceLightNavigationBars = !darkTheme
                    }
                }
            }

            val forceWizard = intent.getBooleanExtra("show_onboarding", false)
            val initialStep = intent.getStringExtra("onboarding_step")?.let { name ->
                runCatching { io.github.alexeikramm.sovereign.ui.onboarding.OnboardingStep.valueOf(name) }.getOrNull()
            } ?: io.github.alexeikramm.sovereign.ui.onboarding.OnboardingStep.MICROPHONE

            var showWizard by rememberSaveable { mutableStateOf(forceWizard || viewModel.shouldShowOnboarding()) }
            SovereignTheme(
                themeMode = themeMode,
                seedColor = themeSeedColor,
                paletteStyle = themePaletteStyle,
                useSystemDynamic = themeUseSystemDynamic,
                depthMode = themeDepthMode,
                darkBgTone = themeDarkBgTone,
                darkCardTone = themeDarkCardTone,
                darkBorderTone = themeDarkBorderTone,
                lightBgTone = themeLightBgTone,
                lightBorderTone = themeLightBorderTone,
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (showWizard) {
                        OnboardingScreen(
                            viewModel = viewModel,
                            initialStep = initialStep,
                            onFinished = {
                                viewModel.completeOnboarding()
                                showWizard = false
                            },
                        )
                    } else {
                        MainScreen(
                            viewModel = viewModel,
                            openServerRequest = openServerRequested,
                            onServerRequestConsumed = { openServerRequested = false },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == SovereignServerService.ACTION_OPEN_SERVER) {
            openServerRequested = true
        }
    }

    override fun onStop() {
        super.onStop()
        viewModel.onAppBackgrounded()
    }
}