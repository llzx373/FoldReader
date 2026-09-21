# 001 — 给文本阅读器菜单补齐进出动画

- **Status**: IMPLEMENTED（待真机验证）
- **Commit**: a855cd3
- **Severity**: HIGH
- **Category**: Cohesion & tokens（与同 App 的漫画阅读器不一致 + 状态变化突兀）
- **Estimated scope**: 1 个文件（`ReaderScreen.kt`），约 20 行改动

## Problem

文本阅读器的顶栏与底部菜单用裸 `if (menuVisible)` 直接挂载/卸载，点击中间区时整块 chrome **瞬间出现、瞬间消失**，没有任何过渡。用户每次开合菜单都能看到这次跳变。

当前代码（`app/src/main/java/com/llzx373/foldreader/feature/reader/ReaderScreen.kt`，行 1513–1589）：

```kotlin
        if (menuVisible) {
            val position by viewModel.readingPosition.collectAsState()
            ReaderTopBar(
                bookTitle = uiState.bookTitle,
                chapterTitle = position.chapterTitle,
                colors = colors,
                onBack = { exit.leaveTo(onBack) },
                dualPage = uiState.dualPage,
                hasRightPage = uiState.spread?.right != null,
                leftBookmarked = uiState.spread?.left?.charStart in bookmarkedOffsets,
                rightBookmarked = uiState.spread?.right?.charStart in bookmarkedOffsets,
                onToggleBookmark = viewModel::toggleBookmark,
                onOpenBookmarks = { bookmarksVisible = true },
                onOpenSearch = { searchVisible = true },
                modifier = Modifier.align(Alignment.TopCenter),
            )
            ReaderMenuPanel(
                prefs = prefs,
                // …（略：参数很多，执行时原样保留）…
                onOpenSettings = { exit.leaveTo(onOpenSettings) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding(),
            )
        }
```

同一个 App 的漫画阅读器对**完全等价**的交互做了动画（`feature/comic/ComicReaderScreen.kt`，行 1267–1300）：

```kotlin
            AnimatedVisibility(
                visible = menuVisible,
                enter = fadeIn(tween(ANIM_MS)) + slideInVertically { -it / 3 },
                exit = fadeOut(tween(ANIM_MS)) + slideOutVertically { -it / 3 },
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                ComicTopBar(/* … */)
            }
            AnimatedVisibility(
                visible = menuVisible,
                enter = fadeIn(tween(ANIM_MS)) + slideInVertically { it / 3 },
                exit = fadeOut(tween(ANIM_MS)) + slideOutVertically { it / 3 },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                ComicMenuPanel(/* … */)
            }
```

两个阅读器是同一入口（`ReaderHost`）分派出来的兄弟界面，一个动一个不动，本身就是一致性问题。

## Target

把 `ReaderScreen.kt` 里那对 `ReaderTopBar` / `ReaderMenuPanel` 原样塞进两个 `AnimatedVisibility`，运动参数与漫画阅读器逐字对齐（顶栏从上方滑入、底栏从下方滑入，时长 220ms）。

在 `ReaderScreen.kt` 顶部常量区（现有 `private val INNER_SPINE_PAD` 附近）加入：

```kotlin
private const val MENU_ANIM_MS = 220
```

把行 1513–1589 的 `if (menuVisible) { … }` 整体替换为：

```kotlin
        AnimatedVisibility(
            visible = menuVisible,
            enter = fadeIn(tween(MENU_ANIM_MS)) + slideInVertically { -it / 3 },
            exit = fadeOut(tween(MENU_ANIM_MS)) + slideOutVertically { -it / 3 },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            val position by viewModel.readingPosition.collectAsState()
            ReaderTopBar(
                bookTitle = uiState.bookTitle,
                chapterTitle = position.chapterTitle,
                colors = colors,
                onBack = { exit.leaveTo(onBack) },
                dualPage = uiState.dualPage,
                hasRightPage = uiState.spread?.right != null,
                leftBookmarked = uiState.spread?.left?.charStart in bookmarkedOffsets,
                rightBookmarked = uiState.spread?.right?.charStart in bookmarkedOffsets,
                onToggleBookmark = viewModel::toggleBookmark,
                onOpenBookmarks = { bookmarksVisible = true },
                onOpenSearch = { searchVisible = true },
                // 注意：modifier 参数整个删掉，位置改由外层 AnimatedVisibility 的 modifier 决定
            )
        }
        AnimatedVisibility(
            visible = menuVisible,
            enter = fadeIn(tween(MENU_ANIM_MS)) + slideInVertically { it / 3 },
            exit = fadeOut(tween(MENU_ANIM_MS)) + slideOutVertically { it / 3 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        ) {
            val position by viewModel.readingPosition.collectAsState()
            ReaderMenuPanel(
                prefs = prefs,
                progressFraction = position.progressFraction,
                // …（其余参数与改动前逐字相同，原样保留）…
                onOpenSettings = { exit.leaveTo(onOpenSettings) },
                // 同样删掉这里的 modifier 参数
            )
        }
```

**必须注意的坑**：`val position by viewModel.readingPosition.collectAsState()` 原本就在 `if (menuVisible)` 里面，是**故意**的——`readingPosition` 每翻一页都会变，在阅读器顶层订阅会让整棵阅读树跟着重组（见 `ReaderScreen.kt:1843-1849` 中 `ReaderCornerChrome` 的 KDoc 说明）。因此**不要**把这个 `position` 提到 `AnimatedVisibility` 外面。按上面的写法，每个 `AnimatedVisibility` 的内容 lambda 各订阅一份（菜单关闭时内容不组合 → 不订阅；退场动画播完才卸载 → 退场期间仍可用）。同时开两份订阅只在菜单开启期间存在，开销可忽略。

新增 import（`ReaderScreen.kt` 顶部）：

```kotlin
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
```

`androidx.compose.animation.core.tween` 已在 `ReaderScreen.kt:26` 导入，无需重复。

## Repo conventions to follow

- 时长常量放在文件顶部 `private val/const val` 区（参照 `ComicReaderScreen.kt:152` 的 `private val ANIM_MS = 220`）。
- 菜单进出动画的既有范例就是 `ComicReaderScreen.kt:1267-1300`，本条指令就是让文本阅读器与它逐字对齐。
- `AnimatedVisibility` 同时会自然获得可中断性：连续快速开合时它会从当前值重定向，而不是每次都从零重启。

## Steps

1. 在 `ReaderScreen.kt` 常量区加入 `private const val MENU_ANIM_MS = 220`。
2. 补齐上面列出的 5 个 import。
3. 把行 1513–1589 的 `if (menuVisible) { … }` 整体替换为两个 `AnimatedVisibility`（内容与参数逐字保留，仅把各自的 `modifier` 参数搬到外层）。
4. 确认 `ReaderMenuPanel` 的 `navigationBarsPadding()` 现在挂在外层 `AnimatedVisibility` 上（底栏仍要避开导航栏），`ReaderTopBar` 的对齐改为 `Alignment.TopCenter`。

## Boundaries

- 只改 `ReaderScreen.kt`。**不要**动 `ComicReaderScreen.kt`（它的菜单动画已经是目标形态）。
- 不要改动 `ReaderTopBar` / `ReaderMenuPanel` 任何调用参数（只删它们自己的 `modifier`），否则可能触发本计划范围外的行为变化。
- 不要把 `readingPosition` 的订阅提到 `AnimatedVisibility` 之外（见 Problem 末的坑）。
- 不要引入新的时长 token 体系或改动 `navigation/TransitionSpecs.kt`——统一 token 是另一条 LOW 事项，不在此计划内。
- 不要顺带改 `catalogVisible` / `bookmarksVisible` 等对话框的显隐（它们是 Material `AlertDialog`，自带动画）。
- 若发现行号/代码与 commit a855cd3 不一致（已漂移），**停下并报告**，不要自行发挥。

## Verification

- **Mechanical**: `./gradlew :app:compileDebugKotlin`（或 `:app:assembleDebug`）通过；`./gradlew :app:testDebugUnitTest` 全绿（本改动不涉及单测，但确认无编译期回归）；`./gradlew :app:lintDebug` 无新增警告。
- **Feel check**（真机或模拟器，打开一本 TXT）：
  - 点屏幕中间 → 顶栏从上方滑入、底栏从下方滑入，同时淡入，约 0.2s，**不再闪现**。
  - 再点一次 → 两者沿原路滑出，路径与进入对称。
  - 高频连点中间区：动画应始终从**当前**位置继续，不出现"先弹回起点再重放"的抖动。
  - 翻页过程中开合菜单，确认菜单与正文互不干扰，且正文不因菜单动画而重排/闪烁。
  - 把系统「开发者选项 → 动画时长缩放」设为 0：菜单应立刻出现/消失（Compose 自动遵循该设置），行为仍正确。
- **Done when**: 菜单显隐有平滑过渡且与漫画阅读器的节奏一致；不再出现瞬时跳变；翻页期间不因该动画掉帧。
