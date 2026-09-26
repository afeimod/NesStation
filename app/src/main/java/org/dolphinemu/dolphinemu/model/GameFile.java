package org.dolphinemu.dolphinemu.model;

/**
 * GameFile —— libishiiruka.so FindClass 目标桩类。
 *
 * 【JNI_OnLoad 契约（反汇编 0xc629c 实测）】
 *   1. FindClass("org/dolphinemu/dolphinemu/model/GameFile")；
 *   2. GetFieldID(env, GameFile, "mPointer", "J")；
 *   3. GetMethodID(env, GameFile, "<init>", "(J)V") —— 必须有单 long 参
 *      构造器（原生侧 GameFileCache native 用 NewObject 包装 C++ 指针）。
 *   空壳私有无参构造器不满足 (3)，构造器签名不匹配 → NoSuchMethodError
 *   → pending exception → SIGABRT 启动闪退。
 *
 * so 内含 Java_org_dolphinemu_dolphinemu_model_GameFile_* 导出符号。
 * NesStation 游戏库走自研实现，不消费本类；保留契约形状即可。
 */
public final class GameFile
{
        /** JNI 契约字段：指向 C++ GameFile 的指针（上游 mPointer）。 */
        @SuppressWarnings("unused")
        private long mPointer;

        /** JNI 契约构造器：包装原生指针（NewObject 目标）。 */
        public GameFile(long pointer)
        {
                this.mPointer = pointer;
        }

        /**
         * 查询游戏平台（NesStation 集成补丁）：
         * 0 = GameCube，1 = Wii 光盘，2 = WiiWare/WAD/ELF。
         *
         * so 导出符号 Java_org_dolphinemu_dolphinemu_model_GameFile_getPlatform
         * （0xc64dc 反汇编实测：经缓存的 mPointer FieldID 取 C++ GameFile*，
         * 读取对象偏移 0x130 的 int 字段返回）。IshiirukaEngine 用它做
         * NGC/Wii 平台判定 —— 修复 Java 兑底 GetPlatform 恒返 0 导致的
         * auto 控制模式失效。
         */
        public native int getPlatform();
}
