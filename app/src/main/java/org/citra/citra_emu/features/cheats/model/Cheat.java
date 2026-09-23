package org.citra.citra_emu.features.cheats.model;

/**
 * Azahar 金手指条目 —— 与上游 org.citra.citra_emu.features.cheats.model.Cheat
 * 保持一致的 JVM 形状（静态 JNI 导出绑定）。
 *
 * createGatewayCode / isValidGatewayCode 为 static native；其余为实例 native，
 * 经核心侧 mPointer 读写。NesStation 金手指编辑器用 CheatEngine 装载/保存
 * 游戏的金手指文件（<userDir>/cheats/<titleId>.txt）。
 */
public final class Cheat {

    private Runnable enabledChangedCallback;
    private final long mPointer;

    private Cheat(long pointer) {
        this.mPointer = pointer;
    }

    public static final native Cheat createGatewayCode(String name, String notes, String code);

    public static final native int isValidGatewayCode(String code);

    private final native void setEnabledImpl(boolean enabled);

    protected final native void finalize();

    public final native String getName();

    public final native String getNotes();

    public final native String getCode();

    public final native boolean getEnabled();

    public final void setEnabled(boolean enabled) {
        setEnabledImpl(enabled);
        Runnable callback = enabledChangedCallback;
        if (callback != null) callback.run();
    }

    public final void setEnabledChangedCallback(Runnable callback) {
        this.enabledChangedCallback = callback;
    }

    private final void onEnabledChanged() {
        Runnable callback = enabledChangedCallback;
        if (callback != null) callback.run();
    }
}
