# 贡献指南

感谢愿意为 FoldReader 出力。本文说明本项目的环境要求、流程约定与验收标准。

## 环境准备

| 依赖 | 版本 |
| --- | --- |
| JDK | 25 |
| Android SDK Platform | `android-37.0` |
| Android SDK Build-Tools | `36.0.0` |
| Gradle | 9.6.0（使用仓库内置 wrapper，勿单独安装） |

1. 用 Android Studio 或命令行打开仓库。
2. 在仓库根目录创建 `local.properties`，写明 `sdk.dir=/path/to/Android/Sdk`。
3. `./gradlew :app:assembleDebug` 能出包即为环境就绪。

构建 release 包不需要任何签名密钥——`app/build.gradle.kts` 会在缺少正式密钥时回退 debug 签名。

## 提交与 PR 流程

1. 从 `main` 拉出功能分支，分支名用 `feat/xxx`、`fix/xxx` 这类前缀。
2. 完成改动，**确保单元测试通过**（见下）。
3. 提交，提交信息遵循下节规范。
4. 推分支、开 PR，等 CI 通过。

`main` 是唯一的长期分支。正式版本通过打 tag 发布，见 [docs/发布流程.md](docs/发布流程.md)。

## 提交信息规范

沿用 Conventional Commits，与仓库既有历史保持一致：**类型与正文用中文**，类型/范围标识符用英文。

```
<type>(<scope>): <中文描述>
```

- `type`：`feat` / `fix` / `perf` / `refactor` / `docs` / `test` / `chore` / `build`
- `scope`：模块名，如 `reader`、`comic`、`paged`、`shelf`、`format`、`index`、`epub`
- 破坏性变更加 `!`，例如 `refactor(index)!: offset_index 改双列模型`

示例：

```
fix(paged): 统一漫画 RTL 方向翻译 + 中间区几何按是否有底边条区分
feat(format): FB2 支持、目录成组导入与 EPUB 深度支持
```

正文里说明**为什么**这么改，而不是复述改了什么——尤其是那些看起来"多余"的边界处理，它们通常对应真机上踩过的坑。

## 版本号

**开发期间不要主动递增版本号。** 目标版本固定在 `gradle.properties` 的默认值，本地与测试包一律用
`<目标版本>-<开发序号>`（如 `1.1.0-1`、`1.1.0-2`），只有确定发布的那一个提交才用不带后缀的版本号、
由发布流水线用 tag 注入。完整规则与命令见 [docs/发布流程.md](docs/发布流程.md) 的「开发期版本号（铁则）」。

## 测试要求

**每一个改动都要带上测试。** 本项目不把测试当作后续补充项。

```bash
./gradlew :app:testDebugUnitTest            # 全部单元测试
./gradlew :app:testDebugUnitTest --tests "com.llzx373.foldreader.core.reader.PaginatorTest"
```

- 纯逻辑用 JVM 单测即可。
- 需要真实 Android API（`Paint` 字宽、`BitmapFactory`、Room、Compose 重组）的用例，用已在依赖里的 Robolectric（`unitTests.isIncludeAndroidResources = true` 已开启）。
- 异步行为（后台队列、节流）用 `kotlinx-coroutines-test` 确定性驱动，不要靠 `Thread.sleep`。
- 涉及 instrumentation 的改动（目前仅 PDF 相关）请说明如何在真机或模拟器上验证。

修 bug 时，请把根因写进测试：测试应该能在修复前失败、修复后通过。

## 代码风格

- Kotlin 官方风格（`kotlin.code.style=official`），4 空格缩进；格式化规则见 `.editorconfig`。
- 换行符统一 LF；`.gitattributes` 已强制，`gradlew` 尤其不能变成 CRLF。
- Compose 组件的稳定性通过 `@Immutable` / `@Stable` 注解声明——AGP 9 内置 Kotlin 编译路径下 `composeCompiler { stabilityConfigurationFile }` 不生效，不要依赖它。
- 注释与文档使用中文；标识符、字符串、API 名保持英文。

## 绝对不要提交

以下内容已在 `.gitignore` 中，若发现它们出现在 `git status` 里，说明忽略了：

- `local.properties`
- `key.properties`、`*.jks`、`*.keystore`（签名密钥，泄露即作废）
- `f30/`（本地测试截图）
- `.commandcode/`（本机工具配置）
- 构建产物 `*.apk`、`*.aab`

## 文档同步

改动若影响以下任一内容，请在同一个 PR 里同步更新：

- 功能、支持格式、系统要求、构建步骤 → `README.md` 与 `README_EN.md`
- 版本可见的变化 → `CHANGELOG.md`
- 第三方依赖增删 → 应用内「设置 → 开源许可」的 `OpenSourceLicenses.kt`（有单测兜底）与 README 的许可表
- 产品设计层面的变更 → `docs/需求与设计说明书.md`
- 里程碑完成情况 → `TODO.md`

## 报告问题

用仓库的 issue 模板提交，尽量附上「设置 → 诊断」导出的日志。折叠屏问题请写明**屏幕形态**（展开内屏 / 外屏 / 直板 / 平板）与设备型号——同一个 bug 在不同形态下的表现经常不同。

安全问题请勿开公开 issue，走 [SECURITY.md](SECURITY.md) 的渠道。
