pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "FoldReader"
include(":app")
// 桌面清理工具：复用 app 里那套纯 JVM 的清理引擎源码，用于离线验证清洗规则。
// 不参与 APK 构建，CI 只跑 :app 的任务，所以它坏了也不影响发布。
include(":tools:cleaner")
 