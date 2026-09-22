plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("kotlin-kapt")
}

// ARMSX2 (PS2) core resources — shaders, GameIndex.yaml, fonts, fullscreenui,
// icons, sounds, controller DB. They live in the ARMSX2 subtree and are staged
// into the APK as assets/resources so the runtime can seed <DataRoot>/resources
// (EmuFolders::Resources). Without them the GS fails to read
// shaders/opengl/convert.glsl and PS2 games boot to a black screen.
val armsx2ResourcesSrc = rootProject.file("ARMSX2-master/bin/resources")
val armsx2AssetsStaging = layout.buildDirectory.dir("generated/assets/armsx2_resources")
val stageArmsx2Resources = tasks.register<Copy>("stageArmsx2Resources") {
    onlyIf { armsx2ResourcesSrc.isDirectory }
    from(armsx2ResourcesSrc)
    into(armsx2AssetsStaging.map { it.dir("resources") })
}
// Stage before asset merging so the files land in the APK.
tasks.named("preBuild") { dependsOn(stageArmsx2Resources) }

android {
    namespace = "com.nesstation.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nesstation.app"
        // API 24 (Android 7.0)：bionic 从 24 起才提供 getifaddrs()/freeifaddrs()，
        // DEV9 的适配器枚举依赖它（见 pcsx2/DEV9/AdapterUtils）。低于 24 只能靠
        // ioctl(SIOCGIFCONF) 兼容实现，在 Android 沙箱里拿不到 wlan0 等接口。
        minSdk = 24
        targetSdk = 34
        versionCode = 10
        versionName = "3.6.3"
        // NDK 必须 >= r28c(28.2.13676358)：ARMSX2 的 common/pcsx2 以 C++20 编译并用到
        // std::lexicographical_compare_three_way，而 r26 的 libc++ 快照没有该符号，
        // 会在编译 WindowInfo.cpp 时报 "no member named 'lexicographical_compare_three_way'".
        // ARMSX2 工程自身默认也使用 28.2.13676358。
        ndkVersion = (project.findProperty("ndkVersion") as String?) ?: "28.2.13676358"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // J2ME: FULL_EMULATOR = true (runtime DEX loading mode)
        buildConfigField("boolean", "FULL_EMULATOR", "true")

        // 对战平台服务器地址（打包时内置，用户无需手动配置）。
        // 可通过 gradle 参数覆盖：-PbattleServerHost=xxx -PbattleServerHttpPort=xxx -PbattleServerTcpPort=xxx
        val battleServerHost = (project.findProperty("battleServerHost") as String?) ?: "afei.ddns.net"
        val battleServerHttpPort = (project.findProperty("battleServerHttpPort") as String?) ?: "1808"
        val battleServerTcpPort = (project.findProperty("battleServerTcpPort") as String?) ?: "1909"
        buildConfigField("String", "BATTLE_SERVER_HOST", "\"$battleServerHost\"")
        buildConfigField("String", "BATTLE_SERVER_HTTP_PORT", "\"$battleServerHttpPort\"")
        buildConfigField("String", "BATTLE_SERVER_TCP_PORT", "\"$battleServerTcpPort\"")

        multiDexEnabled = true

        ndk {
            val abiFilter = (project.findProperty("abiFilter") as String?)
            if (abiFilter.isNullOrBlank()) {
                abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
            } else {
                abiFilters += abiFilter.split(",").map { it.trim() }
            }
        }
        externalNativeBuild {
            cmake {
                // 不要在此注入 -std：CMake 层按目标统一管理。
                // GameBox 各核心(core/cmake/CMakeLists.txt)为 C++17，
                // ARMSX2 子树(platforms/android/.../BuildParameters.cmake)为
                // C++20。全局注入 -std=c++17 会与 ARMSX2 的 C++20 冲突，
                // 导致其标准库 C++20 符号(如 lexicographical_compare_three_way)不可用。
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    // 与 minSdk=24 保持一致：保证 bionic 声明 getifaddrs()/freeifaddrs()。
                    "-DANDROID_PLATFORM=android-24"
                )
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        viewBinding = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-Xjvm-default=all",
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
        )
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
            // ARMSX2 resources staged by :stageArmsx2Resources (assets/resources)
            assets.srcDir(armsx2AssetsStaging)
        }
    }

    val useStub = (project.findProperty("useStubCore") as String?)?.toBoolean() ?: false
    val stubPath = file("../core/native-stub/CMakeLists.txt")
    val realPath = file("../core/cmake/CMakeLists.txt")
    val cmakePath = when {
        useStub && stubPath.exists() -> stubPath
        !useStub && realPath.exists() -> realPath
        stubPath.exists() -> stubPath
        realPath.exists() -> realPath
        else -> null
    }
    if (cmakePath != null) {
        externalNativeBuild {
            cmake {
                path = cmakePath
                version = "3.22.1"
            }
        }
    } else {
        logger.error(
            "NesStation: no CMakeLists.txt found at core/native-stub/ or core/cmake/. " +
            "Building without a native core — emulator will crash at loadLibrary(\"nescore\"). " +
            "Fix: ensure core/ exists next to app/, or pass -PuseStubCore=true."
        )
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            multiDexKeepProguard = file("multidex-config.pro")
        }
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        jniLibs {
            // Flycast (DC/NAOMI) 要求 useLegacyPackaging = true（extractNativeLibs）：
            // 其自定义 Adreno GPU 驱动功能（rend.CustomGpuDriver）运行时经
            // adrenotools/linkernsbypass 从 nativeLibraryDir dlopen 钩子库
            // (libmain_hook/libhook_impl/libfile_redirect_hook/libgsl_alloc_hook.so)，
            // 未解压态（so 以符号链接指向 APK 内部）时该加载路径不可靠。
            // 上游 flycast shell/android-studio 同样强制 useLegacyPackaging = true。
            // 代价仅为安装时解压 so（安装包占用略增），对其它核心无影响。
            useLegacyPackaging = true

            // 4 个 adrenotools 钩子库同时来自两条输入：
            //   1) app/src/main/jniLibs/arm64-v8a/lib*.so —— Flycast 上游 shell 预编译产物；
            //   2) ARMSX2/platforms/android/.../3rdparty/adrenotools/CMakeLists.txt
            //      以 add_library(... SHARED ...) 现场编译（仅 arm64-v8a）。
            // 二者在 mergeReleaseNativeLibs 阶段命中 DuplicateRelativeFileException:
            //   "2 files found with path 'lib/arm64-v8a/libfile_redirect_hook.so'"
            // 用 pickFirsts 让 AGP 取其一即可（两个版本均来自同一 adrenotools 上游，
            // ABI/符号接口一致，运行时 dlopen 不会因版本差异崩）。
            pickFirsts += setOf(
                "lib/arm64-v8a/libfile_redirect_hook.so",
                "lib/arm64-v8a/libgsl_alloc_hook.so",
                "lib/arm64-v8a/libhook_impl.so",
                "lib/arm64-v8a/libmain_hook.so"
            )
        }
        resources {
            excludes += setOf(
                "META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        }
    }

    lint {
        abortOnError = false
        disable += listOf("MissingTranslation", "ExtraTranslation")
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.tv.material)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.rxjava2)
    kapt(libs.androidx.room.compiler)

    implementation(libs.coil.compose)

    implementation(libs.androidx.leanback)
    implementation(libs.androidx.leanback.preference)

    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.webkit)

    // J2ME dependencies
    implementation(project(":dexlib"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.material)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.multidex)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.gson)
    implementation(libs.guava)
    implementation(libs.rxandroid)
    implementation(libs.zip4j)

    // J2ME additional dependencies
    implementation(libs.acra)
    implementation(libs.volley)
    implementation(libs.mididriver)
    implementation(libs.pngj)
    implementation(libs.mobile.ffmpeg)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.filepicker)
    implementation(libs.ambilwarna)

    // Flycast (Dreamcast/NAOMI) core — Java 层依赖（见 core/dc/ 与 com/flycast/emulator/）。
    // 版本与上游 flycast shell/android-studio/gradle/libs.versions.toml 保持一致：
    //   commons-lang3  → InputDeviceManager/FileBrowser 的 ArrayUtils/StringUtils
    //   httpclient5    → emu.HttpClient（RetroAchievements 上传/下载、网络对战）
    //   slf4j-android  → httpclient5 运行时日志绑定
    implementation(libs.commons.lang3)
    implementation(libs.httpclient5)
    implementation(libs.slf4j.android)

    compileOnly(libs.auto.service.annotations)
    kapt(libs.auto.service)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
