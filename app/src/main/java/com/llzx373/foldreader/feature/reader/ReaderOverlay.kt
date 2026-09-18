package com.llzx373.foldreader.feature.reader

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.savedstate.compose.LocalSavedStateRegistryOwner
import com.llzx373.foldreader.core.foldable.FoldableUiState
import com.llzx373.foldreader.navigation.Routes
import com.llzx373.foldreader.navigation.fadeSpring
import com.llzx373.foldreader.navigation.readerEnterTransition
import com.llzx373.foldreader.navigation.readerExitTransition

/**
 * 阅读页全窗口覆盖层。
 *
 * 阅读页不放进外壳（`NavigationSuiteScaffold`）的 content 槽，原因是外壳在展开态用
 * `NavigationRail`，content 槽会被 rail 宽度挤压（rail 宽度 = 80dp + start 侧系统栏 inset）：
 *  - 进书时若把导航组件切走（`layoutType = None`），仍在外壳里的退场书架会整体左移；
 *  - 退书时若 inset 未落地，书架会被变宽的 rail 整体右推。
 *
 * 所以外壳形态固定，阅读页改由本覆盖层在整窗渲染（与书架同属一个
 * [SharedTransitionScope]，封面共享元素照常承接）。同时这里按 NavHost 的做法补上
 * per-entry 的 ViewModel/SavedState/Lifecycle owner——阅读页的 ViewModel 仍绑定在
 * 自己的 back stack entry 上（弹栈即 onCleared 落库阅读时长），rememberSaveable 状态
 * 也按 entry 隔离。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ReaderOverlay(
    navController: NavHostController,
    foldableUiState: FoldableUiState,
    coverTitle: String?,
    sharedTransitionScope: SharedTransitionScope,
    modifier: Modifier = Modifier,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val readerEntry = backStackEntry?.takeIf { it.destination.route == Routes.READER }
    val saveableStateHolder = rememberSaveableStateHolder()

    AnimatedContent(
        targetState = readerEntry,
        modifier = modifier.fillMaxSize(),
        transitionSpec = {
            if (targetState != null) {
                // 进书：自右滑入 + 淡入（书架的 scaleOut/fadeOut 退场由 NavHost 侧负责）
                readerEnterTransition.togetherWith(fadeOut(fadeSpring))
            } else {
                // 退书：向右滑出 + 淡出（书架随 popEnter 正常淡入）
                fadeIn(fadeSpring).togetherWith(readerExitTransition)
            }
        },
        // 同一本书/换书重进都不重放转场：只有"在阅读页 / 不在阅读页"两种内容
        contentKey = { it != null },
        label = "readerOverlay",
    ) { entry ->
        if (entry != null) {
            // 兜底：entry 被销毁后不再组合内容。NavHost 侧占位转场已保证销毁晚于退场动画，
            // 这里防止任何时序反转导致 viewModel() 访问已销毁 entry 的 ViewModelStore。
            val entryState by entry.lifecycle.currentStateFlow.collectAsState()
            if (entryState.isAtLeast(Lifecycle.State.CREATED)) {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides entry,
                    LocalLifecycleOwner provides entry,
                    LocalSavedStateRegistryOwner provides entry,
                ) {
                    saveableStateHolder.SaveableStateProvider(entry.id) {
                        ReaderHost(
                            bookId = entry.arguments?.getLong(Routes.ARG_BOOK_ID) ?: 0L,
                            initialAnchor = entry.arguments?.getLong(Routes.ARG_ANCHOR) ?: -1L,
                            onBack = { navController.popBackStack() },
                            onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                            foldableUiState = foldableUiState,
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = this,
                            coverTitle = coverTitle,
                            // 切到同系列的其它卷：替换掉当前阅读页，返回时回书架而不是退回上一卷
                            onOpenBook = { newBookId ->
                                navController.navigate(Routes.reader(newBookId)) {
                                    popUpTo(Routes.READER) { inclusive = true }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
