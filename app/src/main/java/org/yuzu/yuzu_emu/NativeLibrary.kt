// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later
//
// ★★★ NesStation 集成版契约（org.yuzu.yuzu_emu.NativeLibrary）★★★
//
// 与上游 libyuzu-android.so（Java_org_yuzu_yuzu_1emu_* 符号）一一对应的
// JNI 契约类，vendored 进 NesStation app 源码（集成方式与
// info.cemu.cemu.nativeinterface.* / Cemu-AZ 完全一致）。
// 与上游差异：
//   · System.loadLibrary 与 initJvm 交由 EdenNative（引擎侧）控制时序，
//     对象 init 不再自动加载（避免 :eden-core 被跳过时类加载即崩溃）；
//   · 上游回调（exitEmulationActivity / copyFileToStorage / exists /
//     getFileExtension / ... / onEmulationStarted / ... ）不再依赖
//     EmulationActivity / FileUtil / DocumentsTree，全部桥接到
//     [Host]（由 com.nesstation.app.core.engine.EdenEngine 实现），
//     文件类回调按真实路径（java.io.File）语义实现 —— NesStation 的
//     Switch ROM 一律以真实文件路径喂给核心（与 Cemu/Wii U 同模式），
//     不使用 SAF content-uri 路径。

package org.yuzu.yuzu_emu

import android.view.Surface
import androidx.annotation.Keep

/**
 * Class which contains methods that interact
 * with the native side of the Eden code.
 */
object NativeLibrary {

    @Keep
    data class UpdateResult(
        var tag: String = "",
        var title: String = "",
        var body: String = "",
        var url: String = "",
        var assets: MutableList<String> = mutableListOf()
    ) {
        fun addAsset(asset: String) {
            assets.add(asset)
        }
    }

    /**
     * NesStation 侧宿主回调 —— 由 EdenEngine 注册。
     * 全部为 C++ → Java 方向的回调（id_cache / FS::Android 注册）。
     */
    interface Host {
        /** 非用户请求的模拟退出（run() 返回非 Success / 崩溃路径）。 */
        fun exitEmulationActivity(result: Int)

        /** 模拟开始（核心侧触发，宿主自行 post 主线程）。 */
        fun onEmulationStarted()

        /** 模拟停止（核心侧触发）。 */
        fun onEmulationStopped(result: Int)

        /** 游戏内程序切换（双卡带等）。 */
        fun onProgramChanged(programIndex: Int)
    }

    @JvmStatic
    var host: Host? = null

    // ------------------------------------------------------------------
    // C++ → Java 静态回调（id_cache.cpp / fs_android.cpp / WebBrowser 注册）
    // ------------------------------------------------------------------

    @Keep
    @JvmStatic
    fun openContentUri(path: String?, openmode: String?): Int {
        // 真实文件路径语义：Path open。openmode 仅支持 "r"（核心只读打开）。
        return try {
            java.io.RandomAccessFile(path!!, openmode ?: "r").use { 0 }
        } catch (t: Throwable) {
            -1
        }
    }

    @Keep
    @JvmStatic
    fun getSize(path: String?): Long =
        java.io.File(path!!).takeIf { it.exists() }?.length() ?: 0L

    @Keep
    @JvmStatic
    fun exists(path: String?): Boolean = java.io.File(path!!).exists()

    @Keep
    @JvmStatic
    fun isDirectory(path: String?): Boolean = java.io.File(path!!).isDirectory

    @Keep
    @JvmStatic
    fun getParentDirectory(path: String): String =
        java.io.File(path).parent ?: path

    @Keep
    @JvmStatic
    fun getFilename(path: String): String =
        java.io.File(path).name

    @Keep
    @JvmStatic
    fun copyFileToStorage(source: String, destdir: String): Boolean {
        // 上游：把 content-uri 复制进内部存储。NesStation 集成下 source
        // 已是真实路径（或引擎显式落盘的临时文件），直接按 File 拷贝。
        return try {
            val src = java.io.File(source)
            if (!src.exists()) return false
            val dstDir = java.io.File(destdir)
            dstDir.mkdirs()
            val dst = java.io.File(dstDir, src.name)
            src.copyTo(dst, overwrite = true)
            true
        } catch (t: Throwable) {
            false
        }
    }

    @Keep
    @JvmStatic
    fun getFileExtension(source: String): String =
        java.io.File(source).extension

    @Keep
    @JvmStatic
    fun exitEmulationActivity(result: Int) {
        host?.exitEmulationActivity(result)
    }

    @Keep
    @JvmStatic
    fun onEmulationStarted() {
        host?.onEmulationStarted()
    }

    @Keep
    @JvmStatic
    fun onEmulationStopped(result: Int) {
        host?.onEmulationStopped(result)
    }

    @Keep
    @JvmStatic
    fun onProgramChanged(programIndex: Int) {
        host?.onProgramChanged(programIndex)
    }

    @Keep
    @JvmStatic
    fun addNetPlayMessage(type: Int, message: String) {
        // 网络对战消息 —— NesStation 的 Switch 集成不使用 eden 内置 netplay，
        // 留空实现保住 C++ 侧回调注册（GetStaticMethodID 需要）。
        // ★★★ 参数顺序必须与 native 侧查找签名严格一致（本次双闪退根因）：
        //   id_cache.cpp:
        //     GetStaticMethodID(clazz, "addNetPlayMessage", "(ILjava/lang/String;)V")
        //   即【Int 在前、String 在后】。此前版本误写成 (message: String,
        //   playerId: Int)，编译产物为 (Ljava/lang/String;I)V，与 native
        //   查找不匹配 → GetStaticMethodID 抛 NoSuchMethodError（挂起）→
        //   下一个 FindClass（SoftwareKeyboard::InitJNI）触发 ART
        //   "No pending exception expected" → SIGABRT 闪退
        //   （tombstone: no static method
        //     "Lorg/yuzu/yuzu_emu/NativeLibrary;.addNetPlayMessage(ILjava/lang/String;)V"）。
        //   调用方为 multiplayer.cpp 的 AndroidMultiplayer::AddNetPlayMessage
        //   (jint type, jstring msg) —— type 为 NetPlayStatus 枚举值。
    }

    @Keep
    @JvmStatic
    fun clearChat() {
        // 同上：netplay 聊天清理，空实现。
    }

    @Keep
    @JvmStatic
    fun openExternalUrl(url: String) {
        // 上游：系统浏览器打开（WebBrowser applet 的外链）。
        // 留空实现 —— 游戏内网页 applet 的外链跳转对前端无意义。
    }

    // ------------------------------------------------------------------
    // Java → C++ external（EdenEngine / 设置面板消费；签名与上游一致）
    // ------------------------------------------------------------------

    external fun initJvm()
    external fun setAppDirectory(directory: String)

    external fun initializeGpuDriver(
        hookLibDir: String?,
        customDriverDir: String?,
        customDriverName: String?,
        fileRedirectDir: String?
    )

    external fun reloadKeys(): Boolean
    external fun areKeysPresent(): Boolean
    external fun installKeys(path: String, extension: String): Int

    external fun isFirmwareAvailable(): Boolean
    external fun firmwareVersion(): String?
    external fun gameRequiresFirmware(programId: String): Boolean

    /**
     * Installs a nsp or xci file to nand（固件/更新/DLC 安装同入口）。
     * @return int representation of [ContentManager InstallResult]
     */
    external fun installFileToNand(
        filename: String,
        callback: (max: Long, progress: Long) -> Boolean
    ): Int

    external fun doesUpdateMatchProgram(programId: String, updatePath: String): Boolean

    external fun initializeSystem(reload: Boolean)

    /**
     * Begins emulation.
     */
    external fun run(path: String?, programIndex: Int, frontendInitiated: Boolean)

    // Surface Handling
    external fun surfaceChanged(surf: Surface?)
    external fun surfaceDestroyed()

    external fun getAppletCaptureBuffer(): ByteArray
    external fun getAppletCaptureWidth(): Int
    external fun getAppletCaptureHeight(): Int

    // ------------------------------------------------------------------
    // ★★ v8：小程序（applet）启动 —— 上游 AppletAdapter 同款三入口 ★★
    //   native 实现见 core/eden/src/android/app/src/main/jni/native.cpp
    //   （getAppletLaunchPath:1513 / setCurrentAppletId:1530 /
    //    setCabinetMode:1535，签名逐字核对一致）。
    // ------------------------------------------------------------------

    /** 取固件 NAND 内 applet NCA 的启动路径；未安装固件返回空串。 */
    external fun getAppletLaunchPath(id: Long): String

    /** 启动 applet 前声明当前程序身份（Service::AM::Applets::AppletId）。 */
    external fun setCurrentAppletId(appletId: Int)

    /** 仅 Cabinet（Amiibo 管理）用：选择操作模式（Service::NFP::CabinetMode）。 */
    external fun setCabinetMode(cabinetMode: Int)

    external fun unpauseEmulation()
    external fun pauseEmulation()
    external fun stopEmulation()
    external fun isRunning(): Boolean
    external fun isPaused(): Boolean

    external fun getPerfStats(): DoubleArray
    external fun getShadersBuilding(): Int

    external fun getCpuBackend(): String
    external fun getGpuDriver(): String
    external fun getCpuSummary(): String

    external fun getVulkanDriverVersion(): String
    external fun getVulkanApiVersion(): String
    external fun getGpuModel(): String

    external fun applySettings()
    external fun logSettings()
    external fun logDeviceInfo()

    // 速度档（快进/慢放） —— 与 NesStation 快进按钮联动
    external fun setTurboSpeedLimit(enabled: Boolean)
    external fun setSlowSpeedLimit(enabled: Boolean)
    external fun setStandardSpeedLimit(enabled: Boolean)
    external fun isTurboMode(): Boolean
    external fun isSlowMode(): Boolean

    // 软键盘（applet）内联输入提交
    external fun submitInlineKeyboardText(text: String)
    external fun submitInlineKeyboardInput(keyCode: Int)

    // 游玩时长统计（可选启用）
    external fun playTimeManagerInit()
    external fun playTimeManagerStart()
    external fun playTimeManagerStop()
    external fun playTimeManagerGetPlayTime(programId: String): Long
    external fun playTimeManagerGetCurrentTitleId(): Long
    external fun playTimeManagerResetProgramPlayTime(programId: String)
    external fun playTimeManagerSetPlayTime(programId: String, playTimeMs: Long)
}
