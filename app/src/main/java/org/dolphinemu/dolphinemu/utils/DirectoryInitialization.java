package org.dolphinemu.dolphinemu.utils;

/**
 * DirectoryInitialization —— libishiiruka.so 符号目标桩类。
 * so 内含 Java_org_dolphinemu_dolphinemu_utils_DirectoryInitialization_*
 * （CreateUserDirectories / SetSysDirectory）导出符号。
 * NesStation 目录初始化由自研 DirectoryInitializationService 承担，
 * 本类为空壳，保证 native 侧类查找命中。
 */
public final class DirectoryInitialization
{
        private DirectoryInitialization() {}
}
