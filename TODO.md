# FoldReader 开发任务清单（TODO）

> 依据《docs/AI功能需求与实施.md》v1.4 拆解；网络政策见《docs/需求与设计说明书.md》附录 v2.6。
> 旧版任务清单（M1–M12，全部完成及历史评估记录）已归档至 `docs/TODO-归档-v1.md`。
> 执行规则：按里程碑顺序推进（标注"可并行"的除外）；每个任务完成即勾选 `[x]`；每完成一个里程碑必须达到对应"验收标准"、编译通过（`./gradlew :app:assembleDebug`）且单元测试全绿（`./gradlew :app:testDebugUnitTest`）后再进入下一个。

---

## 0. AI 决议（v1.3 已拍板，存档备查）

- [x] R1 服务商协议：兼容三种——OpenAI Chat Completions / OpenAI Responses / Anthropic Messages
- [x] R2 流式输出（SSE）：**引入 OkHttp（主依赖）+ MockWebServer（test 依赖）**；按页/选段边生成边显示；为 WebDAV 预留同一网络栈
- [x] R3 翻译模型与通用模型分开配置
- [x] R4 目标语言多国：首版 简中/繁中/英文/日文，逐书可选
- [x] R5 全书翻译中翻到未译章（块）：支持插队
- [x] R6 术语表：全局 / 系列（漫画）/ 单书三层级，优先级 书 > 系列 > 全局
- [x] R7 OCR 模型：用户自行下载 + SAF 导入，校验哈希，落 `filesDir/models/`
- [x] R8 OCR 首版语言：日文 + 英文 + 中文
- [x] R9 首版引入气泡检测模型（YOLO-seg）
- [x] R10 条漫跨页气泡合并放 M23
- [x] R11 按页翻译电子书/漫画通用，支持当次临时提示词
- [x] R12 无章节/超大文件：按段落边界切块（2000–4000 字）逐块翻译，块为对齐与进度单位

---

## M13 TTS 听书 + 人物出场索引 ✅ 已完成（2026-09-23）

**验收标准**：从当前位置朗读、翻页联动、锁屏/耳机可控；目录面板「人物」页签可跳转。
**验收记录**：单测全绿（切句器 8 用例 + 人物引擎 8 用例含反向误判用例 + v2→v3 迁移用例）；`assembleDebug` 与 `assembleRelease`（R8）通过。引擎服务级持有 + Activity configChanges，折叠/展开不中断与阅读位置同口径。真机待验：耳机按键路由、锁屏媒体卡片、通知权限拒绝降级、中文 TTS 数据缺失提示。

### 13.1 TTS 听书
- [x] 系统 `TextToSpeech` 接入：初始化、语种检查、错误降级
- [x] 「从当前位置朗读」/「朗读本章」入口（阅读菜单）
- [x] 与自动翻页联动：读完一页翻一页，双页模式按跨页推进
- [x] MediaSession：耳机按键、锁屏控制、进度同步
- [x] 折叠/展开切换朗读不中断（姿态连续性口径同阅读位置）

### 13.2 人物出场索引
- [x] 纯规则人名统计：2-4 字专名候选 + 称谓词过滤 + 频次阈值（`core/format/` 内，纯 JVM + 单测）
- [x] 首次出场章节计算与落库（随章节索引流程产出）
- [x] 目录面板「人物」页签：人物 → 首次出场章节 → 点击跳转
- [x] 反向用例：常见虚词/称谓不被误判为人名

---

## M14 AI 底座（一切 AI 功能的前置）✅ 已完成（2026-09-23）

**验收标准**：未配置 key 时抓包零请求、无 AI 入口；三种协议「测试连接」均通过；MockWebServer 用例全绿；设置页可查看内置提示词全文（随 M15 首个提示词「章节规则生成」落地查看入口）。
**验收记录**：844 单测全绿（46 个新增，含 MockWebServer 三协议套件）；`.env` 真实 API 测试命中 DeepSeek（文本流式 + 图片识别双路径）；`assembleRelease`（R8）通过。

### 14.1 core/ai 模块
- [x] 引入依赖：OkHttp 4.12.0（+okio，主依赖）与 MockWebServer（test 依赖）、kotlinx.serialization 1.8.1；release 构建验证 consumer rules（R2）
- [x] `AiProvider` 接口：`chat(messages, model): Flow<String>`（流式）；消息模型含 Text/Image 内容块（图片仅 USER 消息，为 M23 视觉 OCR 预留）
- [x] 单例 `OkHttpClient`：惰性创建、超时统一策略；协程取消时断开连接回收 socket
- [x] `openai/ChatCompletionsProvider`（R1）：请求构造 + SSE 事件→文本增量映射
- [x] `openai/ResponsesProvider`（R1）：同上，Responses 协议事件格式
- [x] `anthropic/AnthropicProvider`（R1）：同上，Messages 协议事件格式
- [x] MockWebServer 测试网：三协议 SSE 流端到端用例（正常增量/粘包/中途断流/429 限流/非 200 错误体 + 图片块请求体断言）
- [x] 统一错误模型：超时/限流/额度/网络不可达 → 可读错误文案
- [x] `core/ai/prompt/`：提示词模板与响应解析（纯 JVM，解析单测覆盖畸形响应）——已随 M15 落地（章节规则生成提示词）
- [x] `android/CredentialStore`：Keystore 加密存取 key（AES/GCM，KeyStore 惰性获取）；不进备份（backup_rules/data_extraction_rules 显式 exclude）、不写日志
- [x] `gate/AiContentGate`：外发历史记录与查询（时间/功能/数据范围/估算 token，500 条轮转）；确认弹窗已随 M15 接入（首次外发一次性确认，`aiChapterRuleConfirmed`）
- [x] `AppContainer` 惰性装配：未配置 key 不创建任何网络组件（OkHttpClient 亦不创建）

### 14.2 设置页「AI 服务」
- [x] 总开关（默认关）+ 协议预设（OpenAI Chat / OpenAI Responses / Anthropic）+ 服务商地址预设（DeepSeek/OpenAI/Anthropic/通义/自定义）+ base URL + 通用/翻译/视觉模型 + key + 测试连接
- [x] 默认目标语言（R4：简中/繁中/英文/日文）
- [x] 「内置提示词」只读查看——随 M15 首个提示词（章节规则生成）落地查看入口
- [x] 「外发历史」列表 + 「清除凭据」；「清除全部 AI 数据」随 M15+ 的数据产物落地
- [x] 未配置完成时全书 AI 入口不显示（M14 尚无消费功能入口，天然满足）

### 14.3 合规与文档（v2.6 约束 5）
- [x] Manifest 增加 `INTERNET`
- [x] 同一提交内：README / README_EN / 应用内「关于 AI」说明改写

---

## M15 AI 章节规则生成 ✅ 已完成（2026-09-23）

**验收标准**：3~5 本规则库识别失败的 TXT，AI 生成的正则可切出可用目录；预览→确认→重扫全流程通。
**验收记录**：单测全绿（prompt 解析 11 用例 + 采样/试切 14 用例 + 流程状态机 6 用例）；`.env` 真实 API 全链路冒烟通过——规则库识别失败的合成文本采样 41 行，真实 AI 返回 2 条候选，两条试切均切出 40 章零异常。顺带闭环 M14 遗留：`core/ai/prompt/` 与设置页「内置提示词」查看、首次外发一次性确认弹窗。

- [x] 疑似标题行候选集粗筛（复用现有章节扫描器，几百行采样）
- [x] prompt：返回 1~3 条候选正则 + 设计说明；解析失败丢弃该候选
- [x] 本地逐条真实切分并预览：章节数、前 20 个标题、异常标记（超长章/空章/孤儿文本）
- [x] 用户选定 → 存为该书的自定义章节规则（复用现有结构）→ 重扫目录
- [x] 入口：目录面板「未识别到章节」状态 + 书籍详情页

---

## M16 AI 清洗配方推荐 ✅ 已完成（2026-09-24）

**验收标准**：推荐配方走预览报告确认后物化；`novel-corpus` 全语料测试集全绿（AI 不进执行路径，理应零影响）。
**验收记录**：单测全绿 1014（新增 24——`CleanSampleSamplerTest` 6 用例：头尾广告高发区必含 / 小文本原样返回 / 确定性 / 整行对齐；`CleanRecipePromptTest` 12 用例：散文与代码围栏包裹、畸形 JSON 返回 null、未知开关 key 丢弃、坏正则丢弃、广告正则截断 3 条、buildProfile 标准档基线 + 覆盖 + CUSTOM 档、清单覆盖全部开关、BuiltinPrompts 登记；`CleanRecipeAiViewModelTest` 6 用例：首次外发一次性确认闸门、全局广告正则并入、无效响应可重试、AiException 文案透传、台账 feature=清洗配方）；`novel-corpus` 全语料零改动全绿，`tools:cleaner` 同步绿（采样器属共享源码）；`assembleDebug` 通过。AI 只推荐不执行：配方落成普通 `CleanProfile` 后走既有预览报告 → 确认 → 物化链路，`NovelCleaner` 与规则文件零改动。真机待验：真实服务商下的建议质量与采样外发确认弹窗。

- [x] 脏文本采样策略（含头尾广告高发区）
- [x] prompt：返回建议开关列表（映射 `CleanToggles.ENTRIES`）+ 0~3 条自定义广告正则 + 问题说明
- [x] 产出 `CleanProfile` → 现有清洗预览报告 → 确认 → 物化（不新增执行路径）
- [x] 入口：「智能整理」对话框「AI 推荐配方」

---

## M17 元数据补全 + 智能分组 ✅ 已完成（2026-09-24）

**验收标准**：DB 迁移通过；AI 值标「AI 生成」；用户编辑后不被覆盖；可按题材分组。
**验收记录**：单测全绿 1050（新增 36——`MetadataPromptTest` 8 用例：规整/围栏容忍/畸形 null/空白归一/未知题材丢弃/简介截断/消息结构与题材清单/登记表；`BookMetaSourcesTest` 10 用例：编解码往返、畸形丢弃、来源查询打标、AI 只补空字段、不覆盖含自身旧值、锁定字段不补、全满返回 null、用户编辑打标含清空锁定、清空后不再回填、枚举归一化；`MetadataHeadSamplerTest` 3 用例；迁移 v5→v6 1 用例；`BookDaoMetadataTest` 3 用例（真 Room 库：DAO 只填空值兜底/用户覆盖可清空/分组只动有标签）；`MetadataAiViewModelTest` 9 用例：首次确认闸门、只补空且未锁定、已完整不打 AI、非 TXT 降级、无效响应重试、批量失败跳过汇总、批量首次确认、批量中途取消——台账 feature=元数据补全；`BackupCodecTest` +2 用例：v7 题材与来源标记往返、v6 旧备份缺省保持本地）；`assembleDebug` 通过；schema 快照 `6.json` 已生成且与迁移 SQL 逐字一致。真机待验：真实服务商下的归纳质量与批量进度观感。
**与 TODO 原文的偏差**（均已在实现注释与 CHANGELOG 说明）：
- 「简介」复用既有 `description` 列、「作者」复用既有 `author` 列——迁移只新增 `genreTag` + `metaSource` 两列；
- `metaSource` 采用**逐字段**来源标记（编码 `author:ai,genre:user`），而非整书一把锁：用户改过作者后 AI 仍可补题材；用户编辑（含清空）即锁定该字段，AI 单本/批量都永不改写——诚实满足「用户编辑后不被覆盖」且粒度更细；
- 书名不进 AI 输出契约、不回写——书架标题以导入文件名为准，AI 改书名风险大于收益；
- AI 补全仅支持 TXT（EPUB/FB2 元数据来自容器、PDF 有预热回填；漫画无正文可采）；
- 「按题材分组」为全局动作（书架溢出菜单），批量 AI 补全在多选模式触发。

- [x] DB 迁移：`books` 加 `genreTag` / `metaSource`（升 v6 + `DatabaseMigrations.kt` + schemas 快照 `6.json`；`author`/`synopsis` 复用既有列，见偏差说明）
- [x] 采样开头几 KB → 结构化作者/简介/题材标签（固定枚举 `GenreTags` 十项；书名不回写）
- [x] 详情页展示 + 用户编辑覆盖（逐字段 `metaSource`：AI 值标「AI 生成」，user 标字段 AI 不改写）
- [x] 规则版按题材自动分组（书架溢出菜单「按题材分组」，`groupName` 落题材名）
- [x] 书架批量触发：多选「AI 补全信息」串行执行，进度 N/M + 当前书名 + 可取消

---

## M18 选中即译 ✅ 已完成（2026-09-23）

**验收标准**：长按→流式翻译→卡片显示→可存批注。
**验收记录**：单测全绿（新增 `SelectionTranslatePromptTest` 4 用例：消息结构 / 目标语言注入 / 原文进 USER / 登记表占位符）；`assembleDebug` 通过。首外发一次性确认、外发台账（feature=选中即译）、目标语言卡片临时切换、译文存为批注复用标注体系零 DB 变更。真机待验：真实服务商流式增量渲染与中断重试。

- [x] 长按选字动作条加「翻译」
- [x] 底部卡片流式显示译文（R2）；源语言自动检测；目标语言默认设置页值，卡片上可临时换（R4）
- [x] 「存为批注」锚定当前偏移（复用标注体系）

---

## M19 按页翻译 + 单章（块）翻译 + 视角 1 ✅ 已完成（2026-09-23）

**验收标准**：按页结果走对照面板不落盘；原/译双模式进度独立；块边界不跨段（R12）；单章（块）重译不影响其他单位；段落结构化落盘。
**验收记录**：单测全绿 957（新增 `TranslationRelocationTest`/`StringBookContentTest`/`ChapterUnitStatusTest`：半开区间定位、比例 1.0 收在区间内、译本流切片 clamp、章行状态文案规则）；`assembleDebug` 通过。双锚点进度（译文只写 translationAnchor）、译文模式禁用标注写入/选字翻译/TTS、译本页边界不落盘（非共享 PageCache）、删书连带清译本、设置页「清除全部 AI 数据」。真机待验：真实服务商下本页对照面板流式渲染与视角切换重定位手感。

- [x] DB 迁移：`translations` 表（`unitKind[chapter|block]`）+ `reading_progress.translationAnchor`（对称锚点，参照 `keepPagedAnchor`）
- [x] 切块器（R12）：无章节或单章超阈值（约 4000 字）时按段落边界切块（2000–4000 字/块）；纯 JVM + 单测（边界不跨段、无遗漏无重叠）
- [x] 译本副本结构：`filesDir/translations/<bookId>/<lang>/content.txt` + `.toc`（有章共章界；无章生成伪目录「第 N 节」）
- [x] 阅读菜单「翻译本页」：当前页（双页=当前跨页）即时翻译；确认页可临时改提示词（当次生效，R11）；结果对照面板**流式**展示，不落盘
- [x] 阅读菜单「翻译本章/本节」：后台翻译，目录面板每单位状态（未译/翻译中/已译）+ 章行「重译」
- [x] 翻译结果按段落结构化落盘（prompt 按段落数组返回，解析失败该单位重试）
- [x] 视角 1：菜单「原文 / 译文」切换，按「单位号 + 单位内进度比例」定位，首次切换一句话告知

---

## M20 全书翻译 + 术语表 + 视角 2/3 ✅ 已完成（2026-09-24）

**验收标准**：成本预估→断点续译；抽 3 章验证人名跨章一致；术语优先级 书>系列>全局；双页左右按单位同步。
**验收记录**：单测全绿 990（新增 `BilingualSyncTest` 8 用例：跨单位追齐 / 同单位比例漂移 / 首尾 clamp / 空输入 / 连续翻页先追齐后漂移）；`assembleDebug` 通过。视角 2 仅双页翻页布局可用（单页/悬停/切滚动自动退出并释放右分页器），右页译本走非共享 PageCache 不落盘，翻页/跳章/进度条统一经 `spreadFrom` 同步，TTS 翻页联动与「翻译本页」取范围改从左侧分页器实算原文跨页末端；视角 3 单位粒度懒加载 + LRU（24 单位），滚动停止把首可见单位起点写回锚点，跳转经 `paragraphCompareJumps` 事件。真机待验：双页对照翻页动画（覆盖/仿真）中右页替换的观感、大书段落对照滚动流畅度、摄像头开孔规避开启时右页未减容的显示。

- [x] DB 迁移：`glossary_terms(scope[global|series|book], ownerKey, source, target, origin, confirmed)`（R6）
- [x] 「翻译全书」确认页：目标语言选择（R4）+ 字符数 → token → 金额预估，逐书确认
- [x] 批量队列（`BookPrewarmQueue` 模式）：串行/让出/失败重试/断点续译/暂停取消；前台服务 + 完成通知
- [x] 未译单位插队翻译（R5）
- [x] 术语表：全局表 + 单书表；人物索引（13.2）+ 前 3 单位回填候选；每单位调用按 书>系列>全局 合并注入；确认/否决 UI + 全局表增删（R6）；两个层级都进备份
- [x] 视角 2：双页左原文右译文（双 Paginator 按单位对齐，仅双页布局）
- [x] 视角 3：滚动模式段落对照，译文块背景色稍深（随主题派生）

---

## M21 OCR 引擎 + 扫描 PDF 文本层 ✅ 已完成（2026-09-26）

**验收标准**：中/英扫描 PDF 可全文搜索、可选字复制；日/英/中模型导入/校验/删除全流程，错误文件明确报错。
**验收记录**：单测全绿 1094（新增 47——`OcrPostprocessTest` 10 用例：热区解码/噪点过滤/外扩夹取/CTC 去重去空白/输入尺寸；`BubbleGroupingTest` 8 用例：归属/孤儿/框扩展/RTL·LTR 阅读序/置信度取小；`RtdetrPostprocessTest` 3 用例；`OcrTextLayerTest` + `PdfOcrStoreTest` 9 用例：选字并集/snippet/搜索计数/缓存往返/版本失效/删书清理/气泡编解码；`ModelCatalogTest` 4 用例（含哈希防呆闸门）；`ModelManagerTest` 9 用例：sha256 工具/未知文件/哈希不符无残留/流失败/就绪判据/删除回落）；`assembleDebug` 与 `assembleRelease`（R8）通过，APK 仅 arm64-v8a+x86_64 两 ABI。模型清单 SHA-256 实算并与 RapidOCR 官方 default_models.yaml（v3.9.2）/ HuggingFace LFS oid 逐一比对一致。**与 TODO 原文的偏差**：①气泡检测模型由 YOLO-seg 改为 RT-DETR-v2（R9 偏差，原因见 CHANGELOG——上游换代 + 唯一 YOLO-seg 候选 GPL/AGPL 且 104MB）；②识别词典（ppocr_keys_v1/en_dict/japan_dict）随 APK 打包进 assets 而非随模型导入（词表必须与模型类别表一致，打包消除错配）；③引入 ABI 过滤（arm64-v8a+x86_64）。真机待验：int8 气泡模型在 onnxruntime-mobile 精简算子包下的兼容性（退路：fp32 自量化或换源）、真实模型识别质量、扫描 PDF 选字手感、全量搜索首次逐页建层的等待观感。

- [x] 引入 `onnxruntime-mobile`（过依赖评审）；首版只用 CPU 后端
- [x] `core/ocr/`：ONNX 会话管理 + det → rec 管线 + 归一化坐标输出
- [x] `ModelManager`：模型清单（日/英/中 rec + det + 气泡检测，文件名/SHA-256/用途/官方地址，R7/R8/R9）+ SAF 导入 + 校验 + 删除
- [x] 设置页模型管理：清单、下载地址指引、导入入口、就绪状态；未就绪功能入口隐藏
- [x] 扫描 PDF 文本层：`PdfPagedSource` 页位图 → OCR → 全文搜索 + 拖框选字（复用 v2.2 归一化坐标）

---

## M22 漫画翻译 v1 ✅ 已完成（2026-09-26）

**验收标准**：整卷断点续译；覆盖层随缩放/双页/RTL 跟随；译名跨卷一致；临时提示词当次生效。
**验收记录**：单测全绿 1138（M22 新增——`ComicTranslatePromptTest` 8 用例：消息结构/目标语言注入/术语注入/临时提示词当次替换/畸形 JSON/数量校验/流式部分数组/登记表占位符；`ComicTranslationStoreTest`：OCR 缓存与译文往返/损坏回落/删书清理；`BubbleRenderTest` 9 用例：采样点位裁页内/中位色抗文字噪点/文字色反转/全角半角宽度/折行/字号收缩自洽；`ComicTranslationQueueTest` 5 用例：顺序推进/断点续译跳 done/单页退避失败续译/取消/暂停恢复；`GlossaryRepositoryTest` 6 用例：系列跨书注入/书覆盖系列/系列覆盖全局/未确认不注入/不传 seriesKey 不读系列层/系列间隔离；**E2E `ComicTranslationE2ETest` 8 用例**（真 Room 内存库 v7 + 真 ComicTranslationStore + 真 ChatCompletionsProvider 打 MockWebServer SSE 分块 + 真队列，仅 OCR 注入合成气泡）：全链路单页落盘+台账 done/流式逐气泡按序回调（CountingProvider 硬断言第 N 气泡回调时恰到达 N 个 delta，不依赖墙钟）/数量校验失败整页重试/两次都错置 failed/队列断点续译（503→failed→重入队只重译该页）/临时提示词只当次/三级术语合并与覆盖/未配置零网络请求；`ComicTranslateRealApiTest` 读 .env 真实 API 冒烟——本机 deepseek-flash 实跑通过（2 气泡译文正确，未配置环境 Assume 自动跳过）；`DatabaseMigrationTest` v6→v7）。`assembleDebug` 与 `assembleRelease`（R8）通过。**与 TODO 原文的偏差**：①气泡检测模型为 RT-DETR-v2（M21 已定，R9 偏差同前）；②「调好可带同一提示词发起整卷」未做——整卷走默认注入（书>系列>全局术语），临时提示词仅按页当次（整卷确认页明示范围/断点续译/成本口径，不编 token 数）；③系列 Tab 因 GlossaryDialog 唯一调用点在全局设置页（无当前书上下文），改为对话框内自 deriv 书架漫画主干系列清单（与单书表同款自治模式），未新增漫画侧入口。真机待验：int8 气泡模型在 onnxruntime-mobile 精简算子包下的兼容性、真实页气泡检测质量、覆盖层随缩放/掀页观感、双页对照翻页同步、竖排日漫译文横排呈现的观感。

- [x] DB 迁移：`comic_page_translations` 表
- [x] 气泡检测模型（RT-DETR-v2，R9 偏差见 M21）接入：文字块归并为气泡
- [x] 页级管线：OCR 缓存 `.ocr.json` 与翻译结果 `.json` 分文件（`filesDir/comic_translate/<bookId>/`）
- [x] `ComicPageView` 加 `TranslationOverlay`：归一化坐标气泡，随缩放/双页/RTL/条漫自动跟随
- [x] 按页翻译：「翻译本页」+ 临时提示词（R11），气泡译文逐个流式出现
- [x] 批量翻译：后台队列 + 前台服务 + 通知；逐页状态；断点续译
- [x] 三视角：①覆盖层显隐 ②双页左原图右译文 ③气泡对照面板（点条目高亮气泡）
- [x] 系列术语表：`ComicSeriesMatch` 主干产出 `seriesKey`，`scope=series` 跨卷共享（复用 M20 表）
- [x] 气泡底色采样覆盖原文（不做 inpainting）；字体复用字体导入机制

---

## M23 漫画翻译 v2（按 M22 反馈定范围）

- [ ] 条漫/长图跨页气泡合并（R10）
- [ ] 气泡位置手动微调
- [ ] 可选视觉模型 OCR 模式（页图像外发，逐书单独明示）

---

## 每个里程碑收尾统一打钩（隐私合规）

- [x] 未配置 key：无 AI 入口、无网络组件、抓包零请求
- [x] key 不出现在备份/诊断日志/崩溃报告/dumpsys
- [x] 每类功能首次外发有明示；外发历史可查
- [x] 批量翻译有成本预估并逐书确认
- [x] AI 产物标注来源、可一键清除、不改正文
