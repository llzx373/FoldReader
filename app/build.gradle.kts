import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// ---------------------------------------------------------------------------
// 发布签名
// 密钥来源优先级：环境变量 > 仓库根目录 key.properties。
// 两者都不完整时回退 debug 签名，保证本地没有正式密钥也能出 release 包；
// 发布流水线会强制要求正式密钥（见 .github/workflows/release.yml）。
// 这里只判断密钥四项是否齐全，不做文件存在性探测，避免与配置缓存产生文件系统耦合。
// ---------------------------------------------------------------------------
val signingProps = Properties().apply {
    rootProject.file("key.properties")
        .takeIf { it.isFile }
        ?.inputStream()?.use { load(it) }
}

fun signingSecret(env: String, key: String): String? =
    providers.environmentVariable(env).orNull?.takeIf { it.isNotBlank() }
        ?: signingProps.getProperty(key)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingSecret("FOLDREADER_KEYSTORE_FILE", "storeFile")
val releaseStorePassword = signingSecret("FOLDREADER_KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingSecret("FOLDREADER_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingSecret("FOLDREADER_KEY_PASSWORD", "keyPassword")
val hasReleaseSigning = releaseStoreFile != null && releaseStorePassword != null &&
    releaseKeyAlias != null && releaseKeyPassword != null

android {
    namespace = "com.llzx373.foldreader"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.llzx373.foldreader"
        minSdk = 33
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 版本号默认值来自 gradle.properties，发布流水线用 -PfoldReader.* 覆盖。
        versionCode = providers.gradleProperty("foldReader.versionCode").getOrElse("1").toInt()
        versionName = providers.gradleProperty("foldReader.versionName").getOrElse("1.0")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // 有正式密钥就用正式签名，否则回退 debug 签名（本地开发路径）。
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            // R8 全量优化：去虚拟化 / 内联 / 裁剪 / 资源压缩。
            // 关掉时 release 包的方法数、冷启动与运行期性能都明显劣于 debug 之外的预期。
            optimization {
                enable = true
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    // APK 输出文件名与项目目录名一致：FoldReader-debug.apk / FoldReader-release.apk
    base {
        archivesName = "FoldReader"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            // Robolectric 需要合并后的资源与 manifest 才能跑 Android API 用例
            isIncludeAndroidResources = true
        }
    }
}

// 注意：本项目走 AGP 9 的内置 Kotlin 编译（build/intermediates/built_in_kotlinc），
// 实测 `composeCompiler {}` 的 stabilityConfigurationFile / metricsDestination 都不会
// 接到编译任务上（改配置文件不会让 compileDebugKotlin 失效、指标目录也不生成）。
// 因此稳定性一律用代码里的 @Immutable / @Stable 注解声明，不要依赖这个块。

ksp {
    arg("room.schemaDirectory", "$projectDir/schemas")
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // 折叠屏感知与自适应布局
    implementation(libs.androidx.window)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.adaptive.layout)
    implementation(libs.androidx.compose.material3.adaptive.navigation)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.material.icons.core)
    // 架构组件
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    // 本地存储
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // 漫画容器读取：zip/tar/7z（commons-compress + xz 提供 LZMA）+ rar（junrar）
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.junrar)
    // PDF：渲染走 androidx.pdf 的文档服务（沙箱进程 + 平台 PdfRenderer），
    // 元数据/目录/文本走 PdfBox。
    //
    // 不要排掉 pdf-viewer：它不只是"成品 UI"，还是 pdf-document-service 的运行时依赖——
    // 服务端 PdfDocumentRemoteImpl.getPageDimensions 引用了 pdf-viewer 里的
    // androidx.pdf.models.Dimensions。一旦排出，沙箱进程会在该调用上
    // NoClassDefFoundError 直接 FATAL（binder 死掉、整个文档作废）。
    // 我们不实例化 PdfViewer 的界面，但必须让它留在依赖图里。
    implementation(libs.androidx.pdf.core)
    implementation(libs.androidx.pdf.document.service)
    implementation(libs.pdfbox.android)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    // JVM 单测用的 XmlPullParser 实现（生产用 android.util.Xml）
    testImplementation(libs.kxml2)
    // 需要真实 Android API 的单测（Paint 字宽 / BitmapFactory / Room 迁移 / Compose 重组）
    testImplementation(libs.robolectric)
    // 确定性驱动协程（后台队列/节流这类异步行为的单测）
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}