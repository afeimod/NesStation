plugins {
    alias(libs.plugins.android.library)
}

// ============================================================================
// :cemu-core —— Cemu（Wii U）核心的原生构建模块（NesStation 集成）
//
// 上游：afeimod/Cemu-AZ（SSimco Android 移植 + afeimod 修改版，2026-10 基线）
//   https://github.com/afeimod/Cemu-AZ
//
// 构建方式与上游 src/android/app/build.gradle.kts 完全一致（源码级移植，
// 不使用任何预编译 .so）：
//   · externalNativeBuild 直接指向上游根 CMakeLists（core/cemu/CMakeLists.txt）
//   · vcpkg 包管理（ENABLE_VCPKG=ON + VCPKG_TARGET_ANDROID=ON）
//   · ANDROID_STL=c++_shared（与上游一致；so 动态依赖 libc++_shared.so，
//     与 jniLibs 预装副本经 app 的 pickFirsts 去重）
//   · 仅 Vulkan 渲染后端（ENABLE_OPENGL=OFF，Android 不编 GL 后端）
//   · NDK 默认取上游固定的 29.0.14206865，可用 -PcemuNdkVersion=xxx 覆盖
//     （例：复用主工程已装的 28.2.13676358 —— 两者均为 C++20 完整工具链）
//   · ABI 仅 arm64-v8a（上游默认；Wii U 模拟对性能要求高，x86_64 设备
//     罕见，如需可 -PcemuAbiFilters=arm64-v8a,x86_64 打开）
//
// 本模块是"纯原生库"模块：没有任何 Kotlin/Java 源码，只产出
// libCemuAndroid.so，经 app 的 implementation(project(":cemu-core")) 打进
// 主 APK。JNI 契约类在 app 源码内（info.cemu.cemu.nativeinterface.*，
// 与上游库名绑定的 Java_info_cemu_cemu_nativeinterface_* 符号一一对应）。
//
// 首次构建须知：
//   1. 需先执行 ./scripts/fetch_cemu_submodules.sh 拉取 vcpkg 等子模块
//      （zip 包已随附其余子模块源码，脚本只补 vcpkg）；
//   2. vcpkg 首次会从源码编译 Boost/OpenSSL/curl 等依赖，耗时较长
//     （CI 缓存后约 20~40 分钟，本地首包更久），之后有二进制缓存；
//   3. SDK 需安装 NDK 29.0.14206865（sdkmanager "ndk;29.0.14206865"）
//      或用 -PcemuNdkVersion 指向已装版本。
//
// 快速跳过（临时不需要 Wii U 核心时）：
//   ./gradlew assembleDebug -PskipCemuCore=true
//   （settings.gradle.kts 会跳过本模块，app 同步去掉依赖，运行时
//    CemuEngine.probeAvailability() 报告"核心不可用"，其余平台不受影响）
// ============================================================================

val cemuNdkVersion = (project.findProperty("cemuNdkVersion") as String?)
    ?: "29.0.14206865" // 上游 Cemu-AZ 固定版本

val cemuAbiFilters = (project.findProperty("cemuAbiFilters") as String?)
    ?: "arm64-v8a"     // 上游默认仅 arm64

// vcpkg 等子模块存在性预检（给出可操作的提示而不是 CMake 的晦涩报错）
val cemuVcpkgDir = rootProject.file("core/cemu/dependencies/vcpkg")
val cemuRootCmake = rootProject.file("core/cemu/CMakeLists.txt")
if (!cemuRootCmake.isFile) {
    throw GradleException(
        "cemu-core: 找不到 core/cemu/CMakeLists.txt —— Cemu-AZ 源码树未就位。\n" +
        "请确认 core/cemu/ 目录完整（zip 解压或手动复制 afeimod/Cemu-AZ 源码）。"
    )
}
if (!cemuVcpkgDir.isDirectory) {
    throw GradleException(
        "cemu-core: 缺少 core/cemu/dependencies/vcpkg（vcpkg 子模块）。\n" +
        "请先在 NesStation 工程根目录执行：\n" +
        "    ./scripts/fetch_cemu_submodules.sh\n" +
        "或临时跳过 Wii U 核心构建：./gradlew assembleDebug -PskipCemuCore=true"
    )
}

android {
    namespace = "com.nesstation.cemu"
    compileSdk = 34 // 与主工程 app 一致（本模块无 Java/Kotlin 源码，仅原生构建）

    defaultConfig {
        minSdk = 24 // 与主工程一致；Cemu 运行时最低要求 API 30（arm64+Vulkan1.1），
                    // 由 CemuEngine.probeAvailability() 在运行时守护，低版本设备
                    // 报告"核心不可用"而非崩溃。
        consumerProguardFiles("consumer-rules.pro")

        externalNativeBuild {
            cmake {
                // 与上游 Cemu-AZ src/android/app/build.gradle.kts 的参数逐项一致
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DENABLE_VCPKG=ON",
                    "-DVCPKG_TARGET_ANDROID=ON",
                    "-DENABLE_SDL=OFF",
                    "-DENABLE_HIDAPI=OFF",
                    "-DENABLE_WXWIDGETS=OFF",
                    "-DENABLE_OPENGL=OFF",
                    "-DENABLE_BLUEZ=OFF",
                    "-DBUNDLE_SPEEX=ON",
                    "-DENABLE_DISCORD_RPC=OFF",
                    "-DENABLE_NSYSHID_LIBUSB=OFF",
                    "-DENABLE_WAYLAND=OFF",
                )
                // 上游构建从 git 取版本号；源码移植场景下用固定基线号（afeimod
                // 修改版 update.txt 标注的 2026-05 官方基线 + 33 提交）。
                arguments += listOf(
                    "-DEMULATOR_VERSION_MAJOR=2",
                    "-DEMULATOR_VERSION_MINOR=0"
                )
                abiFilters += cemuAbiFilters.split(",").map { it.trim() }
            }
        }
        ndk {
            abiFilters += cemuAbiFilters.split(",").map { it.trim() }
        }
    }

    ndkVersion = cemuNdkVersion

    externalNativeBuild {
        cmake {
            path = file("../core/cemu/CMakeLists.txt")
            // 上游要求 CMake >= 3.25（version = "3.25.0+"），此处与上游完全一致：
            //   Cemu 源码全局开启 LTO/IPO（CMAKE_INTERPROCEDURAL_OPTIMIZATION_*
            //   对 Release/RelWithDebInfo 均 ON），AGP 的 release 构建走
            //   RelWithDebInfo 配置。CMake 3.22 无法在 NDK 工具链下正确处理
            //   LTO 链接：回退到宿主机 /usr/bin/ld.gold（NDK 无 LLVMgold.so
            //   插件，gold 亦不识别 aarch64 的 crt 文件），报
            //   "unsupported ELF machine number 183"。
            // 解决：CMake 升至 >= 3.25。SDK 仓库中满足要求的版本为
            //   cmake;3.30.4，CI（android-build.yml）同步安装。
            version = "3.25.0+"
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
    // 由 app/build.gradle.kts 的 pickFirsts 统一裁决（任一份均可，同一上游 ABI）。

    lint {
        abortOnError = false
    }
}
