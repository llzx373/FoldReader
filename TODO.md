# FoldReader 开发任务清单（TODO）

> 依据《docs/需求与设计说明书.md》v1.2 拆解。
> 执行规则：按里程碑顺序推进；每个任务完成即勾选 `[x]`；每完成一个里程碑必须达到对应"验收标准"并编译通过（`./gradlew :app:assembleDebug`）后再进入下一个。
> 设计语言：Material Design 3 Expressive（material3 1.5.0+，`MaterialExpressiveTheme` 已就位）。
> 目标设备：大折叠 + 阔折叠实测优先；直板/竖折 Flip 仅验证优雅降级。

---

## M1 基础骨架：折叠感知层 + 书架 + TXT 导入

**验收标准**：外部 App 选择 FoldReader 打开 TXT 可导入入架；书架在折叠/展开/直板机正常展示。

### 1.1 项目骨架
- [x] 按说明书 8.1 建立包结构：`core/format`、`core/format/txt`、`core/reader`、`core/foldable`、`core/data`、`feature/bookshelf`、`feature/reader`、`feature/importer`、`feature/settings`
- [x] 创建 `FoldReaderApplication`（App 级容器：DB、仓库的手动依赖装配）并注册到 Manifest
- [x] 配置 Navigation Compose 路由骨架：书架 `/bookshelf`、阅读器 `/reader/{bookId}`、设置 `/settings`
- [x] 根界面接入 `NavigationSuiteScaffold`：折叠态底部导航（书架/设置），展开态侧边导航
- [x] 确认 `MainActivity` 折叠/展开切换不重建（`configChanges` 已配），补 `onNewIntent` 处理外部 VIEW Intent

### 1.2 折叠感知层（core/foldable）
- [x] 定义 `FoldingPosture` 模型：`posture(CLOSED/FLAT/HALF_OPENED)`、`hingeBounds: Rect?`、`hingeOrientation(VERTICAL/HORIZONTAL)`
- [x] 用 `WindowInfoTracker.windowLayoutInfo(activity)` Flow 封装为 `FoldableStateProvider`，输出 `StateFlow<FoldingPosture>`
- [x] 无折叠特征设备返回 CLOSED 且 `hingeBounds=null`（直板降级路径）
- [x] 组合 `calculateWindowSizeClass` 输出宽度类别，作为布局兜底输入
- [x] 单元测试：FoldingFeature 各组合（无铰链/垂直铰链 HALF_OPENED/水平铰链 FLAT）→ Posture 映射正确

### 1.3 数据层（core/data）
- [x] Room 数据库 `FoldReaderDatabase`（导出 schema）
- [x] 实体 `BookEntity`：id、书名、作者(可空)、文件 Uri、内容哈希、格式(TXT)、总字符数、编码、导入时间、最后阅读时间
- [x] 实体 `ReadingProgressEntity`：bookId、字符偏移锚点、章节索引、阅读总时长、更新时间
- [x] 实体 `BookmarkEntity`：id、bookId、字符偏移、章节索引、页内快照文本、创建时间
- [x] 实体 `AnnotationEntity`（划线标注）：id、bookId、起止字符偏移、所选文本、颜色、笔记内容、创建/修改时间
- [x] DAO：书架查询（按最近阅读排序 Flow）、进度读写、书签/标注 CRUD（按 bookId 查询 Flow）
- [x] DataStore Preferences：全局默认阅读偏好（字号/行距/主题/翻页方式/翻页热区/音量键开关）
- [x] Repository：`BookshelfRepository`、`SettingsRepository`（ViewModel 面向接口编程）

### 1.4 TXT 解析基础（core/format）
- [x] 定义格式引擎接口 `BookParser` / `BookMeta` / `Chapter` / `BookContent`（按说明书 8.2，为 EPUB 预留）
- [x] 编码检测器：采样头部字节，识别 UTF-8(BOM/无 BOM)/UTF-16 LE/BE/GBK/GB18030/Big5，输出编码 + 置信度
- [x] `TxtBookContent` 实现：基于 RandomAccessFile/InputStream 的**窗口化读取**（按字符偏移读 ±N KB 窗口），不整文件载入内存
- [x] 字节偏移 ↔ 字符偏移映射索引建立（后台协程，进度可查询；Room 持久化 + 边建边读）
- [x] 章节识别器：内置规则库（"第X章/卷/回/节"、Chapter N、纯数字分卷等），输出章节列表；识别失败降级为"纯进度模式"（无目录不阻断阅读）
- [x] 单元测试：GBK/UTF-8/Big5 样例文件编码检测正确；窗口读取边界不错字；章节正则对典型网文样例命中率

### 1.5 导入（feature/importer）
- [x] SAF 文件选择器导入（`ActivityResultContracts.OpenDocument`，filter `text/plain` + `application/octet-stream` 兜底），取持久化 Uri 权限
- [x] VIEW Intent 接收：冷启动经 `onCreate`、热启动经 `onNewIntent`，解析 `content://` / `file://` Uri
- [x] 导入流水线：编码检测 → 书名推断（文件名去扩展名，正文头部"书名/作者"启发式）→ 内容哈希（采样+长度）→ 去重 → 写库 → 自动建立章节索引（后台）
- [x] 去重策略：同 Uri 直接复用；不同 Uri 同哈希提示"已在书架"
- [x] 导入反馈：波浪形 `LoadingIndicator` + Snackbar 结果提示（Expressive 组件）
- [x] 外部打开的书籍导入成功后直接进入阅读界面

### 1.6 书架（feature/bookshelf）
- [x] 书架 UI：网格视图（自适应列数，展开态多列）+ 列表视图切换
- [x] TXT 自动封面生成：书名首字 + 书脊式配色（按书名哈希取色），Expressive 圆角形状
- [x] 封面下显示阅读进度百分比与最近阅读时间；按最近阅读排序
- [x] FAB Menu 导入口（Expressive `FloatingActionButtonMenu`）
- [x] 可折叠大标题 TopAppBar；空书架引导页（插画位 + "导入书籍"按钮）
- [x] 长按多选 → 删除（含确认对话框，"同时删除本地进度/标注"可选：复选框可交互 + 外键 NO ACTION 显式级联）
- [x] 书架项点击进入阅读器（占位路由即可，M2 实现正文）

---

## M2 阅读核心：分页引擎 + 单页阅读 + 进度 + 目录

**验收标准**：100MB TXT 从点击到显示第一页 < 1.5s；翻页流畅；杀进程重进进度准确恢复。

### 2.1 分页引擎（core/reader）
- [x] `LayoutConfig` 模型：字号、行距、字距、段落间距、页边距（四边独立）、首行缩进、对齐方式、字体
- [x] `Paginator`：输入文本窗口 + 页面像素尺寸 + LayoutConfig，基于 `StaticLayout` 度量输出 `Page(charStart, charEnd, lines)`
- [x] 中文排版规则：标点避头尾（禁则处理）、两端对齐、首行缩进 2 字符
- [x] 分页缓存：key = bookId + LayoutConfig 哈希 + 页面尺寸，内存 LRU + 磁盘缓存（页边界索引 FilePageDiskCache 持久化，行布局内存 LRU 重度量）
- [x] 双向翻页：支持"从偏移向后排一页"与"向前倒推一页"（上一页定位）
- [x] 行长上限约束：中文每行 18–40 字，超出屏宽时居中留白（为展开态横持铺垫）
- [x] 单元测试：边界字符（标点行首/行尾）、窗口边界翻页、改字号后页数变化

### 2.2 单页阅读界面（feature/reader）
- [x] 阅读页 Compose 渲染：按 Page 行布局绘制文本（Canvas/多 Text 行），全屏沉浸（隐藏系统栏，点击呼出）
- [x] 翻页交互：点击热区（左/右/下翻页，热区比例可配）+ 水平滑动手势 + 音量键翻页（可关）
- [x] 翻页动画 v1：覆盖滑动 + 无动画两种（仿真翻页在 M4）
- [x] 上下滚动模式（RecyclerView 等效 LazyColumn 文本流，与分页模式互斥可切换）
- [x] 阅读菜单：底部 Expressive 面板（目录/进度/亮度/设置入口），顶部栏（书名/章节/返回）
- [x] 页眉页脚：章节名、页码（第 x/y 页，双页为第 x–x+1/y 页）/百分比、电量、时间（均可单独开关）
- [x] 屏幕常亮开关、应用内独立亮度（窗口亮度 + 滑杆 + 左侧下拉手势调节，可关）

### 2.3 进度与目录
- [x] 进度锚点 = 当前页首字符偏移，翻页即写（防抖落库）
- [x] 打开书籍恢复到上次位置（锚点 → 重分页 → 定位）
- [x] 进度条跳转（拖动百分比）、章节跳转
- [x] 目录面板：章节列表、当前章节高亮、点击跳转；无目录书显示"按百分比"兜底
- [x] 全书剩余时间估算（按近期阅读速度）
- [x] 阅读时长统计埋点（前后台切换暂停计时）

### 2.4 阅读设置
- [x] 字号/行距/行长(18–40)/段距/字距滑杆（带刻度停靠 Slider），实时预览生效；边距三档（四边独立传递）
- [x] 字体：系统字体列表 + 导入 TTF/OTF
- [x] 阅读主题预设 ≥5：护眼绿、羊皮纸、灰白、夜间、AMOLED 纯黑；自定义背景色/文字色
- [x] 夜间模式：随系统/手动；阅读主题独立于系统动态取色
- [x] 翻页方式、热区、音量键、常亮等开关接入 DataStore（全局默认）

---

## M3 折叠形态：双页书式模式 + 铰链避让 + 姿态连续流转

**验收标准**：展开态双页翻页稳定 60fps；折叠↔展开切换不丢进度、不闪白屏；大折叠与阔折叠（16:10 宽内屏）实测通过。

### 3.1 双页书式模式（说明书 5.2）
- [x] FLAT + 垂直铰链 + 宽度足够时进入双页：左屏=左页、右屏=右页，一次翻两页
- [x] 铰链避让：按 `hingeBounds` 将屏幕分为左右安全区，正文严格避开折痕
- [x] 书脊设计：中缝柔和阴影渐变；内侧（书脊侧）页边距 > 外侧，模拟装订
- [x] 页眉页脚分居左右页外侧；页码以"第 x–x+1/y 页（双页计）"呈现
- [x] 阔折叠适配：宽内屏双页时行长取舒适区间下限（capMaxLineChars 按页宽收敛）；所有尺寸以运行时 FoldingFeature 实测，不写死
- [x] 设置项：展开态可强制单栏（宽幅单栏，行长上限居中）
- [x] 横持展开态保持双页（水平铰链按屏幕中缝均分），行长上限约束生效

### 3.2 姿态连续流转（说明书 3.3）
- [x] 折叠↔展开切换：以锚点重分页并定位，单页↔双页无感切换，不闪白屏（切换期间保留上一帧渲染）
- [x] HALF_OPENED 姿态识别接入阅读器（M4 铺 UI，本里程碑先保证状态流正确驱动布局）
- [x] 旋转切换不丢进度
- [x] 回归测试：连续折叠/展开 20 次进度锚点不变

### 3.3 双页性能
- [x] 双页翻页预渲染（前后各一对页离屏就绪）
- [x] 展开态 60fps（高刷屏 120fps）profile 验证，掉帧优化（减少重组、文本绘制缓存）

---

## M4 悬停与打磨：桌面模式 + 自动翻页 + 仿真翻页 + 主题体系

**验收标准**：悬停半折时上半屏阅读、下半屏控制可用；自动翻页稳定；仿真翻页动画流畅。

### 4.1 悬停桌面模式（说明书 5.3）
- [x] HALF_OPENED + 水平铰链：上半屏正文（单栏），下半屏控制面板
- [x] 下半屏面板：翻页按钮、进度条/章节跳转、亮度、字号快捷调节、自动翻页开关与速度
- [x] 下半屏熄屏选项（面板区变暗，AMOLED 节能）
- [x] 竖铰 HALF_OPENED（左右开合半折）：降级为单页 + 避让铰链

### 4.2 自动翻页
- [x] 按时间间隔翻页/按速度滚动两种模式，速度可调
- [x] 与手动操作互斥（手动翻页暂停/重置计时）
- [x] 悬停态默认入口，普通态设置内开启

### 4.3 仿真翻页动画
- [x] 纸张仿真翻页（展开态双页默认，未显式设置过翻页方式时）：右页向左翻动覆盖动效，含页面卷曲/阴影
- [x] 单页态仿真翻页适配
- [x] 性能降级策略：大幅面掉帧时自动回落覆盖滑动并提示
- [x] 弹簧物理手感与 M3 Expressive 基调一致

### 4.4 主题与视觉打磨
- [x] 外壳 UI Expressive 组件全面核对（说明书 4.2 组件表：SplitButton ✓（阅读菜单翻页方式，主按钮循环切换+箭头展开单选列表）、分段按钮 ✓（主题选择/边距/双页/翻页方式/夜间模式）、emphasized 字阶 ✓、Expressive shapes ✓（主题层已接入））
- [x] 阅读主题自定义编辑器（背景/文字色、实时预览）
- [x] 空状态、加载、错误态插画与文案
- [x] 动画细节：书架↔阅读器共享元素过渡（SharedTransitionLayout，封面 cover-$bookId 双向承接）

---

## M5 体验完善：书签 + 划线标注 + 全文搜索 + 阅读统计

**验收标准**：标注经姿态切换/改排版后锚点不漂移；全文搜索结果按章节分组可跳转。

### 5.1 书签
- [x] 任意位置一键加书签（选区工具条"加书签"，锚点精确到字符偏移 + 快照文本，同页可多条）
- [x] 书签列表（按书聚合、按创建时间倒序）、跳转、重命名、删除
- [x] 双页模式下左右页可分别添加

### 5.2 划线标注
- [x] 长按文本进入选择模式（跨行/跨页拖边翻页选择；滚动模式长按选择已实现）
- [x] 划线：多色可选；可附加笔记
- [x] 标注存储：起止字符偏移 + 所选文本快照（校对用）
- [x] 标注列表：按章节分组，显示划线文本与笔记，可编辑/删除/跳转
- [x] 正文中划线渲染（底色/下划线样式），点击划线弹出查看/编辑（含滚动模式）
- [x] 锚点稳定性测试：改字号/边距/姿态切换后划线位置不漂移；文本快照不符时提示降级定位

### 5.3 全文搜索
- [x] 书内搜索：关键词命中列表按章节分组（SearchGrouping 纯函数归组 + 组标题渲染）+ 上下文摘要，点击跳转并高亮
- [x] 大文件搜索后台分块执行，可取消，显示进度
- [x] 书架级搜索（书名/作者）v1.1 可选

### 5.4 阅读统计
- [x] 每本书：累计时长（ON_STOP/onCleared flush 修复）、阅读天数（按会话去重日期）、平均速度（按实际已读字符 charsReadTotal）
- [x] 全局：本周/本月时长（简单图表，Expressive 样式）

### 5.5 收尾
- [x] 设置页完整（说明书 6.4）：全局默认偏好（已实现）、手势与按键（已实现：热区比例/音量键/亮度手势/滑动翻页手势开关）、关于（已实现：开源许可清单弹窗署名 OpenCC）
- [x] 备份与恢复（JSON v2：偏好全字段、reading_sessions、book_prefs 导出导入；恢复引导与 missing 清单；向后兼容 v1）
- [x] 真机矩阵回归：大折叠 ×2 品牌、阔折叠、直板机各一（已做静态降级分支自查，真机回归待真机）
- [x] 非功能指标验收（说明书第 7 章：大文件开书 JVM 基线 50MB 索引 468ms、内存与书大小解耦已验证；冷启动 <1s 待真机）

---

## M5.6 设计一致性修复（审计增补，详见修复计划）

**格式引擎**
- [x] 无 BOM UTF-16 检测分支修复（含 LE/BE 标签颠倒）；窗口读取解码容错与索引一致（REPLACE）；TxtIndexer EOF 溢出防御
- [x] 编码手动切换入口（书籍详情 + 阅读菜单），低置信度导入提示
- [x] 自定义章节正则（DataStore + 设置页 + TxtIndexer 接线；已有书"重建目录"入口）
- [x] 导入不再同步全量索引（写库即返回，后台建索引）

**数据层（DB v5）**
- [x] 每书独立阅读偏好（BookPrefsEntity v7 补齐字段 + BookPrefsRepository 接线，新书拷贝全局默认后独立演化）
- [x] BookEntity 增加分组字段；ReadingProgressEntity 增加已读字符数
- [x] 进度/书签/标注外键改显式级联（支持删除时保留本地数据）

**导航与转场**
- [x] 阅读页改由 `ReaderOverlay` 全窗口覆盖层渲染（`SharedTransitionLayout` 内、外壳之外）：外壳 rail 形态与路由解耦，消除进书时书架左移与退书时封面/按钮整体右跳（详见 `docs/返回书架右跳分析.md`）
- [x] 退出阅读页统一走 `leaveReader` + `BarsRestoreGate`（四边 inset 到达目标值并连续两帧稳定才导航，800ms 超时兜底）；禁止用 `NavigationSuiteScaffoldState.snapTo` 切换导航组件
- [x] 覆盖层按 entry 提供 ViewModel/SavedState/Lifecycle owner；占位目的地转场时长 ≥ 覆盖层退场动画（避免 entry 先销毁导致 `viewModel()` 崩溃）
- [x] 返回跳动帧级诊断（`core/debug/ReturnTrace`，仅 debug）：layoutType / innerPadding 四边值 / shell slot / content pos
- [x] 应用内诊断日志（`core/debug/DiagnosticLog`）：落盘 `files/diagnostics/return-trace.log` + 崩溃转储；设置页「诊断」可开关/打标记/分享/复制/清空（真机无需 adb）

**排版与布局**
- [x] Paginator 独立左右边距；字距度量/绘制链一致；Kinsoku 连续避头；超长段假段首
- [x] 滚动模式双页退化为双栏连续文本（spread 行语义）
- [x] 宽屏双页用户开关（默认关，直板降级单页）
- [x] 页码"第 x/y 页"（后台全书边界索引）；章节进度展示；下翻页热区；滚动模式音量键 bug 修复
- [x] 展开态未显式设置翻页方式时默认仿真

**v1.1 新功能**
- [x] 智能清理：去空行 / 去广告行（自定义正则）/ 繁简转换（单字映射表），导入时物化清洗副本
- [x] 书架分组（筛选 chips + 批量移动 + 分组管理）

---

## M5.7 性能专项·第一批（低风险高收益）

> 对标：文本阅读器 = Sublime Text；电子书阅读器 = 静读天下。
> 详见 `docs/需求与设计说明书.md` 附录「v1.3 性能专项」。每项均配单元测试。

- [x] 渲染层：行几何缓存（键含页实例身份）+ `getTextWidths` 批量取宽 + Paint 按线程复用
- [x] EPUB 打开路径零拷贝：`pageLabels`/`imageFile` 不再整本复制源文件；压平 sidecar 解析结果进程内备忘（含磁盘失效校验）
- [x] 搜索：命中批处理按时间/条数节流发布；`SearchScanner` 跳过无收益的 lowercase、上下文复用已读窗口
- [x] `chapterIndexAt` 改二分（滚动每页 4 次 + 搜索每命中 1 次的调用点全部受益）
- [x] `TxtBookContent` 块解码缓存 + 解码器/字节缓冲复用
- [x] 页边界缓存追加式落盘；删书按 bookId 清理 + 闲时 GC（每书保留最近 4 份版式）
- [x] DB v11：bookmarks/annotations 补复合索引、去除与主键前缀重复的单列索引；`deleteBooks` 批量查询；覆盖式写入包 `@Transaction`
- [x] `TxtBookParser` 去掉解码回调里的 `runBlocking`（改独立消费者协程）
- [x] 插图解码固定 RGB_565；`onTrimMemory` 同时清插图与翻页位图缓存
- [x] 构建：release 开启 R8（`optimization.enable` + minify + 资源压缩）
- [x] 测试基建：引入 Robolectric（Paint 字宽 / BitmapFactory / 真实 SQLite 迁移 / Compose 用例）

### M5.8 性能专项·第二批（滚动模式，已完成）

> 详见 `docs/需求与设计说明书.md` 附录「v1.4 性能专项·第二批」。

- [x] 拆分 `ReaderUiState`：位置展示态独立为 `ReadingPosition` 流，滚动不再让整棵阅读树重组
- [x] 页眉页脚抽成 `ReaderCornerChrome` 自行订阅；菜单/目录/桌面模式在可见性判断内订阅
- [x] `scrollPages` 改 `SnapshotStateList`（追加前插 O(1)，只失效 LazyColumn items）
- [x] `scrollExtend` 一次批量预取 5 页（`collectScrollPages`），先入列表再异步解图片
- [x] `spansFor` 改 `AnnotationIndex`（二分 + 前缀最大结束偏移提前终止）
- [x] 双栏 `chunked(2)` 改 `derivedStateOf` 缓存
- [x] `positionAt` 收敛重复的 `chapterIndexAt` 调用
- [x] `Page`/`PageLine`/`LineBox`/`RangeSegment`/`LayoutConfig`/`ReaderColors`/`PageSpread`/`ReadingPosition` 加 `@Immutable`

### M5.9 偏移索引改为双列模型（已完成，DB v12）

> 详见 `docs/需求与设计说明书.md` 附录「v1.5 偏移索引的代理对跨块修复」。

- [x] 定位问题：`TxtIndexer` 在增补字符跨块时产出 4095/8190/… 非均匀块起点，
      而 `offset_index` 恢复时按 `i * blockChars` 重建起点 → 次开读出的文本整体偏移、靠后越界
- [x] 论证根因：增补字符在 UTF-8 里不可分割，这种边界上不存在合法字节偏移，非均匀是必然
- [x] `offset_index` 改双列模型：`charOffset` → **`byteOffset`**，新增 **`charStart`**（真实字符起点）
- [x] `OffsetIndexBlock` 取代 `Pair<Int, Long>`，live 逐块落盘一并带字符起点
- [x] `buildSnapshot` 校验改为按相邻跨度（起点严格递增 / 单块跨度 ≤ blockChars / 首块为 0）
- [x] `MIGRATION_11_12` 重建空表（旧行真实起点已丢失，搬过去也是错的）
- [x] 压平文件的索引扫描不再跑被丢弃的章节正则
- [x] 测试：非均匀索引忠实往返（含逐字符读比对）、增量落盘路径、不自洽快照判损坏、
      SchemaV12 / MigrationV12

### M5.10 导入后后台预热 + 就绪角标（已完成，DB v13）

> 详见 `docs/需求与设计说明书.md` 附录「v1.6」。方案 A：压平成本从"首开"挪到"导入后的后台"。

- [x] `BookParser.prewarm(uri)`（默认无操作）+ EPUB/FB2 实现（压平 + 回填章节表）
- [x] `BookPrewarmQueue`：串行、每本之间让出、失败静默；导入成功后入队（TXT 不入队）
- [x] `books.contentPreparedAt` 就绪标记；预热成功与阅读器打开成功各回写一次
- [x] 同一 hash 的压平串行化，避免预热与阅读器并发压同一本书
- [x] 书架封面左下角「待解析」角标（列表视图改在副标题下补一行）
- [x] 测试：队列串行/失败不记账/TXT 不入队/入队不阻塞、压平串行化并发、SchemaV13、MigrationV13

### M5.11 目录修复与层级展示（已完成，DB v14）

> 详见 `docs/需求与设计说明书.md` 附录「v1.7」。起因：合并型 EPUB 打开后目录面板显示「未识别到章节」。

- [x] 定位：解析本身没问题（同一文件能出 26 章），是**章节回填失败后无法自愈**
- [x] 兜底补扫不再被实时索引拦住：EPUB/FB2（只读 `.toc` sidecar，便宜）照常兜底，
      仅 TXT 在实时索引期间跳过（`shouldScanChaptersInBackground`）
- [x] 补扫/补写失败写入诊断日志，不再静默
- [x] 目录层级透传：`TocEntry` / `Chapter` 增 `depth`，NCX 按 navPoint、NAV 按 `<ol>` 嵌套计算
- [x] `chapters` 表增 `depth` 列（DB v14，旧数据保留）
- [x] 目录面板按层级缩进（步长 14dp，封顶 4 级）
- [x] 测试：嵌套 NCX / 嵌套 NAV 的层级、无目录兜底层级为 0、兜底判定、SchemaV14 / MigrationV14
      （含「旧章节数据不丢」）

### M5.12 第三批候选：评估结论（已归档，逐项不做）

> 六项候选逐条评估后均未达到「值得做」的门槛。结论与**理由**留档，避免以后重复提议。

- **EPUB/FB2 增量压平（首章先出）** —— 不做。
  M5.10 的导入后预热已把「首开等压平」压缩到「导入后立刻点开」与「预热失败/进程被杀后首次打开」
  两种少见情况，且没有实测数据表明首开压平不可接受。实现上仍要解决「生长中文件读到 EOF 后等待追加」
  的索引循环，属并发设计而非简单改造。
  *更正早先记录*：原记的「样式必须等整本压平完」过于悲观——粗体/斜体/上下标 span 的偏移是位置局部的，
  可以随章节产出；只有内部链接解析、纸书页码、锚点表需要整本遍历。
- **压平时顺带产出偏移索引** —— 不做。双列模型让「块边界不必落在 4096 字符整点」成为可能，技术上已可行；
  但收益只是省掉首开后的一次后台全量解码（不挡首屏），换不动压平器写入路径的改动风险。
- **引入 `kotlinx.collections.immutable` 让 `PageView`/`SpreadContent` 可跳过重组** —— 不做。
  第二批次的状态拆分已消除滚动时的整树重组，剩余收益不足以引入新依赖并改一批公开签名。
  若将来 profile 显示 item 重组是热点，再回头评估。
- **`ScrollContent` 函数引用改 `remember`** —— 不做。该 Composable 的形参本就不稳定
  （`MutableMap` + `List`），单独稳定函数引用并不会让它变得可跳过。
- **`preferredStartOffset` 去掉整本复制** —— 不做。只在「首次打开且无进度」时走一次；
  改法需要把结构解析结果跨调用传递，或给压平缓存加 sidecar（触发 FLATTEN_VERSION 提升 → 全量重压平）。
- **书架 `SELECT books.*` 改投影列** —— 不做。收益是少读几个短文本列，
  代价是牵动书架 UI 全部取值点与书签总览对话框。

### M5.12 待真机验证（非开发任务）

> 这些不是待决策项，是已经做完但只能在真机上确认的验证。

- [ ] release 包实机跑一遍：Room v10→v13 迁移、EPUB/FB2 解析、自定义字体加载、备份导出导入
- [ ] 滚动流畅度 profile（第二批次的核心收益需要实测确认）
- [ ] 导入一本大 EPUB：看「待解析」角标多久消失；角标消失后点开是否秒进
- [ ] 大 EPUB 首开耗时拆解（解压 / 解析 / 压平写盘 / 建索引）—— 这是**唯一**能推翻上面
      「增量压平不做」结论的依据，有数据再回来

---

## M5.13 仿真翻页：卷曲几何（单页 + 双页）

> 详见 `docs/需求与设计说明书.md` 附录「v1.8 仿真翻页卷曲几何」。
> 现状是**刚性平板绕竖轴旋转**（AGSL 铰链模型）：单页无页背、从起手即整页横向压缩、
> 折痕永远竖直、没有法线光照。本次换成**圆柱卷曲**（折痕可任意角度、`R·sin(s/R)` 网格
> 展开、正面/背面分界、分层阴影），单页与双页统一，双页纸背用真实内容。
> 参考实现族：MIT 的 `FantasticPornTaiQiang/PTQFlipper`（只作观感与阴影分层参照）。

### 阶段 1 · 几何与网格
- [x] `CurlGeometry.kt`：`CurlFrame` 叶片坐标系、`CurlRoll` 卷筒、`curlRollForProgress`
      （折痕从自由边扫到装订边之外 → 两端严格收敛）、`curlProgressFor`（折痕跟手）、
      `curlSurfaceAt`（FLAT/FRONT/BACK/UNDER）、`curlWarpPoint`、`curlMeshVertices`、
      `curlMeshSize`（含顶点数上限）、`curlBandPolygon`（凸多边形裁剪，锁定折痕不越中缝）
- [x] `CurlGeometryTest`：卷角区间、**起手帧 = 当前页 / 终点帧 = 下层页（两端严格收敛）**、
      折痕单调推进、可卷长度不超叶片跨度、表面分区、**平板侧逐点不动**、卷起侧不超伸距、
      自由边落点对齐正背分界、网格顶点数与上限、镜像恒等式、
      **折痕不越中缝（双页左右页双向扫掠）**、阶段状态机、完成度与覆盖率
- [x] 实现偏离（已与用户同步）：PTQFlipper 的 `algorithmStateLoose` 里
      `Rf.y ≡ R.y` 会让折痕斜率恒为 ±∞/NaN，无法靠阅读确认其意图，故未照抄，
      改为自行推导的圆柱模型；PTQ 仅作阴影分层与观感参照

### 阶段 2 · 阴影与绘制
- [x] `CurlShadows.kt`：6 条分层阴影（折痕外侧 / 纸面贴折痕 / 卷筒投影 / 自由边光泽 /
      平铺侧光泽 / **卷筒背面圆柱明暗**），宽度由 `curlShadowWidths` 单一来源、
      颜色由主题明度派生（暗背景自动收敛），全部与叶片矩形求交
- [x] `CurlOverlay.kt`：背景 → 卷筒投影 → 正面 `drawBitmapMesh` → 背面 → 纸面阴影的图层顺序；
      不再有"着色器编译失败"这一失败模式
- [x] `CurlShadowsTest`：带落在各自区间、不越叶片、宽度随卷筒自适应、面积单调、暗背景收敛、
      渐变方向与折痕共线
- [x] `CurlSoftwareRenderer.kt` + `CurlFrameRenderTest`：离线出图到 `build/curlFrames/curl/`，
      断言**起手帧逐像素等于当前页、终点帧逐像素等于目标页**（水平与斜向起手各一条）

### 阶段 3 · 接线
- [x] `ReaderScreen.kt`：单页仿真改用 `CurlOverlay`；进度域统一 0..1（`simMaxProgress`）、
      松手完成度直接用 `simProgress`；拖拽时折痕跟手（`curlDragProgress`）；
      点击翻页起点改从 0 起
- [x] 拖拽改任意方向（角抓取）：`detectDragGestures` 独立一路，与水平那路互斥；
      70ms 起手延迟、竖向起手（`|dy| > 1.6·|dx|`）不接管；抓页中间起手用锚定量防跳帧；
      抓住飞行中的页面用 `curlTakeoverGrab` 虚拟起手点无缝接管
- [x] 折痕方向取自拖动方向，"抓上角"是自然的斜向 `dir`，不需要上下翻转开关

### 阶段 4 · 双页打磨（随整体放弃而作废）
- [ ] 双页仍走旧铰链路径。换卷曲几何**不是改渲染分支就够**：现在这套卷曲模型没有
      "叶片落定到对侧"的相位（折痕扫到装订边之后卷筒只能滑出，无法在另一侧摊开），
      需要先补相位 B（卷起来之后绕中缝转到对侧、纸背用真实内容），
      再把背景改成"当前左页 + 目标右页"的两段拼接、叶片做成半页裁剪
- [ ] 双页纸背改用真实内容（目标跨页另一半页）

### 阶段 5 · 退役旧路径 + 真机（随整体放弃而作废）
- [ ] 双页也换完之后删除 `PageCurlShader.kt` 与铰链几何、`HingeFrameRenderTest` /
      `HingeSoftwareRenderer` / `PageCurlShaderTest`
- [ ] 真机 profile：重折/阔折叠 120fps、`CURL_MESH_INTERVAL_PX` 定值、确认不误触发降级

### 待归档（已完成但未提交）
- [x] `HingeSoftwareRenderer` 与生产 AGSL 失同步修复（`7adaf5b` 改了自由边高光/暗影但没同步镜像）
- [x] `PageTurnCompareRenderTest`：三模型并排出图（现铰链 / 同模型加透视 / 圆柱卷曲参考）
      与「只有卷曲模型保持正文不变形」的断言

---

## M5.13 仿真翻页：**已放弃**（只保留覆盖 / 无动画 / 上下滚动）

> **决定**：放弃全部仿真翻页实现，翻页方式只保留**覆盖滑动（COVER）、无动画（NONE）、
> 上下滚动（SCROLL）**。以"放弃现有 DB 全部向前兼容性、不考虑迁移、只考虑新安装"为前提执行。
>
> **放弃理由**（避免以后重复投入）：
> 1. 三版模型（AGSL 铰链 → 圆柱卷曲 → 紧圆角+斜面）都没能达到与参考（微信读书录屏）一致的
>    观感；核心症结是"翻起的那片纸"始终读起来像被挤出来的角/条，而不是一张摊平的纸；
> 2. 净收益始终压不过维护成本：仿真翻页带来一整套额外状态（动画状态机、离屏位图缓存、
>    掉帧降级、正/背纹理、透视与分层阴影），而覆盖/无动画/滚动三种模式已经够用；
> 3. 已验证模型**无法用简单调参收敛**（透视、阴影、圆心半径都试过），继续投入的边际收益极低。
>
> **历史**：完整实现保留在提交 `9a0c0ad`（单页仿真翻页改为卷曲几何）。任何时候
> `git show 9a0c0ad` 都能取回。

### 删除清单（已完成）
- [x] 删除测试与出图工具：`CurlGeometryTest` / `CurlShadowsTest` / `CurlFrameRenderTest` /
      `CurlSoftwareRenderer` / `PageTurnCompareRenderTest` / `HingeFrameRenderTest` /
      `HingeSoftwareRenderer` / `PageCurlShaderTest` / `SimulationTurnTest` / `SpreadBitmapCacheTest`
- [x] 入口：`ReaderMenu` / `SettingsScreen` 去掉"仿真"；`effectivePageTurnMode` 双页默认改 `COVER`
- [x] `ReaderScreen`：删 sim 状态、两路 `pointerInput`、渲染分支、`maybeDegradeSimulation`
- [x] `ReaderViewModel`：删 `curlBitmap` / `renderCurlBitmap` / `pregenCurlBitmaps` /
      `setCurlRenderContext` / `setSimulationDegraded` / `clearBitmaps`
- [x] 支撑件：`SimulationTurn.kt`、`SpreadBitmapCache.kt`、
      `PageRenderer` 里的 `renderSpreadToBitmap` / `SpreadGeom`
- [x] 铰链：`PageCurlShader.kt`
- [x] 卷曲：`CurlGeometry.kt` / `CurlShadows.kt` / `CurlOverlay.kt`
- [x] 偏好与持久化：`ReadingPreferences` 去掉 `SIMULATION`；`BookPrefsEntity` 去掉
      `simulationDegraded`；`BackupCodec` 同步；`FoldReaderDatabase` 见下方「数据库基线重置」
- [x] `ReaderTheme.pageBackColor` 删除
- [x] 说明书 5.1 / 5.2 回到"只保留覆盖 / 无动画 / 上下滚动"（4.3、5.6 功能表、路线图、风险表一并同步）

### 顺带清理（已完成）

> 应用处于预发布阶段，只保证新安装 → 不再保留任何 schema 历史与迁移，
> 也不需要"升版本 + 不写迁移"这种半吊子状态；因仿真翻页才存在的判据字段一并清掉。
>
> **v1.0.0 发布后已改回**：见 M11「恢复向前兼容」——DB 重新启用迁移，
> 已发布版本的 schema 快照不再删除。

#### 数据库基线重置

- [x] 删 `FoldReaderMigrations.kt`（`MIGRATION_10_11` ~ `MIGRATION_13_14`）与 `@Database(autoMigrations = …)`
- [x] `FoldReaderApplication` 去掉 `addMigrations(…)`
- [x] 删历史 schema 快照（`app/schemas/…` 的 1–14.json）与迁移/schema 测试
      （`MigrationV11`~`V14Test` / `SchemaV5`~`V14Test`）
- [x] `FoldReaderDatabase` 回到单一基线 `version = 1`，重新导出同名 schema 快照
- [x] 说明书附录补 v1.8 变更说明（含"旧附录标题里的「数据库 vNN」已不对应现行 schema"）

#### 仿真遗留字段：`pageTurnModeExplicit`

判据原文是"用户没显式设置过就按姿态取默认（双页仿真、单页覆盖）"——仿真没了之后，
它退化成"没设置过就用 COVER"，而默认值本来就是 COVER，恒等于直接用存储值。

- [x] `ReadingPreferences` / `BookPrefsEntity` 去掉该字段（含 `BookPrefsDao` 的批量更新 SQL）
- [x] `SettingsRepository` / `SettingsRepositoryImpl` 去掉 `setPageTurnModeExplicit`
- [x] `BackupCodec` 导出/导入同步去掉该键
- [x] 删 `ReaderLogic.effectivePageTurnMode`，`ReaderScreen` 直接用 `prefs.pageTurnMode`
- [x] 测试同步（`ReadingPreferencesTest` / `BookPrefsRepositoryTest` / `BackupCodecTest` / `ReaderLogicTest`）

---

## M5.14 竖持退回单页（已完成）

> 详见 `docs/需求与设计说明书.md` 附录「v1.9 竖持退回单页」。
> 起因：阔折叠展开后竖着拿，阅读页仍是左右双页，每页窄到无法成行。

**根因**：`resolvePageLayoutMode` 的双页判定只看姿态与宽度类别，不看窗口方向——
阔折叠多为上下折，展开时上报水平铰链，于是竖持照样命中"FLAT + 铰链 → 双页"
（实测设备内屏横持 860×608dp，竖持即 608×860dp）；"EXPANDED + 宽屏双页"那条同样不带方向
（宽度类别只按宽边算，900×1000 与 1000×900 同为 EXPANDED）。

- [x] 判定加方向前置条件：自动模式下窗口**宽 ≥ 高**才允许双页（`resolvePageLayoutMode` 增 `windowPortrait`）
- [x] `FoldableUiState` 增 `windowPortrait`，取自**真实窗口尺寸**（`LocalConfiguration` 的 `screenWidthDp`/`screenHeightDp`）——
      不能用 `WindowSizeClass` 的 `minWidthDp`/`minHeightDp`：那是断点下限（宽 600/840/…），竖持 608×860dp 会落成 600×480，反判成横向
- [x] `ReaderScreen` 接线；横竖切换仍走既有"尺寸变化 → 重分页 → 锚点定位"链路
- [x] 说明书 3.1 / 5.2 / 5.4 与产品决策表同步；设置页"宽屏双页"标注仅横屏生效
- [x] "强制双页"不受方向限制（想要竖持双页的用户仍有显式出口）
- [x] 测试：`WindowPortraitTest`（窗口方向只看真实宽高、宽高相等不判竖向）+
      `ReaderLogicTest` 新增「竖持退回单页」（两条误判路径 + 强制双页出口），既有横持用例逐一补 `windowPortrait = false`

---

## M12 网络小说智能清理（已完成）

> 详见 `docs/需求与设计说明书.md` 附录「v2.3 网络小说智能清理」。
> 形态决策：确定性规则引擎（离线、零权限、可回归测试），不引入端侧模型 / 云端 LLM。

### M12.1 清洗引擎（`core/format/clean/`）
- [x] `CleanProfile` / `CleanToggles` / `CleanLevel`：三档预设 + 14 项细粒度开关；`ENTRIES`
      作为序列化 / 设置页 / 备份的**唯一有序来源**
- [x] `NovelCleaner`：流式管线（计数 → 繁简 → 字符归一 → 行内空白 → 噪音过滤 → 行中标题提行
      → 标点 → 段落重组 → 章节修复 → 引号规整 → 段首缩进 → 写出），只保留一行 pushback
- [x] `CharRules`（不可见字符/全角空格/HTML 实体与标签/UBB）、`WhitespaceRules`、`NoiseRules`
      （整行规则库 + 行内切除 + 遮罩规整）、`PunctuationRules`、`ParagraphRules`、
      `BlankLineRules`、`ChapterRepairRules`、`TsCharMap`
- [x] 判据全部**局部化**：不按全文比例判定硬换行/空行去留——那会让采样开关在清洗前后漂移，
      破坏幂等（测试集抓到过）
- [x] 删除旧 `TextCleaner`，能力全部并入 `clean/`（一处真相）

### M12.2 测试集（`app/src/test/resources/novel-corpus/`）
- [x] 18 条正向用例（input/expected + 人读说明 + 可选 `profile.txt`），覆盖用户提出的 10 类问题
      与补充调研出的字符/编码、行/段落、章节结构、内容噪音四层
- [x] 5 条反向用例（`negatives/`）：诗行、拉丁词句、正文里的「收藏」、隔着正文的重复标题、
      已排好的书——必须**逐字节不变**
- [x] `NovelCorpusTest` 横向断言：全语料**幂等**（清洗两遍第二遍无变化）+ 反向用例零改动
- [x] 各规则单测 + `CleanTogglesTest`（档位逐级包含、序列化往返、条目唯一）

### M12.3 接入与偏好
- [x] 导入链路改用 `CleanProfile`；`ImportBookUseCase` 返回 `CleanReport`
- [x] 全局偏好 `cleanLevel` / `cleanToggles`（DataStore，**不升 DB**——清洗是应用级选择）；
      `setCleanLevel` / `setCleanToggle` / `setCleanProfile`（备份恢复用后者，避免逐项写把档位打成自定义）
- [x] 备份导出/导入往返；旧备份缺字段时保持现有设置
- [x] 设置页「智能清理」：档位选择 + 规则明细（逐项开关）+ 去广告行规则
- [x] 导入对话框：不清理/保守/标准/激进 + 繁简开关 + **清洗预览**（采样 256KB，给计数与 before/after 样例）
- [x] `RecleanBookUseCase`：读**原始源文件**重洗（所以可反复换档位，结果只取决于「原文 + 本次配方」）、
      写新副本、`changed` 按当前副本实际哈希判定、显式作废偏移索引与页边界、重建目录、
      清理孤儿副本；`contentHash` 刻意不改
- [x] 「不清理」= **撤销清理**：删副本、恢复**原始文件的编码**（副本恒为 UTF-8，直接沿用会乱码）、作废缓存
- [x] 书籍详情「智能整理」入口：档位**当场可选**（改一次重新预览一次）→ 预览 → 确认
      （明说进度/书签偏移可能变化）→ 执行

### M12.4 已知取舍（记录在案）
- [x] 只支持 TXT：EPUB/FB2/PDF-文本 有真实结构，不产生「硬换行 + 广告行」问题
- [x] 缺章/乱序/空章只报告不自动改
- [x] 导出清洗后的 TXT 到用户目录：未做（副本已在库内，属锦上添花）

---

## M6+ 格式扩展（后置）
- [x] EPUB 解析器实现 `BookParser` 接口（core/format/epub），阅读器/UI 零改动验证
      —— 已实现：EPUB3 NAV / EPUB2 NCX 目录、page-list 纸书页码、样式与链接 span、图片抽取、
      元数据全字段。压平后复用 TXT 管线，阅读器零分支
- [x] FB2 支持（含 `.fb2.zip` 变体）
- [ ] MOBI / AZW3 / 其他格式评估

---

## M7 漫画阅读模式（已完成）

> 详见 `docs/需求与设计说明书.md` 附录「v2.0 漫画阅读模式」。
> 决策：融入现有书架（`BookFormat.COMIC`）、默认引用外部源 + 可选复制到本地、格式全量、
> 首批功能全上（缩放平移 + 适应模式、左右方向、纵向连续滚动、双页与跨页）。

### M7.1 漫画内容层（core/comic）
- [x] 容器统一接口 `ComicArchive`：按页序号取字节 + 只读图片头拿尺寸 + 字节 LRU
- [x] `ZipComicArchive`：commons-compress `ZipFile` + `SeekableByteChannel`，读中央目录按条目取图
- [x] 顺序容器一次性解压：`ComicArchiveExtractor`（tar / 7z / rar），junrar 走 UnRAR License
- [x] `ComicExtractionStore`：`cache/<hash>` 可回收 + `local/<hash>` 用户副本 + per-hash 锁 + GC
- [x] 页序与噪音过滤 `ComicPageOrdering`（自然序、`__MACOSX`/`._*`/`Thumbs.db`）
- [x] 容器识别 `ComicContainers`（魔数优先；`ustar` 需扩展名印证，避免文本误判）
- [x] 图片头尺寸解析 `ComicImageSizing`（纯 Java，PNG/JPEG/GIF/BMP/WebP）
- [x] 依赖：commons-compress 1.28 + org.tukaani:xz（LZMA）+ junrar；R8 `-dontwarn` 可选后端
- [x] SAF 目录访问抽成共用的 `SafTree`（文件浏览器 / 批量导入 / 目录漫画三处共用）

### M7.2 数据模型与导入
- [x] `BookFormat.COMIC` + `comicContainer` / `comicPageCount` / `comicLocalPath`（DB v1 基线下重导出 schema）
- [x] `reading_progress.comicPage`：漫画位置按页序号存，不与字符偏移混用
- [x] `ComicImportUseCase`：只登记元数据 + 抽首页封面（zip/目录）+ 页数未知时标记待预热
- [x] 导入分支接线（`ImportBookUseCase` / `FormatDetector` / `isSupportedBookName`）
- [x] 书架：容器徽标、页码进度、详情页漫画分支（页数/容器/存储方式）
- [x] 页数回填用专用 `UPDATE`（`INSERT OR REPLACE` 会因外键 NO_ACTION 在已有进度时失败）

### M7.3 阅读器（feature/comic）
- [x] `ReaderHost` 按格式分流；文本阅读器对 `COMIC` 防御性报错
- [x] 沉浸外壳共享件抽出：`ReaderEffects`（系统栏/亮度）+ `rememberReaderExit`（返回落地门控）
- [x] 单页阅读：点击热区 / 横滑 / 音量键 / 覆盖滑动（COVER）与无动画（NONE）
- [x] 进度：页序号锚点、防抖落库、恢复上次位置、阅读时长按天分桶
- [x] 双页与跨页：`ComicSpreadIndex` 预计算配对（封面单独 / 宽图独占整宽）
- [x] 阅读方向 RTL：热区、滑动、双页左右归属
- [x] 缩放与平移：双指缩放（中点锚）+ 拖动 + 四种适应模式；未溢出时单指拖动让给翻页
- [x] 纵向连续滚动：按真实宽高比定条目高度、页间距 0–24dp、滚动位置反写进度
- [x] 缩略图网格跳转：内存 → 磁盘 JPEG → 容器读页三级缓存，打开滚到当前页
- [x] 动画 GIF / 动态 WebP：`ImageDecoder` 出 Drawable + 按帧驱动绘制失效
- [x] `onTrimMemory` 释放、解码位图双预算、离当前页最远的页先淘汰

### M7.4 漫画库入口
- [x] 目录扫描识别「图片目录 = 一本」（≥3 张图片，命中即不下探）
- [x] 「导入目录为分组」：卷目录与容器文件混排都收，目录漫画走独立登记路径
- [x] 文件浏览器长按 → 「以漫画打开」/「整个目录导入为分组」
- [x] `intent-filter` 补漫画 MIME（cbz/cbr/cbt/cb7 与通用压缩包写法）

### M7.5 打磨
- [x] 「复制到本地」/「删除本地副本」（详情页；脱离 SAF 授权）
- [x] 后台预热：rar/tar/7z 解压 + 页数/封面回填，复用「待解析」角标
- [x] 缓存 GC 接入启动维护（只清 cache，不动本地副本）
- [x] 设置页「漫画」分区（方向/适应/封面单独/跨页识别/滚动页间距）
- [x] 备份导出/导入含漫画偏好字段
- [x] 单测：页序、容器、解压、提取存储、导入、配对索引、适应尺寸、平移钳制、格式判定

### M7.6 待真机验证（非开发任务）
- [ ] 推一个漫画目录进设备 → 文件浏览器长按「以漫画打开」并翻页
- [ ] 用书架「导入目录为分组」导一个系列目录，确认卷目录各成一本
- [ ] `.cbz` / `.cbt` / `.cb7` / `.cbr` 各一本：徽标、待解析角标与后台消失
- [ ] 折叠展开双页 + 铰链避让、日漫 RTL、条漫滚动、缩略图跳转、双指缩放
- [ ] 删源文件后打开 → 报错 → 「复制到本地」后恢复可读

---

## M8 PDF 阅读（进行中）

> 渲染用 `androidx.pdf` 的沙箱文档服务，元数据/目录/文本用 PdfBox。
> 页式阅读**复用漫画那条路径**（`PagedImageSource` 接缝），只有"来源"不同。

### M8.1 依赖与可行性（已完成）
- [x] `androidx.pdf:pdf-core` + `pdf-document-service`（1.0.0-beta01）+ `pdfbox-android`
- [x] 冒烟：`SandboxedPdfLoader.openDocument` → `getPageBitmapSource(0).getBitmap` 出图；PdfBox 读元数据/正文
- [x] **不要排除 `pdf-viewer`**：它是 `pdf-document-service` 的**运行时**依赖
      （服务端 `getPageDimensions` 引用 `androidx.pdf.models.Dimensions`）。
      一旦排除，独立进程在该调用上 `NoClassDefFoundError` → 进程 FATAL → binder 死 →
      整个文档作废（现场只见 `DeadObjectException`）。P1 曾把它当"成品 UI"排掉，是错的。
- [x] `PDFBoxResourceLoader.init(context)` 必须调（AFM/CMap 在 AAR 的 assets 里）
- [x] 独立进程会重跑 `Application.onCreate`：加 `isIsolatedProcess()` 早退，
      否则 `DiagnosticLog` 碰 SharedPreferences 直接杀进程，表现是 `openDocument` 永久挂起

### M8.2 页式阅读器通用化（已完成）
- [x] `core/paged/PagedImageSource`：页式阅读唯一接缝（页数 / 批量宽高比 / 出图 / 缩略图）
- [x] `PagedPageImage`（Still / Animated）从漫画 ViewModel 上提到 core/paged
- [x] `PagedReaderFeatures`：格式能力差异（PDF 关掉 RTL / 跨页配对 / 无缝拼接，菜单据此隐藏）
- [x] `ComicPagedSource`：漫画容器适配（解码与动画判定留在漫画侧）
- [x] 验收：555 个单测全绿，漫画零回归

### M8.3 PDF 页式阅读（已完成）
- [x] `PdfPagedSource`：沙箱渲染，**渲染串行化**（一次只能开一页）+ 批量宽高比 + 缩略图
- [x] `PagedSourcePasswordRequired`：加密 PDF 弹密码框重试，密码不落库
- [x] 导入：`PdfImportUseCase` 只登记（页数留给打开时回填 / 预热），`FormatDetector` 识别 PDF
- [x] 预热：`BookPrewarmQueue.pdfPrepare` 开文档回填页数；加密的失败 → 「待解析」角标留着
- [x] 书架/详情：PDF 徽标、按页进度、详情页走页式字段
- [x] 验证：debug + release（R8 + 资源压缩）全绿；仪器化端到端（导入 → 出图 → 宽高比）通过

### M8.4 元数据 / 目录 / 封面（已完成）
- [x] `PdfBoxReader`：DocumentInformation（title/author/subject/keywords）+ 内嵌目录树
      （展平成 depth + pageIndex）+ 首页封面渲染 + `AccessPermission.canExtractContent`
- [x] 目录/封面写入：`chapters.pageIndex`（页式锚点，charStart/charEnd 写 0）、`covers/<hash>.jpg`
- [x] `books.backfillPdfMetadata`：只填空值（COALESCE），不覆盖已有内容
- [x] 预热接线：`preparePdf` → PdfBox；PdfBox 解析不了时退回"只用沙箱拿页数"
- [x] 阅读器：菜单「目录」入口 + 复用文本阅读器的目录对话框（跳转到页序号）
- [x] 验证：仪器化测试覆盖元数据/目录层级(0,1,0)/页锚点(1,2,4)/封面/预热写库全链路

### M8.5 文本 PDF 额外当电子书（已完成）
- [x] `PdfBoxReader` 抽正文：`PDFTextStripper` 用分页哨兵**一次遍历**同时拿全文与每页起始偏移
- [x] 扫描件判定：平均每页字符数不足 → **不产出压平产物、也不报错**（不写空文件出来骗人）
- [x] `PdfBookParser`：压平进 TXT 管线（分页/搜索/书签/划线/统计全部复用，零重写）
- [x] 目录两套锚点并存：压平时实测页首偏移 → 同一目录项同时写 `pageIndex` 与 `charStart/charEnd`
- [x] 纸书页码：按页写 `PageLabel`，「第 N 页」
- [x] `book_prefs.pdfReadingMode`（PAGED/TEXT，null = 由文档决定）+ `resolvePdfReadingMode` 纯函数
- [x] 路由：`ReaderHost` 观察书与偏好，模式一变**当场换阅读器**（不用退出去重进）
- [x] 双向切换：页式菜单「切到文字模式」/ 文本菜单「页式」；扫描件显示「这是扫描件，暂不支持取字」
- [x] `books.updateConvertedFile` 落 `cleanedFilePath` + `totalChars`（字符数流式数，不用字节数）
- [x] 验证：仪器化覆盖 文本型压平可读 / 章头锚点落在正确页 / 扫描件无产物 / 预热全链路

### M8.6 待真机验证（非开发任务）
- [ ] 推一个文本型 PDF 与一个扫描型 PDF：进书架、翻页、缩略图、双指缩放
- [ ] 文本型 PDF：切文字模式 → 调字号/搜索/加书签 → 切回页式，位置各自保持
- [ ] 加密 PDF：弹密码框 → 输对可读、输错有提示
- [ ] 一个 >250MB 的 PDF（官方已知性能问题，超阈值时给提示）
- [ ] 折叠展开时 PDF 双页与铰链避让

### M8.7 已知取舍（记录在案，不做）
- [x] 扫描件 OCR：系统层没有可用的公开 OCR API（Pixel 的取字是 System Intelligence 内部组件），
      界面显示「这是扫描件，暂不支持取字」而不是留一个点了没反应的按钮
- [x] `androidx.pdf.ocr.OcrProvider` 只被它自己的 `PdfViewer` 消费，自绘路径用不上
- [x] PdfBox 的可选 `com.gemalto.jp2`（JPEG2000）未打包：只影响嵌 JP2 图的解码与封面渲染，
      R8 用 `-dontwarn` 放行
- [x] PDF `PageLabels` 未接线：纸书页码先用「第 N 页」

---

## M9 页内锚点 + 可配中间点击区 + 同系列切换（已完成）

方案：`~/.commandcode/plans/paged-anchor-tapzone-series.md`

### M9.0 正确性修复（已完成）
- [x] 双模式 PDF 进度互覆盖：`reading_progress` 是关键书一行 + 整行 REPLACE，
      `ReaderViewModel` 保存时清空了 `comicPage`（页式读到第 50 页 → 切文本 → 切回，位置没了）。
      改为对称的 `ReadingProgressEntity.keepPagedAnchor` / `keepTextAnchor`，两个阅读器都走它
- [x] 页式书签跳转落到第 0 页：总览弹窗传的是 `charOffset`（页式恒为 0）。
      加 `BookmarkEntity.readerAnchor()` / `AnnotationEntity.readerAnchor()`，总览统一走它
- [x] 验证：`ReadingProgressAnchorTest`（含页式⇄文本来回切换的往返用例）

### M9.1 中间点击区 + 单击/双击派发（已完成）
- [x] `TapZone.MENU` → `TapZone.MIDDLE`（中间区不再天生等于「菜单」）；新增 `TapAction` 设置枚举
- [x] 中间单击默认「菜单」、双击默认「缩放」（页式）；均可配（设置 → 阅读 → 中间点击 / 中间双击）
- [x] `MiddleTapLayer`：**只覆盖「判定为中间区」的那块矩形**，所以左右翻页与底边翻页条保持抬手即响应
      ——这正是之前否掉「双击缩放」的顾虑（给根手势加 `onDoubleTap` 会让每次单击都等 300ms）
- [x] `supportsTapAction`：阅读器执行不了的动作（文本阅读器的「缩放」）**整层不挂**，单击零延迟
- [x] 双击缩放 = 适配模式在「整页 ⇄ 宽度」间切换（本阅读器的缩放本来就每页重置，临时倍率翻页即失效）
- [x] 验证：`middleZoneRect` 与 `tapZoneOf` 一致性、双击判定、动作回退、`supportsTapAction`

### M9.2 页内锚点数据模型（已完成）
- [x] `BookmarkEntity` 加 `pageIndex` + `anchorX/Y/W/H`；`AnnotationEntity` 加 `pageIndex` + `regionX/Y/W/H`
      —— **一律归一化 0..1**：渲染尺寸随适配模式/缩放/窗口变化，存像素必错位
- [x] 索引 `(bookId, pageIndex)`；DB 基线仍为 v1（预发布政策：只保证新安装，不写迁移）
      —— **该政策已于 v1.0.0 后废止**（见 M11）
- [x] 备份 v4 → v5：导出/导入新字段；**去重键改为位置感知**（原先只按 `charOffset`，
      页式书签全是 0 会互相吞掉）
- [x] 文本/页式锚点互相隔离：文本阅读器只取 `pageIndex == null`，页式只取 `!= null`；
      顺带修掉 `verifyAnnotationSnapshots` 会把页式标注全判成「错位」的问题
- [x] 验证：`BookmarkLogicTest` 页式 toggle/容差/排序、`BackupCodecTest` v5

### M9.3 页式长按锚点与框选（已完成）
- [x] 纯几何：`comicDrawRect` / `comicNormalizedAt` / `comicScreenPosition` / `comicZoomAnchored`
      （绘制、命中、反查、缩放锚点共用同一套落位公式）
- [x] 顺带修正**双指缩放锚点漂移**：原公式漏了「捏合点相对页面中心」一项，偏离中心时不跟手
- [x] 长按不动 = 点书签；长按拖动 = 框选（`MIN_SELECTION_SIZE` 区分，避免指腹抖动被当成框选）
- [x] 有文字层时吸附成选字（`snapSelectionToText`），无文字层退化为自由矩形
- [x] 锚点手势放在**页内部**（`ComicPageView`）：天然知道页序号与页内坐标，双页模式无需算铰链
- [x] 纵向连续滚动模式暂不接受锚点手势（页内坐标与屏幕坐标不是一套换算），但锚点照常显示
- [x] 验证：`ComicLogicTest`（归一化往返/缩放锚点/越界）、`PagedAnchorUiTest`（吸附与点判定）

### M9.4 页式锚点渲染 + 列表 + 跳转（已完成）
- [x] 绘制：书签点/区域描边、高亮半透明填充、下划线、选区框（归一化 × 当前绘制尺寸，随缩放贴合）
- [x] 动作条：色点即高亮 / 下划线 / 书签(区域) / 取消（复用 `SelectionActionBar`，文本侧的「笔记」在页式隐藏）
- [x] 顶栏书签缎带（页级书签开关）+ 书签列表入口 + 标注列表入口（跳转 / 改色 / 改样式 / 删除）
- [x] 验证：编译 + 全量单测（578 项）

### M9.5 PDF 页内选字（已完成）
- [x] **API 探针**（`pdfprobe`，对 1.0.0-beta01 的 pdf-core）：`PdfDocument.getSelectionBounds(page, PointF, PointF)`
      直接吃页内两点返回 `PageSelection`（含 `PdfPageTextContent` 的并集矩形与文字），
      `getPageContent(page)` 给页文本，`searchDocument(q, range)` 给逐页命中，`getPageLinks(page)` 给链接
- [x] 结论：**不自己取文本再吸附**，改用文档自己的选区——阅读顺序/连字/分栏只有它知道，且更准更省
- [x] `PagedImageSource.selectText(index, startX/Y, stopX/Y)`（归一化进出；默认 null，漫画零改动）
- [x] `PdfPagedSource`：页点尺寸与宽高比探测**共用一次 IPC 并缓存**；选字只调一次，拖框期间不回源
- [x] 交互：先按手指画的框亮出动作条，松手后异步换成文档选区；失败/扫描件保留原始框
- [x] 动作条「复制」只在拿到选中文字时出现；已确认扫描件直接跳过 IPC
- [ ] 待真机：文字型 PDF 拖框是否跟手、多行选区是不是一整块（并集框，与「区域是一个框」的模型一致）

### M9.6 漫画/PDF「同系列」前后切换（已完成）
- [x] `core/comic/ComicSeriesMatch.kt`（纯逻辑 + 单测）：主干归一化（去扩展名/括号标签/全角/卷号）、
      卷号提取（`第3卷`/`第三巻`/`v03`/`vol.3`/`ch.5`/`- 09`/`(11)`/`_04`）、同系列判等、排序、两来源合并
- [x] 铁律：**只认「主干相等」，不做相似度匹配**——误判的代价是跳到一本不相干的书，宁可漏
- [x] `SafTree.listSiblingsOfDocument`：SAF 没有取父目录的 API，从 documentId 反推（`primary:a/b/c.cbz` → `primary:a/b`）；
      不透明 id 返回空 → 功能降级而不是猜错目标（附单测）
- [x] 两个来源：同目录（可能未导入）+ 库内（同分组或同目录）；同 uri 去重且库内优先（带 bookId）
- [x] 未入库的卷显示「未导入」，点击**按需导入**（复用外部导入链路）后打开
- [x] UI：菜单「上一卷 / 下一卷 / 同系列」+「3/12」位置；切换用 `popUpTo`，返回回书架而不是退回上一卷
- [ ] 待真机：授权失效/不透明 id 的提供方上，入口应安静禁用

### M9.7 既有缺口补齐（部分完成）
- [x] PDF `searchDocument` 接入：菜单「搜索」→ 逐页命中列表 → 点一条跳页
      （命中只给字符下标，上下文按 `textStartIndex` 回取页文本切片；只给前 40 页取，避免一串 IPC）
- [x] 自动翻页接页式：间隔模式到点发请求走**正常翻页动画**；滚动模式按 px/s 匀速推进
      （`dispatchRawDelta` 同步推进，不必每帧起协程）；手动操作后暂停一段时间（沿用文本阅读器的时钟）
- [x] 跳到指定页：页数多时进度条点不准，给数字输入（1 基显示、越界夹取）
- [x] 外接键盘/鼠标：方向键 / PageUp-Down / 空格 / 媒体键翻页，滚轮翻页（滚动模式下滚动）
- [x] >250MB 提示：菜单里一句「文件较大，翻页与缩放可能偏慢」（不挡开书）
- [ ] **PDF 内链跳转未做**：需要「根坐标 → 页内坐标」的映射，而 M9.3 刻意把锚点手势放在页内部
      以避开这层管道；链接只影响脚注/交叉引用这一类场景，暂不值得为此重做坐标层
- [ ] **缩放跨页保持未做**：本阅读器的缩放是每页重置的设计（换页即复位），
      临时倍率跨页保留会让「这一页为什么是放大的」变得不可预期；适配模式本身是持久化的
- [ ] **纵向连续模式下的锚点手势未做**：条漫里页内坐标与屏幕坐标不是一套换算，
      要像文本阅读器那样再维护一份滚动命中逻辑

### M9.8 待真机验证（非开发任务）
- [ ] 缩放/平移后长按，锚点是否仍落在按下处；切适配模式后是否跟随
- [ ] 折叠展开双页模式下，左右页各自的锚点是否落在正确的页
- [ ] 长按不动 vs 长按拖动能否稳定区分；长按绝不触发翻页
- [ ] 中间区双击生效，且左右区单击**无额外延迟**
- [ ] 文字型 PDF：拖框选字是否跟手、复制是否拿到文字；扫描件是否退化为框选
- [ ] 同系列：同目录/分组能否认出前后卷；未导入的卷按需导入后能否直接打开
- [ ] 自动翻页：间隔到点是否顺畅翻页、手动点按后是否暂停
- [x] ~~有旧安装的设备需清数据重装（DB 基线已变）~~ 已不需要：v1 → v2 走 `MIGRATION_1_2` 原地升级（见 M11）

---

## M10 开源化与发布流水线（已完成）

目标：把项目从「只有源码和 TODO」补成一个可对外发布的 GitHub 正式项目，并打通 tag 触发的自动打包发布。

### M10.1 仓库元文件
- [x] `LICENSE`：MIT
- [x] `README.md`：中文完整版（徽章、功能、格式表、安装、构建、架构、技术栈、已知限制、许可署名）
- [x] `README_EN.md`：英文精简版，与中文版互相加语言切换链接
- [x] `CHANGELOG.md`：Keep a Changelog + SemVer，`1.0.0` 首版按 Added/Changed/Removed/Fixed 归纳 48 个提交
- [x] `CONTRIBUTING.md` / `SECURITY.md` / `CODE_OF_CONDUCT.md`
- [x] `.editorconfig`、`.gitattributes`（强制 LF，防 Windows 下 `gradlew` 变 CRLF 挂掉 Linux CI）
- [x] `.gitignore` 补 `key.properties` / `*.jks` / `*.keystore` / `*.apk` / `*.aab`（原先完全没有覆盖密钥文件）
- [x] `.github/ISSUE_TEMPLATE/`（bug 表单含**屏幕形态**必填、feature 表单）、`pull_request_template.md`、`dependabot.yml`

### M10.2 构建改造
- [x] 版本号可注入：`gradle.properties` 提供默认值，`-PfoldReader.versionCode/-PfoldReader.versionName` 覆盖；
      用 `providers.gradleProperty` 而非 `System.getenv`，与已开启的配置缓存兼容
- [x] Release 签名：环境变量 > 根目录 `key.properties` > 回退 debug；只按「密钥四项是否齐全」判断，
      不做文件存在性探测，避免与配置缓存耦合
- [x] **顺带修复**：应用内「开源许可」弹窗原先只列 5 项，漏了 junrar(UnRAR，有署名要求) / PDFBox /
      androidx.pdf / commons-compress / xz；抽到 `OpenSourceLicenses.kt` 并补全
- [x] **顺带修复**：`Locale.getDefault()` 在 Composable 里不可观察（lint `NonObservableLocale`）。
      直接 `assembleRelease` 只跑 `lintVitalRelease`，子集不含这条，因此一直没暴露——CI 加上
      `lintDebug` 后立刻变成红灯。新增 `ui/LocaleSupport.kt` 的 `rememberLocale()`（走 `LocalConfiguration`），
      并把 6 处同款写法一次扫干净：书籍详情、书签列表、全书签总览、书架副标题、书架封面、阅读器时钟。
      纯函数（`formatLastRead` / `formatTime`）改为由调用方传入 Locale，`bookSubtitle` 同步加参数
- [x] 验证：`.github/actions/setup-build/action.yml` 把 JDK 25 + Gradle 缓存 + Android SDK 安装抽成 composite action，
      两个 workflow 共用（`sdkmanager` 包名是整条流水线唯一有猜度的一处，集中在一个文件里便于修）

### M10.3 流水线
- [x] `.github/workflows/ci.yml`：push main / PR / 手动触发，单元测试 + Debug 构建 + 上传产物，Lint 单独成 job
- [x] `.github/workflows/release.yml`：`v*` tag 触发（版本格式在 job 内严格校验，非法 tag 带明确信息失败）；
      解析 versionName/versionCode、`apksigner` 验签、发布 APK + AAB + R8 mapping 到 GitHub Release
- [x] 签名降级策略：Secrets 齐全走正式签名；缺任一项退化为 debug 签名，并打告警 + 写 Job Summary +
      在 Release 说明顶部加 `[!WARNING]`。之所以不能只是静默回退：签名不一致会让设备拒绝覆盖安装，
      用户从 debug 签名的版本升级前必须先卸载，这件事必须写在下载页
- [x] Release 说明按 `git log` 自行生成而非用 `--generate-notes`：本仓库单人开发、无 PR，
      自动生成只会得到提交列表；首个版本折叠收起全部提交并指向 CHANGELOG，后续版本给出与上一 tag 的对比链接
- [x] 版本号规则 `major*10000 + minor*100 + patch`，带 `-` 后缀的 tag 自动标为 Pre-release
- [x] 变量一律经 `env:` 传入而非直接插值进 `run:`，避免 tag 名里的元字符注入构建命令
- [x] `docs/发布流程.md`：keystore 生成、Base64 转换、4 个 Secrets 配置、发布步骤、排错表
- [x] `docs/images/README.md`：README 截图采集清单（README 暂不含截图区，避免破图）

### M10.4 验证结果
- [x] `:app:testDebugUnitTest` 全绿，606 项 / 80 个文件（含新增 `OpenSourceLicensesTest`、
      以及 `ReadingProgressFormatTest` 中新增的 Locale 排版用例）
- [x] `:app:lintDebug` 0 error（修掉上面那 2 处 `NonObservableLocale` 后）
- [x] 无 `key.properties` 时 `assembleRelease` 成功且证书为 debug（与改动前行为一致）
- [x] 放入 `key.properties` 后证书切换为正式证书；**删掉后能切回 debug**——
      两个方向都验过，确认配置缓存不会把签名状态缓存住
- [x] 版本注入：`-PfoldReader.versionCode=999 -PfoldReader.versionName=9.9.9` 生效
- [x] `git add --renormalize .` 后无意外改动，`gradlew` 保持 LF、`gradlew.bat` 保持 CRLF

### M10.5 首次发布实跑结果（v1.0.0，2026-09-18）
- [x] `main` 上 CI 跑通：单元测试（606 项）、Lint、Debug 构建、产物上传，全部成功
- [x] `v1.0.0` tag 上 Release 跑通：解析版本号 → 确定签名方式（debug 降级，`写入签名配置` 被正确跳过）
      → 构建 APK + AAB → apksigner 验签 → 整理产物 → 生成说明 → 创建 GitHub Release
- [x] 线上产物：`FoldReader-1.0.0.apk`（约 11.1 MB）、`FoldReader-1.0.0.aab`（约 12.9 MB）、
      `FoldReader-1.0.0-mapping.txt`（约 66.5 MB）
- [x] 下载已发布的 APK 复核：`versionCode=10000`、`versionName=1.0.0`（tag 驱动注入生效）、
      证书 `CN=Android Debug`。CI 出的 debug 证书指纹与本地不同，印证了「debug 密钥由构建机即时生成」
- [x] **顺带修复**：`gradlew` 在 git 里是 100644（无可执行位），Linux runner 上 `./gradlew` 直接
      exit 126，两个 job 一步没跑就挂。仓库在 Windows 上创建、本地跑的是 `gradlew.bat`，所以一直没暴露

### M10.6 待人工
- [ ] 生成正式 keystore 并配置 `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`
      （配好后下次发布自动切正式签名，告警与说明里的 debug 条目会自动消失）
- [ ] README 截图按 `docs/images/README.md` 清单补齐后接入截图区
- [ ] 真机验证：装了 v1.0.0（debug 签名）的设备，之后换正式签名的版本需要先卸载再装
- [ ] dependabot 已按配置开出若干 PR（部分 PR 的 CI 是在 gradlew 修好之前跑的，会失败，重跑即可），逐个评估合并

---

## M11 空白归一化与恢复向前兼容（已完成）

目标：加一个可开关的排版归一化能力；同时撤掉 v1.0.0 之后失效的"只保证新安装、不写迁移"策略，让已发布的库能原地升级。

### M11.1 空白归一化开关
- [x] 规则：段首空白整段折叠（改由标准首行缩进对齐）、行尾空白折叠、行内连续空白折叠为一个、BOM/零宽空格/连接符/软连字符等不可见字符折叠
- [x] **只折叠绘制宽度、不改动任何字符**：测量侧喂等长的 `U+200B` 掩码文本（断行下标因此仍是原文下标），绘制侧按同一掩码把字宽与两端对齐字距归零 → 选区/划线/书签/搜索偏移零影响；开关关闭时逐像素维持原行为
- [x] 每书开关（阅读器菜单，默认关）：`normalizeWhitespaceEnabled` 贯通 ReadingPreferences / BookPrefs / 备份 / 分页磁盘缓存键
- [x] 验证：`WhitespaceNormalizerTest`（8 条规则）、`WhitespaceNormalizationLayoutTest`（3 条，真实 `StaticLayout` + `Paint`，确认零宽占位符不占宽、归一化后首行铺满且可见文字从缩进处开始）、PageGeometry / Paginator 新增用例

### M11.2 恢复数据库向前兼容
- [x] 废止"重置基线、只保证新安装"：`FoldReaderDatabase.version = 2`，新增 `core/data/db/DatabaseMigrations.kt` 登记 `MIGRATION_1_2`（`book_prefs` 加列），`FoldReaderApplication` 接 `addMigrations`
- [x] `app/schemas/…/1.json` 保持 v1.0.0 原样（已发布快照不可改写），构建自动导出 `2.json`
- [x] 不启用 `fallbackToDestructiveMigration`：缺迁移宁可报错，也不静默清库
- [x] 备份导入去掉版本上限硬限制：只拒绝认不出的版本，比本机新的备份按已知字段尽力导入
- [x] 验证：`DatabaseMigrationTest` 用 v1 建表语句建库 → 跑迁移 → 老数据保留 + `PRAGMA table_info` 与 Room 期望逐项一致

### M11.3 菜单展示
- [x] 底部开关区 `Row(SpaceEvenly)` → `FlowRow`，标签强制单行：中文不再被挤成逐字竖排，窄屏整块换行

### M11.4 待人工
- [ ] 真机：装 v1.0.0 → 覆盖安装本次包，确认书架/进度/书签/标注都在（迁移实跑），且不再需要清数据重装

---

## 全局持续事项（每个里程碑都做）
- [x] 新增代码编译通过 + 关键路径单元测试
- [ ] 折叠/展开/旋转手动回归一遍（待真机）
- [x] M3 Expressive 组件使用符合说明书第 4 章
- [x] 与说明书不一致时：先更新说明书，再改代码
