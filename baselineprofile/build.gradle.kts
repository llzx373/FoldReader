plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.llzx373.foldreader.baselineprofile"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 33
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildTypes {
        // com.android.test 模块没有任何默认构建类型，两个都要显式建：
        // release 承载 Baseline Profile 生成任务，benchmark 与 app 的 benchmark 变体对齐
        create("release") {
            isMinifyEnabled = false
        }
        create("benchmark") {
            matchingFallbacks += listOf("release")
        }
    }

    flavorDimensions += "models"
    productFlavors {
        create("lite")
        create("full")
    }

    // 被测目标：本模块的测试直接驱动 :app
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

baselineProfile {
    // 本仓库不托管 Gradle Managed Device 镜像（生成必须真实跑设备）：
    // 连真机跑 generate<Variant>BaselineProfile 即可，见 docs/构建与打包.md。
    useConnectedDevices = true
}

dependencies {
    implementation(libs.junit)
    implementation(libs.androidx.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
