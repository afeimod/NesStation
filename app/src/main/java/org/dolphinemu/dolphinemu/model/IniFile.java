package org.dolphinemu.dolphinemu.model;

/**
 * IniFile —— libishiiruka.so 符号目标桩类。
 * so 内含 Java_org_dolphinemu_dolphinemu_model_IniFile_* 导出符号。
 *
 * 【JNI_OnLoad 契约（反汇编 0xc6230 实测）】FindClass 本类后立即
 *   GetFieldID(env, IniFile, "mPointer", "J") 按名查找实例字段 ——
 *   空壳类缺少该字段会在 JNI_OnLoad 抛 NoSuchFieldError → pending
 *   exception → 下一次 FindClass 触发 ART 断言 → SIGABRT 启动闪退。
 *   字段访问级别不影响 JNI 查找（GetFieldID 不检查访问修饰符）。
 *
 * NesStation 不消费本类（配置读写走自研实现）；仅保留契约字段。
 */
public final class IniFile
{
        /** JNI 契约字段：指向 C++ IniFile 的指针（上游 mPointer）。 */
        @SuppressWarnings("unused")
        private long mPointer;

        public IniFile() {}
}
