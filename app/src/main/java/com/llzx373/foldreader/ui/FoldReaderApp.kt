package com.llzx373.foldreader.ui

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.llzx373.foldreader.FoldReaderApplication
import com.llzx373.foldreader.core.debug.ReturnTrace
import com.llzx373.foldreader.core.foldable.FoldableUiState
import com.llzx373.foldreader.feature.reader.ReaderOverlay
import com.llzx373.foldreader.navigation.FoldReaderNavHost
import com.llzx373.foldreader.navigation.Routes

private val topLevelRoutes = listOf(Routes.BOOKSHELF, Routes.SETTINGS)

@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalSharedTransitionApi::class)
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

    // 外壳导航组件形态与路由无关：阅读页由 ReaderOverlay 全窗口渲染，不再从 content 槽进出。
    // （曾经把非顶层路由切成 NavigationSuiteType.None：进书时退场中的书架会因 rail 消失整体
    // 左移，退书时又会因 rail 变宽整体右推；也不要碰 scaffold state，其内部有按 state 驱动的
    // animateFloatAsState 占位动画，切换时同样会逐帧挤压内容。）
    val layoutType = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(adaptiveInfo)
    ReturnTrace.log("recompose: route=$currentRoute layoutType=$layoutType")

    // 进书时封面共享元素需要与书架同一份标题（阅读页加载态用它承接飞入封面）
    var readerCoverTitle by remember { mutableStateOf<String?>(null) }

    SharedTransitionLayout {
        Box(modifier = Modifier.fillMaxSize()) {
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
                layoutType = layoutType,
            ) {
                FoldReaderNavHost(
                    navController = navController,
                    foldableUiState = foldableUiState,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    onOpenBook = { bookId, title ->
                        readerCoverTitle = title
                        navController.navigate(Routes.reader(bookId))
                    },
                    onOpenBookAt = { bookId, anchor, title ->
                        readerCoverTitle = title
                        navController.navigate(Routes.reader(bookId, anchor))
                    },
                    // 外壳 content 槽的实测位置/尺寸（= rail 宽度 + 剩余宽度；不受 NavHost 内部
                    // 转场缩放影响）。返回跳动即此值变化，是本次重构要求"恒定"的观测量。
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned {
                            ReturnTrace.log("shell: slot pos=${it.positionInWindow()} size=${it.size}")
                        },
                )
            }
            ReaderOverlay(
                navController = navController,
                foldableUiState = foldableUiState,
                coverTitle = readerCoverTitle,
                sharedTransitionScope = this@SharedTransitionLayout,
            )
        }
    }
}

private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
