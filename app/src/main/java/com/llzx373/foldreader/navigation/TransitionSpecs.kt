package com.llzx373.foldreader.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.unit.IntOffset

// M3 Expressive 基调：全 App 转场采用弹簧物理（NavHost 与阅读页覆盖层共用同一组，
// 保证两侧动画同时起步、节奏一致）
internal val fadeSpring = spring<Float>(stiffness = Spring.StiffnessMediumLow)

internal val offsetSpring = spring(
    stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = IntOffset.VisibilityThreshold,
)

/**
 * 进书：纯淡入。
 *
 * 刻意**不**叠加整屏横向滑入。阅读页加载态里那张封面是共享元素，它会自己从书架格子
 * 飞向屏幕中央；如果外层再整体平移一整屏，封面就会被"共享元素插值"和"父布局平移"
 * 同时推着走——出现一股多余的运动（表现为画面从右侧滑入、封面却在中间乱飞）。
 * 空间关系由封面承担，画面本身淡入即可。
 */
internal val readerEnterTransition: EnterTransition = fadeIn(fadeSpring)

/** 退书：纯淡出，与进书对称（退出时封面不参与共享过渡，无需另一套运动）。 */
internal val readerExitTransition: ExitTransition = fadeOut(fadeSpring)

/**
 * 阅读页在 NavHost 中的占位转场：内容为空、不可见，唯一作用是**界定该 back stack entry
 * 的生命周期**——NavHost 在转场跑完时调用 `onTransitionComplete(entry)` 才把 entry 置为
 * DESTROYED。时长必须 ≥ [readerExitTransition] 的实际收敛时间（弹簧淡出 ~330ms），否则 entry
 * 先被销毁、ReaderOverlay 仍在组合退场内容，`viewModel()` 会访问已销毁 entry 的
 * ViewModelStore 而抛 IllegalStateException。
 */
private const val PLACEHOLDER_LIFETIME_MS = 700

internal val readerPlaceholderEnter: EnterTransition = fadeIn(tween(PLACEHOLDER_LIFETIME_MS))

internal val readerPlaceholderExit: ExitTransition = fadeOut(tween(PLACEHOLDER_LIFETIME_MS))
