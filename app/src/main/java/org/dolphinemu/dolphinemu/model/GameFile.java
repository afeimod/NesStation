package org.dolphinemu.dolphinemu.model;

/**
 * GC/Wii 游戏元信息 —— 与上游 org.dolphinemu.dolphinemu.model.GameFile 一致：
 * private GameFile(long pointer) 构造器（native NewObject 直接调用）+
 * 14 个实例 native getter（GameFileCache.addOrGet/getAllGames 返回的对象）。
 *
 * getPlatform(): 0=GameCube, 1=Wii, 2=WiiWare —— NesStation 用它判断
 * GC/Wii 控制器方案与游戏库徽标。
 */
public class GameFile {

    private long mPointer;

    /** native 构造入口（GameFileCache 返回指针时由 JNI NewObject 调用）。 */
    private GameFile(long pointer) {
        mPointer = pointer;
    }

    public native int[] getBanner();

    public native int getBannerHeight();

    public native int getBannerWidth();

    public native String getCompany();

    public native int getCountry();

    public native int getDiscNumber();

    public native String getGameId();

    public native String getGameTdbId();

    public native String getName();

    public native String getPath();

    public native int getPlatform();

    public native int getRegion();

    public native int getRevision();

    public native String getTitlePath();

    /** 平台判定辅助：0=GameCube，1=Wii，2=WiiWare。 */
    public boolean isWii() {
        return getPlatform() != 0;
    }
}
