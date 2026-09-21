# 004 — 书架返回时镜像出程（补 scaleIn）

- **Status**: IMPLEMENTED（待真机验证）
- **Commit**: a855cd3
- **Severity**: MEDIUM
- **Category**: Physicality & origin / 空间一致性
- **Estimated scope**: 1 个文件（`FoldReaderNavHost.kt`），1 行 + 1 个 import

> **依赖**：本计划修改的 `composable` 与 **003** 是同一个。请先执行并合入 003，再执行 004。若 003 未做，`popEnterTransition` 的行号/上下文可能不同。

## Problem

打开一本书时书架是**缩小并淡出**退场的，但返回书架时它只是**淡入**、不放大回来——反向没有镜像出程。结果是一个"缩着消失、原地凭空出现"的跳变，空间关系不成立。

`app/src/main/java/com/llzx373/foldreader/navigation/FoldReaderNavHost.kt`，当前代码：

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

注意第 48 行的注释写着「返回时反向」，但 `popEnterTransition` 只有 `fadeIn`，并**没有**反向的缩放——注释与实现不符，说明这是漏做而不是有意为之。

Apple《Designing Fluid Interfaces》的原则：可逆转场要让去程与回程镜像（"If something disappears one way, we expect it to emerge from where it came"）。这里去向是 `scaleOut(0.94)`，回程就应是 `scaleIn(0.94)`。

## Target

给 `popEnterTransition` 加上 `scaleIn`，与 `exitTransition` 的 `scaleOut(0.94f)` 严格对称：

```kotlin
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
```

新增 import：

```kotlin
import androidx.compose.animation.scaleIn
```

**关键**：`initialScale` 必须显式写成 `0.94f`。`scaleIn()` 的默认 `initialScale = 0f`——从 0 缩放进场是"凭空出现"（现实里没有东西从零体积长出来），也正是本计划要修的同一类问题。这里必须与 `scaleOut` 的 `targetScale = 0.94f` 取同一个值，两个方向才咬合。

## Repo conventions to follow

- 缩放幅度与曲线沿用同一个 `composable` 里 `scaleOut` 的既有取值（`0.94f` + `fadeSpring`），不引入新数值。
- `fadeSpring` 定义在 `navigation/TransitionSpecs.kt:17`，与所有其它转场同源。

## Steps

1. 在 `FoldReaderNavHost.kt` 顶部加入 `import androidx.compose.animation.scaleIn`（按现有 import 的字母序，放在 `scaleOut` 附近）。
2. 把 `BOOKSHELF` 的 `popEnterTransition = { fadeIn(fadeSpring) }` 替换为上面的 `scaleIn(initialScale = 0.94f, animationSpec = fadeSpring) + fadeIn(fadeSpring)` 形式。

## Boundaries

- 只改 `BOOKSHELF` 的 `popEnterTransition` 这一行，外加一个 import。
- 不要改 `exitTransition` 的 `scaleOut(0.94f)` 取值（0.94 是既有约定）。
- 不要用 `scaleIn()` 默认参数（`initialScale = 0f`）。
- 不要改 `FILE_BROWSER` / `SETTINGS` / `READER` 的任何转场。
- 若已按 003 改动，`popEnterTransition` 的上下文会带上 `enterTransition` / `popExitTransition`；以实际代码为准，只替换 `popEnterTransition` 的取值。
- 若代码与 commit a855cd3 + 003 的结果不一致，**停下并报告**。

## Verification

- **Mechanical**: `./gradlew :app:assembleDebug` 通过；`./gradlew :app:testDebugUnitTest` 全绿。
- **Feel check**（真机）：
  - 打开一本书（进书）：书架缩小 + 淡出。
  - 从阅读页返回书架：书架应从 0.94 放大回 1.0，同时淡入——去程与回程看起来是同一段动画的正放/倒放。
  - 反复进书 / 退书多次：不应出现"凭空冒出来"或先大后小的闪动。
  - 同样检查从**漫画 / PDF** 阅读页返回（走同一条书架转场）。
  - 慢放（动画时长缩放 5x）确认缩放与淡入在**同一时间轴**上同步收敛，不是先后两段。
- **Done when**: 返回书架的放大与进书时的缩小在幅度、曲线、时长上完全对称。
