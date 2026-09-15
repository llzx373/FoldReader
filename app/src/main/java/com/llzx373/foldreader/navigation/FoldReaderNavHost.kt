package com.llzx373.foldreader.navigation

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.llzx373.foldreader.core.foldable.FoldableUiState
import com.llzx373.foldreader.feature.bookshelf.BookshelfScreen
import com.llzx373.foldreader.feature.reader.ReaderScreen
import com.llzx373.foldreader.feature.settings.SettingsScreen

// M3 Expressive 基调：全 App 转场采用弹簧物理
private val fadeSpring = spring<Float>(stiffness = Spring.StiffnessMediumLow)
private val offsetSpring = spring(
    stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = IntOffset.VisibilityThreshold,
)

private const val COVER_TITLE_KEY = "coverTitle"

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun FoldReaderNavHost(
    navController: NavHostController,
    foldableUiState: FoldableUiState,
    modifier: Modifier = Modifier,
) {
    SharedTransitionLayout(modifier = modifier) {
        NavHost(
            navController = navController,
            startDestination = Routes.BOOKSHELF,
        ) {
            composable(
                route = Routes.BOOKSHELF,
                // 书架退场：缩放淡出；返回时反向
                exitTransition = {
                    scaleOut(targetScale = 0.94f, animationSpec = fadeSpring) +
                        fadeOut(fadeSpring)
                },
                popEnterTransition = {
                    scaleIn(initialScale = 0.94f, animationSpec = fadeSpring) +
                        fadeIn(fadeSpring)
                },
            ) {
                BookshelfScreen(
                    foldableUiState = foldableUiState,
                    onOpenBook = { bookId, title ->
                        navController.navigate(Routes.reader(bookId))
                        navController.currentBackStackEntry
                            ?.savedStateHandle?.set(COVER_TITLE_KEY, title)
                    },
                    onOpenBookAt = { bookId, anchor, title ->
                        navController.navigate(Routes.reader(bookId, anchor))
                        navController.currentBackStackEntry
                            ?.savedStateHandle?.set(COVER_TITLE_KEY, title)
                    },
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this,
                )
            }
            composable(
                route = Routes.READER,
                arguments = listOf(
                    navArgument("bookId") { type = NavType.LongType },
                    navArgument("anchor") {
                        type = NavType.LongType
                        defaultValue = -1L
                    },
                ),
                // 阅读器：自右推入 + 淡入；返回时向右滑出
                enterTransition = {
                    slideInHorizontally(initialOffsetX = { it }, animationSpec = offsetSpring) +
                        fadeIn(fadeSpring)
                },
                exitTransition = { fadeOut(fadeSpring) },
                popEnterTransition = {
                    slideInHorizontally(initialOffsetX = { it }, animationSpec = offsetSpring) +
                        fadeIn(fadeSpring)
                },
                popExitTransition = {
                    slideOutHorizontally(targetOffsetX = { it }, animationSpec = offsetSpring) +
                        fadeOut(fadeSpring)
                },
            ) { backStackEntry ->
                ReaderScreen(
                    bookId = backStackEntry.arguments?.getLong("bookId") ?: 0L,
                    initialAnchor = backStackEntry.arguments?.getLong("anchor") ?: -1L,
                    onBack = { navController.popBackStack() },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    foldableUiState = foldableUiState,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this,
                    coverTitle = backStackEntry.savedStateHandle.get<String>(COVER_TITLE_KEY),
                )
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
}
