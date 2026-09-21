# 007 — 修掉仿真掀页的"起步 / 抬手"竞态（横滑漏翻 + 卡在半掀）

- **Status**: IMPLEMENTED（待真机验证）
- **Commit**: a855cd3
- **Severity**: HIGH（横滑是主交互；症状是漏翻 + 掀页永久卡死）
- **Category**: Interruptibility / 手势时序
- **Estimated scope**: 2 个文件（`ReaderScreen.kt`、`ComicReaderScreen.kt`），每个约 20 行

## Problem（真机复现，已定位）

仿真翻页模式下横滑，**偶尔**出现两种症状：

1. 滑了但**没翻页**；
2. 掀页**卡在某个位置不动**，之后必须再点一下（或再滑一次）才继续。

根因是掀页的"起步"与"抬手"之间存在竞态。

`ReaderScreen.kt` / `ComicReaderScreen.kt` 中，横滑的第一个事件把起步丢进协程：

```kotlin
    val latestBeginPeelDrag by rememberUpdatedState<(Boolean, Offset) -> Unit> { dragForward, pos ->
        scope.launch {
            ...
            if (!preparePeelBitmaps(dragForward, target, leaf)) { peelTarget = null; return@launch }
            if (peelGeneration != gen) return@launch
            peel.beginDrag(dragForward, corner, leaf, local, opposite)   // ← phase 到这里才变成 Drag
        }
    }
```

`preparePeelBitmaps` 是**异步且可能很慢**的：它在 `Dispatchers.Default` 上渲染 2–3 张整页位图；漫画侧还要 `awaitPage(index, timeoutMs = 800L)` 等解码，最长 800ms。

而抬手是独立回调，只检查 `phase`：

```kotlin
                        onDragEnd = {
                            ...
                            scope.launch {
                                if (peel.phase != PeelPhase.Drag) {   // ← 起步还没落地就是 null
                                    started = false
                                    return@launch                     // ← 直接放弃
                                }
                                val committed = peel.endDrag(vx, vy, distanceThreshold)
                                ...
                            }
                        },
```

于是：**抬手早于起步落地**时，`onDragEnd` 看到 `phase == null` 直接返回（这一次翻页被丢掉）；随后起步协程才把 `phase` 置为 `Drag`，而**再也没有人会调 `endDrag`** —— 掀页从此永久停在半掀状态。

这也解释了为什么"再点一下能继续"：点按走 `turn()`，而 `turn()` 在 002 里已经去掉了 `peel.busy` 守卫，于是 `peel.autoPlay()` 会把卡住的掀页顶走。**在此之前**（002 之前）`turn()` 有 `if (… || peel.busy) return`，卡住之后点按也没用，只能靠切几何（折叠/旋转触发 `LaunchedEffect(currentGeom)` 里的 `peel.reset()`）脱困。

`onDragCancel` 有**同一个**问题，而且更隐蔽：它先 `peel.reset()`，如果起步随后才落地，会重新把 `phase` 置为 `Drag` → 同样卡死。

## Target

让**抬手 / 取消等起步落地**，而不是撞上就放弃。起步函数改为返回 `Job`，调用方在判定前 `join()`。

`latestBeginPeelDrag`（两个文件）：

```kotlin
    val latestBeginPeelDrag by rememberUpdatedState<(Boolean, Offset) -> Job> { dragForward, pos ->
        scope.launch {
            ...                                   // 函数体完全不变
            peel.beginDrag(dragForward, corner, leaf, local, opposite)
        }
    }
```

横滑块内新增一个**手势局部**的 Job 引用，并在抬手 / 取消时先 join：

```kotlin
                var beginJob: Job? = null                  // 本次手势的起步协程
                detectHorizontalDragGestures(
                    onDragStart = {
                        ...
                        beginJob = null
                    },
                    onHorizontalDrag = { change, delta ->
                        ...
                        if (!started) {
                            started = true
                            dragForward = dragged < 0f
                            beginJob = latestBeginPeelDrag(dragForward, change.position)
                        } else if (peel.phase == PeelPhase.Drag) {
                            peel.updateDrag(latestPeelDragLocal(change.position))
                        }
                    },
                    onDragEnd = {
                        ...
                        val job = beginJob                  // 先抓本地再置空：新一次手势会覆盖它
                        beginJob = null
                        scope.launch {
                            job?.join()                     // ← 等起步落地再判定
                            if (peel.phase != PeelPhase.Drag) { started = false; return@launch }
                            ...                             // 其余完全不变
                        }
                    },
                    onDragCancel = {
                        ...
                        val job = beginJob
                        beginJob = null
                        scope.launch {
                            job?.join()                     // ← 取消同样必须等
                            if (peel.phase == PeelPhase.Drag) peel.endDrag(0f, 0f)
                            ...                             // 其余完全不变
                        }
                    },
                )
```

新增 import：`kotlinx.coroutines.Job`（两个文件）。

关键点：

- **`job` 必须在置空 `beginJob` 之前抓进本地**。新的手势会覆盖 `beginJob`，如果收尾时再去读字段，会等到别人的起步。
- `join()` 对手势起步失败（`adjacentSpread` 返回 null / 位图准备失败 / `peelGeneration` 被顶掉）同样安全：job 正常结束、`phase` 仍不是 `Drag`，走原来的"放弃"分支。
- 全部回调都在 pointer 事件线程上串行执行，`beginJob` 这个局部 `var` 没有数据竞争。
- `pointerInput` 的 key 变化会重建该块，`beginJob` 随之归零——手势进行中 key 不会变。

## Repo conventions to follow

- 保持既有写法：协程里逐帧/逐手势用局部 `var`，跨重组的值用 `rememberUpdatedState`（本改动沿用）。
- 不改 `PeelController` 的状态机与可见行为；这只是让"抬手"不再抢在"起步"之前。

## Boundaries

- 只改两个阅读器的横滑 **SIMULATION 分支** 与 `latestBeginPeelDrag` 的返回类型 + 注释；覆盖翻页（COVER）分支不涉及此竞态，不要动。
- 不要改动 `preparePeelBitmaps` / `PeelController.endDrag` / `beginDrag` 的实现。
- 不要把步骤改成"抬手时重新开始一次自动掀页"（`latestTurn`）——那会绕过 `shouldCompletePeel` 的距离/甩速判据，让短滑也翻页。
- 不要在 SIMULATION 分支补 `change.consume()`（与覆盖分支的差异是既有行为，属另一件事）。

## Verification

- **Mechanical**: `./gradlew :app:compileDebugKotlin`、`:app:testDebugUnitTest` 通过（已通过）。
- **Feel check**（真机，翻页方式=仿真，TXT 与漫画各一本）：
  - 连续快速横滑 20 次以上（刻意滑得又快又短）：**每一次都应翻页**，不应出现"滑了没反应"。
  - 同样的连滑中，页面**不应**停在半掀状态；不再需要额外点按。
  - 漫画侧尤其要测（`awaitPage` 最慢）：连滑大图页、未解码页，确认无卡死。
  - 滑到一半反悔、缓慢拖回松手：应干净回缩，不留半掀。
  - 手指按下后**不移动**直接抬起（可能触发 `onDragCancel`）：不应卡住。
  - 横滑与竖向亮度手势交替使用：互不干扰。
- **Done when**: 连滑 N 次全部生效且无卡死。
