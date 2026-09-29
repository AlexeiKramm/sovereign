package fi.ak.smartvoice.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Memory
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import fi.ak.smartvoice.ui.benchmark.BenchmarkScreen
import fi.ak.smartvoice.ui.history.HistoryScreen
import fi.ak.smartvoice.ui.home.HomeScreen
import fi.ak.smartvoice.ui.models.ModelsScreen
import fi.ak.smartvoice.ui.onboarding.OnboardingScreen
import fi.ak.smartvoice.ui.onboarding.OnboardingStep
import fi.ak.smartvoice.ui.profile.ProfilesScreen
import fi.ak.smartvoice.ui.settings.SettingsScreen
import fi.ak.smartvoice.ui.transcribe.TranscribeHubScreen

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
    MODELS(
        title = "Models",
        selectedIcon = Icons.Filled.Memory,
        unselectedIcon = Icons.Outlined.Memory,
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
    var tabHistory by rememberSaveable { mutableStateOf(listOf(0)) }
    val currentTabIndex = tabHistory.lastOrNull() ?: 0
    val focusManager = LocalFocusManager.current
    val tabs = NavTab.entries
    val snackbarHostState = remember { SnackbarHostState() }

    // The wizard can be re-opened from Home ("set up models") or Settings ("run setup again").
    // It covers the whole screen, and finishing simply uncovers the tab that launched it.
    var showOnboarding by rememberSaveable { mutableStateOf(false) }
    var onboardingStart by rememberSaveable { mutableStateOf(OnboardingStep.MICROPHONE) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showTranscribe by rememberSaveable { mutableStateOf(false) }
    var showBenchmark by rememberSaveable { mutableStateOf(false) }

    val selectTab: (Int) -> Unit = { index ->
        focusManager.clearFocus(force = true)
        showHistory = false
        showBenchmark = false
        showTranscribe = false
        if (tabHistory.lastOrNull() != index) {
            tabHistory = tabHistory + index
        }
    }

    // System Back gesture/button handling
    BackHandler(enabled = showHistory) {
        showHistory = false
    }
    BackHandler(enabled = !showHistory && showBenchmark) {
        showBenchmark = false
    }
    BackHandler(enabled = !showHistory && !showBenchmark && showTranscribe) {
        showTranscribe = false
    }
    BackHandler(enabled = !showHistory && !showBenchmark && !showTranscribe && tabHistory.size > 1) {
        tabHistory = tabHistory.dropLast(1)
    }

    val primary = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
    val surfaceContainerLow = MaterialTheme.colorScheme.surfaceContainerLow
    val isDark = surface.luminance() < 0.5f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                val centerOffset = Offset(x = size.width * 0.5f, y = size.height * 0.02f)
                val radius = size.maxDimension * 0.75f

                val brush = if (isDark) {
                    Brush.radialGradient(
                        colorStops = arrayOf(
                            0.0f to primary.copy(alpha = 0.38f),
                            0.28f to primary.copy(alpha = 0.18f),
                            0.58f to surfaceContainer.copy(alpha = 0.95f),
                            1.0f to surface,
                        ),
                        center = centerOffset,
                        radius = radius,
                    )
                } else {
                    Brush.radialGradient(
                        colorStops = arrayOf(
                            0.0f to primary.copy(alpha = 0.28f),
                            0.28f to primary.copy(alpha = 0.12f),
                            0.62f to surfaceContainerLow,
                            1.0f to surface,
                        ),
                        center = centerOffset,
                        radius = radius,
                    )
                }
                drawRect(brush = brush)
            },
    ) {
        if (showOnboarding) {
            OnboardingScreen(
                viewModel = viewModel,
                initialStep = onboardingStart,
                onFinished = { showOnboarding = false },
            )
        } else {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        tabs.forEachIndexed { index, tab ->
                            val isSelected = !showHistory && !showBenchmark && !showTranscribe && (currentTabIndex == index)
                            val icon = if (isSelected) tab.selectedIcon else tab.unselectedIcon
                            NavigationBarItem(
                                selected = isSelected,
                                onClick = { selectTab(index) },
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
                        .padding(bottom = innerPadding.calculateBottomPadding()),
                ) {
                    when {
                        showHistory -> HistoryScreen(
                            viewModel = viewModel,
                            modifier = Modifier.fillMaxSize(),
                            onNavigateUp = { showHistory = false },
                        )

                        showBenchmark -> BenchmarkScreen(
                            modifier = Modifier.fillMaxSize(),
                            onNavigateUp = { showBenchmark = false },
                        )

                        showTranscribe -> TranscribeHubScreen(
                            viewModel = viewModel,
                            modifier = Modifier.fillMaxSize(),
                            onNavigateUp = { showTranscribe = false },
                        )

                        else -> {
                            when (tabs[currentTabIndex]) {
                                NavTab.HOME -> HomeScreen(
                                    viewModel = viewModel,
                                    modifier = Modifier.fillMaxSize(),
                                    onNavigateToModels = {
                                        selectTab(tabs.indexOf(NavTab.MODELS).coerceAtLeast(0))
                                    },
                                    onNavigateToProfiles = {
                                        selectTab(tabs.indexOf(NavTab.PROFILES).coerceAtLeast(0))
                                    },
                                    onOpenHistory = { showHistory = true },
                                    onOpenTranscribe = { showTranscribe = true },
                                    onSetUpModels = {
                                        onboardingStart = OnboardingStep.MODELS
                                        showOnboarding = true
                                    },
                                )

                                NavTab.MODELS -> ModelsScreen(
                                    viewModel = viewModel,
                                    modifier = Modifier.fillMaxSize(),
                                    onOpenBenchmark = { showBenchmark = true },
                                )

                                NavTab.PROFILES -> ProfilesScreen(
                                    viewModel = viewModel,
                                    modifier = Modifier.fillMaxSize(),
                                )

                                NavTab.SETTINGS -> SettingsScreen(
                                    viewModel = viewModel,
                                    modifier = Modifier.fillMaxSize(),
                                    onOpenSetup = {
                                        onboardingStart = OnboardingStep.MICROPHONE
                                        showOnboarding = true
                                    },
                                )
                            }
                        }
                    }

                    // Subtle top gradient scrim to maintain status bar icon legibility as content scrolls underneath
                    val statusBarHeight = with(LocalDensity.current) {
                        WindowInsets.statusBars.getTop(this).toDp()
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(statusBarHeight + 14.dp)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        (if (isDark) surface else surfaceContainerLow).copy(alpha = 0.72f),
                                        (if (isDark) surface else surfaceContainerLow).copy(alpha = 0.35f),
                                        Color.Transparent,
                                    ),
                                ),
                            ),
                    )
                }
            }
        }
    }
}
