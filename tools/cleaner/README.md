# 智能清理 · 桌面验证工具

把折叠屏 App 里的「智能清理」搬到桌面上跑，用来**离线验证清洗规则**：打开一本脏 TXT、
选档位、点清洗，左右两栏看原文与结果。改规则后不必再装 APK、导书、翻页找问题。

## 运行

```bash
# 直接起窗口
./gradlew :tools:cleaner:run

# 启动时顺带载入某个文件
./gradlew :tools:cleaner:run --args="F:/books/某本小说.txt"

# 打一个自带运行时的绿色包（不需要本机有 JRE）
./gradlew :tools:cleaner:createDistributable
# 产物：tools/cleaner/build/compose/binaries/main/app/FoldReaderCleaner/FoldReaderCleaner.exe
```

界面里能做的事：

- **打开 TXT**：自动检测编码（UTF-8 / UTF-16 / GBK / GB18030 / Big5），也可以手动覆盖后重新解码；
- **档位**：保守 / 标准 / 激进；展开「规则明细」可逐项勾选 15 项开关，勾完档位落到「自定义」；
- **过滤框**：只显示包含指定文本的行（例如输入 `【` 直接跳到所有分篇标题），两栏都会按它过滤，
  行号仍是原文行号；
- **结果另存为**：把清洗结果写成文件，方便和自己的编辑器 / diff 工具对照；
- 底部给出 `CleanReport` 摘要（删了多少行、合并了多少次、耗时）与改动样例（前 20 条）。

## 为什么结果一定和 App 里一致

这个模块**不复制清洗逻辑**，而是把 `app/src/main/java/com/llzx373/foldreader/core/format` 当作
源码目录直接编译进来（见 `build.gradle.kts` 里的 `kotlin.srcDir` + `include`）：

```
clean/**            ← 整个清理引擎，与 App 共用同一份文件
ChapterRules.kt     ← 章节标题判据（被 ChapterRepairRules 依赖）
EncodingDetector.kt ← 编码检测（工具里复用，App 里也是这个）
```

规则只有一处真相，所以这里跑出来的结果就是 App 导入 / 智能整理时的结果。

### 由此产生的一条硬约束

上面被共享的文件**不能引用任何 `android.*`**，否则这个模块编译不过（报错就会直接指向那一行）。
正是这条约束促成了两处拆分：

- `ChapterRules` 从 `ChapterScanner.kt` 拆到独立文件——后者所在文件依赖 `BookParser` 的
  `Chapter`，而那是带 `android.net.Uri` 的；
- `TsCharMap` 只留纯解析，Android 侧从 assets 取表的加载器挪到了
  `core/format/android/TsCharMapAndroid.kt`（`clean/` 之外，「clean 整包是纯的」这条不变量
  因此是绝对的，不靠排除清单维持）。

## 测试

`./gradlew :tools:cleaner:test` 跑的是 **app 的那批 clean 测试与语料**（`app/src/test/...` 与
`app/src/test/resources/novel-corpus` 通过 source set 复用，同样没有复制）。同一套规格跑两遍：
既防规则被改坏，也是「桌面工具与 App 行为一致」的证据。

`novel-corpus/22-anthology-flush-header-flush-body` 那类真实文件暴露过的形态都在这套语料里。

## 踩过的坑

**`jdk.charsets` 必须显式声明**（`build.gradle.kts` 里 `nativeDistributions { modules("jdk.charsets") }`）。

GBK / GB18030 / Big5 都在这个 JDK 模块里，而 `Charset.forName("GBK")` 是**运行期查找**，
jpackage 的 jdeps 静态分析看不到它，于是默认不会打进来。后果不只是乱码：

`EncodingDetector` 把三个遗留编码的 `Charset.forName` 放在**对象初始化**里，缺模块时静态初始化
直接抛 `UnsupportedCharsetException` → 之后任何一次 `EncodingDetector` 访问都变成
`Could not initialize class ...EncodingDetector`，**连 UTF-8 文件都打不开**。
而 `./gradlew :tools:cleaner:run` 用的是完整 JDK，所以只在绿色包上复现——
排查时先看 `runtime/release` 里的 `MODULES=` 一行最快。

## 说明

- 这是开发工具，**不参与 APK 构建**；CI 只跑 `:app` 的任务，所以它坏了不影响发布。
- 首次构建需要联网拉 Compose Multiplatform 与 skiko（本机 Gradle 缓存里可能已有）。
