package com.llzx373.foldreader.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntOffset

// M3 Expressive 基调：全 App 转场采用弹簧物理（NavHost 与阅读页覆盖层共用同一组，
// 保证两侧动画同时起步、节奏一致）
internal val fadeSpring = spring<Float>(stiffness = Spring.StiffnessMediumLow)

internal val offsetSpring = spring(
    stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = IntOffset.VisibilityThreshold,
)

/** 进书：自右滑入 + 淡入（ReaderOverlay 的实际画面转场） */
internal val readerEnterTransition: EnterTransition =
    slideInHorizontally(initialOffsetX = { it }, animationSpec = offsetSpring) + fadeIn(fadeSpring)

/** 退书：向右滑出 + 淡出（ReaderOverlay 的实际画面转场） */
internal val readerExitTransition: ExitTransition =
    slideOutHorizontally(targetOffsetX = { it }, animationSpec = offsetSpring) + fadeOut(fadeSpring)

/**
 * 阅读页在 NavHost 中的占位转场：内容为空、不可见，唯一作用是**界定该 back stack entry
 * 的生命周期**——NavHost 在转场跑完时调用 `onTransitionComplete(entry)` 才把 entry 置为
 * DESTROYED。时长必须 ≥ [readerExitTransition] 的实际收敛时间（弹簧 ~420ms），否则 entry
 * 先被销毁、ReaderOverlay 仍在组合退场内容，`viewModel()` 会访问已销毁 entry 的
 * ViewModelStore 而抛 IllegalStateException。
 */
private const val PLACEHOLDER_LIFETIME_MS = 700

internal val readerPlaceholderEnter: EnterTransition = fadeIn(tween(PLACEHOLDER_LIFETIME_MS))

internal val readerPlaceholderExit: ExitTransition = fadeOut(tween(PLACEHOLDER_LIFETIME_MS))
