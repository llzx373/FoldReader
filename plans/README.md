# 动画审查 · 执行计划

本目录是 [`improve-animations`] 审查的产物：一次审查、五份自包含的执行计划。每份计划都写明了确切文件、当前代码、目标代码、边界与验收方式，执行者**不需要**本次会话的上下文，也不需要自己的审美判断。

审查基线 commit：`a855cd3`。若代码已漂移，按各计划 "Boundaries" 的约定**停下并报告**，不要即兴发挥。

**当前状态：001–008 已全部实现（工作区改动，未提交），编译 / 单测均已通过，等待真机验证。** 验证清单见各计划的 "Verification" 一节。

## 计划列表

| # | 标题 | 严重度 | 类别 | 状态 |
| --- | --- | --- | --- | --- |
| [001](001-text-reader-menu-animation.md) | 给文本阅读器菜单补齐进出动画 | HIGH | Cohesion & tokens | 已实现 |
| [002](002-page-turn-input-not-swallowed.md) | 翻页动画进行中不再吞掉后续翻页输入 | HIGH | Interruptibility | 已实现 |
| [003](003-navhost-missing-transitions.md) | 补齐 NavHost 缺失的转场，消除 700ms 默认淡入淡出 | MEDIUM | Easing & duration | 已实现 |
| [004](004-bookshelf-return-mirror.md) | 书架返回时镜像出程（补 scaleIn） | MEDIUM | Physicality & origin | 已实现 |
| [005](005-peel-velocity-handoff.md) | 仿真翻页抬手后继承手指速度 | MEDIUM | Interruptibility | 已实现 |
| [006](006-reader-entry-double-motion.md) | 修掉进书时"整屏滑入"与"封面共享元素"的双重运动 | MEDIUM | Physicality & origin | 已实现 |
| [007](007-peel-begin-end-race.md) | 修掉仿真掀页的"起步 / 抬手"竞态（横滑漏翻 + 卡在半掀） | HIGH | Interruptibility | 已实现 |
| [008](008-vertical-swipe-as-tap.md) | 竖向滑动等价于"在起手处点一下"，亮度热区收窄到左 1/6 | MEDIUM | Missed opportunity | 已实现 |

> 006 / 007 / 008 不在最初那份审查表里——它们是后续复查与真机反馈中新发现并确认的问题，按同一套格式补记。

## 建议执行顺序

```
001 ──► 002 ──► 003 ──► 004 ──► 005
```

理由：

- **001 / 002** 是两条 HIGH，改动小、收益最直接，互相独立，可先做。
- **003 → 004 有顺序依赖**：两者都改 `FoldReaderNavHost.kt` 中 `Routes.BOOKSHELF` 的同一个 `composable`（003 补 `enterTransition`/`popExitTransition`，004 改 `popEnterTransition`）。**先 003 再 004**，避免行号与上下文漂移。
- **005** 只碰 `PeelTurn.kt`，与其它计划完全独立，可任意时点执行；列在最后只是因为它是唯一的"物理手感"调优，需要真机慢放验证。

## 依赖关系

| 计划 | 依赖 | 说明 |
| --- | --- | --- |
| 001 | 无 | 仅 `ReaderScreen.kt` |
| 002 | 无 | 仅 `ReaderScreen.kt` + `ComicReaderScreen.kt` 的 `turn()` |
| 003 | 无 | 仅 `FoldReaderNavHost.kt` |
| 004 | **003** | 同一个 `composable`，需在 003 之后 |
| 005 | 无 | 仅 `PeelTurn.kt` |
| 006 | 无 | 仅 `TransitionSpecs.kt` + `ReaderOverlay.kt` |
| 007 | 无 | 两个阅读器的横滑 SIMULATION 分支 |

> 注：001 与 002 都改 `ReaderScreen.kt`，但作用的函数不同（001 改菜单显隐的 `if (menuVisible)` 块，002 改 `turn()`），互不冲突。若并行执行，注意不要互相覆盖。

## 不在本次计划内（审查中的 LOW / 已明确不做）

以下在审查中被记录为 LOW 或**刻意保留**，未生成计划：

- **动效常量散落**：`tween(220)` 字面量（`ReaderScreen.kt:509`）与 `ANIM_MS = 220`（`ComicReaderScreen.kt:152`）重复；覆盖翻页 220ms 与仿真翻页 400ms（`PEEL_AUTO_MS`）口径不一。建议后续收敛为一组 `PageTurn` 时长 token。
- **标准缓动曲线**：阅读器内联的 `FastOutSlowInEasing` 属 Material 标准曲线，偏弱；导航层已有 `TransitionSpecs.kt` 这一 token 入口，可考虑统一。
- **遗漏的机会**（增量、非纠错）：选区手柄与操作条瞬时出现（`ReaderScreen.kt:1361-1439`）；亮度提示瞬时出现/消失；书封面按压无缩放反馈。均未列为计划。
- **刻意保留、不要再"修"**：
  - 双击延迟被限定在中间区（`MiddleTapLayer`），且只在该区真配了双击动作时才挂——这是经过权衡的正确设计。
  - `Routes.READER` 的占位转场时长是生命周期契约（防止 entry 被过早销毁），不要压缩。

## 横滑"滑了没翻页"的判据（设计如此，不是 bug）

真机反馈过"滑动偶尔不翻页"。除 007 修的竞态外，另外两种常见原因是**有意设计**，调参前请先确认是不是这两条：

1. **滑动方向偏上下**：横滑用 `detectHorizontalDragGestures`，它的触摸 slop 判定在**横向分量先过大**时才成立；
   若滑动以纵向为主，横向检测器会主动放弃（这是为了让竖向滑动去调亮度）。
   判断依据见 `ReaderScreen.kt` / `ComicReaderScreen.kt` 的横滑 `pointerInput` 与
   `horizontalSwipeDirection`（`ReaderLogic.kt:278`）。
2. **距离与甩速都不够**：判据是"位移 ≥ `swipeDistanceDp`（默认 40dp）**或** 甩速 ≥ `swipeFlingVelocityDpPerSec`（默认 500dp/s）"，
   两者都不满足就不翻（防止误触发）。阈值可在阅读设置里按手感调整。

另外一个值得注意的交互竞争：亮度手势只在**屏幕最左侧一条**（屏宽 1/6，见 `BRIGHTNESS_EDGE_FRACTION`）激活；
在更靠内的左区竖滑会走"点击区动作"（见 008）。如果实测觉得亮度热区太窄不好调，
只需调大 `BRIGHTNESS_EDGE_FRACTION`。

## 已知的次要手感问题（未生成计划）

- **掀页起步有延迟**：`preparePeelBitmaps` 是异步的（文本要渲染整页位图，漫画 `awaitPage` 最长 800ms）。
  在位图就绪前，拖动的**触点位置不会更新**（`updateDrag` 被 `phase == Drag` 挡住），
  所以掀页出现时会从"手指最初的位置"开始跟手，而不是当前位置。彻底解法是
  **同步 `beginDrag` + 位图并行准备**（掀页立即跟手，位图到了再画纸面），改动比 007 大，需要单独验证。
- **翻页时长口径不一**：覆盖翻页 `tween(220)` 与仿真自动翻页 `PEEL_AUTO_MS = 400` 不一致，且 220 在两处重复。
- **遗漏的机会**：选区手柄与操作条瞬时出现（`ReaderScreen.kt` 选区一段）；亮度提示瞬时出现/消失；书封面按压无缩放反馈。
- **书架退场仍保留 `scaleOut(0.94f)`**（006 刻意未动）。若真机上仍觉得它干扰封面飞行，可再单独去掉。
- **SIMULATION 横滑分支不 `change.consume()`**，而覆盖分支会。当前靠 slop 判定互斥，暂无实测问题，属一致性瑕疵。
