# 002 — 翻页动画进行中不再吞掉后续翻页输入

- **Status**: IMPLEMENTED（待真机验证）
- **Commit**: a855cd3
- **Severity**: HIGH
- **Category**: Interruptibility
- **Estimated scope**: 2 个文件（`ReaderScreen.kt`、`ComicReaderScreen.kt`），每个约 10 行

## Problem

翻页与其它正在跑的动画互斥，用的是"直接丢弃"的写法：动画在跑时，下一次 `turn()` 调用立即 `return`，这次点击**凭空消失**。阅读本身就是"点、点、点"，而覆盖翻页有 220ms、仿真掀页有 400ms 的窗口，窗口内的连点一律不生效。

`app/src/main/java/com/llzx373/foldreader/feature/reader/ReaderScreen.kt`，行 465–468：

```kotlin
    fun turn(forward: Boolean) {
        clearSelection()
        scope.launch {
            if (animSpread != null || peel.busy) return@launch
            val target = viewModel.adjacentSpread(forward) ?: return@launch
```

`app/src/main/java/com/llzx373/foldreader/feature/comic/ComicReaderScreen.kt`（`turn` 开头）：

```kotlin
    fun turn(forward: Boolean, slideFromRight: Boolean = comicSlideFromRight(forward, rtl)) {
        scope.launch {
            if (animPages != null || peel.busy) return@launch
            val state = uiState
            val target = if (forward) spreadIndex.next(state.pageIndex)
            else spreadIndex.previous(state.pageIndex)
```

这是两个技能的"最高原则"都点名的问题：Apple《Designing Fluid Interfaces》——"Never lock out input during a transition"；Emil 的决策框架——动画绝不能把用户的输入吃掉。

## Target

用 `Mutex` 把翻页动作**串行化**，而不是丢弃：动画运行期间到来的点击会在锁上排队，前一次动画结束后立刻按顺序执行。这样每次点击仍然等于翻一页，且顺序不变。

对 `ReaderScreen.kt`：

```kotlin
    val turnMutex = remember { Mutex() }

    fun turn(forward: Boolean) {
        clearSelection()
        scope.launch {
            turnMutex.withLock {
                val target = viewModel.adjacentSpread(forward) ?: return@withLock
                if (rawMode == PageTurnMode.NONE || size.width <= 0) {
                    viewModel.showSpread(target, countCharsRead = true)
                    return@withLock
                }
                // …（其余正文完全不变，只是把原来的 return@launch 改成 return@withLock）…
            }
        }
    }
```

对 `ComicReaderScreen.kt` 同样处理（用各自的 `remember { Mutex() }`，`return@launch` → `return@withLock`）。

关键约束，务必遵守：

1. **`clearSelection()` 留在锁外**（点击要立刻作用于选区，不能被排队）。
2. **`viewModel.adjacentSpread(forward)` / `spreadIndex.next(...)` 必须留在锁内**。如果在锁外先算好 target，排队中的多次点击会各自算一次、却都基于同一份旧状态 → 连点 N 次只前进 1 页。放进锁内，后一次才能读到前一次 `showSpread` 之后的状态，连点 N 次正确前进 N 页。
3. 删掉 `animSpread != null || peel.busy` / `animPages != null || peel.busy` 这道守卫（`Mutex` 已保证同一时刻只有一个翻页体在跑）。拖动掀页路径（`latestBeginPeelDrag`）不在锁内设置 `peel.busy`，所以排队中的翻页会等这次拖动掀页结束后再执行——这正是期望行为，不要额外加逻辑去打断它。

新增 import（两个文件各一份）：

```kotlin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
```

## Repo conventions to follow

- 已有的互斥/单飞写法可参考仓库里对并发的处理风格（`scope.launch { … }` + 状态守卫）。本计划只是把"守卫丢弃"换成"锁排队"，不改动任何动画本身。
- 自动翻页走的是 `latestTurn`（同一入口，见 `ReaderScreen.kt:517`、`ComicReaderScreen.kt` 的 `latestTurn`），因此也会一起被串行化——自动翻页与手动翻页不会再互相插队，这是期望结果。

## Steps

1. 在 `ReaderScreen.kt` 中加入 `val turnMutex = remember { Mutex() }`（放在 `scope`/`peel` 等 `remember` 附近，保证跨重组稳定）。
2. 把 `turn()` 的协程体包进 `turnMutex.withLock { … }`，删掉守卫，把函数体内的 `return@launch` 改为 `return@withLock`。
3. 在 `ComicReaderScreen.kt` 中做同样改动（注意它的 `turn` 只有一个 `return@launch` 守卫在顶部）。
4. 两个文件各补 `Mutex` / `withLock` 两个 import。

## Boundaries

- 只改这两个 `turn()` 函数与各自的 import。不要动 `PeelController`、`Animatable`、`tween` 时长，也不要动拖动/抬手路径。
- 不要"顺手"加节流/防抖（如 `debounce`、时间窗）——那会重新引入丢输入。
- 不要限制队列长度。连点 10 次就翻 10 页是**预期**且可预测的行为；不要加最大排队计数。
- 不要改 `MiddleTapLayer`、手势 `pointerInput` 的 key。
- 若发现代码与 commit a855cd3 不一致，**停下并报告**，不要自行发挥。

## Verification

- **Mechanical**: `./gradlew :app:assembleDebug` 通过；`./gradlew :app:testDebugUnitTest` 全绿（`PeelTurnTest`、`ReaderLogicTest`、`ComicLogicTest` 不应受影响）。
- **Feel check**（真机，TXT 与漫画各一本）：
  - 快速连点"下一页" 5 次：应**恰好**前进 5 页（改之前会少于 5 页）。
  - 覆盖翻页模式下连点：每一页都应完整播完覆盖动画，页序不跳、不乱。
  - 仿真掀页模式下连点：每次掀起都完整播放，顺序正确，不出现漏页或多翻。
  - 点"上一页"与"下一页"交替快速点击：最终落点应等于点击序列的净效果。
  - 手动翻页进行中让自动翻页触发一次：两者不叠加、不互抢，表现为依次执行。
  - 拖动手势掀页（手指按住）期间不应有排队翻页抢先执行。
  - 开 `动画时长缩放 = 0` 后连点：每次点击仍各翻一页（动画瞬时，锁不造成可见延迟）。
- **Done when**: 连点 N 次得到 N 次翻页，且无乱序、无重复、无卡顿。
