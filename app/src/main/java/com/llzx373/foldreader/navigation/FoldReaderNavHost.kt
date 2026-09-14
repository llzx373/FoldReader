package com.llzx373.foldreader.navigation

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
        composable(Routes.BOOKSHELF) {
            BookshelfScreen(
                foldableUiState = foldableUiState,
                onOpenBook = { bookId -> navController.navigate(Routes.reader(bookId)) },
            )
        }
        composable(
            route = Routes.READER,
            arguments = listOf(navArgument("bookId") { type = NavType.LongType }),
        ) { backStackEntry ->
            ReaderScreen(
                bookId = backStackEntry.arguments?.getLong("bookId") ?: 0L,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(foldableUiState)
        }
    }
}
