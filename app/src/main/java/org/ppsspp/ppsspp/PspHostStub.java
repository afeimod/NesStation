package org.ppsspp.ppsspp;

import android.content.Context;
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
 * NesStation drives the core inside its own EmulatorScreen surface, so there
 * is no PpssppActivity instance; this stub plays that role. postCommand
 * surfaces user-visible commands (toast) through the app context held by
 * PspEngine and swallows activity-bound ones (keyboard/browser browsing is
 * not reachable from an embedded session).
 *
 * Threading: called from the core's emu/render thread — toasts are posted to
 * the main looper.
 */
public class PspHostStub {

    private static final String TAG = "PspHostStub";

    /** App context + UI handler, set by PspEngine before boot(). */
    @SuppressWarnings("StaticFieldLeak")
    private static volatile Context appContext = null;
    private static volatile Handler mainHandler = null;

    public static void setAppContext(Context ctx) {
        appContext = ctx;
        mainHandler = ctx != null ? new Handler(Looper.getMainLooper()) : null;
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
                            Context c = appContext;
                            if (c != null && !text.isEmpty()) {
                                Toast.makeText(c, text, Toast.LENGTH_SHORT).show();
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
