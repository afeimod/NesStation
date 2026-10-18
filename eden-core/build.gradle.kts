plugins {
    alias(libs.plugins.android.library)
}

// ============================================================================
// :eden-core —— Eden（Nintendo Switch）核心的原生构建模块（NesStation 集成）
//
// 上游：eden-emulator/eden（mirror 快照固定在 core/eden/，2026-10 基线；
//   mirror 仓库带 855 处 [[maybe_unused]] 反爬损坏（形如 [aybe_unused]]），
//   已在集成包内机械修复，构建前无需再处理）
//   https://github.com/eden-emulator/mirror.git
//
// 集成方式完全模仿 :cemu-core（Wii U / Cemu-AZ）：
//   · 完整源码树随包分发（core/eden/），externalNativeBuild 直接指向上游
//     根 CMake（core/eden/CMakeLists.txt）—— 纯源码集成，不用任何预编译 so；
//   · 构建参数与上游 src/android/app/build.gradle.kts 的默认 flavor
//     （main，arm64-v8a）逐项一致：ENABLE_QT=0 / ANDROID_ARM_NEON /
//     CPM + 全量捆绑依赖（FFmpeg/OpenSSL 等首次从源码编译，之后有缓存）；
//   · ANDROID_STL=c++_shared（与 cemu-core/主工程一致；libc++_shared.so 的
//     同名冲突由 app 的 pickFirsts 裁决，任一份均可）；
//   · 额外关闭两处上游为【独立 App 形态】设计的步骤：
//       -DYUZU_DOWNLOAD_ANDROID_VVL=OFF  不在构建期下载 Vulkan 校验层
//       -DENABLE_UPDATE_CHECKER=OFF     不编译更新检查器（前端负责升级）
//   · RelWithDebInfo 强制覆盖（同 cemu-core 的 Debug 变体提速方案）；
//   · NDK/CMake 取上游固定版本（28.2.13676358 / 3.31.6），可用
//     -PedenNdkVersion / -PedenCmakeVersion 覆盖；
//   · 仅 arm64-v8a（Switch 模拟对性能要求高，与上游一致）。
//
// 本模块是"纯原生库"模块：没有任何 Kotlin/Java 源码，只产出
// libyuzu-android.so（上游 JNI 库名，符号 Java_org_yuzu_yuzu_1emu_*），
// 经 app 的 implementation(project(":eden-core")) 打进主 APK。
// JNI 契约类 vendored 于 app 源码 org.yuzu.yuzu_emu.*（与上游库名绑定的
// Java_org_yuzu_yuzu_1emu_* 符号一一对应），引擎封装为
// com.nesstation.app.core.engine.EdenEngine（推模型，与 CemuEngine 同构）。
//
// 首次构建须知：
//   1. CPM 首次会从源码编译 FFmpeg/OpenSSL/cpp-jwt 等依赖（联网下载），
//      耗时较长（CI 缓存后约 25~45 分钟），之后有二进制缓存；
//   2. SDK 需安装 NDK 28.2.13676358 与 CMake 3.31.6
//      （sdkmanager "ndk;28.2.13676358" "cmake;3.31.6"），
//      或用 -PedenNdkVersion / -PedenCmakeVersion 指向已装版本；
//   3. tz 数据（nx_tzdb）与 SDL 等其余依赖均已随源码树附带，无需联网。
//
// 快速跳过（临时不需要 Switch 核心时）：
//   ./gradlew assembleDebug -PskipEdenCore=true
//   （settings.gradle.kts 会跳过本模块，app 同步去掉依赖，运行时
//    EdenEngine.probeAvailability() 报告"核心不可用"，其余平台不受影响）
// ============================================================================

val edenNdkVersion = (project.findProperty("edenNdkVersion") as String?)
    ?: "28.2.13676358" // 上游 eden 固定版本

val edenCmakeVersion = (project.findProperty("edenCmakeVersion") as String?)
    ?: "3.31.6"        // 上游 gradle 固定版本（根 CMake 要求 >= 3.31）

val edenAbiFilters = (project.findProperty("edenAbiFilters") as String?)
    ?: "arm64-v8a"     // 上游默认仅 arm64

// 源码树存在性预检（给出可操作的提示而不是 CMake 的晦涩报错）
val edenRootCmake = rootProject.file("core/eden/CMakeLists.txt")
if (!edenRootCmake.isFile) {
    throw GradleException(
        "eden-core: 找不到 core/eden/CMakeLists.txt —— Eden 源码树未就位。\n" +
        "请确认 core/eden/ 目录完整（zip 解压或手动复制 eden-emulator/mirror 源码，" +
        "注意 mirror 仓库的 [aybe_unused]] 反爬损坏需先修复为 [[maybe_unused]]）。"
    )
}

android {
    namespace = "com.nesstation.eden"
    compileSdk = 34 // 与主工程 app 一致（本模块无 Java/Kotlin 源码，仅原生构建）

    defaultConfig {
        minSdk = 28 // 上游 main flavor 为 24；NCE/动态链接等在 <28 上问题
                    // 频发，取 yuzu 系长期支持下限 28。更低版本设备上
                    // System.loadLibrary 抛 UnsatisfiedLinkError →
                    // EdenEngine.probeAvailability() 报"核心不可用"，不崩溃。
                    // 主工程 minSdk 仍为 24，清单合并冲突由 app 的
                    // AndroidManifest.xml 以 tools:overrideLibrary 豁免。
        consumerProguardFiles("consumer-rules.pro")

        externalNativeBuild {
            cmake {
                // 与上游 src/android/app/build.gradle.kts（main flavor）逐项一致
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DENABLE_QT=0",                    // 不编 Qt（上游 Android 同款）
                    "-DENABLE_WEB_SERVICE=1",           // 对联网服务保持上游默认
                    "-DANDROID_ARM_NEON=true",          // cryptopp 需要 Neon
                    "-DYUZU_USE_CPM=ON",
                    "-DCPMUTIL_FORCE_BUNDLED=ON",
                    "-DYUZU_USE_BUNDLED_FFMPEG=ON",
                    "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON",
                    "-DBUILD_TESTING=OFF",
                    "-DYUZU_TESTS=OFF",
                    "-DDYNARMIC_TESTS=OFF",
                    // NesStation 集成差异：关闭两个"独立 App 形态"专属步骤
                    "-DYUZU_DOWNLOAD_ANDROID_VVL=OFF",  // 构建期不下载校验层
                    "-DENABLE_UPDATE_CHECKER=OFF",      // 无更新检查器（前端负责）
                    // ★ 同 cemu-core：所有 APK 变体（含 Debug）原生核心都拿到
                    //   -O2 + LTO（AGP 追加在自身 -DCMAKE_BUILD_TYPE=Debug 之后，
                    //   CMake 取最后一次定义）
                    "-DCMAKE_BUILD_TYPE=RelWithDebInfo"
                )
                abiFilters += edenAbiFilters.split(",").map { it.trim() }
            }
        }
        ndk {
            abiFilters += edenAbiFilters.split(",").map { it.trim() }
        }
    }

    ndkVersion = edenNdkVersion

    externalNativeBuild {
        cmake {
            path = file("../core/eden/CMakeLists.txt")
            version = edenCmakeVersion
        }
    }

    buildFeatures {
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 注：不在此设置 packaging —— useLegacyPackaging/pickFirsts 是 APK 打包
    // 期属性，库模块不适用；libc++_shared.so 与 jniLibs 预装副本的同名冲突
    // 由 app/build.gradle.kts 的 pickFirsts 统一裁决（任一份均可）。

    lint {
        abortOnError = false
    }
}
