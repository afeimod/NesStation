package org.dolphinemu.dolphinemu.model;

/**
 * GC/Wii 游戏缓存 —— 与上游 org.dolphinemu.dolphinemu.model.GameFileCache 一致：
 * native init() 读取 <userDir>/GameListCache 的游戏库缓存；update(paths)
 * 扫描新增路径；getAllGames() 返回全部 GameFile。
 *
 * NesStation NGC/WII 游戏扫描用它拿标题/公司/平台(0=GC,1=Wii,2=WiiWare)，
 * 为库页提供准确的游戏名与 GC/Wii 判定（.gcm/.iso 双用途消歧）。
 */
public class GameFileCache {

    public GameFileCache() {
        init();
    }

    private static native void init();

    private native boolean save();

    private native boolean update(String[] paths);

    private native boolean updateAdditionalMetadata();

    public native GameFile addOrGet(String path);

    public native GameFile[] getAllGames();

    public native boolean load();
}
