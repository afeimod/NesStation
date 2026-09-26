package org.dolphinemu.dolphinemu.model;

/**
 * GameFileCache —— libishiiruka.so 符号目标桩类。
 * so 内含 Java_org_dolphinemu_dolphinemu_model_GameFileCache_* 导出符号。
 * NesStation 游戏库走自研实现；本类保留契约形状并暴露 addOrGet 供
 * NGC/WII 平台判定使用（NesStation 集成补丁）。
 */
public final class GameFileCache
{
        private GameFileCache() {}

        /**
         * 把路径加入核心 GameFileCache（不存在则创建并解析卷头），
         * 返回包装 C++ GameFile 指针的 Java 对象（不识别时返回 null）。
         *
         * so 导出符号 Java_org_dolphinemu_dolphinemu_model_GameFileCache_addOrGet
         * （0xc6afc 反汇编实测：GetJString → GameFileCache 单例 AddOrGet →
         * NewLocalRef 返回 GameFile jobject）。返回对象的 {@link GameFile#getPlatform}
         * 可精确区分 GameCube / Wii（覆盖 rvz/wbfs/gcz 等全部容器格式）。
         */
        public static native GameFile addOrGet(String path);
}
