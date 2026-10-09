package com.nesstation.app.core.jni

/**
 * Citra MMJ（3DS）核心库加载器 —— NesStation 集成。
 *
 * libmain.so（= 上游 Citra_MMJ_20250220.apk 的核心库，提取改名
 * libcitra_mmj.so 放 `app/src/main/jniLibs/arm64-v8a/`）的 JNI 符号按
 * Java_org_citra_emu_NativeLibrary_<method> 绑定到 vendored 契约类
 * `org.citra.emu.NativeLibrary`。本对象只负责 loadLibrary + 可用性探测，
 * 全部原生方法经 [lib] 访问。
 *
 * ★★★★★ 本 .so 是【二进制补丁版】，严禁用原版 APK 的 libmain.so 覆盖！★★★★★
 *
 * 原版 boot 主体带【包名指纹白名单】（libmain.so 反汇编实证，四道检查）：
 *   ① settings[0x490] == 原版 versionName "f805c929d"（9 字节，GOT 0x61e5f0）
 *   ② 包名 [0x478] ∈ {"org.citra.emu", "com.antutu.ABenchMark"}
 *   ③ 魔数 [0x4f0] == 0x00d660c4f937902a（经 JNI 调宿主 Java 方法获取）
 *   ④ 串 [0x4a8] == "Citra"（同上）
 * 任一失败 → 执行"降级清理块"（boot 主体 0x267534 / 0x267630 / 0x267684 /
 * 0x267708 四处），强制清零 use_gles[0x408]、[0x268]、[0x3fd]、[0x3ff]、
 * [0x2a8]、[0x418] 并写 [0]=1（受限模式）—— 渲染器工厂（0x3d4f54）因此
 * 选中桌面 GL 路径（RendererOpenGL），其后处理管线在 GLES 上下文不生效
 * →【全部后处理滤镜（全局 xbr/hqx 映射 + 自带 SEDI/FXAA/bloom 等）静默
 * 失效】= 用户报告的核心问题。原版 APK（包名 org.citra.emu）四检查全过，
 * 滤镜正常。
 *
 * 补丁内容（scripts/patch_libcitra_mmj.py，md5 1ef2fd20...）：
 *   1. 四个降级清理块整体 NOP（补丁后行为 = 原版白名单通过时行为）；
 *   2. 渲染器工厂 v1：0x3d4fac `cbz w8,+9` → `b +9`：无条件进入
 *      use_gles[0x408] 检查（原版先查能力位 [x23,#0x26a] 再查 use_gles）；
 *   3. 渲染器工厂 v2：0x3d4fec（Vulkan 分配块入口）→ `b 0x3d5004`，
 *      0x3d4ff0..0x3d5000 五条 NOP：即便 use_gles≠0 且虚方法能力位返回
 *      0（原路径 = Vulkan 渲染器 0x3e2254），也强制落入 OpenGL 渲染器
 *      0x4251a0（分配 0x258 + 构造）—— OpenGL 渲染器是唯一消费
 *      pp_shader_name → <userDir>/shaders/<name>.glsl 后处理链的渲染器
 *      （0x4254c4/0x4255d0，VFS RemoteFileOpen 打开失败静默回落直通）。
 *      补丁后 use_gles 值不再决定渲染器族：任何初始值都走 GL 后处理管线。
 *      use_gles 的 boot 初始值本身由 0x266b10 从运行时配置对象
 *      （0x61e580→0xf7d2b8，[+0x30] 字节 cset ne）推导，随设备/驱动不同。
 * 覆盖回未补丁 so = 滤镜失效复发。
 *
 * 仅提供 arm64-v8a（上游 MMJ 即 64 位 only）；32 位 / x86 进程加载
 * 失败时 [loaded] 为 false，UI 报告不可用而非崩溃（同 Azahar/DraStic）。
 */
object CitraMmjNative {

    @Volatile
    var loaded: Boolean = false
        private set

    @Volatile
    private var loadAttempted = false

    /** 契约类（首次引用即触发其类初始化）。 */
    val lib: org.citra.emu.NativeLibrary
        get() = org.citra.emu.NativeLibrary

    /**
     * 尝试加载 libcitra_mmj.so。重复调用安全（幂等）。
     */
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        synchronized(this) {
            if (loaded) return true
            if (loadAttempted) return false
            loadAttempted = true
            loaded = try {
                // 触发契约类 <clinit> 前先显式 loadLibrary，失败语义清晰
                System.loadLibrary("citra_mmj")
                true
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.e("CitraMmjNative", "Failed to load libcitra_mmj.so", e)
                false
            } catch (e: SecurityException) {
                false
            }
            return loaded
        }
    }
}
