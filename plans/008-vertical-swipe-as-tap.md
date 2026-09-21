# 008 — 竖向滑动等价于"在起手处点一下"，并把亮度热区收窄到最左侧一条

- **Status**: IMPLEMENTED（待真机验证）
- **Commit**: a855cd3
- **Severity**: MEDIUM（分页模式下的死区：竖向滑动完全无反馈，像"翻页坏了"）
- **Category**: Missed opportunity / 手势可达性
- **Estimated scope**: 3 个文件（`ReaderLogic.kt`、`ReaderScreen.kt`、`ComicReaderScreen.kt`）

## Problem

分页模式下，竖向滑动是**死区**：

- 竖向手势只在「亮度手势开启 **且** 起手点在屏幕左 1/3」时才挂（见两个阅读器的竖向 `pointerInput`）。
- 竖向一旦在触摸 slop 竞争里赢了，横向检测器就已经主动放弃 —— 于是右 2/3 的竖向滑动**什么都不做**。
- 更糟的是：亮度手势关闭时，两个阅读器里那段 `pointerInput` 会**整体早退**，连左 1/3 也没有任何竖向响应。

只有滚动模式里竖向=滚动是好的。

另外，左侧的对称性也容易被误读成"坏了"：双页模式下"左 1/3"基本就是左页，所以**左页竖滑=调亮度、右页竖滑=没反应**。

## Target

### 1. 竖向滑动 = "在起手那一列点一下"

竖向手势抬手时，若这次不是亮度手势，就调用**与真点击完全相同的入口** `handleTap(startOffset, isDouble = false)`，于是自然得到：

- 左区 → 上一页（漫画下按 RTL 方向翻译）
- 右区 → 下一页
- 中间区 → 该区配置的单击动作（默认开关菜单）
- 链接 / 划线 / 脚注的优先级、菜单开着时先关菜单 —— 全部与点击一字不差

`ReaderScreen.kt`（`handleTap` 之后）：

```kotlin
    // 竖向滑动（不在亮度热区内）等价于"在起手那一列点一下"：直接复用 handleTap，
    // 点击区/中间动作/链接/划线的优先级与真点击一字不差，不会出现两套口径。
    // 走 rememberUpdatedState 是因为 pointerInput 的 lambda 不随重组更新。
    val latestVerticalSwipeTap by rememberUpdatedState<(Offset) -> Unit> { start ->
        handleTap(start, false)
    }
```

`ComicReaderScreen.kt` 同构（复用它的 `handleTap`，因此 RTL 方向、漫画没有底边条等差异都自动遵循）。

竖向手势的 `onDragEnd`：

```kotlin
                    onDragEnd = {
                        if (active) {
                            brightnessHint = null      // 文本阅读器这里是 delay(600) 后清掉
                        } else if (!scrollMode) {
                            // 滚动模式里竖向就是在滚动，不能顺手翻页
                            latestVerticalSwipeTap(startOffset)
                        }
                        active = false
                    },
```

### 2. 不再以 `brightnessGestureEnabled` 为门槛早退

关掉亮度手势时，竖向滑动仍要能做点击区动作。文本阅读器原来的 `if (scrollMode || !prefs.brightnessGestureEnabled || selection != null) return` 改为只挡 `scrollMode` 与选区，`brightnessGestureEnabled` 下沉到 `active` 判定里。

### 3. 亮度热区收窄：左 1/3 → 左 1/6

默认 `pageTurnHotspotRatio = 0.3`，也就是左 1/3 既是亮度区又是"上一页"点击区。两个热区完全重叠会让**左区竖滑永远只能调亮度**，收窄后左区竖滑才能回到上一页。

`ReaderLogic.kt` 新增共享常量（两个阅读器都用它，避免各写一份又漂移）：

```kotlin
/**
 * 亮度手势的热区宽度（占屏宽比例）：只在屏幕最左侧这一条内起手才调到亮度。
 * ...
 */
const val BRIGHTNESS_EDGE_FRACTION = 1f / 6f
```

两个阅读器的 `onDragStart` 都改为：

```kotlin
                        active = !menuVisible &&
                            prefs.brightnessGestureEnabled &&
                            offset.x <= size.width * BRIGHTNESS_EDGE_FRACTION
```

### 4. 其他顺带修正

- **热区判定从"每帧看 x"改成"起手时定一次"**。漫画原来是在 `onVerticalDrag` 里逐帧判 `change.position.x`，抬手时无法回答"这次到底是不是亮度手势"，因此无法支持第 1 条。
- **漫画的竖向手势补上 `scrollMode` 与 `menuVisible` 判定**，与文本阅读器口径一致；滚动模式下不再消费事件（原先有在左 1/3 抢走滚动/改亮度的风险）。
- **文本阅读器的 `pointerInput` key 补上 `menuVisible`**：原先 `menuVisible` 被闭包捕获但不是 key，菜单开关后可能读到过期值（表现为菜单开着时竖滑仍在调亮度）。

## Repo conventions to follow

- 复用既有入口而不是另写一套解析：`handleTap` 已经是"根手势层与中间点击层共用"的那一个（两个文件里都有注释强调"其余分支必须完全一致"），竖向滑动接进去正好延续这条约定。
- 手势 lambda 用 `rememberUpdatedState` 挂最新闭包 —— 与 `latestTurn` / `latestBeginPeelDrag` 同一写法。
- 共享常量放 `feature/reader/ReaderLogic.kt`（漫画阅读器已经从这里 import 了 `tapZoneOf` 等）。

## Boundaries

- 不改 `handleTap` 本身、不改 `tapZoneOf` / `resolveMiddleTap` / `comicTapForward` 的语义。
- 不改横滑翻页的判据（那是 002 / 007 的范围）。
- 不改 `MiddleTapLayer`。
- 亮度手势的**灵敏度**（`step * 2f`、`coerceIn(0.05f, 1f)`）不变，只改热区宽度与判定时机。
- 不要给滚动模式加竖向翻页。
- 如果发现代码与 commit a855cd3 不一致，**停下并报告**。

## Verification

- **Mechanical**: `./gradlew :app:compileDebugKotlin`、`:app:testDebugUnitTest` 通过（已通过）。
- **Feel check**（真机；覆盖与仿真两种翻页方式、TXT 与漫画各一本）：
  - **右侧区**竖向上下滑动 → 下一页；**左侧区（最左 1/6 以外）**竖向滑动 → 上一页。
  - **最左侧一条**（约屏宽 1/6 内）竖向滑动 → 仍是调亮度，并出现亮度提示。
  - **中间区**竖向滑动 → 走中间点击动作（默认开关菜单）。
  - 日漫（从右往左） comic：左/右区竖向滑动的翻页方向应与点击一致（镜像）。
  - 把「亮度手势」在设置里关掉：竖向滑动**仍然**能翻页（改前是完全无响应）。
  - 在竖向滑动**开始**于右区、但过程中滑到左侧：不应中途变成调亮度（起手定一次）。
  - 菜单打开时竖向滑动：应先关菜单（点击语义），不应改亮度。
  - 滚动模式下竖向滑动：正常滚动，且**不会**顺手翻页。
  - 横滑翻页、长按选字、双击动作：均不受影响。
  - 误触检查：在页面上随手竖向划一下是否会翻页——这是本次设计接受的代价，请确认真机上不至于频繁误翻。
- **Done when**: 分页模式下竖向滑动不再是无反馈的死区，且最左侧一条仍能调亮度。
