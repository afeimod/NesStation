package org.citra.citra_emu.model;

/**
 * 3DS 标题媒体类型（与上游 org.citra.citra_emu.model.Game$MediaType 一致）。
 * 仅 NesStation/宿主侧使用；native 不直接引用该枚举。
 */
public final class Game {

    public enum MediaType {
        Game,
        Update,
        AddOn
    }

    private Game() {
    }
}
