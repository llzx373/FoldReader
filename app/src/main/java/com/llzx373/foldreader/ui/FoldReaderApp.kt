package com.llzx373.foldreader.ui

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
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
import androidx.compose.runtime.LaunchedEffect
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
import com.llzx373.foldreader.feature.reader.ExtraOverReferenceInsets
import com.llzx373.foldreader.feature.reader.ReaderOverlay
import com.llzx373.foldreader.feature.reader.ShellInsets
import com.llzx373.foldreader.navigation.FoldReaderNavHost
import com.llzx373.foldreader.navigation.Routes
import kotlinx.coroutines.delay

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

    // 环境变化（旋转 / 折叠展开 / 半折 / 尺寸类别）逐次记录：真机折叠适配问题时
    // 这几行能直接对齐"当时是什么形态、什么窗口尺寸"，不用靠猜
    val sizeClass = adaptiveInfo.windowSizeClass
    val envKey = buildString {
        append(sizeClass.minWidthDp).append('x').append(sizeClass.minHeightDp)
        append('|').append(posture.posture)
        append('|').append(posture.hingeOrientation)
        append('|').append(posture.hingeBounds)
        append('|').append(layoutType)
    }
    LaunchedEffect(envKey) {
        ReturnTrace.log(
            "env: minWidth=${sizeClass.minWidthDp}dp minHeight=${sizeClass.minHeightDp}dp " +
                "posture=${posture.posture} hinge=${posture.hingeOrientation} " +
                "hingeBounds=${posture.hingeBounds} layoutType=$layoutType",
        )
    }

    // 进书时封面共享元素需要与书架同一份标题（阅读页加载态用它承接飞入封面）
    var readerCoverTitle by remember { mutableStateOf<String?>(null) }

    // 离开阅读页后的一小段窗口内，抑制"超出沉浸前实测快照的额外 inset"：
    // 真机上每次系统栏显隐变化（hide/show）之后，平台都会补报一次挖孔侧边 inset（140px），
    // 约 500ms 后才消失；书架若在这段窗口内首帧布局，就会先窄后宽 → 右边缘外扩、整体右跳。
    // 门控（等 inset 到位再导航）堵不住它，因为该 inset 出现得比 show() 晚约 50ms。
    var suppressExtraInsets by remember { mutableStateOf(false) }
    var wasInReader by remember { mutableStateOf(false) }
    LaunchedEffect(currentRoute) {
        if (currentRoute == Routes.READER) {
            wasInReader = true
            suppressExtraInsets = false
        } else if (wasInReader) {
            wasInReader = false
            suppressExtraInsets = true
            ReturnTrace.log("shell: suppressExtraInsets on (reference=${ShellInsets.visibleReference()})")
            delay(1200L)
            suppressExtraInsets = false
            ReturnTrace.log("shell: suppressExtraInsets off")
        }
    }
    val shellInsetReference = ShellInsets.visibleReference()

    SharedTransitionLayout {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (suppressExtraInsets && shellInsetReference != null) {
                            Modifier.consumeWindowInsets(
                                ExtraOverReferenceInsets(
                                    live = WindowInsets.systemBars.union(WindowInsets.displayCutout),
                                    reference = shellInsetReference,
                                ),
                            )
                        } else {
                            Modifier
                        },
                    ),
            ) {
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
                        // 外壳 content 槽的实测位置/尺寸（= rail 宽度 + 剩余宽度；不受 NavHost
                        // 内部转场缩放影响）。返回跳动即此值变化，是要求"恒定"的观测量。
                        modifier = Modifier
                            .fillMaxSize()
                            .onGloballyPositioned {
                                ReturnTrace.log(
                                    "shell: slot pos=${it.positionInWindow()} size=${it.size}",
                                )
                            },
                    )
                }
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
