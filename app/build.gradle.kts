plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "com.llzx373.foldreader"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.llzx373.foldreader"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
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
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    // JVM 单测用的 XmlPullParser 实现（生产用 android.util.Xml）
    testImplementation(libs.kxml2)
    // 需要真实 Android API 的单测（Paint 字宽 / BitmapFactory / Room 迁移 / Compose 重组）
    testImplementation(libs.robolectric)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}