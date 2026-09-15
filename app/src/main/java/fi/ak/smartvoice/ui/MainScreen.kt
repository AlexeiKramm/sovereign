package fi.ak.smartvoice.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import fi.ak.smartvoice.ui.home.HomeScreen
import fi.ak.smartvoice.ui.onboarding.OnboardingScreen
import fi.ak.smartvoice.ui.onboarding.OnboardingStep
import fi.ak.smartvoice.ui.profile.ProfilesScreen
import fi.ak.smartvoice.ui.settings.SettingsScreen

enum class NavTab(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    HOME(
        title = "Home",
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Outlined.Home,
    ),
    PROFILES(
        title = "Profiles",
        selectedIcon = Icons.Filled.Tune,
        unselectedIcon = Icons.Outlined.Tune,
    ),
    SETTINGS(
        title = "Settings",
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
    ),
}

@Composable
fun MainScreen(
    viewModel: DictationViewModel = viewModel(),
) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val focusManager = LocalFocusManager.current
    val tabs = NavTab.entries
    val snackbarHostState = remember { SnackbarHostState() }

    // The wizard can be re-opened from Home ("set up models") or Settings ("run setup again").
    // It covers the whole screen, and finishing simply uncovers the tab that launched it.
    var showOnboarding by rememberSaveable { mutableStateOf(false) }
    var onboardingStart by rememberSaveable { mutableStateOf(OnboardingStep.MICROPHONE) }

    if (showOnboarding) {
        OnboardingScreen(
            viewModel = viewModel,
            initialStep = onboardingStart,
            onFinished = { showOnboarding = false },
        )
        return
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                tabs.forEachIndexed { index, tab ->
                    val isSelected = selectedTabIndex == index
                    val icon = if (isSelected) tab.selectedIcon else tab.unselectedIcon
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = {
                            focusManager.clearFocus(force = true)
                            selectedTabIndex = index
                        },
                        icon = {
                            Icon(
                                imageVector = icon,
                                contentDescription = tab.title,
                            )
                        },
                        label = {
                            Text(
                                text = tab.title,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (tabs[selectedTabIndex]) {
                NavTab.HOME -> HomeScreen(
                    viewModel = viewModel,
                    onSetUpModels = {
                        onboardingStart = OnboardingStep.MODELS
                        showOnboarding = true
                    },
                )

                NavTab.PROFILES -> ProfilesScreen()
                NavTab.SETTINGS -> SettingsScreen(
                    viewModel = viewModel,
                    onOpenSetup = {
                        onboardingStart = OnboardingStep.MICROPHONE
                        showOnboarding = true
                    },
                )
            }
        }
    }
}
