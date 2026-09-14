package com.llzx373.foldreader.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.llzx373.foldreader.FoldReaderApplication
import com.llzx373.foldreader.core.foldable.FoldableUiState
import com.llzx373.foldreader.navigation.FoldReaderNavHost
import com.llzx373.foldreader.navigation.Routes

private val topLevelRoutes = listOf(Routes.BOOKSHELF, Routes.SETTINGS)

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun FoldReaderApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val container = (LocalContext.current.applicationContext as FoldReaderApplication).container
    val posture by container.foldableStateProvider.posture.collectAsState()
    val adaptiveInfo = currentWindowAdaptiveInfoV2()
    val foldableUiState = FoldableUiState(
        posture = posture,
        windowSizeClass = adaptiveInfo.windowSizeClass,
    )

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            item(
                selected = currentRoute == Routes.BOOKSHELF,
                onClick = { navController.navigateTopLevel(Routes.BOOKSHELF) },
                icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                label = { Text("书架") },
            )
            item(
                selected = currentRoute == Routes.SETTINGS,
                onClick = { navController.navigateTopLevel(Routes.SETTINGS) },
                icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                label = { Text("设置") },
            )
        },
        layoutType = if (currentRoute == null || currentRoute in topLevelRoutes) {
            NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(adaptiveInfo)
        } else {
            NavigationSuiteType.None
        },
    ) {
        FoldReaderNavHost(navController, foldableUiState = foldableUiState)
    }
}

private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
