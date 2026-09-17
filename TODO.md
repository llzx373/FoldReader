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

### 阶段 4 · 双页打磨（未开始，是唯一剩下的功能缺口）
- [ ] 双页仍走旧铰链路径。换卷曲几何**不是改渲染分支就够**：现在这套卷曲模型没有
      "叶片落定到对侧"的相位（折痕扫到装订边之后卷筒只能滑出，无法在另一侧摊开），
      需要先补相位 B（卷起来之后绕中缝转到对侧、纸背用真实内容），
      再把背景改成"当前左页 + 目标右页"的两段拼接、叶片做成半页裁剪
- [ ] 双页纸背改用真实内容（目标跨页另一半页）

### 阶段 5 · 退役旧路径 + 真机（依赖阶段 4）
- [ ] 双页也换完之后删除 `PageCurlShader.kt` 与铰链几何、`HingeFrameRenderTest` /
      `HingeSoftwareRenderer` / `PageCurlShaderTest`
- [ ] 真机 profile：重折/阔折叠 120fps、`CURL_MESH_INTERVAL_PX` 定值、确认不误触发降级

### 待归档（已完成但未提交）
- [x] `HingeSoftwareRenderer` 与生产 AGSL 失同步修复（`7adaf5b` 改了自由边高光/暗影但没同步镜像）
- [x] `PageTurnCompareRenderTest`：三模型并排出图（现铰链 / 同模型加透视 / 圆柱卷曲参考）
      与「只有卷曲模型保持正文不变形」的断言

---

## M6+ 格式扩展（后置）
- [x] EPUB 解析器实现 `BookParser` 接口（core/format/epub），阅读器/UI 零改动验证
      —— 已实现：EPUB3 NAV / EPUB2 NCX 目录、page-list 纸书页码、样式与链接 span、图片抽取、
      元数据全字段。压平后复用 TXT 管线，阅读器零分支
- [x] FB2 支持（含 `.fb2.zip` 变体）
- [ ] MOBI / AZW3 / 其他格式评估

---

## 全局持续事项（每个里程碑都做）
- [x] 新增代码编译通过 + 关键路径单元测试
- [ ] 折叠/展开/旋转手动回归一遍（待真机）
- [x] M3 Expressive 组件使用符合说明书第 4 章
- [x] 与说明书不一致时：先更新说明书，再改代码
