package tech.torlando.reticulumkt.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import tech.torlando.reticulumkt.ui.screens.HomeScreen
import tech.torlando.reticulumkt.ui.screens.InterfacesScreen
import tech.torlando.reticulumkt.ui.screens.ModeScreen
import tech.torlando.reticulumkt.ui.screens.MonitorScreen
import tech.torlando.reticulumkt.ui.screens.PerformanceScreen
import tech.torlando.reticulumkt.ui.screens.SettingsScreen
import tech.torlando.reticulumkt.ui.screens.wizard.RNodeWizardScreen
import tech.torlando.reticulumkt.ui.screens.wizard.TcpClientWizardScreen
import tech.torlando.reticulumkt.viewmodel.ReticulumViewModel

sealed class Screen(
    val route: String,
    val title: String,
    val icon: ImageVector,
) {
    data object Home : Screen("home", "Home", Icons.Filled.Home)
    data object Mode : Screen("mode", "Mode", Icons.Filled.Settings)
    data object Interfaces : Screen("interfaces", "Interfaces", Icons.Filled.Router)
    data object Performance : Screen("performance", "Performance", Icons.Filled.Memory)
    data object Monitor : Screen("monitor", "Monitor", Icons.Filled.Monitor)
    data object Settings : Screen("settings", "Settings", Icons.Filled.Settings)

    // Wizard screens (not shown in bottom nav)
    data object TcpWizard : Screen("tcp_wizard", "TCP Wizard", Icons.Filled.Router)
    data object RNodeWizard : Screen("rnode_wizard", "RNode Wizard", Icons.Filled.Router)
}

// Main navigation items (bottom nav)
val mainNavigationItems = listOf(
    Screen.Home,
    Screen.Interfaces,
    Screen.Monitor,
    Screen.Settings,
)

@Composable
fun AppNavigation(
    viewModel: ReticulumViewModel,
) {
    val navController = rememberNavController()
    val navBackStackEntry = navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry.value?.destination

    // Screens that should hide the bottom navigation bar
    val hideBottomNavScreens = listOf(
        Screen.Mode.route,
        Screen.Performance.route,
        Screen.TcpWizard.route,
        Screen.RNodeWizard.route,
    )
    val shouldShowBottomNav = currentDestination?.route !in hideBottomNavScreens

    @Suppress("UnusedMaterial3ScaffoldPaddingParameter")
    Scaffold(
        bottomBar = {
            if (shouldShowBottomNav) {
                NavigationBar {
                mainNavigationItems.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                        label = { Text(screen.title) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
                }
            }
        }
    ) { _ ->
        // Inner screens have their own Scaffolds with TopAppBars that handle content padding
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route
        ) {
            // Main screens
            composable(Screen.Home.route) {
                HomeScreen(
                    viewModel = viewModel,
                    onNavigateToMode = {
                        navController.navigate(Screen.Mode.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onNavigateToInterfaces = {
                        navController.navigate(Screen.Interfaces.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onNavigateToPerformance = {
                        navController.navigate(Screen.Performance.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable(Screen.Mode.route) {
                ModeScreen(
                    viewModel = viewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(Screen.Interfaces.route) {
                InterfacesScreen(
                    viewModel = viewModel,
                    onNavigateToTcpWizard = { navController.navigate(Screen.TcpWizard.route) },
                    onNavigateToRNodeWizard = { navController.navigate(Screen.RNodeWizard.route) },
                )
            }
            composable(Screen.TcpWizard.route) {
                TcpClientWizardScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onSave = { config ->
                        viewModel.addInterface(config)
                        navController.popBackStack()
                    },
                )
            }
            composable(Screen.RNodeWizard.route) {
                RNodeWizardScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onSave = { config ->
                        viewModel.addInterface(config)
                        navController.popBackStack()
                    },
                )
            }
            composable(Screen.Performance.route) {
                PerformanceScreen(
                    viewModel = viewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(Screen.Monitor.route) {
                MonitorScreen(viewModel = viewModel)
            }
            composable(Screen.Settings.route) {
                SettingsScreen(viewModel = viewModel)
            }
        }
    }
}
