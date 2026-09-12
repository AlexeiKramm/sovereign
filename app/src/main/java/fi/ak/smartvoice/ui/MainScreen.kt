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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.viewmodel.compose.viewModel
import fi.ak.smartvoice.ui.benchmark.BenchmarkScreen
import fi.ak.smartvoice.ui.models.ModelsScreen
import fi.ak.smartvoice.ui.profile.ProfilesScreen
import fi.ak.smartvoice.ui.stats.StatsScreen

enum class NavTab(val title: String) {
    SETTINGS("Settings"),
    PROFILES("Profiles"),
    MODELS("Models"),
    STATS("Stats"),
    BENCHMARK("Benchmark"),
}

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
                        NavTab.SETTINGS -> Icons.Default.Settings
                        NavTab.PROFILES -> Icons.Default.Edit
                        NavTab.MODELS -> Icons.Default.Info
                        NavTab.STATS -> Icons.Default.Star
                        NavTab.BENCHMARK -> Icons.Default.PlayArrow
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
                NavTab.SETTINGS -> DictationScreen(viewModel = viewModel)
                NavTab.PROFILES -> ProfilesScreen()
                NavTab.MODELS -> ModelsScreen(viewModel = viewModel)
                NavTab.STATS -> StatsScreen()
                NavTab.BENCHMARK -> BenchmarkScreen()
            }
        }
    }
}
