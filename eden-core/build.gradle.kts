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
//     （mainline，arm64-v8a）逐项一致：ENABLE_QT=0 / ANDROID_ARM_NEON /
//     CPM + 全量捆绑依赖（FFmpeg/OpenSSL 等首次从源码编译，之后有缓存）；
//   · ANDROID_STL=c++_shared（与 cemu-core/主工程一致；libc++_shared.so 的
//     同名冲突由 app 的 pickFirsts 裁决，任一份均可）；
//   · 额外关闭两处上游为【独立 App 形态】设计的步骤：
//       -DYUZU_DOWNLOAD_ANDROID_VVL=OFF  不在构建期下载 Vulkan 校验层
//           （上游该步骤把 libVkLayer_khronos_validation.so 拷入其独立 App
//             的 src/android/app/src/main/jniLibs/ —— 该目录在 NesStation
//             集成里不属于任何 AGP 模块、不会进 APK，下载纯属浪费）
//       -DENABLE_UPDATE_CHECKER=OFF     不编译更新检查器（前端负责升级）
//   · RelWithDebInfo 强制覆盖（同 cemu-core 的 Debug 变体提速方案）；
//   · NDK/CMake 取上游固定版本（28.2.13676358 / 3.31.6），可用
//     -PedenNdkVersion / -PedenCmakeVersion 覆盖；
//   · 仅 arm64-v8a（Switch 模拟对性能要求高，与上游一致）。
//
// ★★ glslangValidator 宿主机工具供给链（v2 修复）★★
//   Eden 的 CMake（src/video_core/host_shaders/CMakeLists.txt:101）在
//   configure 阶段用 find_program(GLSLANGVALIDATOR glslangValidator) 查找
//   【宿主机】可执行文件，把 GLSL 着色器编译成 SPIR-V 头文件 —— 上游文档
//   （docs/build/Android.md）要求 Windows 装 Vulkan SDK、Linux 装
//   glslang-tools。未安装时 configure 直接 FATAL_ERROR（即 v1 构建失败
//   的根因）。本模块自给自足，无需用户预装任何东西：
//     1) 本模块携带三平台 glslangValidator（Khronos glslang 16.6.0）：
//          prebuilt/glslang/windows-x86_64/  （官方预编译，静态自包含）
//          prebuilt/glslang/linux-x86_64/    （本仓源码构建，静态链接
//                                            libstdc++/libgcc，已用 Eden
//                                            真实着色器逐类验证）
//          prebuilt/glslang/macos-universal/ （官方 universal 预编译）
//        buildGlslangValidator 任务校验可运行后复制到 build/glslang-host/；
//     2) 预编译不可用（exotic 架构 / glibc 过旧等）→ 用本模块随附的
//        glslang/ 完整源码 + SDK CMake 现场构建（Windows 需 VS2022/MinGW，
//        与上游构建要求一致）；
//     3) 仍失败 → 回退系统 PATH 上的 glslangValidator/glslang
//        （装有 Vulkan SDK / glslang-tools 的机器等同上游官方路径）；
//     4) 全部失败 → 抛出带操作指引的 GradleException。
//   解析结果经 -DGLSLANGVALIDATOR=<绝对路径> 注入 CMake（find_program 的
//   缓存变量可被命令行 -D 覆盖，上游 CMake 一行不改）。
//   手动指定：-PglslangValidator=/path/to/glslangValidator
//   强制模式：-PglslangBuild=prebuilt|source|auto（默认 auto）
//
// 本模块是"纯原生库"模块：没有任何 Kotlin/Java 源码，只产出
// libyuzu-android.so（上游 JNI 库名，符号 Java_org_yuzu_yuzu_1emu_*），
// 经 app 的 implementation(project(":eden-core")) 打进主 APK。
// JNI 契约类 vendored 于 app 源码 org.yuzu.yuzu_emu.*（与上游库名绑定的
// Java_org_yuzu_yuzu_1emu_* 符号一一对应），引擎封装为
// com.nesstation.app.core.engine.EdenEngine（推模型，与 CemuEngine 同构）。
//
// 首次构建须知：
//   1. CPM 首次会从源码编译 FFmpeg/OpenSSL/SDL3 等依赖（联网下载到
//      core/eden/.cache/cpm/，约数百 MB），耗时较长（约 25~45 分钟），
//      之后有二进制缓存；上游 Android 构建同样如此（CPM_SOURCE_CACHE）。
//   2. SDK 需安装 NDK 28.2.13676358 与 CMake 3.31.6
//      （sdkmanager "ndk;28.2.13676358" "cmake;3.31.6"），
//      或用 -PedenNdkVersion / -PedenCmakeVersion 指向已装版本；
//   3. 其余依赖均已随源码树附带，无需联网。
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

// ============================================================================
// glslangValidator 供给链（先于 android{} 块定义，供其参数注入使用）
// ============================================================================

val glslangOverride = (project.findProperty("glslangValidator") as String?)
    ?.takeIf { it.isNotBlank() }          // -PglslangValidator=/path/to/bin
val glslangBuildMode = (project.findProperty("glslangBuild") as String?)
    ?.takeIf { it in setOf("auto", "prebuilt", "source") } ?: "auto"

val osName = System.getProperty("os.name").lowercase()
val isWindowsHost = osName.contains("windows")
val isMacHost = osName.contains("mac") || osName.contains("darwin")
val hostArch = when (System.getProperty("os.arch")?.lowercase()) {
    "amd64", "x86_64" -> "x86_64"
    "aarch64", "arm64" -> "aarch64"
    else -> System.getProperty("os.arch")?.lowercase() ?: "unknown"
}
val validatorExeName = if (isWindowsHost) "glslangValidator.exe" else "glslangValidator"

/** 供给链产物（本模块 build 目录内的规范路径，任务负责把它造出来） */
val glslangHostDir = File(project.layout.buildDirectory.get().asFile, "glslang-host")
val glslangValidatorOut = File(glslangHostDir, validatorExeName)

/** 随包分发的预编译目录（按宿主平台选择） */
fun prebuiltCandidate(): File? {
    val dirName = when {
        isWindowsHost && hostArch == "x86_64" -> "windows-x86_64"
        !isWindowsHost && !isMacHost && hostArch == "x86_64" -> "linux-x86_64"
        isMacHost -> "macos-universal" // 官方 universal（x86_64 + arm64）
        else -> return null
    }
    val f = File(projectDir, "prebuilt/glslang/$dirName/$validatorExeName")
    return f.takeIf { it.isFile }
}

/** 随包分发的 glslang 源码目录（Khronos glslang 16.6.0，剔除测试/CI 文件） */
val glslangSourceDir = File(projectDir, "glslang")

/** 供 -D 注入 CMake 的路径（优先手动覆盖） */
fun glslangValidatorCMakeArg(): String {
    val path = glslangOverride?.let { File(it).absolutePath } ?: glslangValidatorOut.absolutePath
    return "-DGLSLANGVALIDATOR=$path"
}

/** 运行 --version 校验候选可执行文件可用（非零退出码 / 输出异常均不算） */
fun verifyRunnable(cand: File): Boolean {
    if (!cand.isFile) return false
    return try {
        val p = ProcessBuilder(cand.absolutePath, "--version")
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        val code = p.waitFor()
        code == 0 && out.contains("glslang", ignoreCase = true)
    } catch (e: Exception) {
        false
    }
}

/** macOS：浏览器下载的 zip 解压产物可能带 quarantine 属性，Gatekeeper 会拦
 *  CLI 执行 —— 先摘除（失败不影响后续流程，verifyRunnable 自会说话） */
fun stripQuarantineIfMac(cand: File) {
    if (isMacHost) {
        try {
            ProcessBuilder("xattr", "-r", "-d", "com.apple.quarantine", cand.absolutePath)
                .redirectErrorStream(true).start().waitFor()
        } catch (e: Exception) {
            // 无 xattr / 无 quarantine 属性 —— 忽略
        }
    }
}

/** 定位可用的宿主机 CMake：优先 SDK 内与本模块 externalNativeBuild 同源的
 *  CMake（$SDK/cmake/<版本>/bin/cmake(.exe)），其次 SDK 里任意已装版本，
 *  最后系统 PATH。 */
fun findHostCmake(): File? {
    val exe = if (isWindowsHost) "cmake.exe" else "cmake"
    val candidates = ArrayList<File>()
    runCatching { android.sdkDirectory }.getOrNull()?.let { sdk ->
        candidates += File(sdk, "cmake/$edenCmakeVersion/bin/$exe")
        File(sdk, "cmake").takeIf { it.isDirectory }?.listFiles()
            ?.sortedByDescending { it.name }?.forEach {
                candidates += File(it, "bin/$exe")
            }
    }
    for (c in candidates) if (c.isFile) return c
    return try {
        val probe = ProcessBuilder(exe, "--version").redirectErrorStream(true).start()
        val ok = probe.waitFor() == 0
        probe.inputStream.close()
        if (ok) File(exe) else null
    } catch (e: Exception) {
        null
    }
}

/** 在构建树里找 standalone 产物（glslang ≤11 叫 glslangValidator，
 *  ≥11.1 叫 glslang；VS 多配置生成器会把 exe 放进 Release/ 子目录） */
fun findBuiltValidator(buildDir: File): File? {
    val names = if (isWindowsHost) {
        listOf("glslangValidator.exe", "glslang.exe")
    } else {
        listOf("glslangValidator", "glslang")
    }
    return buildDir.walkTopDown()
        .filter { it.isFile && it.name in names && it.parentFile?.name != "CMakeFiles" }
        .maxByOrNull { it.length() } // 同名多份（安装副本等）取最大者
}

/** 从随附源码现场构建（ENABLE_OPT=OFF 免编 SPIRV-Tools，静态链接，只出
 *  standalone 验证器；Windows 走 VS/MinGW 默认生成器，Linux/macOS 走默认） */
fun buildValidatorFromSource(): File? {
    if (!glslangSourceDir.isDirectory) return null
    val cmake = findHostCmake() ?: run {
        logger.warn("eden-core: 未找到可用的宿主机 CMake，无法源码构建 glslangValidator")
        return null
    }
    val buildDir = File(glslangHostDir, "build")
    val cmakeBin = cmake.absolutePath

    fun run(vararg args: String): Boolean = try {
        val p = ProcessBuilder(*args).redirectErrorStream(true).start()
        val output = p.inputStream.bufferedReader().use { it.readText() }
        val code = p.waitFor()
        if (code != 0) {
            logger.lifecycle("eden-core: glslang 源码构建命令失败（exit=$code）：\n$output")
        }
        code == 0
    } catch (e: Exception) {
        logger.lifecycle("eden-core: glslang 源码构建命令异常：${e.message}")
        false
    }

    val ok = run(
        cmakeBin, "-S", glslangSourceDir.absolutePath, "-B", buildDir.absolutePath,
        "-DCMAKE_BUILD_TYPE=Release",
        "-DENABLE_GLSLANG_BINARIES=ON",
        "-DENABLE_OPT=OFF",           // 不需要 SPIRV-Tools 优化器
        "-DGLSLANG_TESTS=OFF",
        "-DBUILD_TESTING=OFF",
        "-DBUILD_EXTERNAL=OFF",
        "-DBUILD_SHARED_LIBS=OFF"
    ) && run(cmakeBin, "--build", buildDir.absolutePath, "--config", "Release")
    if (!ok) return null
    return findBuiltValidator(buildDir)?.also {
        logger.lifecycle("eden-core: 源码构建 glslangValidator 成功：$it")
    }
}

/** 系统 PATH 上的 glslangValidator / glslang（装有 Vulkan SDK 或
 *  glslang-tools 的机器 —— 即上游文档要求的官方环境） */
fun validatorFromPath(): File? {
    for (tool in listOf("glslangValidator", "glslang")) {
        try {
            val finder = if (isWindowsHost) "where" else "which"
            val p = ProcessBuilder(finder, tool).redirectErrorStream(true).start()
            val firstLine = p.inputStream.bufferedReader().use { it.readLine() }
            p.waitFor()
            if (firstLine.isNullOrBlank()) continue
            val cand = File(firstLine.trim())
            if (cand.isFile) return cand
        } catch (e: Exception) {
            // 继续下一个候选
        }
    }
    return null
}

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
        minSdk = 28 // 上游 mainline flavor 为 33；NCE/动态链接等在 <28 上问题
                    // 频发，取 yuzu 系长期支持下限 28。更低版本设备上
                    // System.loadLibrary 抛 UnsatisfiedLinkError →
                    // EdenEngine.probeAvailability() 报"核心不可用"，不崩溃。
                    // 主工程 minSdk 仍为 24，清单合并冲突由 app 的
                    // AndroidManifest.xml 以 tools:overrideLibrary 豁免。
        consumerProguardFiles("consumer-rules.pro")

        externalNativeBuild {
            cmake {
                // 与上游 src/android/app/build.gradle.kts（mainline flavor）逐项一致
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
                    "-DCMAKE_BUILD_TYPE=RelWithDebInfo",
                    // ★ v2：注入宿主机 glslangValidator（见文件头"供给链"说明）。
                    //   find_program(GLSLANGVALIDATOR ...) 优先采用已设缓存变量，
                    //   上游 CMake 无需任何改动。
                    glslangValidatorCMakeArg()
                )
                abiFilters += edenAbiFilters.split(",").map { it.trim() }
            }
        }
        ndk {
            abiFilters += edenAbiFilters.split(",").map { it.trim() }
        }
    }

    // 上游 App 的官方构建类型之一（buildTypes.register("relWithDebInfo")，
    // 上游以其为默认变体）。在库模块侧同样注册（幂等），保证：
    //   1) :eden-core:configureCMakeRelWithDebInfo[arm64-v8a] 等任务存在，
    //      Android Studio 变体面板可选、命令行 assembleRelWithDebInfo 可用
    //      （与上游 App 的推荐构建类型对齐）；
    //   2) 若上层 app 也注册了 relWithDebInfo，库按同名变体精确匹配，
    //      而不是回退到 debug 变体。
    buildTypes {
        if (findByName("relWithDebInfo") == null) {
            create("relWithDebInfo") {
                isMinifyEnabled = false // 库模块：混淆由 app 统一处理
            }
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

// ============================================================================
// glslangValidator 供给任务 + 与 AGP CMake 任务的接线
// ============================================================================

val buildGlslangValidator = tasks.register("buildGlslangValidator") {
    group = "build"
    description = "供给宿主机 glslangValidator（预编译 → 源码构建 → 系统 PATH 三级回退），供 Eden CMake 编译 SPIR-V 着色器头"

    inputs.property("mode", glslangBuildMode)
    inputs.property("override", glslangOverride ?: "")
    inputs.property("hostOs", osName)
    inputs.property("hostArch", hostArch)
    if (glslangSourceDir.isDirectory) inputs.dir(glslangSourceDir)
    prebuiltCandidate()?.let { inputs.file(it) }
    outputs.file(glslangValidatorOut)

    onlyIf { glslangOverride == null } // 手动指定路径时整个任务跳过

    doLast {
        if (glslangValidatorOut.isFile && verifyRunnable(glslangValidatorOut)) {
            logger.lifecycle("eden-core: glslangValidator 已就绪（复用）：$glslangValidatorOut")
            return@doLast
        }
        glslangHostDir.mkdirs()

        // 1) 随包预编译（Khronos glslang 16.6.0，三平台）
        if (glslangBuildMode != "source") {
            prebuiltCandidate()?.let { pre ->
                stripQuarantineIfMac(pre)
                if (verifyRunnable(pre)) {
                    pre.copyTo(glslangValidatorOut, overwrite = true)
                    glslangValidatorOut.setExecutable(true)
                    logger.lifecycle(
                        "eden-core: 使用随包预编译 glslangValidator（${pre.parentFile.name}）→ $glslangValidatorOut"
                    )
                    return@doLast
                }
                logger.warn(
                    "eden-core: 随包预编译 ${pre.absolutePath} 在本机不可运行（架构/glibc 不匹配？），尝试源码构建"
                )
            } ?: logger.warn("eden-core: 本平台（$osName/$hostArch）无随包预编译，尝试源码构建")
        }

        // 2) 源码构建（SDK CMake + 本模块随附 glslang 源码）
        if (glslangBuildMode != "prebuilt") {
            buildValidatorFromSource()?.let { built ->
                stripQuarantineIfMac(built)
                if (verifyRunnable(built)) {
                    built.copyTo(glslangValidatorOut, overwrite = true)
                    glslangValidatorOut.setExecutable(true)
                    logger.lifecycle("eden-core: 使用源码构建的 glslangValidator → $glslangValidatorOut")
                    return@doLast
                }
            }
        }

        // 3) 系统 PATH（Vulkan SDK / glslang-tools —— 上游文档的官方环境）
        validatorFromPath()?.let { sys ->
            stripQuarantineIfMac(sys)
            if (verifyRunnable(sys)) {
                sys.copyTo(glslangValidatorOut, overwrite = true)
                glslangValidatorOut.setExecutable(true)
                logger.lifecycle("eden-core: 使用系统 PATH 上的 glslangValidator（${sys.absolutePath}）")
                return@doLast
            }
        }

        throw GradleException(
            "eden-core: 无法获得可运行的宿主机 glslangValidator（Eden 着色器编译必需）。\n" +
            "已尝试：随包预编译（本机 $osName/$hostArch " +
            "${if (prebuiltCandidate() != null) "有" else "无"}）→ 源码构建（需宿主机 CMake + 编译器）→ 系统 PATH。\n" +
            "解决办法（任选其一）：\n" +
            "  1) 指定现成二进制：./gradlew assembleDebug -PglslangValidator=/path/to/glslangValidator\n" +
            "  2) Windows：安装 Visual Studio 2022（含\"使用 C++ 的桌面开发\"）后重试（走源码构建），\n" +
            "     或安装 Vulkan SDK（glslangValidator 进 PATH）\n" +
            "  3) Linux：sudo apt install glslang-tools（或确保 cmake+g++ 走源码构建）\n" +
            "  4) macOS：安装 Xcode Command Line Tools（xcode-select --install）后重试"
        )
    }
}

// 所有 CMake configure 任务（Debug/Release/RelWithDebInfo × arm64-v8a）都依赖
// glslangValidator 供给任务 —— configure 阶段就要执行着色器工具探测
tasks.matching { it.name.startsWith("configureCMake") }.configureEach {
    dependsOn(buildGlslangValidator)
}
