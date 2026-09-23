# FoldReader

[![CI](https://github.com/llzx373/FoldReader/actions/workflows/ci.yml/badge.svg)](https://github.com/llzx373/FoldReader/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/llzx373/FoldReader?sort=semver)](https://github.com/llzx373/FoldReader/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-13%2B%20(API%2033)-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
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

- **单页 → 双页书模式 → 桌面模式**：折起时单页阅读，展开时自动进入双页书模式，半开时进入桌面模式（上半正文、下半控制面板）。
- **铰链即书脊**：按铰链位置切分布局，避开物理折痕区域，并在中缝渲染书脊阴影；靠书脊一侧的页边距自动加宽。
- **姿态连续性**：折叠、展开、旋转、进入桌面模式，阅读位置与分页状态连续保持，不会跳回章节开头。
- **双页策略可覆盖**：自动判断 / 强制双页 / 强制单页（`DualPageMode`）。

### 排版引擎

- 基于 `StaticLayout` 的分页引擎，按实际字宽逐行排布后切页，而非按字符数估算。
- **中文避头尾（kinsoku）**：标点不出现在行首/行尾的违规位置。
- 两端对齐、首行缩进、段间距、字距、行距、每行字数（18–40 字）、页边距档位均可调。
- **分页边界缓存**：内存 LRU + 磁盘缓存双层，二次打开与来回翻页不再重排。

### 大文件与编码

- **100MB+ TXT 窗口化读取**：按字节偏移窗口读取，绝不把整本文件读进内存；章节索引与预热在后台队列完成。
- **编码自动识别**：UTF-8（含/不含 BOM）、UTF-16 LE/BE、GBK、GB18030、Big5，并支持手动指定。
- **章节规则库**：正则规则识别章节标题，可自定义增删；识别不出时退化为按进度跳转。

### 阅读辅助

- 多级目录、书签、多色高亮与批注。
- 全文搜索，结果按章节分组。
- 自动翻页（定时 / 滚动两种模式）、阅读计时与阅读统计。
- **仿真翻页**（可选，默认仍是覆盖）：2.5D 折痕反射，翻起一张不变形的平纸；电子书与漫画 / 页式 PDF 都支持，展开双页只卷一叶并绕书脊盖住对页。日漫方向与覆盖动画同一口径。
- 5 套内置主题（绿、羊皮纸、灰白、夜间、AMOLED 纯黑）+ 自定义配色。
- 应用内亮度调节、屏幕常亮。
- 页内锚点：书签与批注可精确到页内位置（点或矩形），漫画 / PDF 同理。
- 中间点击区可配置：单击与双击各自绑定动作（切换菜单 / 上一页 / 下一页 / 书签 / 缩放 / 无）。

### 导入与书架

- 书架支持网格 / 列表视图、分组、自动生成的书籍封面与漫画封面。
- 支持 **TXT / EPUB / FB2 / PDF** 与漫画容器（**CBZ / CBR / CBT / CB7**，以及直接当作容器的 zip / rar / tar / 7z，还有图片目录）；批量导入、内置文件浏览器；也支持从其他 App 或文件管理器用"打开方式"送入，以及从系统分享面板**分享**给本 App（`VIEW` + `SEND` / `SEND_MULTIPLE`）。
- **同系列上下卷切换**：自动在当前目录/分组中匹配同名（同系列）书籍，一键切上一卷 / 下一卷。
- 导入时可选**智能清理**（离线规则引擎）：三档预设（保守/标准/激进）+ 14 项细粒度开关，覆盖空白混掺、行尾空格、段中空行、硬换行拆段、章节名不顶格、章节命名混乱、引号内被拆开、星号遮蔽、网站宣传语、论坛残留等；可先看**清洗预览报告**再决定，已导入的书也能「智能整理」；另可自定义广告行正则与繁简转换。档位**三处入口同一份规则**：书架导入对话框按当次选择，「浏览」打开与「导入目录为分组」跟随设置页的档位与繁简偏好。
- 导入时选了清理，**原版与清洗版会各占一个书架条目**（两本共用同一份源副本，不额外占空间），书架上用「已清洗」角标区分，详情页写明读的是原文件还是清洗副本——想对照或回读原文，不必重新导入一次。
- 导入时把**原文原样复制一份**进应用私有目录，正文从此不依赖外部授权——外部「打开方式」给的是临时授权，任务结束或重启后就会失效，直接引用它等于那本书早晚打不开。

### 数据与隐私

- **本地优先**：无账号、无官方云服务、无统计上报。书架、进度、书签与标注全部存储在本机。
- **网络仅用于用户显式配置的功能**：`INTERNET` 权限已随 AI 功能引入（设置页「AI 服务」，用户自带 API key、自配服务商端点，应用直连其配置的服务商）；未配置 AI 服务时，应用不产生任何网络请求。WebDAV 备份/恢复（用户自托管服务器）仍属规划。
- 备份与恢复：本地 JSON 导出/导入（WebDAV 远程备份规划中）。
- 诊断日志：崩溃与返回栈追踪可导出，便于报 issue 时附上。

## 支持的格式

| 类别 | 扩展名 / 容器 | 说明 |
| --- | --- | --- |
| 纯文本 | `.txt` | 主力格式，支持超大文件与多种编码 |
| EPUB | `.epub` | 目录支持 EPUB3 NAV 与 EPUB2 NCX |
| FictionBook | `.fb2`、`.fb2.zip` | 支持裸 XML 与 zip 打包两种形态 |
| PDF | `.pdf` | 文本型可按普通电子书阅读（含排版与搜索），扫描件走页式渲染 |
| 漫画 | `.cbz` `.zip`、`.cbr` `.rar`、`.cbt` `.tar`、`.cb7` `.7z` | 按真实魔数判容器，扩展名错标也能正确解开 |
| 漫画 | 图片文件夹 | 通过系统文件选择器（SAF）直接选择一个图片目录 |

格式识别采用 **magic bytes 优先、扩展名/MIME 兜底** 的策略：`.cbr` 实际是 zip 这类错标在漫画资源里相当常见，按真实字节判定才解得开。

漫画阅读额外支持：从右往左（日漫）方向、适应整页/宽度/高度/原始尺寸四种缩放、缩略图快速跳页、页内锚点书签。漫画不依赖目录结构，而是通过文件名规则自动排序并识别同系列。

## 安装

1. 到 [Releases](https://github.com/llzx373/FoldReader/releases) 下载最新的 `FoldReader-<版本>.apk`。
2. 系统要求 **Android 13（API 33）及以上**。
3. APK 未上架任何应用商店，安装时需允许"安装未知来源的应用"。

**设备说明**：大折叠屏（华为 Mate X 系列、三星 Z Fold 系列、荣耀/OPPO/vivo/小米折叠屏）与阔折叠（16:10 内屏，如华为 Pura X）是主要目标形态。直板机与竖折机可正常使用，但会退化为单页阅读，体验不到双页书模式。

## 从源码构建

### 前置要求

| 依赖 | 版本 |
| --- | --- |
| JDK | **25**（`gradle/gradle-daemon-jvm.properties` 固定了 daemon toolchain 为 25） |
| Android SDK Platform | `android-37.0` |
| Android SDK Build-Tools | `36.0.0` |
| Gradle | 9.6.0（wrapper 已内置，无需单独安装） |

在仓库根目录创建 `local.properties` 指向本地 SDK：

```properties
sdk.dir=/path/to/Android/Sdk
```

### 常用命令

```bash
./gradlew :app:assembleDebug        # 构建 debug APK
./gradlew :app:testDebugUnitTest    # 运行全部单元测试（600+ 用例）
./gradlew :app:lintDebug            # Lint
./gradlew :app:assembleRelease      # 构建 release APK（R8 + 资源压缩）
./gradlew :app:bundleRelease        # 构建 AAB
```

### 发布签名

release 构建的签名密钥按以下优先级读取：

1. 环境变量：`FOLDREADER_KEYSTORE_FILE`、`FOLDREADER_KEYSTORE_PASSWORD`、`FOLDREADER_KEY_ALIAS`、`FOLDREADER_KEY_PASSWORD`
2. 仓库根目录的 `key.properties`：

   ```properties
   storeFile=/absolute/path/to/release.jks
   storePassword=****
   keyAlias=****
   keyPassword=****
   ```

两者都不完整时会**回退到 debug 签名**，因此贡献者无需任何密钥即可构建 release 包做性能验证。正式发布流水线则会强制要求正式密钥（见 [docs/发布流程.md](docs/发布流程.md)）。

> `key.properties`、`*.jks`、`*.keystore` 已在 `.gitignore` 中，请勿提交密钥。

### 版本号

`gradle.properties` 中的 `foldReader.versionCode` / `foldReader.versionName` 是本地默认值，发布流水线会用 `-P` 覆盖：

```bash
./gradlew :app:assembleRelease -PfoldReader.versionCode=10001 -PfoldReader.versionName=1.0.1
```

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
│   ├── backup/     # 备份导出 / 导入编解码
│   └── debug/      # 诊断日志、返回栈追踪
└── feature/        # 界面层：bookshelf / reader / comic / importer / filebrowser / settings
```

三条贯穿全局的设计主线：

1. **手写依赖注入**：`AppContainer` 在 `FoldReaderApplication` 中集中装配数据库、仓库、解析器、用例与缓存，不引入 Hilt/Koin。依赖关系一眼可见，构建期成本为零。
2. **格式引擎与阅读内核分离**：非 TXT 格式（EPUB / FB2 / PDF 文本模式）在导入时被**统一拍平成 TXT + `.toc` 目录旁文件**，之后走同一套排版与分页内核。阅读核心因此没有任何格式分支。
3. **页式阅读接缝**：漫画与 PDF 统一实现 `PagedImageSource`，`ReaderHost` 只按格式在"文本阅读"与"页式阅读"之间分派一次，两种页式格式共享锚点、书签、缩放与翻页手势。

## 技术栈

| 层 | 选型 |
| --- | --- |
| 语言 | Kotlin 2.2.10（AGP 9 内置 Kotlin 编译器 + Compose 编译器插件） |
| UI | Jetpack Compose（BOM 2026.02.01）、Material 3 Expressive（1.5.0-alpha28）、Material3 Adaptive |
| 折叠屏 | `androidx.window` 1.5.0 + `FoldingFeature` |
| 架构 | MVVM + 单向数据流，仓库暴露 `Flow` |
| 持久化 | Room 2.8.4（KSP）、DataStore Preferences 1.2.0 |
| 导航 | Navigation Compose 2.9.6 |
| 并发 | kotlinx-coroutines 1.10.2 |
| 漫画容器 | Apache Commons Compress 1.28.0、XZ for Java 1.10、junrar 7.6.0 |
| PDF | androidx.pdf 1.0.0-beta01（沙箱文档服务）、PDFBox-Android 2.0.27.0 |
| 构建 | Gradle 9.6.0、AGP 9.4.0、KSP 2.2.10-2.0.2、JDK toolchain 25 |
| 测试 | JUnit4、Robolectric 4.17、kotlinx-coroutines-test、Compose UI Test |

## 已知限制

- **数据库向前兼容**。自 `v1.0.0` 起的已发布版本都能原地升级：改 schema 必须同时升 `version` 并在 `core/data/db/DatabaseMigrations.kt` 补一条迁移，`app/schemas/` 下各版本的快照一律保留、不重置基线；也不启用破坏性降级（对不上宁可报错，不静默清库）。跨大版本升级前仍建议先导出一次应用内备份。
- **不做 OCR**。扫描版 PDF 只能按图片页式阅读，不能提取文字或对其做全文搜索。
- **不做在线书城、账号体系、社交**。书籍与数据以本地为准；唯一的联网例外是用户显式配置的 AI 服务与 WebDAV 备份（见「数据与隐私」）。
- **仿真翻页是可选的第四种方式**（覆盖 / 仿真 / 无动画 / 滚动），默认仍是覆盖。旧的圆柱/铰链 3D 模型已删除（提交 `9a0c0ad`），现用 2.5D 折痕反射，见设计说明书附录 v2.4。
- **未上架任何应用商店**，仅通过 GitHub Releases 分发。
- 目标 SDK 为滚动版本（API 37），需要较新的 Android SDK 才能从源码构建。

## 文档

| 文档 | 内容 |
| --- | --- |
| [docs/需求与设计说明书.md](docs/需求与设计说明书.md) | 完整的产品定位、折叠形态分析、交互与排版设计、技术架构 |
| [docs/AI功能需求与实施.md](docs/AI功能需求与实施.md) | AI 功能规划：网络策略、技术底座、功能优化 / 翻译 / OCR 三条功能线 |
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
| [OpenCC](https://github.com/BYVoid/OpenCC) 繁简转换字表（`assets/ts_map.txt`） | Apache License 2.0 |
| Apache Commons Compress | Apache License 2.0 |
| XZ for Java | Public Domain |
| [junrar](https://github.com/junrar/junrar) | UnRAR License |
| PDFBox-Android | Apache License 2.0 |
| androidx.pdf | Apache License 2.0 |

同一份清单也在应用内「设置 → 开源许可」中展示。
