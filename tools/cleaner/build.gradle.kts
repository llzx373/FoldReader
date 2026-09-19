import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    // 不带版本：Kotlin 插件已由根项目的 classpath 提供（`kotlin.compose` 传递带入），
    // 这里再写版本号会与已在 classpath 上的那份冲突。
    kotlin("jvm")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

/**
 * 桌面清理工具。
 *
 * **直接复用 `app` 里的清理引擎源码，不复制一份**：规则只有一处真相，
 * 这里跑出来的结果和 App 里导入时的清洗结果必然一致。
 *
 * 代价是这批源码必须保持纯 JVM（不引用任何 Android API）——所以
 * `clean/` 本身是纯的，两个被它依赖的文件也就地拆开了：
 * `ChapterRules.kt`（纯判据）与 `TsCharMapAndroid.kt`（Context 加载器，只属于 app）。
 * 谁要是往共享文件里加一个 `android.*` 的 import，这个模块会立刻编译失败。
 */
kotlin {
    jvmToolchain(17)
    sourceSets.main {
        kotlin.srcDir(rootProject.file("app/src/main/java/com/llzx373/foldreader/core/format"))
        // include 作用于所有 srcDir，所以本模块自己的源码也要列进来。
        kotlin.include(
            "clean/**",
            "ChapterRules.kt",
            "EncodingDetector.kt",
            "com/llzx373/foldreader/tools/cleaner/**",
        )
    }
}

/**
 * 测试直接复用 app 的 clean 测试与语料——**同一套规格跑两遍**。
 * 以后谁改坏了共享源码，两个模块会一起红；同时这也是「桌面工具与 App 行为一致」的证据。
 */
sourceSets.test {
    kotlin.srcDir(rootProject.file("app/src/test/java/com/llzx373/foldreader/core/format"))
    kotlin.include("clean/**")
    resources.srcDir(rootProject.file("app/src/test/resources"))
}

// 繁简字表随包内置：分发包（createDistributable）没有仓库目录结构，
// 只靠 "app/src/main/assets/ts_map.txt" 相对路径会找不到。
sourceSets.main {
    resources.srcDir(rootProject.file("app/src/main/assets"))
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    testImplementation(libs.junit)
}

compose.desktop {
    application {
        mainClass = "com.llzx373.foldreader.tools.cleaner.MainKt"

        nativeDistributions {
            // msi 需要 WiX；createDistributable（app-image）不需要，优先用那个。
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "FoldReaderCleaner"
            packageVersion = "1.0.0"
            description = "FoldReader 智能清理·桌面验证工具"

            // **必须显式声明**：GBK / GB18030 / Big5 都在 jdk.charsets 里，而
            // `Charset.forName("GBK")` 是运行期查找，jpackage 的 jdeps 静态分析看不到它，
            // 不会自动把这个模块打进来。缺了它，EncodingDetector 的静态初始化就会
            // UnsupportedCharsetException → 连 UTF-8 文件都打不开。
            modules("jdk.charsets")
        }
    }
}
