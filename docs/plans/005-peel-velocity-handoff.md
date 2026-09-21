# 005 — 仿真翻页抬手后继承手指速度（消除离手"缝"）

- **Status**: IMPLEMENTED（待真机验证）
- **Commit**: a855cd3
- **Severity**: MEDIUM
- **Category**: Interruptibility / 速度交接（velocity handoff）
- **Estimated scope**: 1 个文件（`PeelTurn.kt`），约 15 行

## Problem

仿真掀页在抬手之后，用**固定时长的 `tween`** 把触点送到终点：

`app/src/main/java/com/llzx373/foldreader/feature/reader/peel/PeelTurn.kt`，行 128–141：

```kotlin
        val (p0, _, p2) = autoPlayPathPoints(corner, leaf.width, leaf.height, oppositeWidth)
        touchX.snapTo(current.x)
        touchY.snapTo(current.y)
        dragTouch = null
        return if (complete) {
            phase = PeelPhase.Complete
            animateTouch(p2, PEEL_AUTO_MS)
            true
        } else {
            phase = PeelPhase.Cancel
            animateTouch(p0, PEEL_CANCEL_MS)
            reset()
            false
        }
    }
```

行 162–168：

```kotlin
    private suspend fun animateTouch(target: Offset, durationMs: Int) {
        val spec = tween<Float>(durationMs, easing = FastOutSlowInEasing)
        coroutineScope {
            launch { touchX.animateTo(target.x, spec) }
            launch { touchY.animateTo(target.y, spec) }
        }
    }
```

抬手速度 `velocityX / velocityY` 虽然在 `endDrag(...)` 里被用来决定"过不过线"（`shouldCompletePeel`，阈值 `PEEL_FLING_PX_PER_SEC = 800`），但**一旦判定要翻，动画本身并不使用这个速度**：轻甩一下和慢慢拖到位，尾段都是同样 400ms 的固定曲线。手指离手的那一刻速度被丢掉，动画从零速重新起步——就是 Apple《Designing Fluid Interfaces》所说的"接缝"（the seam between drag and animation）。快速的甩动应当被"甩出去"，而不是被一段匀速动画接管。

**技术前提（执行者必须知道）**：Compose 的 `tween`（`DurationBasedAnimationSpec`）**会忽略** `Animatable.animateTo` 的 `initialVelocity` 参数——把 `tween` 换成弹簧却仍然传 `initialVelocity` 给 `tween` 是无效的。只有 `spring`（`SpringSpec`）会消费初始速度。所以本计划把**完成**这一路换成弹簧。

## Target

`PeelTurn.kt` 顶部（文件级私有常量区，紧挨 `PeelPhase` 枚举之后即可）新增两个 spec：

```kotlin
/**
 * 甩出后的收敛弹簧：临界阻尼（不回弹），初始速度由抬手速度提供。
 * 之所以必须用弹簧而不是 tween——tween 是固定时长、会忽略 initialVelocity，
 * 只有弹簧会把离手速度接上，让"轻甩"和"慢拖"的尾段真的不同，且离手瞬间无速度突变。
 */
private val peelSettleSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

/** 取消回缩：固定 250ms 快速复位。系统纠正用户的动作应当干脆，偏慢反而拖沓，故不继承速度。 */
private val peelCancelSpec: FiniteAnimationSpec<Float> =
    tween(PEEL_CANCEL_MS, easing = FastOutSlowInEasing)
```

把 `endDrag` 里的两处调用改为传入 spec 与速度：

```kotlin
        return if (complete) {
            phase = PeelPhase.Complete
            // 完成：继承抬手速度，把这一甩接上
            animateTouch(p2, peelSettleSpring, Offset(velocityX, velocityY))
            true
        } else {
            phase = PeelPhase.Cancel
            // 取消：快速回缩，不继承速度（速度指向外侧时，继承会先向外窜一下再回来）
            animateTouch(p0, peelCancelSpec, Offset.Zero)
            reset()
            false
        }
    }
```

把 `animateTouch` 改为接收 spec 与初始速度：

```kotlin
    private suspend fun animateTouch(
        target: Offset,
        spec: FiniteAnimationSpec<Float>,
        velocity: Offset = Offset.Zero,
    ) {
        coroutineScope {
            launch { touchX.animateTo(target.x, spec, velocity.x) }
            launch { touchY.animateTo(target.y, spec, velocity.y) }
        }
    }
```

新增 import：

```kotlin
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
```

`androidx.compose.animation.core.tween`、`androidx.compose.animation.core.FastOutSlowInEasing`、`androidx.compose.ui.geometry.Offset` 均已导入，保留。

说明：`velocityX / velocityY` 与 `touchX / touchY` 同在**叶内局部坐标**（`endDrag` 里先 `touchX.snapTo(current.x)`），单位都是 px/s，直接传即可，无需换算。

**为什么完成走弹簧、取消仍走 tween**：这是有意的不对称。抬手是否"翻过去"由用户决定，完成是用户意图的延续 → 应继承速度；取消是系统纠正一次未完成的操作 → 应当干脆（Emil 的不对称时长原则：用户在做决定时慢，系统响应时快）。此外取消时速度通常指向**外侧**，若强行继承，纸角会先向外窜一段再回弹，观感容易变成"卡了一下"。故取消保持固定 250ms。

## Repo conventions to follow

- 弹簧一律临界阻尼（`DampingRatioNoBouncy`）——与 `navigation/TransitionSpecs.kt:17`、`ReaderScreen.kt` / `ComicReaderScreen.kt` 中 `animateFloatAsState(..., spring(stiffness = Spring.StiffnessMediumLow))` 的既有口径一致，全 App 无回弹。
- 常量命名与位置沿用 `PeelGeometry.kt` 的 `PEEL_*` 风格；本计划的 spec 是 `PeelTurn.kt` 内部的实现细节，放文件私有即可。
- `PEEL_CANCEL_MS`（`PeelGeometry.kt:73`）继续使用，**不要删除**。
- `PEEL_AUTO_MS`（`PeelGeometry.kt:72`）继续被 `autoPlay(...)`（`PeelTurn.kt:158`）使用，**不要动**——点击自动翻页是"无速度来源"的触发，固定时长是对的。

## Steps

1. 在 `PeelTurn.kt` 顶部（`enum class PeelPhase` 之后）加入 `peelSettleSpring` 与 `peelCancelSpec` 两个私有 spec，以及它们的 KDoc。
2. 在 `endDrag` 的 `if (complete)` 分支把 `animateTouch(p2, PEEL_AUTO_MS)` 改为 `animateTouch(p2, peelSettleSpring, Offset(velocityX, velocityY))`。
3. 同处 `else` 分支把 `animateTouch(p0, PEEL_CANCEL_MS)` 改为 `animateTouch(p0, peelCancelSpec, Offset.Zero)`。
4. 把私有函数 `animateTouch` 的签名与实现改为接收 `spec: FiniteAnimationSpec<Float>` 与 `velocity: Offset`，并在两个 `animateTo` 调用里带上 `velocity.x` / `velocity.y`。
5. 补 3 个 import。

## Boundaries

- 只改 `PeelTurn.kt`。不要动 `PeelGeometry.kt` 的常量值、`shouldCompletePeel` 的阈值逻辑、`PeelRenderer` / `PeelOverlay`。
- 不要改 `autoPlay(...)` 里的 `progress.animateTo(1f, tween(PEEL_AUTO_MS, …))`。
- 不要给取消路径也传速度（保持本计划规定的不对称）。
- 不要改动 `PEEL_CANCEL_MS = 250` / `PEEL_AUTO_MS = 400` 的数值——那是另一条 LOW 事项（翻页时长统一 token）的范围。
- 不要同时改 `PeelController` 的相位状态机（`phase` / `forward` / `corner` 等）。
- 漫画侧（`ComicReaderScreen.kt`）与文本阅读器共用同一个 `PeelController`，因此本改动自动同时覆盖两者，**无需**改漫画文件。
- `PeelTurnTest.kt` 只测纯函数 `peelEndShouldComplete`，不涉及 `animateTouch`；不要为它新增对 `Animatable` 的单测。若发现代码与 commit a855cd3 不一致，**停下并报告**。

## Verification

- **Mechanical**: `./gradlew :app:assembleDebug` 通过；`./gradlew :app:testDebugUnitTest` 全绿（尤其 `PeelTurnTest`、`PeelGeometryTest`、`PeelFrameRenderTest`、`ComicPeelTest` 不回归）；`./gradlew :app:lintDebug` 无新增警告。
- **Feel check**（真机，翻开页模式设为「仿真」，TXT 与漫画各一本）：
  - **轻甩**：快速横向一甩（不拖到过线距离）→ 页角应被"甩"过去，尾段明显比慢拖时更快，离手瞬间**没有**速度突变（不出现"先顿一下再走"）。
  - **慢拖过线后松手**：尾段较柔和地收敛到终点，不突兀加速。
  - **未过线松手**：仍快速回缩、干净利落，不出现"先向外窜一下再回来"的怪异感（若出现，说明取消路径误传了速度，需回退第 3 步的 `Offset.Zero`）。
  - 用慢放（动画时长缩放设为 5x 或 10x）反复对比"轻甩 vs 慢拖"，确认两者**尾段形状不同**；改之前两者应几乎一样。
  - 双页展开下掀一叶、日漫（RTL）方向各试一次，确认速度方向与翻页方向一致（左滑掀右叶 / 反向）。
  - 取消与完成两条路径都不应出现纸角抖动、阴影跳变或页序错乱。
- **Done when**: 快速甩动的尾段明显比慢拖快，且离手瞬间无停顿；取消与自动翻页手感不劣化。
