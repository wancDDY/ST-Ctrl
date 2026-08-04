package com.tavern.app.console

import android.util.Log
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.tavern.app.console.pages.*

@Composable
fun ConsoleNavHost(
    @Suppress("UNUSED_PARAMETER") onBack: () -> Unit,
    startRoute: String = "home",
    onEnterTavern: () -> Unit,
    onRefreshTavern: () -> Unit = {},
    onRestartNode: () -> Unit = {},
    onStopNode: () -> Unit = {},
    onStartNode: () -> Unit = {}
) {
    val navController: NavHostController = rememberNavController()
    val activity = LocalContext.current as? androidx.activity.ComponentActivity
    if (activity == null) {
        Log.e("ConsoleNavHost", "LocalContext is not a ComponentActivity, cannot create ViewModel")
        return
    }
    val viewModel: ConsoleViewModel = viewModel(viewModelStoreOwner = activity)

    NavHost(
        navController = navController,
        startDestination = startRoute,
        enterTransition = { fadeIn(animationSpec = tween(250)) },
        exitTransition = { fadeOut(animationSpec = tween(150)) }
    ) {
        composable("home") {
            ConsoleScreen(
                onEnterTavern = onEnterTavern,
                onRestartNode = onRestartNode,
                onRefreshTavern = onRefreshTavern,
                onStopNode = onStopNode,
                onNavigate = { route ->
                    navController.navigate(route) { launchSingleTop = true }
                }
            )
        }
        composable("backup") {
            BackupScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
        composable("restore") {
            RestoreScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onRestoreComplete = { navController.popBackStack("home", false) }
            )
        }
        composable("auto_backup") {
            AutoBackupScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
        composable("status") {
            ServerStatusScreen(viewModel = viewModel, onBack = { navController.popBackStack() }, onStartNode = onStartNode, onStopNode = onStopNode)
        }
        composable("logs") {
            LogScreen(onBack = { navController.popBackStack() })
        }
        composable("storage") {
            StorageScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
        composable("update") {
            CoreUpdateScreen(onBack = { navController.popBackStack() })
        }
        composable("extensions") {
            ExtensionsHubScreen(
                onBack = { navController.popBackStack() },
                onRefreshTavern = onRefreshTavern,
                onNavigateToFiles = { path ->
                    val encoded = java.net.URLEncoder.encode(path, "UTF-8")
                    navController.navigate("files/$encoded") { launchSingleTop = true }
                }
            )
        }
        composable("cache") {
            ClearCacheScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
        composable("settings") {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable("files") {
            FileManagerScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = "files/{path}",
            arguments = listOf(androidx.navigation.navArgument("path") { type = androidx.navigation.NavType.StringType })
        ) { backStackEntry ->
            val path = java.net.URLDecoder.decode(backStackEntry.arguments?.getString("path") ?: "", "UTF-8")
            FileManagerScreen(onBack = { navController.popBackStack() }, initialPath = path)
        }
    }
}
