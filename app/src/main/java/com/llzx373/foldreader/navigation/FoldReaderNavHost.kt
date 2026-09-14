package com.llzx373.foldreader.navigation

import androidx.compose.animation.core.tween
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
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.llzx373.foldreader.core.foldable.FoldableUiState
import com.llzx373.foldreader.feature.bookshelf.BookshelfScreen
import com.llzx373.foldreader.feature.reader.ReaderScreen
import com.llzx373.foldreader.feature.settings.SettingsScreen

private const val TRANSITION_MS = 300

@Composable
fun FoldReaderNavHost(
    navController: NavHostController,
    foldableUiState: FoldableUiState,
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
                scaleOut(targetScale = 0.94f, animationSpec = tween(TRANSITION_MS)) +
                    fadeOut(tween(TRANSITION_MS))
            },
            popEnterTransition = {
                scaleIn(initialScale = 0.94f, animationSpec = tween(TRANSITION_MS)) +
                    fadeIn(tween(TRANSITION_MS))
            },
        ) {
            BookshelfScreen(
                foldableUiState = foldableUiState,
                onOpenBook = { bookId -> navController.navigate(Routes.reader(bookId)) },
                onOpenBookAt = { bookId, anchor ->
                    navController.navigate(Routes.reader(bookId, anchor))
                },
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
                slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(TRANSITION_MS)) +
                    fadeIn(tween(TRANSITION_MS))
            },
            exitTransition = { fadeOut(tween(TRANSITION_MS)) },
            popEnterTransition = {
                slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(TRANSITION_MS)) +
                    fadeIn(tween(TRANSITION_MS))
            },
            popExitTransition = {
                slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(TRANSITION_MS)) +
                    fadeOut(tween(TRANSITION_MS))
            },
        ) { backStackEntry ->
            ReaderScreen(
                bookId = backStackEntry.arguments?.getLong("bookId") ?: 0L,
                initialAnchor = backStackEntry.arguments?.getLong("anchor") ?: -1L,
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                foldableUiState = foldableUiState,
            )
        }
        composable(
            route = Routes.SETTINGS,
            enterTransition = {
                slideInVertically(initialOffsetY = { it / 12 }, animationSpec = tween(TRANSITION_MS)) +
                    fadeIn(tween(TRANSITION_MS))
            },
            popExitTransition = {
                slideOutVertically(targetOffsetY = { it / 12 }, animationSpec = tween(TRANSITION_MS)) +
                    fadeOut(tween(TRANSITION_MS))
            },
        ) {
            SettingsScreen(foldableUiState)
        }
    }
}
