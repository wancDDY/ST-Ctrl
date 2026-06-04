package com.tavern.app.console

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
    onBack: () -> Unit,
    startRoute: String = "home",
    onEnterTavern: () -> Unit
) {
    val navController: NavHostController = rememberNavController()
    val activity = LocalContext.current as? androidx.activity.ComponentActivity
    val viewModel: ConsoleViewModel = viewModel(
        viewModelStoreOwner = activity ?: return
    )

    NavHost(
        navController = navController,
        startDestination = startRoute,
        enterTransition = { fadeIn(animationSpec = tween(250)) },
        exitTransition = { fadeOut(animationSpec = tween(150)) }
    ) {
        composable("home") {
            ConsoleScreen(
                onEnterTavern = onEnterTavern,
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
            ServerStatusScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
        composable("storage") {
            StorageScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
        composable("update") {
            CoreUpdateScreen(onBack = { navController.popBackStack() })
        }
        composable("extensions") {
            ExtensionsHubScreen(onBack = { navController.popBackStack() })
        }
        composable("cache") {
            ClearCacheScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
        composable("settings") {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
