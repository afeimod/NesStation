package org.citra.citra_emu.applets

import android.util.Log
import androidx.annotation.Keep

/**
 * Azahar 软键盘系统小程序 —— JNI 契约桩 + 真实输入对话框桥。
 *
 * 原生侧（jni/applets/swkbd.cpp）在 JNI_OnLoad（IDCache 初始化）时缓存：
 *  - `Execute(Lorg/citra/citra_emu/applets/SoftwareKeyboard$KeyboardConfig;)Lorg/citra/citra_emu/applets/SoftwareKeyboard$KeyboardData;`
 *  - `ShowError(Ljava/lang/String;)V`
 * —— 均为 **PascalCase**（AzaharPlus fork 实际契约，经 libazahar.so 反汇编验证；
 * 上游官方 azahar 源码同名），并按字段名反射读写 KeyboardConfig（buttonConfig:I /
 * maxTextLength:I / multilineMode:Z / hintText:Ljava/lang/String; /
 * buttonText:[Ljava/lang/String;）与 KeyboardData（text:Ljava/lang/String; / button:I）。
 * 部分 3DS 游戏对话 / 命名时触发。
 *
 * ★★ 建档卡死修复（OoT3D 等"部分游戏建立存档时画面直接卡死"）★★：
 * 旧最小桩立即返回**空文本** —— 需要**非空名字**的游戏（时之笛 3D 建档
 * 命名、宝可梦起名等）拿到空文本后会重新请求键盘，无限循环 → 游戏线程
 * 卡死、画面冻结、azahar_log.txt 无任何新增行（applet 重启不打 Error 级
 * 日志，用户实测日志尾部停在 FS 存档探测处）。现在：
 *  1) 宿主已注册（模拟器画面在场）→ 在主线程弹出真实输入对话框，
 *     阻塞原生调用线程等用户输入（与上游 Citra Android 行为一致——
 *     游戏画面停在键盘阶段是正常表现），输入名字确认后游戏继续；
 *  2) 宿主缺失/异常 → 返回**非空兜底名** + 确认按钮，杜绝空文本
 *     重试死循环；
 *  3) 每次 Execute 记入环形缓冲（[recentRequests]），核心日志诊断弹窗
 *     直接展示 —— 下次卡死报告能立刻看出是否卡在键盘环节。
 */
@Keep
object SoftwareKeyboard {

    /**
     * 宿主桥：由模拟器界面注册。requestInput 应把请求转投到主线程显示
     * 对话框并**阻塞调用线程**（原生 swkbd 在模拟线程上调用本 Execute）
     * 直到用户确认/取消；返回 null = 无法显示（宿主即将销毁等）。
     */
    interface Host {
        fun requestInput(config: KeyboardConfig): KeyboardData?
    }

    @Volatile
    var host: Host? = null

    /** 最近 16 次 swkbd 请求记录（诊断用，核心日志弹窗展示）。 */
    private val recent = ArrayDeque<String>(16)

    fun recentRequests(): List<String> = synchronized(recent) { recent.toList() }

    private fun record(line: String) {
        synchronized(recent) {
            while (recent.size >= 16) recent.removeFirst()
            recent.addLast(line)
        }
        Log.i("AzaharNative", "swkbd: $line")
    }

    enum class ValidationError {
        None,
        ButtonOutOfRange,
        MaxDigitsExceeded,
        AtSignNotAllowed,
        PercentNotAllowed,
        BackslashNotAllowed,
        ProfanityNotAllowed,
        CallbackFailed,
        FixedLengthRequired,
        MaxLengthExceeded,
        BlankInputNotAllowed,
        EmptyInputNotAllowed
    }

    class KeyboardConfig {
        @JvmField
        var buttonConfig: Int = 0

        @JvmField
        var maxTextLength: Int = 16

        @JvmField
        var multilineMode: Boolean = false

        @JvmField
        var hintText: String? = null

        @JvmField
        var buttonText: Array<String?> = arrayOfNulls(3)
    }

    class KeyboardData {
        @JvmField
        var text: String = ""

        @JvmField
        var button: Int = 0
    }

    /**
     * 方法名必须为 `Execute`（PascalCase）—— JNI_OnLoad 按 GetStaticMethodID
     * 字符串名查找，名字不符会抛 NoSuchMethodError → pending exception →
     * SIGABRT 启动闪退。
     */
    @JvmStatic
    fun Execute(config: KeyboardConfig?): KeyboardData {
        val c = config ?: KeyboardConfig()
        record("Execute hint='${c.hintText ?: ""}' maxLen=${c.maxTextLength} " +
                "btnCfg=${c.buttonConfig} host=${if (host != null) "已注册" else "无"}")
        val h = host
        if (h != null) {
            try {
                h.requestInput(c)?.let { result ->
                    record("用户输入 text='${result.text.take(24)}' button=${result.button}")
                    return result
                }
                record("宿主未应答（界面销毁中）→ 非空兜底名")
            } catch (t: Throwable) {
                record("宿主异常 ${t.javaClass.simpleName}: ${t.message} → 非空兜底名")
            }
        } else {
            record("宿主未注册 → 非空兜底名（旧版此处返回空文本 → 部分游戏建档死循环卡死）")
        }
        return KeyboardData().apply {
            text = FALLBACK_TEXT
            button = okButtonIndex(c.buttonConfig)
        }
    }

    /** 兜底名：非空，避免空文本触发游戏无限重请求键盘（画面卡死）。 */
    private const val FALLBACK_TEXT = "Player"

    /**
     * buttonConfig 语义（上游 ButtonConfig 常量）：
     * 0=SINGLE（仅 OK）、1=DUAL（取消|OK）、2=TRIPLE（取消|忘记|OK）——
     * OK 按钮的索引恰为 buttonConfig 的值。
     */
    fun okButtonIndex(buttonConfig: Int): Int = when (buttonConfig) {
        1 -> 1   // Dual: Cancel(0) | Ok(1)
        2 -> 2   // Triple: Cancel(0) | I Forgot(1) | Ok(2)
        else -> 0
    }

    /** 同上，必须为 `ShowError`（PascalCase）。 */
    @JvmStatic
    fun ShowError(error: String) {
        record("ShowError: $error")
        Log.w("AzaharNative", "SoftwareKeyboard error: $error")
    }
}
