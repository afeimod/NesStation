package org.ppsspp.ppsspp;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.util.ArrayList;

/**
 * NesStation in-process host stub for the PPSSPP core.
 *
 * The core calls back into "the activity" through the method IDs captured by
 * PpssppActivity.registerCallbacks (v1.20.4 app-android.cpp):
 *
 *   postCommand(String cmd, String param)   — frame commands (toast,
 *       showKeyboard, vibrate, launchBrowser, screenshot finished, ...)
 *   getDebugString(String key)              — debug helper (input device)
 *   getNativeCrashHistory(int max)          — crash-history probe
 *
 * ★★ NesStation 集成补丁（PSP 闪退根治 #3 —— 契约方法一个都不能少）★★
 *
 * 尽管官方源注释写着 "It's OK if getNativeCrashHistory is missing"
 * （v1.20.4 app-android.cpp:608），但 GetMethodID 失败抛出的
 * NoSuchMethodError 并不会被清除 —— registerCallbacks 紧接着调用的
 * Android_RegisterStorageCallbacks → FindClass("org/ppsspp/ppsspp/ContentUri")
 * 在 "pending exception" 状态下执行，ART 直接 AssertNoPendingException
 * → SIGABRT（2026-10-12 tombstone：NoSuchMethodError:
 * Lorg/ppsspp/ppsspp/PspHostStub;.getNativeCrashHistory(I)Ljava/util/ArrayList;）。
 * 因此三个契约方法必须全部实现 —— 空列表即新装状态的正确语义。
 *
 * ★★ NesStation 集成补丁（PSP 闪退根治 #2 —— 必须是 Context）★★
 *
 * registerCallbacks 把传入对象存为全局的 "nativeActivity"，核心不止拿它
 * 调 postCommand —— 它还会被原样传给消费 Context/Activity 的 Java 静态方法：
 *
 *   · TextRenderer.allocFont(Context, String)（draw_text_android.cpp 的
 *     TextDrawerAndroid::SetOrCreateFont → CallStaticIntMethod(activity_, ...)
 *     → Typeface.createFromAsset(ctx.getAssets(), ...)）—— PPSSPP 的 UI /
 *     字幕文本绘制初始化字体时必经。旧版 PspHostStub 不是 Context →
 *     ClassCastException → 字体分配失败（-1337），UI 文字全灭甚至下游
 *     NPE 崩溃；
 *   · ContentUri.*（openContentUri / filePathGetFreeStorageSpace 等）的
 *     首参声明为 Activity —— 文件路径直读模式下不触发，但一旦触发同样
 *     依赖真实 Context 链。
 *
 * 因此本类改为继承 ContextWrapper，包装宿主 Application（PspEngine.boot
 * 前传入 appContext）：getAssets()/getPackageName()/getFilesDir() 等全部
 * 直通宿主 —— 与官方 Activity 传入时的语义对齐（官方也常在
 * allocFont 里收到的是 Activity，而它需要的只是 Context 接口）。
 *
 * NesStation drives the core inside its own EmulatorScreen surface, so there
 * is no PpssppActivity instance; this stub plays that role. postCommand
 * surfaces user-visible commands (toast) through the wrapped context and
 * swallows activity-bound ones (keyboard/browser browsing is not reachable
 * from an embedded session).
 *
 * Threading: called from the core's emu/render thread — toasts are posted to
 * the main looper.
 */
public class PspHostStub extends ContextWrapper {

    private static final String TAG = "PspHostStub";

    /** Main-looper handler, set on construction (toasts must be posted). */
    private static volatile Handler mainHandler = null;

    public PspHostStub(Context base) {
        super(base);
        if (base != null && mainHandler == null) {
            mainHandler = new Handler(Looper.getMainLooper());
        }
    }

    /** Frame-command sink — must exist with this exact signature. */
    public void postCommand(final String cmd, final String param) {
        if (cmd == null) return;
        Log.i(TAG, "postCommand: '" + cmd + "' '" + (param == null ? "" : param) + "'");
        switch (cmd) {
            case "toast": {
                final String text = param == null ? "" : param;
                Handler h = mainHandler;
                if (h != null) {
                    h.post(() -> {
                        try {
                            // this == ContextWrapper → 宿主 Application context，
                            // 与旧版静态 appContext 等价但不再依赖外部注入。
                            if (!text.isEmpty()) {
                                Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
                            }
                        } catch (Throwable ignored) {
                        }
                    });
                }
                break;
            }
            // "showKeyboard" / "browseFile" / "browseFolder" / "launchBrowser"
            // / "vibrate" / "screenshot" / "recording" / "uistate" ... are
            // activity-bound UI conveniences; embedded sessions ignore them.
            default:
                break;
        }
    }

    /** Debug helper — must exist with this exact signature. */
    public String getDebugString(String key) {
        return "";
    }

    /**
     * Crash-history probe — must exist with this exact signature.
     *
     * v1.20.4 app-android.cpp:604 在 registerCallbacks 里对本方法做
     * GetMethodID；缺失时抛出的 NoSuchMethodError 不会被核心清理，下一个
     * FindClass(ContentUri) 直接触发 ART "No pending exception expected"
     * SIGABRT —— 即 PSP 游戏启动必闪退的第二个根因（第一个是 dlopen
     * 不触发 JNI_OnLoad）。返回空列表 = 无历史崩溃，语义与全新安装一致；
     * native 侧仅在主动询问崩溃历史（Java_org_..._GetNativeCrashHistory）
     * 时才调用本方法，空列表完全合法（app-android.cpp:564 有空判保护）。
     */
    public ArrayList<String> getNativeCrashHistory(int max) {
        return new ArrayList<>();
    }
}
