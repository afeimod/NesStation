package org.citra.citra_emu.features.cheats.model;

/**
 * 金手指引擎 —— 与上游 org.citra.citra_emu.features.cheats.model.CheatEngine
 * 一致：实例 native（经核心侧按 titleId 打开当前游戏的金手指表）。
 */
public final class CheatEngine {

    public CheatEngine() {
    }

    public final native void loadCheatFile(long titleId);

    public final native void saveCheatFile(long titleId);

    public final native Cheat[] getCheats();

    public final native void addCheat(Cheat cheat);

    public final native void removeCheat(int index);

    public final native void updateCheat(int index, Cheat cheat);
}
