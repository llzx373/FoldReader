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

## M28 词典取词 + 生词本（P1）

**验收标准**：长按选中可查词典释义（离线词典或 AI 解释至少其一可用）；生词收藏带上下文例句与来源书，可导出 CSV。

- [ ] AI 划词解释：复用选中即译链路换词典式 prompt（释义 / 词性 / 例句），零新底座，台账 feature=划词解释
- [ ] 本地词典：StarDict / MDict 格式选型评审（离线优先，契合产品哲学）；SAF 导入词典文件，查词优先本地、无命中回落 AI（已配置时）
- [ ] 生词本：词条 + 上下文例句 + 来源书与位置；列表管理（按书 / 按时间）
- [ ] 导出 CSV（Anki 兼容列：单词 / 释义 / 例句）
- [ ] 生词本进备份

---

## M29 AI 章节摘要 + 问书（P1）

**验收标准**：章节摘要可后台预生成（复用全书翻译队列骨架）；问书限定已读范围并在回答中标注章节出处；全部走现有合规链路。

- [ ] 章节摘要：prompt + 结构化落盘（新表或复用译本存储模式，需 DB 迁移 + schemas 快照）；目录面板章行展示摘要入口
- [ ] 摘要批量预生成入队（复用 `BookPrewarmQueue` / 全书翻译队列骨架），断点续做
- [ ] 全书大纲：摘要链聚合生成
- [ ] 问书 QA：上下文 = 当前章 + 已有摘要链；提问页明示外发范围；回答标注来源章节
- [ ] 首次外发一次性确认、台账、提示词登记 BuiltinPrompts——同既有口径

---

## M30 AI 校对 + 翻译扩展（P1，可与 M28/M29 并行）

**验收标准**：AI 校对只标不改、逐条确认；新增目标语言全书翻译跑通。

- [ ] AI 校对：TXT 错别字 / 病句检测，结果以批注形式呈现（只标不改，符合「AI 不改正文」口径），用户逐条确认后可生成清洗配方交 M16 链路执行
- [ ] 翻译目标语言扩展：韩语 / 法语 / 德语 / 西班牙语（prompt 已参数化，扩枚举 + 设置页）
- [ ] 漫画视觉模型整卷翻译：M23 自留口子，加成本预估与速率限制后开放

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
