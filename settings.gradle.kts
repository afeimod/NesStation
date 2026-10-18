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
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "NesStation"
include(":app")
include(":dexlib")
// ★★ Wii U（Cemu-AZ 源码核心）模块：默认启用，-PskipCemuCore=true 可跳过 ★★
//   该模块把 afeimod/Cemu-AZ 的完整源码树（core/cemu/）经 vcpkg + CMake 从
//   源码编译为 libCemuAndroid.so 并入主 APK。跳过时 app 自动解除依赖，
//   运行时 CemuEngine 探测到库缺失会报告"核心不可用"，其余平台不受影响。
if (!(providers.gradleProperty("skipCemuCore").getOrElse("false").toBoolean())) {
    include(":cemu-core")
}
// ★★ Switch（Eden 源码核心）模块：默认启用，-PskipEdenCore=true 可跳过 ★★
//   该模块把 eden-emulator/eden 的完整源码树（core/eden/）经 CPM + CMake
//   从源码编译为 libyuzu-android.so 并入主 APK（集成方式与 :cemu-core
//   完全一致）。跳过时 app 自动解除依赖，运行时 EdenEngine 探测到库缺失
//   会报告"核心不可用"，其余平台不受影响。
if (!(providers.gradleProperty("skipEdenCore").getOrElse("false").toBoolean())) {
    include(":eden-core")
}
