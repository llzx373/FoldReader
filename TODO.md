# FoldReader 开发任务清单（TODO）

> 依据 2026-10-08 功能增补分析（v1.3.0 发布后）拆解；网络政策见《docs/需求与设计说明书.md》附录 v2.6——所有新功能一律不得突破「未配置零网络」与「本地优先」口径。
> 旧版任务清单：M1–M12 归档于 `docs/TODO-归档-v1.md`；M13–M24 归档于 `docs/TODO-归档-v2.md`。
> 执行规则：按里程碑顺序推进（标注"可并行"的除外）；每个任务完成即勾选 `[x]`；每完成一个里程碑必须达到对应"验收标准"、编译通过（`./gradlew :app:assembleDebug`）且单元测试全绿（`./gradlew :app:testDebugUnitTest`）后再进入下一个。

---

## 0. 明确不做（防重复评估，存档备查）

- 在线书源 / OPDS / Calibre 同步——与「不做在线书城」口径直接冲突
- 账号体系 / 云书架 / 社交分享——同上，且会打破「未配置零网络」卖点
- 在线元数据刮削（豆瓣 / Google Books 封面等）——引入第三条网络通道会稀释合规口径，AI 元数据补全已是替代路径
- 备份/台账/诊断日志中任何时候不得出现 API key 与用户明文内容（M14 起的既有约束，后续里程碑持续适用）

---

## M25 WebDAV 备份 + 自动备份（P0，文档承诺在先）✅ 已完成（2026-10-08）

**验收标准**：自托管 WebDAV（Nextcloud / 坚果云）可完成上传 / 列表 / 恢复全流程；凭据走 Keystore；未配置时抓包零请求、无入口；本地自动备份可落 SAF 指定目录并保留最近 N 份。

- [x] WebDAV 配置项：服务器地址 / 账号 / 密码（Keystore 加密存储，口径同 AI key，不进备份与日志）+ 测试连接；复用 M14 的 OkHttp 单例网络栈（R2 决议为此预留）
- [x] 传输层：上传 / 列表 / 下载恢复，仅加 WebDAV 协议层，备份编解码复用现有 `core/backup/BackupCodec` 零改动
- [x] 合规：首次连接一次性明示确认；传输记入外发历史台账（feature=WebDAV 备份，scope=备份文件名与大小）
- [x] 恢复前预览：备份版本 / 条目数 / 导出时间，确认后走现有导入链路
- [x] 本地自动备份：退出时或每日一次导出到 SAF 指定目录，轮转保留最近 N 份（N 可配）
- [ ] （可选开关）备份含书文件：默认关，开启后打包源副本，明示体积
- [x] 冲突处理：远端同名按时间戳区分；恢复按 contentHash 对齐既有书（现 BackupCodec 已支持）

验收记录（2026-10-08）：

- 协议层：`core/backup/webdav/WebDavClient` 纯 JVM 零 android import（PROPFIND/MKCOL/PUT/GET/DELETE，Basic 预置头 UTF-8，multistatus 解析注入 XmlPullParser 工厂——生产 `android.util.Xml`、单测 kxml2；错误分型 INVALID_URL/AUTH/NOT_FOUND/HTTP/NETWORK/TIMEOUT，异常消息不含凭据）。`WebDavClientTest` 16 用例（MockWebServer 端到端：正常/401/404/500/断连/超时/中文文件名编码/无前缀 multistatus/非法地址零请求）。
- 编排层：`WebDavBackupManager`（函数注入编解码，MockWebServer 可测）——上传按 `BackupFileNames.timestamped`（`foldreader-backup-yyyyMMdd-HHmmss.json`，字典序即时间序，天然规避远端同名）；列表只留本应用命名；恢复前预览 `BackupManager.preview`（版本/条目数/导出时间，纯函数）确认后走 `importFromText` 既有导入链路（contentHash 对齐）。`BackupCodec` 备份格式零改动（BACKUP_VERSION 仍 7），仅 `BackupManager` 新增 exportJsonText/importFromText/preview 三个不改语义的入口。
- 合规：密码经 `WebDavCredentialStore`（AndroidKeyStore AES/GCM，与 AI key 同口径，Robolectric 5 用例含「SharedPreferences 无原文」）；首次网络动作前一次性明示确认（`webdavConfirmed` 偏好，对话框明示服务器地址与传输范围）；每次上传/下载记外发台账（feature=WebDAV 备份、scope=文件名+大小、token 记 0，台账 UI 对 0 token 记录不再显示 token 尾缀）；未配置时 `webDavClient()` 返回 null 且进程内不创建任何网络组件（与 AI 共用同一惰性 OkHttp 单例，`sharedAiHttpClient` 更名为 `sharedOkHttpClient`）。
- 自动备份：`AutoBackupRunner` 挂 AppContainer 维护协程，启动时检查距上次成功 ≥24h 才导出到 SAF 树目录（DocumentsContract.createDocument 写入，目录授权 takePersistableUriPermission 持久化），轮转 `BackupFileNames.rotationDeletes` 只删本应用命名的超额最旧份；N 可配（3/5/10/20）；失败只记诊断日志不更新上次时间（下次启动自然重试）。**与 TODO 原文的偏差**：「退出时」未采用——Android 无可靠退出钩子（进程随时被回收），实现为「每日一次（启动检查制）」，对每天开一次阅读器的使用习惯等价；设置页另有「立即备份一次」手动入口。
- 设置页：「备份与恢复」区下新增「自动备份」与「WebDAV 备份」两个分区；「上传/列表/恢复」入口常驻（未配置时动作报「请先完成 WebDAV 配置」，不发请求）。
- 测试：`testLiteDebugUnitTest` 全绿（新增 35 用例：WebDavClientTest 16 + WebDavBackupManagerTest 5 + WebDavCredentialStoreTest 5 + BackupFileNamesTest 5 + AutoBackupRunnerTest 4 门槛/失败口径）；`assembleLiteDebug` 通过。
- 未做（可选项）：「备份含书文件」开关——会把备份从单 JSON 变成 zip 容器并涉及恢复侧源副本重建，属备份格式语义扩展，留待独立里程碑按 BACKUP_VERSION 升级惯例实现。
- 真机待验：Nextcloud/坚果云实测上传与恢复全流程；SAF 目录授权收回后的失败提示观感；自动备份在真实目录的轮转。

---

## M26 TTS 增强（P0，README 自列扩展点）✅ 已完成（2026-10-08）

**验收标准**：睡眠定时到点自动停止（含「本章读完」档）；语速 / 音调调节即时生效；外文书可按书切换 TTS 语言，语种数据缺失有降级提示。

- [x] 睡眠定时：15/30/45/60 分钟 + 「本章读完」；通知栏显示剩余时间；到时停队列并退出前台服务
- [x] 语速 / 音调：设置页全局默认值 + 朗读中面板临时调整（当次生效）
- [x] 按书 TTS 语言：书籍详情可选（跟随全书翻译目标语言联动）；设备缺对应 TTS 数据时复用现有降级提示口径
- [x] 译文朗读：译本已有完整 content.txt + .toc，用译本自身偏移坐标系独立走 TTS 链路（M19 禁用仅因锚点是原文坐标系）

验收记录（2026-10-08）：

- 睡眠定时：`core/tts/TtsSleepTimer`（纯 JVM 状态机：档位、deadline、剩余分钟向上取整、文案）；`ReaderTtsController` 分钟档 Handler 到点精确停 + 30s tick 兜底刷新文案，「读完本章」与自然播完同口径（speakFromHere/speakChapter 范围本就截到章末）；通知 contentText 追加「定时：…」（`NotificationSig` 替代 Triple）。
- 语速/音调：`core/tts/TtsSpeech`（纯 JVM，0.5~2.0、clamp、NaN 回落默认）；全局默认存 DataStore（设置页「听书朗读」区滑杆），朗读中菜单滑杆只调当次值、不写回设置；speak() 每次从全局偏好重取。备份导出/导入同步两字段（BACKUP_VERSION 不升，导入容错）。
- 按书语言：`core/tts/TtsLanguage`（纯 JVM：简/繁/英/日 + `resolveTtsLanguage` 优先级——按书显式 > 译文视角跟随译本语言 > 默认简体中文）；存 `book_prefs` 新可空列 `ttsLang`（DB v7→v8，`MIGRATION_7_8`，老行落 NULL）——项目惯例是「随书独立演化的项进 BookPrefsEntity」，比 DataStore 散存 map 更一致，且随备份走。入口在书籍详情（非漫画）「朗读语言」行。语言检查从引擎 init 移到 `startQueueFrom`：缺语音数据按语言动态报「当前设备没有「X」的语音数据」，不再永久锁死引擎。
- 译文朗读：阅读器菜单 TTS 组不再对译文视角隐藏（speakRange 的 content/chapters 本就是当前坐标系，天然兼容）；`switchToOriginal` 补对称的停 TTS（坐标系不可互带）。
- 测试：`testLiteDebugUnitTest` 全绿（新增 13 用例：TtsSleepTimerTest 5 + TtsSpeechTest 3 + TtsLanguageTest 5，另 DatabaseMigrationTest 补 v7→v8）。
- 真机待验：睡眠定时到点停与通知剩余时间刷新；各语言语音数据缺失提示（尤其英/日）；朗读中语速/音调滑杆即时生效；译文朗读的翻页联动与切回原/译时的停止行为。

---

## M27 批注 / 高亮导出（P0，标注体系闭环）✅ 已完成（2026-10-08）

**验收标准**：单书批注导出为 Markdown（按章节组织，含高亮颜色与笔记），SAF 另存；书架多选可批量导出。

- [x] Markdown 导出格式：章节分组、原文引用块、笔记、颜色标记、页内锚点位置说明
- [x] 入口：书籍详情 + 批注列表 + 书架多选批量
- [x] 选项：含 / 不含笔记；含 / 不含颜色标记
- [x] 漫画 / PDF 锚点批注降级为「第 N 页 + 区域描述」

验收记录（2026-10-08）：

- 纯逻辑层：`core/export/AnnotationMarkdown` 纯 JVM 零 android import——文本书按章节分组（章节归属与批注列表 UI 同口径：「最后一个 charStart <= 起点的章」，首章前归第 0 章），原文进引用块（逐行 `> ` 前缀），笔记以列表项缩进续行承载，行内最小 Markdown 转义（`\` `*` `` ` `` `_` `[` `]`）；调色板五色落中文名（黄/绿/蓝/粉/紫），板外颜色落 `#AARRGGBB`；样式区分「底色高亮 / 下划线」；文本锚点位置行含全书百分比，页式锚点降级为「第 N 页 + 区域百分比描述」（页式目录能解出章节名时附在页标题后）；两种锚点混存时分「正文批注 / 页面批注」两个顶级分区。装配层 `AnnotationExport.render`（仓储取数，无批注返回 null）。`AnnotationMarkdownTest` 14 用例（章节分组与排序/多行引用块/笔记与颜色开关/页码降级/章节名解析/混存分区/空批注/特殊字符转义/文件名清洗）。
- 入口与选项：三处单书入口共用宿主 `rememberAnnotationExport`（feature/reader/AnnotationExportUi）——选项对话框（含笔记 / 含颜色标记，默认皆开）→ 渲染 → SAF「另存为」（CreateDocument("text/markdown")，默认文件名 `书名-批注-日期.md`，书名非法字符清洗）。书籍详情页「导出批注（Markdown）」对全格式开放；文本与漫画两个阅读器的批注列表底部新增「导出」（先收列表再弹选项，避免对话框叠置抢焦点）。无批注时提示「本书还没有批注可导出」，不落空文件。
- 批量导出：书架多选顶栏新增「导出批注」——选项确认时取定书单快照（不受后续选择变化影响）→ SAF 目录授权（OpenDocumentTree）→ `BookshelfViewModel.exportAnnotationsBatch` 逐书 `DocumentsContract.createDocument` 落盘（与自动备份同一链路），无批注的书跳过、单本失败不中断，snackbar 汇总「已导出 N 本书的批注，M 本无批注跳过，K 本导出失败」。
- 测试：`testLiteDebugUnitTest` 全绿（新增 14 用例）；`assembleLiteDebug` 通过。
- 决策备注：批量走「选目录 + 逐书一个文件」而非逐个 CreateDocument——N 本书逐个弹「另存为」要连点 N 次系统选择器，体验明显更差；同名文件由文档提供方自动改名，不做覆盖判定。
- 真机待验：三种入口的 SAF 写入在各品牌文档提供方下的落盘观感；大批量（几十本）导出时的进度反馈（当前只在结束时汇总）。

---

## M28 词典取词 + 生词本（P1）✅ 已完成（2026-10-08）

**验收标准**：长按选中可查词典释义（离线词典或 AI 解释至少其一可用）；生词收藏带上下文例句与来源书，可导出 CSV。

- [x] AI 划词解释：复用选中即译链路换词典式 prompt（释义 / 词性 / 例句），零新底座，台账 feature=划词解释
- [x] 本地词典：StarDict / MDict 格式选型评审（离线优先，契合产品哲学）；SAF 导入词典文件，查词优先本地、无命中回落 AI（已配置时）
- [x] 生词本：词条 + 上下文例句 + 来源书与位置；列表管理（按书 / 按时间）
- [x] 导出 CSV（Anki 兼容列：单词 / 释义 / 例句）
- [x] 生词本进备份

验收记录（2026-10-08）：

- StarDict 纯 JVM 解析层：`core/dict/StarDict` 零 android import——ifo 解析、idx 流式索引、按 UTF-8 无符号字节序二分查词、大小写三档回落（原样 → 小写 → 首字母大写）、dict 按区间读取、html 词条剥标签纯文本化、`.dz`（dictzip）解压。`StarDictTest` 11 用例。
- 词典导入与查词服务：`DictionaryStore` 把 SAF 目录导入的三件套（ifo/idx/dict，dz 自动解压）落到 `filesDir/dicts/<stem>/` 并校验完整性，MDict（.mdx/.mdd）明确报「暂不支持」；`DictionaryLookupService` 本地优先，未命中返回 `Miss(aiAvailable)` 由 UI 决定是否回落 AI，词典增删后 `invalidate()` 重建索引。设置页「词典管理」对话框（SAF 目录导入 / 逐个删除）。`DictionaryStoreTest` 13 用例。
- AI 划词解释：`core/ai/prompt/SelectionExplainPrompt` 词典式输出（释义 / 词性 / 例句），解释语言占位符注入，登记 `BuiltinPrompts`（feature 名「划词解释」），4 用例。
- 阅读器集成：选区操作条新增「查词」——`lookupAvailable = aiConfigured || hasDictionaries`，两者皆无时不出现，译文视角禁用；DictCard 状态机（LocalHit / Miss / AwaitConfirmation / Loading / AiStreaming / Error）：本地命中零网络零确认直显释义，未命中且已配 AI 时先经 `aiExplainConfirmed` 一次性确认再走通用模型流式解释（台账 feature=划词解释）；卡片「收藏」落生词本。
- 生词本：DB v9 新建 `vocabulary_entries`（词条 / 释义 / 上下文例句 / 来源书与 charOffset / 来源 / 时间，FK 随书级联，索引 bookId+word），MIGRATION_8_9 + 迁移单测 + schema 快照（9.json）；例句抽取纯函数 `ContextSentence` 6 用例；设置页生词本列表按时间 / 按书分组、逐条删除。
- 导出与备份：`VocabularyCsv` Anki 兼容三列（单词,释义,例句）RFC4180 转义，SAF 另存为导出（4 用例）；BACKUP_VERSION 7→8 备份含 vocabulary 段，恢复按 contentHash 重映射 bookId、以 (bookId, word, charOffset) 去重，旧备份缺段兼容（BackupCodecTest +4）。
- 合规自查：本地查词零网络零确认 ✓；未配置 AI 且无本地词典时查词入口不出现（lookupAvailable 把关）✓；台账 feature=划词解释 ✓；MDict 明确不支持 ✓；生词本进备份 ✓。
- 测试：`testLiteDebugUnitTest` 全绿（本里程碑新增 43 用例：11+13+4+1 迁移+6+4+4）；`assembleLiteDebug` 通过。
- 提交拆分备注：与主 agent 建议的 6 提交略有出入——原「提交 3/4」对调（先 prompt 后阅读器集成），生词本拆成「底座（DB）」与「集成（阅读器+设置）」两笔，保证每个提交独立可编译。
- 真机待验：真实 StarDict 词典导入观感与查词卡片交互；SAF 目录授权在各品牌文档提供方下的行为；CSV 导出落盘。

---

## M29 AI 章节摘要 + 问书（P1）✅ 已完成（2026-10-08）

**验收标准**：章节摘要可后台预生成（复用全书翻译队列骨架）；问书限定已读范围并在回答中标注章节出处；全部走现有合规链路。

- [x] 章节摘要：prompt + 结构化落盘（新表或复用译本存储模式，需 DB 迁移 + schemas 快照）；目录面板章行展示摘要入口
- [x] 摘要批量预生成入队（复用 `BookPrewarmQueue` / 全书翻译队列骨架），断点续做
- [x] 全书大纲：摘要链聚合生成
- [x] 问书 QA：上下文 = 当前章 + 已有摘要链；提问页明示外发范围；回答标注来源章节
- [x] 首次外发一次性确认、台账、提示词登记 BuiltinPrompts——同既有口径

验收记录（2026-10-08）：

- 提示词：`core/ai/prompt/ChapterSummaryPrompt`（150~300 字纯文本摘要，不评论不剧透本章之外）与 `BookOutlinePrompt`（摘要链聚合成 3~8 阶段大纲，标注章节范围）——摘要/大纲输出为纯文本，解析只做围栏剥离 + 空输出丢弃（与既有「畸形丢弃」口径一致）；两者连同 M29 后半的 `BookQaPrompt` 均登记 `BuiltinPrompts`（章节摘要 / 全书大纲 / 问书），登记表保留 `{{目标语言}}` 占位符原文。9 用例。
- 存储：DB v10 新建 `chapter_summaries`（(bookId, lang, unitIndex) 主键，状态机 pending/summarizing/done/failed + 摘要正文 + 单位标题快照，随书级联）与 `book_outlines`（(bookId, lang) 主键 + 聚合时摘要条数）。**决策**：选 DB 新表而非文件存储——摘要正文短（≤300 字）落库即可，状态机与 `translations` 同构，删书/清数据一行 SQL 收尾，少一套目录清理。MIGRATION_9_10 + 迁移单测（列结构逐项对 Room 期望 + 主键约束 + 级联形状）+ schema 快照 10.json（快照 SQL 与迁移逐字一致）。
- 队列：`BookSummaryQueue` 复用全书翻译队列骨架（Channel + 单协程串行、单位间让出、指数退避重试、断点续做跳 done、暂停/恢复/按书取消、StateFlow 进度），单位划分直接复用 M19 `BookTranslationSource`（摘要单位 = 翻译单位，与目录翻译状态同一份切块边界）；`SummaryService` 前台服务托进程（进度通知限频 ≥2s、暂停/继续动作、完成通知，channel=book_summary，id 45/46）。书籍详情新增「预生成摘要」（仅文本格式 + AI 已配置时渲染）——确认页明示外发范围（全书正文分批发往所配置服务商）+ 成本估算（token ≈ 字符数/2 × 设置单价，同「翻译全书」口径）+ 已有 done 计数断点续做提示；首次外发的一次性确认并入此页（未确认时按钮为「同意外发并开始」，落 `aiSummaryConfirmed`）。队列 5 用例 + 引擎 5 用例（含未配置 AI 零外发零台账）。
- 目录面板：章行在翻译状态旁新增摘要状态（未摘要/摘要中/已摘要 x/n/已摘要/失败，映射纯函数 `chapterSummaryRows` 5 用例）+ 「摘要」查看（该章覆盖单位的全部 done 摘要）与「生成摘要」（未 done 单位逐个跑，章行首次外发走同一个 `aiSummaryConfirmed` 确认框）；面板顶部「全书大纲」视图展示聚合成品（附摘要条数，链变长提示可重新生成）。
- 问书：阅读菜单翻译组新增「问书」（仅 AI 已配置且原文视角）。上下文组装为纯 JVM `core/summary/BookQaContext`（5 用例）：前序摘要链（只取当前单位之前的 done 摘要 = 限已读范围）+ 当前章正文，超长截断策略「摘要全保留、当前章截头」（尾部 = 最近情节）；提问页常驻明示外发范围，首次走 `aiQaConfirmed` 一次性确认（附本次范围与服务商地址），回答流式上屏并随答展示依据范围，prompt 约束以《章节标题》标注来源、上下文不足明说、不剧透当前章之后。台账 feature=问书（引擎按单位记 章节摘要 / 大纲记 全书大纲 / 确认页整书记 全书摘要）。
- 备份：BACKUP_VERSION 8→9，新增 summaries（仅 done）/outlines 段，按 contentHash 重映射来源书，书未导入跳过、本机已有同键不覆盖、重复恢复不堆行；v8 旧备份缺段兼容（BackupCodecTest +3）。「清除全部 AI 数据」连带摘要与大纲两表；删书走外键级联。
- 测试：`testLiteDebugUnitTest` 全绿（本里程碑新增 36 用例：12 prompt + 1 迁移 + 5 引擎 + 5 队列 + 5 章行映射 + 5 QA 上下文 + 3 备份）；`assembleLiteDebug` 通过。
- 真机待验：预生成队列在退桌面/锁屏下的前台服务行为；目录面板长标题 + 双状态标签 + 双动作按钮的窄屏折行；问书在大章（截头后）下的回答质量。

---

## M30 AI 校对 + 翻译扩展（P1，可与 M28/M29 并行）✅ 已完成（2026-10-09）

**验收标准**：AI 校对只标不改、逐条确认；新增目标语言全书翻译跑通。

- [x] AI 校对：TXT 错别字 / 病句检测，结果以批注形式呈现（只标不改，符合「AI 不改正文」口径），用户逐条确认后可生成清洗配方交 M16 链路执行
- [x] 翻译目标语言扩展：韩语 / 法语 / 德语 / 西班牙语（prompt 已参数化，扩枚举 + 设置页）
- [x] 漫画视觉模型整卷翻译：M23 自留口子，加成本预估与速率限制后开放

验收记录（2026-10-09）：

- 目标语言扩展（提交 cfd47f7）：`AiTargetLang` 扩为 8 种（+KO/FR/DE/ES）；prompt 侧本就参数化——`SelectionTranslatePrompt.displayName` 一处注入全链路（选中即译/章节翻译/漫画文本与视觉/摘要/大纲/问书全部经它）。`TtsLanguage` 同步扩 4 种（ko/fr/de/es，`fromAiTarget` 译文朗读跟随用得到）；分段按钮短标签与「翻译全书」语言表补齐。设置页「默认目标语言」从 4 项分段按钮改为弹窗单选（8 项一行放不下）。**偏差**：设置页交互从分段按钮改为单选弹窗（TODO 只说「扩枚举 + 设置页」，8 项分段按钮在窄屏不可用）。TtsLanguageTest 原断言「FR 为非法枚举名」随扩展修正。
- AI 校对 prompt + 解析（提交 735a136）：`core/ai/prompt/ProofreadPrompt`——输出契约 JSON 数组（offset/length 相对送校单位正文，USER 只放正文不带标题前缀保证偏移对齐；original/suggestion/type=typo|grammar），宁缺毋滥；解析容忍散文包裹与代码围栏，区间越界/原文不符（允许就近重定位一次，模型数偏移常数错）/空建议/原文=建议逐条丢弃，按 offset 去重升序、上限 50 条。登记 `BuiltinPrompts`（AI 校对）。10 用例。
- 校对执行链路（提交 5c3c564）：`ProofreadAiViewModel`（CleanRecipe 同款 lambda 注入纯 JVM 可测）按 M19 翻译单位切块逐单位送校（通用模型，每单位 90s 超时、流异常或解析失败整体重试一次后记失败单位继续）；结果**只落成批注**（note=`【AI 校对·类型】「原文」→「建议」`，橙色高亮，坐标 = 单位 charStart + 单位内偏移，与阅读器批注同一坐标系），正文一个字节不动；重跑先清上一轮校对批注（`AnnotationDao.deleteByBookAndNotePrefix`，纯 @Query 无迁移）。首次外发一次性确认 `aiProofreadConfirmed`（确认页明示服务商地址 + 全书字数 + token 估算），台账按单位记 feature=AI 校对；入口在书籍详情「AI 校对」按钮（仅 TXT + AI 已配置渲染，未配置零入口零网络）。VM 8 用例（首次确认闸门/全书坐标换算/未配置零外发零台账零批注/畸形重试记失败单位/重跑清旧/合并计数等）。
- 逐条确认 → 清洗配方（提交 f273c16）：`CleanProfile` 新增 `replacements: List<Pair<Regex, String>>`（字面替换，`Regex.escape` 构造、transform 重载写入防 `$` 组引用），`NovelCleaner` 管线最前加 `ReplacementSink`，报告新增「替换修正」计数；校对对话框 Done 态按「原文→建议」合并成确认列表（同一条全书多处只勾一次），「生成清洗配方」把勾选项转成**纯替换配方**（toggles 全关、不加广告正则、建议含原文的不幂等规则丢弃、上限 100 条），经 `onApply` 带进「智能整理」对话框（`RecleanConfirmDialog` 新增 initialProfile），走既有预览报告 → 确认物化链路，**不新增执行路径**。NovelCleaner +4 用例、VM +2 用例。**偏差/决策**：确认粒度按「唯一 原文→建议 对」合并而非逐处（替换规则本就是全书生效，逐处勾选没有语义差异）；配方只含确认的替换项（不动既有清洗开关），避免顺手触发用户没确认的重排。
- 漫画视觉整卷翻译（提交 865a01b）：`ComicTranslationQueue` 支持视觉模式（Job 带 vision 标记，页间隔限速 1500ms——文本模式 200ms；进度带 vision 标记、前台服务通知标注「视觉」）；菜单「视觉整卷」与「视觉翻译」同一配置闸门（视觉/通用模型其一非空）；确认页明示「整页图片逐页外发 × N 页（扣已译断点）」+ 成本估算（每页 ≈10K token 粗估——长边 1024px JPEG q85 ≈150KB→base64/16 口径——× 设置单价）+ 限速说明；逐书首次确认复用 `aiComicVisionConfirmedBooks`（与「视觉翻译本页」同一账本）；引擎侧 `translatePageVision` 既有（台账 feature=漫画视觉翻译注明页图像）。队列 +1 用例（视觉调用分发与进度标记），E2E 与假引擎签名同步更新。
- 测试：`testLiteDebugUnitTest` 全绿（本里程碑新增 27 用例：11 校对 prompt + 10 校对 VM + 4 清洗器替换 + 1 队列视觉 + 1 TTS 映射）；`assembleLiteDebug` 通过。
- 真机待验：校对批注在清洗副本书上的锚点对齐（校对读的是读者当前文本=清洗副本）；确认列表长书名/长原文片段的折行；视觉整卷在大页数书上的速率与费用实际偏差。

---

## M31 漫画翻译观感（P1）

**验收标准**：竖排气泡内译文竖排可读；inpainting 模式下气泡底色无明显穿帮；整卷视觉翻译可用。

- [ ] 竖排译文渲染：`core/translate/BubbleRender` 扩展竖排模式（日漫 RTL 时默认），字距 / 行距适配
- [ ] inpainting：LaMa 等轻量模型 ONNX 选型评审；走 `ModelManager` 新增槽位（沿用清单 SHA-256 校验 / 自定义槽位机制）；失败回落采样底色
- [ ] ComicInfo.xml：漫画容器标准元数据读取，回填系列 / 卷号 / 作者（提升系列匹配准确度）
- [ ] 自动裁白边：扫描漫画白边检测与裁剪，逐书记忆
- [ ] 条漫专项：滚动模式气泡微调（已知边界补全）；原生超长图的检测与翻译管线

---

## M32 PDF 增强（P1）

**验收标准**：扫描 PDF 裁边后有效面积明显增大、逐书记忆；PDF 双页模式与漫画双页同口径。

- [ ] 裁边：自动检测 + 手动框选，逐书记忆，渲染时应用（androidx.pdf 裁剪框）
- [ ] 双页模式：PDF 走 `PagedImageSource` 接缝复用漫画双页布局与翻页动画
- [ ] （远期，单独立项再排）手写批注 ink：笔迹存储 / 压感 / 橡皮

---

## M33 折叠屏深化（P2）

**验收标准**：桌面模式下半屏可切目录 / 批注 / 术语表面板；双屏两书方案有结论（做或不做均记录在案）。

- [ ] 桌面模式下半屏面板扩展：目录 / 批注列表 / 术语表标签页（「上边读下边查」折叠屏独占交互）
- [ ] 双屏同开两本书：activity embedding / multi-window 调研先行，结论归档
- [ ] 外屏 → 内屏续读过渡动画：封面 → 双页书，强化「打开一本书」物理隐喻

---

## M34 书架与管理（P2）

**验收标准**：系列视图同时覆盖漫画与 EPUB series 字段；阅读目标打卡数据全部本地现成复用。

- [ ] 系列视图：漫画系列匹配 + EPUB series 元数据统一成「系列」一等公民；书架系列聚合展示与系列内排序
- [ ] 阅读目标与连续打卡：每日目标 X 分钟 + 连续天数（复用阅读统计日桶，零新数据采集）
- [ ] 书架级全文搜索：后台索引现有章节索引复用，可选开关，明示索引占用
- [ ] 批量管理增强：批量移动分组 / 批量删除 / 批量导出
- [ ] 隐私锁：应用锁（生物识别）或指定书籍隐藏
- [ ] 桌面小部件：「继续阅读」widget（最近在读 + 进度，一键回阅读页）

---

## M35 工程质量（P2，可随时插队，可并行）

- [ ] OCR GPU / NNAPI 后端：onnxruntime execution providers 评审（TODO 归档 v1 已列为后续优化；整卷漫画翻译 CPU 是瓶颈）
- [ ] Baseline Profile + Macrobenchmark：开书 / 分页 / 翻页核心路径建基准防回归
- [ ] TalkBack 无障碍审计：阅读器自定义绘制区域的语义树、选区播报、翻页播报

---

## 每个里程碑收尾统一打钩（隐私合规）

- [ ] 未配置对应服务（AI / WebDAV）：无入口、无网络组件、抓包零请求
- [ ] 凭据不出现在备份 / 诊断日志 / 崩溃报告 / dumpsys
- [ ] 每类功能首次外发有明示；外发历史可查
- [ ] 批量功能有成本预估并逐书确认
- [ ] AI 产物标注来源、可一键清除、不改正文
