package org.citra.citra_emu.utils

import androidx.annotation.Keep
import org.citra.citra_emu.NativeLibrary

/**
 * Azahar CIA 安装 —— JNI 契约类（与 AzaharPlus 上游 org.citra.citra_emu.utils
 * .CiaInstallWorker 同源同形）。
 *
 * 原生侧（libazahar.so，AzaharPlus 提取，BuildId 见 worklog）：
 *  - JNI_OnLoad 缓存路径 FindClass("org/citra/citra_emu/utils/CiaInstallWorker")
 *    + GetMethodID 缓存 setProgressCallback "(II)V"（反汇编 0x1b8b570 实测）。
 *    参数顺序 (max, progress) 与上游一致；安装过程原生侧在调用线程同步回调。
 *  - 导出符号 Java_org_citra_citra_1emu_utils_CiaInstallWorker_installCIA
 *    （反汇编 0x1b930dc 实测）：单 jstring 参数 → std::string（UTF-16 转换），
 *    调 Service::AM::InstallCIA(path, callback)，返回 InstallStatus 枚举常量
 *    （原生 map<int, jobject> 按 "Success"/"ErrorFailedToOpenFile"/
 *    "ErrorFileNotFound"/"ErrorAborted"/"ErrorInvalid"/"ErrorEncrypted" 字段名
 *    GetStaticFieldID + GetStaticObjectField + NewGlobalRef 缓存 —— 按名绑定，
 *    与 NativeLibrary.InstallStatus 枚举常量名严格一致）。
 *  - ⚠ installCIA 必须是**实例**方法：进度回调以本实例为 receiver 走
 *    CallVoidMethod；声明为 static 会以 jclass 做 receiver 触发 JNI abort。
 *  - path 必须是真实文件系统路径（content:// 需先拷贝到本地再传入）。
 */
@Keep
class CiaInstallWorker {

    /**
     * 原生侧安装进度回调（(max, progress)；安装线程同步调用，
     * UI 更新需自行切主线程）。
     */
    fun setProgressCallback(max: Int, progress: Int) {
        listener?.invoke(max, progress)
    }

    /**
     * 安装一个 CIA 到 Azahar NAND（阻塞直至完成；须在 IO 线程调用）。
     *
     * @param path CIA 文件的真实文件系统路径
     * @return 安装结果（Success / Error* 枚举常量）
     */
    external fun installCIA(path: String): NativeLibrary.InstallStatus

    companion object {
        /**
         * NesStation 宿主进度观察者（UI 层注册；安装流程结束务必置 null，
         * 避免泄漏 Activity 引用）。
         */
        @Volatile
        var listener: ((max: Int, progress: Int) -> Unit)? = null
    }
}
