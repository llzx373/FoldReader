# 006 — 修掉进书时"整屏滑入"与"封面共享元素"的双重运动

- **Status**: IMPLEMENTED（待真机验证）
- **Commit**: a855cd3
- **Severity**: MEDIUM
- **Category**: Physicality & origin / 空间一致性（叠加运动）
- **Estimated scope**: 2 个文件（`TransitionSpecs.kt`、`ReaderOverlay.kt`），约 6 行 + 注释

## Problem（已确认存在）

进书时，阅读页外层被赋予了一个**整屏横向滑入**：

`app/src/main/java/com/llzx373/foldreader/navigation/TransitionSpecs.kt`（改动前）：

```kotlin
/** 进书：自右滑入 + 淡入（ReaderOverlay 的实际画面转场） */
internal val readerEnterTransition: EnterTransition =
    slideInHorizontally(initialOffsetX = { it }, animationSpec = offsetSpring) + fadeIn(fadeSpring)

/** 退书：向右滑出 + 淡出（ReaderOverlay 的实际画面转场） */
internal val readerExitTransition: ExitTransition =
    slideOutHorizontally(targetOffsetX = { it }, animationSpec = offsetSpring) + fadeOut(fadeSpring)
```

而阅读页承载的那张封面是一个**共享元素**（`ReaderScreen.kt` / `ComicReaderScreen.kt`）：

```kotlin
BookCover(
    title = coverTitle ?: uiState.bookTitle,
    modifier = Modifier
        .then(
            if (uiState.loading) {
                Modifier.sharedElement(
                    sharedContentState = rememberSharedContentState(key = "cover-$bookId"),
                    animatedVisibilityScope = animScope,
                )
            } else {
                Modifier
            },
        )
        .width(160.dp)
        .graphicsLayer { alpha = coverAlpha },
)
```

共享元素会自己从书架格子飞向屏幕中央（源/目标 bounds 由 `SharedTransitionScope` 插值）。**同时**外层 `AnimatedContent` 又把整块内容平移了一整屏宽，于是同一次进书里出现了三股运动叠加：

1. 封面共享元素从书架格 → 屏幕中央的位移（期望的、也是唯一有用的）
2. 阅读页整屏自右滑入（`slideInHorizontally(initialOffsetX = { it })`，`it` = 整屏宽）
3. 书架 `scaleOut(0.94) + fadeOut` 退场

第 2 条是多余的：封面已经在解释"这本书从哪来"，画面本身再滑一屏只是噪声，观感上就是"画面从右侧滑入、封面却在中间自己飞"。

## Target（已实现）

### 1. 阅读页转场改为纯淡入淡出

`TransitionSpecs.kt`：

```kotlin
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
```

随之 `slideInHorizontally` / `slideOutHorizontally` 两个 import 不再使用，已删除（`offsetSpring` 仍被 `FILE_BROWSER` / `SETTINGS` 使用，保留）。

### 2. 占位时长注释同步

`PLACEHOLDER_LIFETIME_MS = 700` 的取值不变（仍 ≥ 退场收敛时间），只把注释里的"弹簧 ~420ms"更正为"弹簧淡出 ~330ms"。

### 3. `ReaderOverlay` 过时注释

```kotlin
            if (targetState != null) {
                // 进书：纯淡入（封面共享元素负责空间关系；书架退场由 NavHost 侧负责）
                readerEnterTransition.togetherWith(fadeOut(fadeSpring))
            } else {
                // 退书：纯淡出（书架随 popEnter 正常淡入）
                fadeIn(fadeSpring).togetherWith(readerExitTransition)
            }
```

退场也一并去掉横向滑出：进书已经是淡入，退场再滑出会变成不对称的可逆转场（Apple《Designing Fluid Interfaces》：去程与回程应当镜像）。且退出时封面本就**不**参与共享过渡（见 `ReaderScreen.kt` 中的说明：退出时不注册 `sharedElement`，避免大封面闪现），所以退场也没有需要"让位"的共享元素，纯淡出即最干净。

## 保留未动

- 书架的 `scaleOut(0.94f) + fadeOut` 退场**保留**。它是 6% 的细微后撤，与整屏滑动不是一个量级；去掉它会让"书本从书架上被抽走"的感觉消失。若真机上仍觉得封面飞行被书架缩放干扰，下一步可以再单独去掉 `scaleOut`（不在本次范围）。
- `ReaderScreen.kt` / `ComicReaderScreen.kt` 里 `coverAlpha` 的 `spring(stiffness = Spring.StiffnessMediumLow)` 淡出**保留**（加载完成后封面淡出是预期行为）。

## Verification

- **Mechanical**: `./gradlew :app:compileDebugKotlin` 通过（已通过）。
- **Feel check**（真机）：
  - 从书架点一本**未读**的书（会走加载态、封面共享元素生效）：应看到封面从书架格子飞到屏幕中央、同时放大，背景**直接淡入**，不再有整屏自右滑入。
  - 快速连点两本书：封面飞行不应与画面滑动打架（不出现封面"乱飞"或双重位移）。
  - 返回书架：纯淡出 + 书架 `scaleIn(0.94→1)`。
  - 打开一本**已读**的书（加载极快、封面共享元素一闪而过）：不应出现横向滑动的残影。
  - 慢放（开发者选项 → 动画时长缩放 5x）确认进书时**只有**封面在位移，其余是淡入淡出。
- **Done when**: 进书只剩"封面飞行 + 淡入淡出"，没有整屏横向平移。
