package org.citra.citra_emu.utils;

/**
 * 3DS 系统配置（System Settings applet 数据）—— 与上游
 * org.citra.citra_emu.utils.SystemSaveGame（Kotlin object）保持一致的
 * JVM 形状：static INSTANCE + 实例 native 方法。
 *
 * NesStation 3DS 设置页的「系统配置」项（用户名/生日/语言/家长控制外的
 * 本体设置）直接读写该对象；数据落盘在 <userDir>/nand/SystemSaveData/。
 */
public final class SystemSaveGame {

    public static final SystemSaveGame INSTANCE = new SystemSaveGame();

    private SystemSaveGame() {
    }

    public final native String getUsername();

    public final native void setUsername(String username);

    public final native short[] getBirthday();

    public final native void setBirthday(short month, short day);

    public final native short getSystemLanguage();

    public final native void setSystemLanguage(short language);

    public final native short getSoundOutputMode();

    public final native void setSoundOutputMode(short mode);

    public final native short getCountryCode();

    public final native void setCountryCode(short code);

    public final native int getPlayCoins();

    public final native void setPlayCoins(int coins);

    public final native String getMac();

    public final native void regenerateMac();

    public final native long getConsoleId();

    public final native void regenerateConsoleId();

    public final native boolean getIsSystemSetupNeeded();

    public final native void setSystemSetupNeeded(boolean setupNeeded);

    public final native int getCountryCompatibility(int countryCode);

    public final native void load();

    public final native void save();
}
