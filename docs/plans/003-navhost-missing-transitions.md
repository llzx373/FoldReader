# 003 — 补齐 NavHost 缺失的转场，消除 700ms 默认淡入淡出

- **Status**: IMPLEMENTED（待真机验证）
- **Commit**: a855cd3
- **Severity**: MEDIUM
- **Category**: Easing & duration
- **Estimated scope**: 1 个文件（`FoldReaderNavHost.kt`），约 10 行

## Problem

`app/src/main/java/com/llzx373/foldreader/navigation/FoldReaderNavHost.kt` 里，三个顶层目的地的转场只填了**一半的槽位**：

- `BOOKSHELF` 只定义了 `exitTransition` 与 `popEnterTransition`（行 46–55）
- `FILE_BROWSER` 只定义了 `enterTransition` 与 `popExitTransition`（行 82–93）
- `SETTINGS` 只定义了 `enterTransition` 与 `popExitTransition`（行 95–105）

没填的槽位会回落到 Compose Navigation 的默认值 `fadeIn(animationSpec = tween(700))` / `fadeOut(animationSpec = tween(700))`。也就是说，**底部导航切换 Tab 的若干方向会走 700ms 的整屏交叉淡入淡出**，是 App 自身节奏（220–420ms）的两倍多，而且与相邻方向（滑动 + 弹簧）不是一套语言。具体受影响的槽位：

| 目的地 | 未定义的槽位 | 默认值 | 触发场景 |
| --- | --- | --- | --- |
| BOOKSHELF | `enterTransition` | `fadeIn(tween(700))` | 从「浏览 / 设置」切回书架 |
| FILE_BROWSER | `exitTransition` | `fadeOut(tween(700))` | 从「浏览」切到别的 Tab |
| FILE_BROWSER | `popEnterTransition` | `fadeIn(tween(700))` | 返回「浏览」 |
| SETTINGS | `exitTransition` | `fadeOut(tween(700))` | 从「设置」切到别的 Tab |
| SETTINGS | `popEnterTransition` | `fadeIn(tween(700))` | 返回「设置」 |

当前代码（行 46–55）：

```kotlin
        composable(
            route = Routes.BOOKSHELF,
            // 书架退场：缩放淡出；返回时反向
            exitTransition = {
                scaleOut(targetScale = 0.94f, animationSpec = fadeSpring) +
                    fadeOut(fadeSpring)
            },
            popEnterTransition = { fadeIn(fadeSpring) },
        ) {
```

浏览 / 设置（行 82–105，两处同构）：

```kotlin
        composable(
            route = Routes.FILE_BROWSER,
            enterTransition = {
                slideInVertically(initialOffsetY = { it / 12 }, animationSpec = offsetSpring) +
                    fadeIn(fadeSpring)
            },
            popExitTransition = {
                slideOutVertically(targetOffsetY = { it / 12 }, animationSpec = offsetSpring) +
                    fadeOut(fadeSpring)
            },
        ) {
```

## Target

只补上缺的槽位，全部复用该文件已有的 token（`fadeSpring` / `offsetSpring`，定义在 `navigation/TransitionSpecs.kt:17-22`），不要新造曲线或时长。

横向的 Tab 切换用纯弹簧淡入淡出（不滑动）——同级的平级切换不该暗示"深度/前进后退"，淡入淡出是最中性的选择，也避免和竖向进入（浏览 / 设置自下方滑入）撞在一起造成"两个东西朝同方向动"的忙乱感。

`Routes.BOOKSHELF` 补两个槽位：

```kotlin
        composable(
            route = Routes.BOOKSHELF,
            enterTransition = { fadeIn(fadeSpring) },
            exitTransition = {
                scaleOut(targetScale = 0.94f, animationSpec = fadeSpring) +
                    fadeOut(fadeSpring)
            },
            popEnterTransition = { fadeIn(fadeSpring) },
            popExitTransition = { fadeOut(fadeSpring) },
        ) {
```

`Routes.FILE_BROWSER` 与 `Routes.SETTINGS` 各补两个槽位（两处同构）：

```kotlin
        composable(
            route = Routes.FILE_BROWSER,
            enterTransition = {
                slideInVertically(initialOffsetY = { it / 12 }, animationSpec = offsetSpring) +
                    fadeIn(fadeSpring)
            },
            exitTransition = { fadeOut(fadeSpring) },
            popEnterTransition = { fadeIn(fadeSpring) },
            popExitTransition = {
                slideOutVertically(targetOffsetY = { it / 12 }, animationSpec = offsetSpring) +
                    fadeOut(fadeSpring)
            },
        ) {
```

`Routes.READER` 的占位转场（`readerPlaceholderEnter/Exit`）**不要动**——它对整窗阅读页的生命周期有硬约束（见该文件顶部注释与 `TransitionSpecs.kt` 的 `PLACEHOLDER_LIFETIME_MS`）。

## Repo conventions to follow

- 4 个转场 slot 一个不落地写全，是本文件对「阅读页占位」之外的目的地应有的写法。
- 曲线/时长一律取自 `navigation/TransitionSpecs.kt` 的既有 token；该文件已明确是全 App 转场的唯一来源（注释：「NavHost 与阅读页覆盖层共用同一组，保证两侧动画同时起步、节奏一致」）。
- `fadeOut(fadeSpring)` / `fadeIn(fadeSpring)` 是弹簧（临界阻尼、无过冲），与其它转场同一物理语言。

## Steps

1. 在 `BOOKSHELF` 的 `composable(...)` 中：在 `exitTransition` 之前加一行 `enterTransition = { fadeIn(fadeSpring) },`；在 `popEnterTransition` 之后加一行 `popExitTransition = { fadeOut(fadeSpring) },`。
2. 在 `FILE_BROWSER` 的 `composable(...)` 中：在 `enterTransition` 之后加 `exitTransition = { fadeOut(fadeSpring) },`；在 `popExitTransition` 之前加 `popEnterTransition = { fadeIn(fadeSpring) },`。
3. 对 `SETTINGS` 重复第 2 步（两处结构完全相同）。
4. 不新增 import（`fadeIn` / `fadeOut` 已在该文件导入）。

## Boundaries

- 只改 `FoldReaderNavHost.kt`，且只增补齐缺槽位，不改已有槽位的取值。
- **不要**动 `Routes.READER` 的 `enterTransition` / `exitTransition` / `popEnterTransition` / `popExitTransition`（占位转场的时长是生命周期契约的一部分）。
- 不要改 `TransitionSpecs.kt` 里的弹簧参数。
- 本计划与 **004** 都会改 `BOOKSHELF` 那个 `composable`：请**先执行 003，再执行 004**（004 只改 `popEnterTransition` 一行）。
- 不要顺手把 Tab 切换改成"无动画"——那属于另一个决策，不在本计划内。
- 若代码与 commit a855cd3 不一致，**停下并报告**。

## Verification

- **Mechanical**: `./gradlew :app:assembleDebug` 通过；`./gradlew :app:testDebugUnitTest` 全绿。
- **Feel check**（真机，底部导航三个 Tab 来回切）：
  - 「书架 → 设置 → 浏览 → 书架」走一圈：**没有**任何一段是慢吞吞的整屏淡入淡出；每段过渡大约 0.2–0.4s。
  - 从「浏览 / 设置」切回书架：书架是快速淡入，不再是 ~0.7s 的慢淡入。
  - 从「设置」切到「浏览」：设置退出是淡出（不滑动），浏览自下方滑入，两者不打架。
  - 按返回键从「设置 / 浏览」回书架：仍是自下而下滑出 + 淡出（`popExitTransition` 未改，应保持不变）。
  - 进书 / 退书（书架 ↔ 阅读）的过渡应与改动前完全一致，无回归。
  - 在慢放（动画时长缩放设为 5x 或 10x）下确认每个方向都无跳变、淡入淡出与滑动同步。
- **Done when**: 所有顶层导航方向都有过渡，且没有任何一段再使用 700ms 默认时长。
