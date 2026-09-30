package com.echonote.app.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.echonote.app.di.AppContainer
import com.echonote.app.ui.detail.DetailScreen
import com.echonote.app.ui.feasibility.FeasibilityScreen
import com.echonote.app.ui.history.HistoryScreen
import com.echonote.app.ui.home.HomeScreen
import com.echonote.app.ui.models.ModelsScreen
import com.echonote.app.ui.recording.RecordingScreen
import com.echonote.app.ui.search.SearchScreen
import com.echonote.app.ui.settings.SettingsScreen

/** Every navigation target in the app. Kept as constants so deep links, the
 *  notification and the bottom bar all agree on one spelling. */
object Routes {
    const val HOME = "home"
    const val HISTORY = "history"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val RECORDING = "recording"
    const val DETAIL = "detail/{recordingId}"
    const val FEASIBILITY = "feasibility"
    const val MODELS = "models"

    fun detail(recordingId: Long) = "detail/$recordingId"
}

private data class BottomDestination(val route: String, val label: String, val icon: ImageVector)

private val bottomDestinations = listOf(
    BottomDestination(Routes.HOME, "首页", Icons.Filled.Home),
    BottomDestination(Routes.HISTORY, "记录", Icons.AutoMirrored.Filled.List),
    BottomDestination(Routes.SEARCH, "搜索", Icons.Filled.Search),
    BottomDestination(Routes.SETTINGS, "设置", Icons.Filled.Settings),
)

@Composable
fun EchoNoteApp(
    container: AppContainer,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in bottomDestinations.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomDestinations.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    container = container,
                    onOpenRecording = { navController.navigate(Routes.RECORDING) },
                    onOpenDetail = { id -> navController.navigate(Routes.detail(id)) },
                    onOpenModels = { navController.navigate(Routes.MODELS) },
                    onOpenFeasibility = { navController.navigate(Routes.FEASIBILITY) },
                )
            }
            composable(Routes.HISTORY) {
                HistoryScreen(
                    container = container,
                    onOpenDetail = { id -> navController.navigate(Routes.detail(id)) },
                )
            }
            composable(Routes.SEARCH) {
                SearchScreen(
                    container = container,
                    onOpenDetail = { id, _ -> navController.navigate(Routes.detail(id)) },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    container = container,
                    onOpenFeasibility = { navController.navigate(Routes.FEASIBILITY) },
                    onOpenModels = { navController.navigate(Routes.MODELS) },
                )
            }
            composable(Routes.RECORDING) {
                RecordingScreen(
                    container = container,
                    onDone = {
                        navController.navigate(Routes.HISTORY) {
                            popUpTo(Routes.HOME) { inclusive = false }
                        }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                Routes.DETAIL,
                arguments = listOf(navArgument("recordingId") { type = NavType.LongType }),
            ) { entry ->
                val id = entry.arguments?.getLong("recordingId") ?: return@composable
                DetailScreen(
                    container = container,
                    recordingId = id,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.FEASIBILITY) {
                FeasibilityScreen(container = container, onBack = { navController.popBackStack() })
            }
            composable(Routes.MODELS) {
                ModelsScreen(container = container, onBack = { navController.popBackStack() })
            }
        }
    }
}
