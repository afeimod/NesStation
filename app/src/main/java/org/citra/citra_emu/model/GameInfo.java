package org.citra.citra_emu.model;

/**
 * 3DS 游戏元信息 —— 与上游 org.citra.citra_emu.model.GameInfo（Kotlin 类）
 * 保持一致的 JVM 形状：构造器 GameInfo(String path) → native initialize(path)
 * 返回核心侧元信息指针，其余 getter/setter 全部经该指针读写。
 *
 * NesStation 用它做：
 *  - 3DS 游戏扫描元数据（标题/公司/图标/区域）
 *  - ★ 加密检测（isEncrypted）—— 引导用户导入 aes_keys.txt 或先解密
 */
public final class GameInfo {

    private final long pointer;

    public GameInfo(String path) {
        this.pointer = initialize(path);
    }

    private static final native long initialize(String path);

    protected final native void finalize();

    public final native boolean isValid();

    public final native String getTitle();

    public final native String getCompany();

    public final native long getTitleID();

    public final native int[] getIcon();

    public final native String getRegions();

    public final native String getFileType();

    public final native boolean isEncrypted();

    public final native boolean isSystemTitle();

    public final native boolean getIsVisibleSystemTitle();

    public final native boolean getIsInsertable();
}
