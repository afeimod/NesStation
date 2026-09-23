package org.citra.citra_emu.applets;

import android.app.Activity;
import android.text.InputFilter;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.citra.citra_emu.CitraHost;

/**
 * 3DS 软键盘 applet —— 与上游 org.citra.citra_emu.applets.SoftwareKeyboard
 * 保持一致的 JVM 形状：
 *
 *  - native 构造 KeyboardConfig（直接按字段名写 buttonConfig/maxTextLength/
 *    buttonText/hintText/multilineMode），然后调用 static Execute(config)，
 *    阻塞等待返回 KeyboardData（native 用 (I, Ljava/lang/String;)V 构造）。
 *  - ValidateFilters / ValidateInput 为实例 native（静态 JNI 导出）。
 *
 * NesStation 实现：在宿主 activity 上弹一个 EditText 对话框（阻塞 latch），
 * 按 OK 返回 KeyboardData(0, text)，取消返回 KeyboardData(1, "")。
 */
public final class SoftwareKeyboard {

    public static final SoftwareKeyboard INSTANCE = new SoftwareKeyboard();

    private static KeyboardData data = null;
    private static final Object finishLock = new Object();

    private SoftwareKeyboard() {
    }

    public static final class KeyboardData {
        private int button;
        private String text;

        public KeyboardData(int button, String text) {
            this.button = button;
            this.text = text == null ? "" : text;
        }

        public final int getButton() {
            return button;
        }

        public final void setButton(int v) {
            this.button = v;
        }

        public final String getText() {
            return text;
        }

        public final void setText(String v) {
            this.text = v;
        }
    }

    /** 字段名/类型被 native 直接访问（SetIntField 等），勿改名字。 */
    public static final class KeyboardConfig
        implements java.io.Serializable {
        private int buttonConfig;
        public String[] buttonText;
        private String hintText;
        private int maxTextLength;
        private boolean multilineMode;

        public KeyboardConfig() {
        }

        public final int getButtonConfig() {
            return buttonConfig;
        }

        public final void setButtonConfig(int v) {
            this.buttonConfig = v;
        }

        public final String[] getButtonText() {
            return buttonText;
        }

        public final void setButtonText(String[] v) {
            this.buttonText = v;
        }

        public final String getHintText() {
            return hintText;
        }

        public final void setHintText(String v) {
            this.hintText = v;
        }

        public final int getMaxTextLength() {
            return maxTextLength;
        }

        public final void setMaxTextLength(int v) {
            this.maxTextLength = v;
        }

        public final boolean getMultilineMode() {
            return multilineMode;
        }

        public final void setMultilineMode(boolean v) {
            this.multilineMode = v;
        }
    }

    /** 校验错误枚举（ordinal 被 native 使用 —— 顺序不可改）。 */
    public enum ValidationError {
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

    /** native 调用：弹出软键盘并阻塞等待输入结果。 */
    public static KeyboardData Execute(KeyboardConfig config) {
        if (config.getButtonConfig() == 3) {
            // ButtonConfig.None：上游直接报错返回
            android.util.Log.e("CitraHost", "SoftwareKeyboard: unexpected button config None");
            return new KeyboardData(1, "");
        }
        final KeyboardData[] result = new KeyboardData[1];
        Runnable dialogTask = () -> result[0] = ExecuteImpl(config);
        Activity activity = CitraHost.getActivity();
        if (activity == null) {
            android.util.Log.w("CitraHost", "SoftwareKeyboard: no activity, auto-cancel");
            return new KeyboardData(1, "");
        }
        activity.runOnUiThread(dialogTask);
        try {
            synchronized (finishLock) {
                finishLock.wait();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new KeyboardData(1, "");
        }
        KeyboardData out = result[0];
        return out == null ? new KeyboardData(1, "") : out;
    }

    private static KeyboardData ExecuteImpl(KeyboardConfig config) {
        Activity activity = CitraHost.getActivity();
        if (activity == null) {
            synchronized (finishLock) {
                finishLock.notifyAll();
            }
            return new KeyboardData(1, "");
        }
        final EditText input = new EditText(activity);
        int maxLength = config.getMaxTextLength() > 0 ? config.getMaxTextLength() : 32;
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLength)});
        input.setSingleLine(!config.getMultilineMode());
        if (!config.getMultilineMode()) {
            input.setInputType(InputType.TYPE_CLASS_TEXT);
        }
        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * activity.getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad / 2, pad, 0);
        if (config.getHintText() != null && !config.getHintText().isEmpty()) {
            TextView hint = new TextView(activity);
            hint.setText(config.getHintText());
            container.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        container.addView(input, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final android.app.AlertDialog[] holder = new android.app.AlertDialog[1];
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(activity)
            .setTitle(config.getButtonText() != null && config.getButtonText().length > 0
                && config.getButtonText()[0] != null && !config.getButtonText()[0].isEmpty()
                ? config.getButtonText()[0] : "输入")
            .setView(container)
            .setPositiveButton("OK", (d, w) -> {
                synchronized (finishLock) {
                    data = new KeyboardData(0, input.getText().toString());
                    finishLock.notifyAll();
                }
            })
            .setNegativeButton("取消", (d, w) -> {
                synchronized (finishLock) {
                    data = new KeyboardData(1, "");
                    finishLock.notifyAll();
                }
            })
            .setOnCancelListener(d -> {
                synchronized (finishLock) {
                    data = new KeyboardData(1, "");
                    finishLock.notifyAll();
                }
            });
        holder[0] = builder.create();
        try {
            holder[0].show();
        } catch (Throwable t) {
            synchronized (finishLock) {
                data = new KeyboardData(1, "");
                finishLock.notifyAll();
            }
        }
        return new KeyboardData(1, "");
    }

    /** Java 侧错误弹窗（native 不调用；保留上游 API）。 */
    public static void ShowError(String error) {
        NativeLibrary.displayAlertMsg("错误", error, false);
    }

    private final native ValidationError ValidateFilters(String p0);

    public final native ValidationError ValidateInput(String p0);

    /** Java→native 校验包装（金手指/按键输入过滤用）。 */
    public ValidationError validateFilters(String text) {
        return ValidateFilters(text);
    }
}
