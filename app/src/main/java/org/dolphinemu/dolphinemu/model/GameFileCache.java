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
         * 创建原生 GameFileCache 单例（NesStation 集成补丁）。
         *
         * ★ NGC/WII 闪退修复（反汇编 0xc699c 实测）：so 导出
         * Java_org_dolphinemu_dolphinemu_model_GameFileCache_init —— 分配
         * 0x48 字节的 GameFileCache 对象（vtable + 内部列表初始化）并把
         * 指针写入全局单例槽 0x80add8。而 addOrGet 直接解引用该槽
         * （0xc6b20 ldr → 0x2553c8 ldr x28,[x22,#0x18]!）—— 单例为 null
         * 时立即 SIGSEGV。NesStation 旧实现从未调用 init()，overlay 渲染
         * 线程每次查询 effectiveMode() → isGameCubeGame() → addOrGet
         * 都踩空指针，这就是 "NGC/WII 彻底闪退" 的根因（tombstone
         * #00 pc 0x2553c8 与本函数调用链完全吻合）。
         *
         * 幂等：重复调用会重建单例并释放旧对象（引用计数处理），每局
         * 调用一次是安全且推荐的做法（loadRom 时调用）。
         */
        public static native void init();

        /**
         * 把路径加入核心 GameFileCache（不存在则创建并解析卷头），
         * 返回包装 C++ GameFile 指针的 Java 对象（不识别时返回 null）。
         *
         * ⚠ 前置条件：必须先调用 [init] 创建原生单例，否则空指针解引用
         * 直接 SIGSEGV（Java try/catch 拦不住 native 崩溃）。
         *
         * so 导出符号 Java_org_dolphinemu_dolphinemu_model_GameFileCache_addOrGet
         * （0xc6afc 反汇编实测：GetJString → GameFileCache 单例 AddOrGet →
         * NewLocalRef 返回 GameFile jobject）。返回对象的 {@link GameFile#getPlatform}
         * 可精确区分 GameCube / Wii（覆盖 rvz/wbfs/gcz 等全部容器格式）。
         */
        public static native GameFile addOrGet(String path);
}
