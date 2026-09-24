package org.dolphinemu.dolphinemu.model;

/**
 * GameFile —— libishiiruka.so FindClass 目标桩类。
 *
 * so 内含 Java_org_dolphinemu_dolphinemu_model_GameFile_* 导出符号，
 * 并在部分路径上 FindClass("org/dolphinemu/dolphinemu/model/GameFile")。
 * NesStation 游戏库走自研实现，不消费本类；提供空壳保证
 * native 侧 FindClass 命中（返回 null 会触发 native 判空分支甚至 abort）。
 */
public final class GameFile
{
        private GameFile() {}
}
