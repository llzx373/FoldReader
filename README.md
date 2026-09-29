# FoldReader

[![CI](https://github.com/llzx373/FoldReader/actions/workflows/ci.yml/badge.svg)](https://github.com/llzx373/FoldReader/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/llzx373/FoldReader?sort=semver)](https://github.com/llzx373/FoldReader/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-13%2B%20(API%2033)-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.20-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material3%20Expressive-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)

**专为折叠屏打造的本地电子书 / 漫画 / PDF 阅读器。**

> 折叠屏展开后不是"更大的手机"，而是"一本打开的书"。铰链是书脊，左右两屏是左右两页。

FoldReader 不把展开态当成一块更宽的画布来硬塞内容，而是按"书"的物理模型重新组织版式：展开时左右两屏各承载一页、铰链位置自动避让并渲染书脊阴影、书脊侧的页边距加宽；折到桌面模式时，上半屏放正文、下半屏放控制面板。折叠与展开、旋转与姿态切换全程不丢阅读位置。

[English](README_EN.md) | 简体中文

---

## 目录

- [功能特性](#功能特性)
- [支持的格式](#支持的格式)
- [安装](#安装)
- [从源码构建](#从源码构建)
- [项目结构与架构](#项目结构与架构)
- [技术栈](#技术栈)
- [已知限制](#已知限制)
- [文档](#文档)
- [贡献](#贡献)
- [开源许可](#开源许可)

---

## 功能特性

### 折叠屏形态适配

- **单页 → 双页书模式 → 桌面模式**：折起时单页阅读，展开时自动进入双页书模式，半开时进入桌面模式（上半正文、下半控制面板）。自动模式下窗口竖持（高 > 宽）一律退回单页，横持才进双页。
- **铰链即书脊**：按铰链位置切分布局，避开物理折痕区域，并在中缝渲染书脊阴影；靠书脊一侧的页边距自动加宽。
- **姿态连续性**：折叠、展开、旋转、进入桌面模式，阅读位置与分页状态连续保持，不会跳回章节开头。
- **双页策略可覆盖**：自动判断 / 强制双页 / 强制单页（`DualPageMode`）；强制双页不受窗口方向限制。

### 排版引擎

- 基于 `StaticLayout` 的分页引擎，按实际字宽逐行排布后切页，而非按字符数估算。
- **中文避头尾（kinsoku）**：标点不出现在行首/行尾的违规位置。
- 两端对齐、首行缩进、段间距、字距、行距、每行字数（18–40 字）、页边距档位均可调。
- **分页边界缓存**：内存 LRU + 磁盘缓存双层，二次打开与来回翻页不再重排。

### 大文件与编码

- **100MB+ TXT 窗口化读取**：按字节偏移窗口读取，绝不把整本文件读进内存；章节索引与预热在后台队列完成。
- **编码自动识别**：UTF-8（含/不含 BOM）、UTF-16 LE/BE、GBK、GB18030、Big5，并支持手动指定。
- **章节规则库**：正则规则识别章节标题，可自定义增删；识别不出时退化为按进度跳转，也可用 AI 归纳规则（见下）。

### 阅读辅助

- 多级目录、书签、多色高亮与批注。
- 全文搜索，结果按章节分组。
- 自动翻页（定时 / 滚动两种模式）、阅读计时与阅读统计。
- **仿真翻页**（可选，默认仍是覆盖）：2.5D 折痕反射，翻起一张不变形的平纸；电子书与漫画 / 页式 PDF 都支持，展开双页只卷一叶并绕书脊盖住对页。日漫方向与覆盖动画同一口径。
- 5 套内置主题（绿、羊皮纸、灰白、夜间、AMOLED 纯黑）+ 自定义配色。
- 应用内亮度调节、屏幕常亮。
- 页内锚点：书签与批注可精确到页内位置（点或矩形），漫画 / PDF 同理。
- 中间点击区可配置：单击与双击各自绑定动作（切换菜单 / 上一页 / 下一页 / 书签 / 缩放 / 无）。

### TTS 听书

- 系统 TTS 引擎朗读：从当前位置 / 朗读整章，逐句推进，翻页随朗读进度联动。
- **前台服务 + MediaSession**：锁屏、退桌面不中断；通知栏与耳机按键可暂停 / 继续 / 停止。
- 目前按中文引擎朗读；语速调节与按书语言切换是后续扩展点。

### AI 翻译与辅助（自配服务商，未配置零网络）

AI 功能全部依赖用户在「设置 → AI 服务」自行配置的服务商（OpenAI Chat / OpenAI Responses / Anthropic 三种协议，通用 / 翻译 / 视觉模型分别配置）。未配置时相关入口一律隐藏，应用不产生任何网络请求。

- **翻译族**：长按选中即译（可一键存为批注）、翻译本页（原文/译文逐段对照的流式面板）、翻译本章/本节、**全书翻译**（批量队列、断点续译、前台服务进度通知、读到未译段自动插队）。
- **三种译文视角**：原文/译文切换、双页对照（左原右译）、滚动段落对照。
- **漫画翻译**：气泡检测与 OCR 完全本地离线，只把识别出的文字发给 AI 翻译；覆盖层 / 双页对照 / 气泡对照三种视角；跨页气泡合并、气泡位置手动微调；可选视觉模型整页直译（整页图片外发，逐书单独确认）。
- **术语表**：「原文 → 固定译法」词条，全局 / 漫画系列 / 单书三级作用域，翻译时自动注入，保证人名与术语译法一致；候选由人物索引与模型回填生成，确认后才生效。
- **AI 辅助**：AI 识别章节规则（TXT，采样标题行归纳正则并本地试切预览）、AI 推荐清洗配方（只推荐不执行，照常走预览确认）、AI 补全书籍元数据（只填空不覆盖，支持批量，AI 填的字段有标记）。
- **合规口径**：API key 经 Android Keystore 加密存储；每类功能首次外发前一次性明示确认；「外发历史」台账记录每次发出的内容与 token 估算；内置提示词全文只读可查；一键清除全部 AI 数据。

### OCR 与离线模型

- 本地 ONNX 推理（CPU 后端），识别词典随包携带；支持中 / 英 / 日三种识别语言。
- **扫描版 PDF 文本层**：无内嵌文本层的扫描件经 OCR 生成文本层，可全文搜索、可拖框选字。
- **漫画气泡识别**：RT-DETR 气泡检测 + PP-OCRv4 文字识别，为漫画翻译提供离线前段。
- **模型管理**：官方模型按清单全量 SHA-256 校验导入；也可导入自己的微调 / 自定义 `.onnx`（优先生效，可单独删除回落官方）。full 包自带全部 5 个官方模型，lite 包自行导入（见「安装」）。

### 导入与书架

- 书架支持网格 / 列表视图、分组、自动生成的书籍封面与漫画封面。
- 支持 **TXT / EPUB / FB2 / PDF** 与漫画容器（**CBZ / CBR / CBT / CB7**，以及直接当作容器的 zip / rar / tar / 7z，还有图片目录）；批量导入；也支持从其他 App 或文件管理器用"打开方式"送入，以及从系统分享面板**分享**给本 App（`VIEW` + `SEND` / `SEND_MULTIPLE`）。
- **内置文件浏览器**（底部「浏览」页）：用 SAF 授权外置文件夹后直接浏览其中的书——单本打开即按「外置来源」入库、整个目录导入为分组、图片目录直接当漫画打开；书架上有外置来源角标。
- **同系列上下卷切换**：自动在当前目录/分组中匹配同名（同系列）书籍，一键切上一卷 / 下一卷。
- 导入时可选**智能清理**（离线规则引擎）：三档预设（保守/标准/激进）+ 14 项细粒度开关，覆盖空白混掺、行尾空格、段中空行、硬换行拆段、章节名不顶格、章节命名混乱、引号内被拆开、星号遮蔽、网站宣传语、论坛残留等；可先看**清洗预览报告**再决定，已导入的书也能「智能整理」；另可自定义广告行正则与繁简转换。档位**三处入口同一份规则**：书架导入对话框按当次选择，「浏览」打开与「导入目录为分组」跟随设置页的档位与繁简偏好。
- 导入时选了清理，**原版与清洗版会各占一个书架条目**（两本共用同一份源副本，不额外占空间），书架上用「已清洗」角标区分，详情页写明读的是原文件还是清洗副本——想对照或回读原文，不必重新导入一次。
- 导入时把**原文原样复制一份**进应用私有目录，正文从此不依赖外部授权——外部「打开方式」给的是临时授权，任务结束或重启后就会失效，直接引用它等于那本书早晚打不开。

### 数据与隐私

- **本地优先**：无账号、无官方云服务、无统计上报。书架、进度、书签与标注全部存储在本机。
- **网络仅用于用户显式配置的功能**：`INTERNET` 权限随 AI 功能引入（设置页「AI 服务」，用户自带 API key、自配服务商端点，应用直连其配置的服务商）；未配置 AI 服务时，应用不产生任何网络请求。WebDAV 备份/恢复（用户自托管服务器）仍属规划。
- 备份与恢复：本地 JSON 导出/导入（WebDAV 远程备份规划中）。
- 诊断日志：崩溃与返回栈追踪可导出，便于报 issue 时附上。

## 支持的格式

| 类别 | 扩展名 / 容器 | 说明 |
| --- | --- | --- |
| 纯文本 | `.txt` | 主力格式，支持超大文件与多种编码 |
| EPUB | `.epub` | 目录支持 EPUB3 NAV 与 EPUB2 NCX |
| FictionBook | `.fb2`、`.fb2.zip` | 支持裸 XML 与 zip 打包两种形态 |
| PDF | `.pdf` | 文本型可按普通电子书阅读（含排版与搜索）；扫描件走页式渲染，可经本地 OCR 生成文本层（搜索 / 选字） |
| 漫画 | `.cbz` `.zip`、`.cbr` `.rar`、`.cbt` `.tar`、`.cb7` `.7z` | 按真实魔数判容器，扩展名错标也能正确解开 |
| 漫画 | 图片文件夹 | 通过系统文件选择器（SAF）直接选择一个图片目录 |

格式识别采用 **magic bytes 优先、扩展名/MIME 兜底** 的策略：`.cbr` 实际是 zip 这类错标在漫画资源里相当常见，按真实字节判定才解得开。

漫画阅读额外支持：从右往左（日漫）方向、适应整页/宽度/高度/原始尺寸四种缩放、缩略图快速跳页、页内锚点书签，以及离线气泡识别 + AI 翻译（见「AI 翻译与辅助」）。漫画不依赖目录结构，而是通过文件名规则自动排序并识别同系列。

## 安装

1. 到 [Releases](https://github.com/llzx373/FoldReader/releases) 下载最新的 APK，两个版本二选一（功能完全相同，包名与签名一致，只能装一个、可互相覆盖安装切换）：
   - `FoldReader-<版本>-full.apk` —— **自带全部 OCR/漫画翻译模型**，装完即用（包较大）；
   - `FoldReader-<版本>-lite.apk` —— 不带模型（包小），模型在「设置 → OCR 模型 → 模型管理」里自行下载导入，也可以导入自己的微调/自定义 .onnx。
2. 系统要求 **Android 13（API 33）及以上**（仅 arm64-v8a 与 x86_64）。
3. APK 未上架任何应用商店，安装时需允许"安装未知来源的应用"。

**设备说明**：大折叠屏（华为 Mate X 系列、三星 Z Fold 系列、荣耀/OPPO/vivo/小米折叠屏）与阔折叠（16:10 内屏，如华为 Pura X）是主要目标形态。直板机与竖折机可正常使用，但会退化为单页阅读，体验不到双页书模式。

## 从源码构建

构建环境、flavor 说明、常用命令、签名与版本号规则见 **[docs/构建与打包.md](docs/构建与打包.md)**。

快速开始：安装 JDK 25 与 Android SDK，在仓库根目录建 `local.properties` 写上 `sdk.dir=...`，然后 `./gradlew :app:assembleLiteDebug`。

## 项目结构与架构

单模块（`:app`），Kotlin + Jetpack Compose，包根为 `com.llzx373.foldreader`。

```
com.llzx373.foldreader
├── MainActivity.kt / FoldReaderApplication.kt   # 入口 + AppContainer（手写依赖注入）
├── navigation/     # 路由与 NavHost
├── ui/             # 应用外壳、主题、通用对话框
├── core/
│   ├── format/     # 格式引擎：BookParser 抽象 + txt/epub/fb2 解析器、编码识别、章节扫描
│   ├── reader/     # 排版与分页引擎：Paginator、避头尾、页面几何、缓存、字体
│   ├── data/       # Room 数据库 + 仓库 + DataStore 设置
│   ├── foldable/   # 折叠状态与姿态（FoldableStateProvider / FoldingPosture）
│   ├── comic/      # 漫画容器（zip/rar/tar/7z）、切页、图片解码、封面、系列匹配
│   ├── paged/      # PagedImageSource：漫画与 PDF 共用的页式读取接缝
│   ├── pdf/        # PDF 渲染与元数据
│   ├── ocr/        # OCR 管线（检测/识别/气泡）与模型清单；android/ 下为 ONNX 会话与文本层
│   ├── translate/  # 译本存储、术语合并、气泡渲染等翻译纯逻辑
│   ├── ai/         # AI 服务商协议（OpenAI Chat/Responses、Anthropic）、外发台账、提示词
│   ├── tts/        # TTS 切句器；android/ 下为引擎控制器与前台播放服务
│   ├── backup/     # 备份导出 / 导入编解码
│   └── debug/      # 诊断日志、返回栈追踪
└── feature/        # 界面层：bookshelf / reader / comic / importer / filebrowser / translate / settings
```

三条贯穿全局的设计主线：

1. **手写依赖注入**：`AppContainer` 在 `FoldReaderApplication` 中集中装配数据库、仓库、解析器、用例与缓存，不引入 Hilt/Koin。依赖关系一眼可见，构建期成本为零。
2. **格式引擎与阅读内核分离**：非 TXT 格式（EPUB / FB2 / PDF 文本模式）在导入时被**统一拍平成 TXT + `.toc` 目录旁文件**，之后走同一套排版与分页内核。阅读核心因此没有任何格式分支。
3. **页式阅读接缝**：漫画与 PDF 统一实现 `PagedImageSource`，`ReaderHost` 只按格式在"文本阅读"与"页式阅读"之间分派一次，两种页式格式共享锚点、书签、缩放与翻页手势。

## 技术栈

| 层 | 选型 |
| --- | --- |
| 语言 | Kotlin 2.4.20（AGP 9 内置 Kotlin 编译器 + Compose 编译器插件） |
| UI | Jetpack Compose（BOM 2026.09.00）、Material 3 Expressive（1.5.0-alpha28）、Material3 Adaptive |
| 折叠屏 | `androidx.window` 1.5.0 + `FoldingFeature` |
| 架构 | MVVM + 单向数据流，仓库暴露 `Flow` |
| 持久化 | Room 2.8.5（KSP）、DataStore Preferences 1.2.0 |
| 导航 | Navigation Compose 2.10.1 |
| 并发 | kotlinx-coroutines 1.11.0 |
| 漫画容器 | Apache Commons Compress 1.28.0、XZ for Java 1.12、junrar 8.1.1 |
| PDF | androidx.pdf 1.0.0-beta01（沙箱文档服务）、PDFBox-Android 2.0.27.0 |
| OCR 推理 | onnxruntime-mobile 1.18.0（CPU 后端；ABI 限 arm64-v8a + x86_64） |
| 构建 | Gradle 9.7.1、AGP 9.4.1、KSP 2.3.12、JDK toolchain 25 |
| 测试 | JUnit4、Robolectric 4.17、kotlinx-coroutines-test、Compose UI Test |

## 已知限制

- **数据库向前兼容**。自 `v1.0.0` 起的已发布版本都能原地升级：改 schema 必须同时升 `version` 并在 `core/data/db/DatabaseMigrations.kt` 补一条迁移，`app/schemas/` 下各版本的快照一律保留、不重置基线；也不启用破坏性降级（对不上宁可报错，不静默清库）。跨大版本升级前仍建议先导出一次应用内备份。
- **OCR 完全离线、需模型**。扫描版 PDF 的文本层（搜索 / 选字）与漫画翻译的气泡识别都跑在本机 ONNX 推理上，但要先导入模型（full 包自带，lite 包自行导入）；不做任何在线 OCR / 云识别。
- **不做在线书城、账号体系、社交**。书籍与数据以本地为准；唯一的联网例外是用户显式配置的 AI 服务与 WebDAV 备份（见「数据与隐私」）。
- **仿真翻页是可选的第四种方式**（覆盖 / 仿真 / 无动画 / 滚动），默认仍是覆盖。旧的圆柱/铰链 3D 模型已删除（提交 `9a0c0ad`），现用 2.5D 折痕反射，见设计说明书附录 v2.4。
- **未上架任何应用商店**，仅通过 GitHub Releases 分发。
- 目标 SDK 为滚动版本（API 37），需要较新的 Android SDK 才能从源码构建。

## 文档

| 文档 | 内容 |
| --- | --- |
| [docs/需求与设计说明书.md](docs/需求与设计说明书.md) | 完整的产品定位、折叠形态分析、交互与排版设计、技术架构 |
| [docs/AI功能需求与实施.md](docs/AI功能需求与实施.md) | AI 功能规划：网络策略、技术底座、功能优化 / 翻译 / OCR 三条功能线 |
| [docs/构建与打包.md](docs/构建与打包.md) | 从源码构建：环境、flavor、命令、签名与版本号 |
| [docs/发布流程.md](docs/发布流程.md) | 签名密钥生成、GitHub Secrets 配置、打 tag 发布 |
| [docs/images/README.md](docs/images/README.md) | README 截图采集清单 |
| [CHANGELOG.md](CHANGELOG.md) | 版本变更记录 |
| [TODO.md](TODO.md) | 里程碑与待办清单 |

## 贡献

欢迎提交 issue 与 PR。开始之前请先读 [CONTRIBUTING.md](CONTRIBUTING.md)；安全问题请走 [SECURITY.md](SECURITY.md) 的私密渠道，不要开公开 issue。

## 开源许可

本项目以 [MIT License](LICENSE) 发布。

使用的第三方组件及其许可：

| 组件 | 许可 |
| --- | --- |
| Jetpack Compose（UI / Material3 / Material Icons） | Apache License 2.0 |
| AndroidX（Activity / Lifecycle / Navigation / DataStore / Window / Adaptive） | Apache License 2.0 |
| Room 持久化库 | Apache License 2.0 |
| kotlinx-coroutines | Apache License 2.0 |
| ONNX Runtime（onnxruntime-mobile） | MIT License |
| OCR / 气泡检测模型（PaddleOCR PP-OCRv4、RT-DETR-v2） | Apache License 2.0 |
| [OpenCC](https://github.com/BYVoid/OpenCC) 繁简转换字表（`assets/ts_map.txt`） | Apache License 2.0 |
| Apache Commons Compress | Apache License 2.0 |
| XZ for Java | Public Domain |
| [junrar](https://github.com/junrar/junrar) | UnRAR License |
| PDFBox-Android | Apache License 2.0 |
| androidx.pdf | Apache License 2.0 |

同一份清单也在应用内「设置 → 开源许可」中展示。
