package org.dolphinemu.dolphinemu.utils;

/**
 * DirectoryInitialization —— libishiiruka.so 符号目标桩类。
 *
 * 【JNI 契约（导出符号 + 反汇编 0xce064 实测）】
 *   - Java_org_dolphinemu_dolphinemu_utils_DirectoryInitialization_CreateUserDirectories
 *     （无参，反汇编 0xce0bc 尾跳证实）；
 *   - Java_org_dolphinemu_dolphinemu_utils_DirectoryInitialization_SetSysDirectory
 *     （单 jstring 参数，0xce074 mov x1, x2 → jstring 转换证实）。
 * native 方法必须声明在【本类】上（符号按类名解析）—— 声明在其它类
 * （如 org.dolphinemu.ishiiruka.services.DirectoryInitializationService）
 * 上会因符号名不匹配抛 UnsatisfiedLinkError。
 *
 * NesStation 目录初始化由 DirectoryInitializationService 委托调用本类。
 */
public final class DirectoryInitialization
{
        private DirectoryInitialization() {}

        /** 在 User 目录下建立 GC / Wii / Config / Cache / StateSaves 等标准结构。 */
        public static native void CreateUserDirectories();

        /** 告知原生层 Sys 数据目录（GC 字体 / DSP ROM / Wii shared 字体等）。 */
        public static native void SetSysDirectory(String path);
}
