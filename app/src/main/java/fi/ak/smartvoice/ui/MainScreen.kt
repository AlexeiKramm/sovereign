package fi.ak.smartvoice.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import fi.ak.smartvoice.ui.benchmark.BenchmarkScreen
import fi.ak.smartvoice.ui.models.ModelsScreen
import fi.ak.smartvoice.ui.profile.ProfilesScreen
import fi.ak.smartvoice.ui.settings.SettingsScreen
import fi.ak.smartvoice.ui.stats.StatsScreen
import fi.ak.smartvoice.ui.transcribe.TranscribeHubScreen

enum class NavTab(val title: String) {
    TRANSCRIBE("Transcribe"),
    PROFILES("Profiles"),
    MODELS("Models"),
    STATS("Stats"),
    BENCHMARK("Benchmark"),
    SETTINGS("Settings"),
}

private val MicIcon: ImageVector
    get() = ImageVector.Builder(
        name = "Mic",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(12f, 14f)
            curveToRelative(1.66f, 0f, 3f, -1.34f, 3f, -3f)
            verticalLineTo(5f)
            curveToRelative(0f, -1.66f, -1.34f, -3f, -3f, -3f)
            reflectiveCurveTo(9f, 3.34f, 9f, 5f)
            verticalLineToRelative(6f)
            curveToRelative(0f, 1.66f, 1.34f, 3f, 3f, 3f)
            close()
            moveTo(17f, 11f)
            curveToRelative(0f, 2.76f, -2.24f, 5f, -5f, 5f)
            reflectiveCurveToRelative(-5f, -2.24f, -5f, -5f)
            horizontalLineTo(5f)
            curveToRelative(0f, 3.53f, 2.61f, 6.43f, 6f, 6.92f)
            verticalLineTo(21f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(-3.08f)
            curveToRelative(3.39f, -0.49f, 6f, -3.39f, 6f, -6.92f)
            horizontalLineToRelative(-2f)
            close()
        }
    }.build()

@Composable
fun MainScreen(
    viewModel: DictationViewModel = viewModel(),
) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val focusManager = LocalFocusManager.current
    val tabs = NavTab.entries

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                tabs.forEachIndexed { index, tab ->
                    val isSelected = selectedTabIndex == index
                    val icon = when (tab) {
                        NavTab.TRANSCRIBE -> MicIcon
                        NavTab.PROFILES -> Icons.Default.Edit
                        NavTab.MODELS -> Icons.Default.Info
                        NavTab.STATS -> Icons.Default.Star
                        NavTab.BENCHMARK -> Icons.Default.PlayArrow
                        NavTab.SETTINGS -> Icons.Default.Settings
                    }
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = {
                            focusManager.clearFocus(force = true)
                            selectedTabIndex = index
                        },
                        icon = { Icon(icon, contentDescription = tab.title) },
                        label = { Text(tab.title) },
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (tabs[selectedTabIndex]) {
                NavTab.TRANSCRIBE -> TranscribeHubScreen(viewModel = viewModel)
                NavTab.PROFILES -> ProfilesScreen()
                NavTab.MODELS -> ModelsScreen(viewModel = viewModel)
                NavTab.STATS -> StatsScreen()
                NavTab.BENCHMARK -> BenchmarkScreen()
                NavTab.SETTINGS -> SettingsScreen(viewModel = viewModel)
            }
        }
    }
}
