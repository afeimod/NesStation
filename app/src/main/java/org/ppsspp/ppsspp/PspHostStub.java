package org.ppsspp.ppsspp;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

/**
 * NesStation in-process host stub for the PPSSPP core.
 *
 * The core calls back into "the activity" through the method IDs captured by
 * PpssppActivity.registerCallbacks (v1.20.4 app-android.cpp):
 *
 *   postCommand(String cmd, String param)   — frame commands (toast,
 *       showKeyboard, vibrate, launchBrowser, screenshot finished, ...)
 *   getDebugString(String key)              — debug helper (input device)
 *   getNativeCrashHistory(int max)          — OPTIONAL (missing is fine)
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
}
