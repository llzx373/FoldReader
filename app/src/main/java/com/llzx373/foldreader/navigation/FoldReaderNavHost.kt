package com.llzx373.foldreader.navigation

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.llzx373.foldreader.core.foldable.FoldableUiState
import com.llzx373.foldreader.feature.bookshelf.BookshelfScreen
import com.llzx373.foldreader.feature.settings.SettingsScreen

/**
 * 顶层目的地（书架/设置）的 NavHost。
 *
 * 阅读页在这里**只占位**：它需要整窗宽高（不能被外壳 content 槽的 rail 宽度约束），
 * 所以画面由同一 [SharedTransitionScope] 下的 `ReaderOverlay` 全窗口层渲染。
 * 这样外壳导航组件（[androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold]）
 * 的形态与路由无关，rail 宽度在整个会话内不变——退场中的书架不会被推移，
 * 阅读页也不会先按窄槽布局、转场结束后再重排。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun FoldReaderNavHost(
    navController: NavHostController,
    foldableUiState: FoldableUiState,
    sharedTransitionScope: SharedTransitionScope,
    onOpenBook: (bookId: Long, title: String) -> Unit,
    onOpenBookAt: (bookId: Long, anchor: Long, title: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Routes.BOOKSHELF,
        modifier = modifier,
    ) {
        composable(
            route = Routes.BOOKSHELF,
            // 书架退场：缩放淡出；返回时反向
            exitTransition = {
                scaleOut(targetScale = 0.94f, animationSpec = fadeSpring) +
                    fadeOut(fadeSpring)
            },
            popEnterTransition = { fadeIn(fadeSpring) },
        ) {
            BookshelfScreen(
                foldableUiState = foldableUiState,
                onOpenBook = onOpenBook,
                onOpenBookAt = onOpenBookAt,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = this,
            )
        }
        composable(
            route = Routes.READER,
            arguments = listOf(
                navArgument(Routes.ARG_BOOK_ID) { type = NavType.LongType },
                navArgument(Routes.ARG_ANCHOR) {
                    type = NavType.LongType
                    defaultValue = -1L
                },
            ),
            // 画面在 ReaderOverlay：这里不参与任何视觉转场。占位转场的时长只为界定本 entry
            // 的生命周期（必须 ≥ 覆盖层退场动画，见 readerPlaceholderEnter/Exit 注释）
            enterTransition = { readerPlaceholderEnter },
            exitTransition = { readerPlaceholderExit },
            popEnterTransition = { readerPlaceholderEnter },
            popExitTransition = { readerPlaceholderExit },
        ) {
            // 故意留空：仅保留压栈、参数与返回语义
        }
        composable(
            route = Routes.SETTINGS,
            enterTransition = {
                slideInVertically(initialOffsetY = { it / 12 }, animationSpec = offsetSpring) +
                    fadeIn(fadeSpring)
            },
            popExitTransition = {
                slideOutVertically(targetOffsetY = { it / 12 }, animationSpec = offsetSpring) +
                    fadeOut(fadeSpring)
            },
        ) {
            SettingsScreen(foldableUiState)
        }
    }
}
