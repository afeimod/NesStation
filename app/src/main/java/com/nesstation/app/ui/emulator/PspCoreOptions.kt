package com.nesstation.app.ui.emulator

/**
 * ★★ PPSSPP（libretro 核心）完整核心选项目录 —— NesStation 集成 ★★
 *
 * 与 PPSSPP 官方 libretro/libretro.cpp 的 check_variables()（buildbot
 * nightly 同源）逐一核对：每个键的合法取值字符串必须与核心里
 * strcmp() 比较的字符串【逐字节一致】（大小写敏感！），否则核心
 * 静默忽略 —— 历史教训：
 *   · "jit" ≠ "JIT"（小写被忽略 → 核心退回解释器 → 严重卡顿）；
 *   · 纯数字 "1" 不在 texture_scaling_level 的枚举里（应为 "disabled"）；
 *   · "ppsspp_rendering_mode" / "ppsspp_audio_latency" 键根本不存在
 *     （渲染模式对应 skip_buffer_effects；音频延迟由前端 AudioTrack 控制）。
 *
 * 设置通道（与 RetroArch 同构）：
 *   设置面板 → PspSettingsStore.writeCoreOption（<filesDir>/psp_core_options.ini）
 *            → PspEngine.loadRom 前 applyCoreOptions() 整表灌入核心；
 *   会话中修改 → PspEngine.setCoreOption（持久化 + PspNative.setCoreOption
 *   直推，核心经 GET_VARIABLE_UPDATE 每帧感知，图形选项即时生效）。
 *
 * 分组（group）用于设置面板分节展示：
 *   GFX 画面 / TEX 纹理 / PERF 性能 / SYS 系统 / PAD 摇杆 / NET 联机
 */
object PspCoreOptions {

    /** 单个核心选项定义。 */
    data class Def(
        val key: String,                    // ppsspp_* 核心选项键
        val label: String,                  // 设置面板显示名
        val group: String,                  // 分组（GFX/TEX/PERF/SYS/PAD/NET）
        val default: String,                // 默认值（空串 = 不下发，用核心默认）
        val choices: List<Pair<String, String>>, // (值, 显示文案)
        val note: String = ""               // 可选说明（面板副标题）
    )

    // ---- 分组名 ----
    const val G_GFX = "GFX"
    const val G_TEX = "TEX"
    const val G_PERF = "PERF"
    const val G_SYS = "SYS"
    const val G_PAD = "PAD"
    const val G_NET = "NET"

    // ---- 常用布尔枚举 ----
    private val EN = listOf("enabled" to "开启", "disabled" to "关闭")
    private val DE = listOf("disabled" to "关闭", "enabled" to "开启")

    /** 完整目录（顺序 = 面板展示顺序）。 */
    val CATALOG: List<Def> = listOf(

        // ─────────────── 画面 ───────────────
        Def("ppsspp_internal_resolution", "渲染分辨率", G_GFX, "480x272",
            listOf(
                "480x272" to "1x (480x272·最流畅·默认)", "960x544" to "2x (960x544)",
                "1440x816" to "3x (1440x816)", "1920x1088" to "4x (1920x1088)",
                "2400x1360" to "5x (2400x1360)", "2880x1632" to "6x (2880x1632)",
                "3360x1904" to "7x (3360x1904)", "3840x2176" to "8x (3840x2176)",
                "4320x2448" to "9x (4320x2448)", "4800x2720" to "10x (4800x2720)"
            ),
            "PSP 原生 1x；高分辨率=更清晰但更吃 GPU（官方 PPSSPP 默认 1x）"),
        Def("ppsspp_skip_buffer_effects", "渲染模式", G_GFX, "disabled",
            listOf(
                "disabled" to "缓冲渲染 (默认·兼容性最好)",
                "enabled" to "跳过帧缓冲效果 (更快·部分游戏异常)"
            ),
            "即官方 Non-Buffered Rendering；追求速度可试，画面异常请改回"),
        Def("ppsspp_skip_gpu_readbacks", "跳过 GPU 回读", G_GFX, "disabled",
            DE, "开启可提速，个别游戏特效/画面会异常"),
        Def("ppsspp_frameskip", "跳帧", G_GFX, "0",
            (0..10).map { it.toString() to if (it == 0) "0 (不跳帧·默认)" else "$it" }),
        Def("ppsspp_auto_frameskip", "自动跳帧", G_GFX, "disabled", DE),
        Def("ppsspp_frame_duplication", "帧重复", G_GFX, "disabled",
            DE, "30fps 游戏补足 60 帧；开启增加 GPU 负担"),
        Def("ppsspp_lower_resolution_for_effects", "特效降分辨率", G_GFX, "disabled",
            listOf(
                "disabled" to "关闭 (默认)", "Safe" to "Safe (保守)",
                "Balanced" to "Balanced (均衡)", "Aggressive" to "Aggressive (激进·最快)"
            ),
            "Bloom 等全屏特效以低分辨率计算（官方 Bloom Hack）"),
        Def("ppsspp_spline_quality", "曲线曲面质量", G_GFX, "Low",
            listOf("Low" to "Low (最快·默认)", "Medium" to "Medium", "High" to "High (最平滑)")),
        Def("ppsspp_cropto16x9", "画面裁剪至 16:9", G_GFX, "disabled", DE),
        Def("ppsspp_gpu_hardware_transform", "硬件顶点变换", G_GFX, "enabled",
            DE, "性能关键项，无特殊兼容问题请保持开启"),
        Def("ppsspp_mulitsample_level", "多重采样 (MSAA)", G_GFX, "Disabled",
            listOf(
                "Disabled" to "Disabled (默认·最快)", "x2" to "x2",
                "x4" to "x4", "x8" to "x8"
            ),
            "★ 官方键名拼写就是 mulitsample（官方 typo）；抗锯齿，吃 GPU"),
        Def("ppsspp_detect_vsync_swap_interval", "检测帧率变化", G_GFX, "disabled",
            DE, "自动跟踪刷新率变化；跳帧/帧重复开启时核心自动隐藏此项"),
        Def("ppsspp_backend", "图形后端 (高级)", G_GFX, "auto",
            listOf(
                "auto" to "auto (跟随前端·推荐)",
                "opengl" to "opengl (本前端唯一支持)"
            ),
            "本前端只实现了 OpenGL ES 硬件渲染路径；留 auto 即可"),
        Def("ppsspp_software_rendering", "软件渲染", G_GFX, "disabled",
            DE, "排错用：完全绕过 GPU（极慢，强制 1x）"),

        // ─────────────── 纹理 ───────────────
        Def("ppsspp_texture_scaling_level", "纹理放大", G_TEX, "disabled",
            listOf(
                "disabled" to "关闭 (默认·最快)", "2x" to "2x", "3x" to "3x",
                "4x" to "4x", "5x" to "5x"
            ),
            "xBRZ 等算法放大纹理，非常吃 CPU/GPU——卡顿请保持关闭"),
        Def("ppsspp_texture_scaling_type", "纹理放大算法", G_TEX, "xbrz",
            listOf(
                "xbrz" to "xBRZ (默认)", "hybrid" to "Hybrid",
                "bicubic" to "Bicubic", "hybrid_bicubic" to "Hybrid + Bicubic"
            )),
        Def("ppsspp_texture_deposterize", "纹理去色带", G_TEX, "disabled", DE,
            "仅纹理放大开启时有意义"),
        Def("ppsspp_texture_filtering", "纹理过滤", G_TEX, "Auto",
            listOf(
                "Auto" to "Auto (自动·默认)", "Nearest" to "Nearest (最近邻·像素风)",
                "Linear" to "Linear (线性)", "Auto max quality" to "Auto max quality (最高质量)"
            )),
        Def("ppsspp_smart_2d_texture_filtering", "智能 2D 过滤", G_TEX, "disabled", DE,
            "2D UI 文字更锐利"),
        Def("ppsspp_texture_anisotropic_filtering", "各向异性过滤", G_TEX, "disabled",
            listOf(
                "disabled" to "关闭 (默认)", "2x" to "2x", "4x" to "4x",
                "8x" to "8x", "16x" to "16x"
            )),
        Def("ppsspp_texture_shader", "纹理着色器 (硬件放大)", G_TEX, "disabled",
            listOf(
                "disabled" to "关闭 (默认)", "2xBRZ" to "2xBRZ",
                "4xBRZ" to "4xBRZ", "MMPX" to "MMPX"
            ),
            "GPU 上做纹理放大（与上面的 CPU 放大二选一）"),
        Def("ppsspp_texture_replacement", "高清纹理替换", G_TEX, "disabled", DE,
            "读取 textures/ 下的替换包"),

        // ─────────────── 性能 / CPU ───────────────
        Def("ppsspp_cpu_core", "CPU 核心", G_PERF, "JIT",
            listOf(
                "JIT" to "JIT (默认·最快·动态重编译)",
                "IR JIT" to "IR JIT (中间层·稍慢)",
                "Interpreter" to "Interpreter (解释器·极慢·排错用)"
            ),
            "★ 必须 JIT 才能满速运行；改后重启游戏生效"),
        Def("ppsspp_fast_memory", "快速内存访问", G_PERF, "enabled",
            DE, "JIT 加速关键项，保持开启"),
        Def("ppsspp_ignore_bad_memory_access", "忽略非法内存访问", G_PERF, "enabled", DE),
        Def("ppsspp_locked_cpu_speed", "锁定 PSP CPU 时钟", G_PERF, "0",
            listOf(
                "0" to "不锁定 (默认·跟随游戏)", "111" to "111MHz (省电)",
                "222" to "222MHz (PSP 标准)", "266" to "266MHz", "333" to "333MHz (最高)"
            )),
        Def("ppsspp_io_timing_method", "I/O 时序模拟", G_PERF, "Fast",
            listOf(
                "Fast" to "Fast (最快·默认)", "Host" to "Host (跟随主机)",
                "Simulate UMD delays" to "模拟 UMD 延迟",
                "Simulate UMD slow reading speed" to "模拟 UMD 慢速读盘"
            )),
        Def("ppsspp_inflight_frames", "GPU 帧缓冲深度", G_PERF, "",
            listOf(
                "" to "核心默认 (推荐)", "No buffer" to "无缓冲 (最低延迟)",
                "Up to 1" to "最多 1 帧 (折中)"
            ),
            "降低输入延迟可试\"无缓冲\"，闪退/花屏请回默认"),
        Def("ppsspp_cache_iso", "整盘镜像缓存进内存", G_PERF, "disabled",
            DE, "大幅加快读盘，但需 1GB+ 空闲内存"),
        Def("ppsspp_force_lag_sync", "强制联机滞后同步", G_PERF, "disabled", DE),

        // ─────────────── 系统 ───────────────
        Def("ppsspp_language", "游戏内系统语言", G_SYS, "Chinese Simplified",
            listOf(
                "Chinese Simplified" to "简体中文 (默认)",
                "Chinese Traditional" to "繁體中文",
                "Automatic" to "自动 (跟随前端·当前为英文)",
                "English" to "English", "Japanese" to "日本語",
                "French" to "Français", "Spanish" to "Español",
                "German" to "Deutsch", "Italian" to "Italiano",
                "Dutch" to "Nederlands", "Portuguese" to "Português",
                "Russian" to "Русский", "Korean" to "한국어"
            ),
            "游戏内系统对话框/菜单文字（需 PPSSPP lang 资产已播种）"),
        Def("ppsspp_psp_model", "PSP 型号", G_SYS, "",
            listOf(
                "" to "核心默认",
                "psp_1000" to "PSP-1000 (Fat·32MB 内存)",
                "psp_2000_3000" to "PSP-2000/3000 (Slim·64MB 内存)"
            ),
            "个别游戏需要 Slim 的 64MB 内存"),
        Def("ppsspp_memstick_inserted", "记忆棒插入状态", G_SYS, "enabled",
            EN, "极少数游戏需要拔出记忆棒的状态（保持默认即可）"),
        Def("ppsspp_button_preference", "确认键偏好", G_SYS, "Cross",
            listOf("Cross" to "✕ 确认 (日式·默认)", "Circle" to "○ 确认 (美式)"),
            "影响游戏内\"确认/取消\"方位"),
        Def("ppsspp_cheats", "金手指", G_SYS, "disabled", DE,
            "开启后可用 CFGLDR/.db 金手指（放到 PSP/Cheats/）"),

        // ─────────────── 摇杆 ───────────────
        Def("ppsspp_analog_deadzone", "摇杆死区", G_PAD, "0.15",
            listOf(
                "0.00" to "0 (无死区)", "0.05" to "0.05", "0.10" to "0.10",
                "0.15" to "0.15 (默认)", "0.20" to "0.20", "0.30" to "0.30", "0.40" to "0.40"
            ),
            "摇杆漂移就调大"),
        Def("ppsspp_analog_sensitivity", "摇杆灵敏度", G_PAD, "1.00",
            listOf(
                "0.80" to "0.80x", "0.90" to "0.90x", "1.00" to "1.00x (默认)",
                "1.10" to "1.10x", "1.20" to "1.20x", "1.30" to "1.30x"
            )),
        Def("ppsspp_analog_is_circular", "圆形摇杆限制", G_PAD, "disabled", DE,
            "方形行程→圆形行程，圆盘手感更自然"),

        // ─────────────── 联机 (Ad-hoc / 基础设施) ───────────────
        Def("ppsspp_enable_wlan", "WLAN (Ad-hoc 联机)", G_NET, "disabled", DE,
            "开启后游戏内可用联机功能"),
        Def("ppsspp_wlan_channel", "WLAN 信道", G_NET, "0",
            listOf("0" to "自动 (默认)", "1" to "1", "6" to "6", "11" to "11")),
        Def("ppsspp_enable_builtin_pro_ad_hoc_server", "内置 PRO ad-hoc 服务器", G_NET, "disabled", DE,
            "本机作 ad-hoc 服务器（同一局域网多设备联机）"),
        Def("ppsspp_enable_upnp", "UPnP 端口映射", G_NET, "disabled", DE),
        Def("ppsspp_upnp_use_original_port", "UPnP 使用原始端口", G_NET, "disabled", DE),
        Def("ppsspp_forced_first_connect", "强制首次连接", G_NET, "disabled", DE,
            "联机卡在\"等待加入\"时尝试开启"),
        Def("ppsspp_port_offset", "端口偏移", G_NET, "0",
            (0..9).map { it.toString() to "$it" },
            "同机多开联机时错开端口"),
        Def("ppsspp_minimum_timeout", "最小超时 (秒)", G_NET, "0",
            listOf("0" to "核心默认", "1" to "1", "2" to "2", "5" to "5", "10" to "10"))
    )

    /** 按分组名取该组选项（保持目录顺序）。 */
    fun group(group: String): List<Def> = CATALOG.filter { it.group == group }

    /** 键 → Def 索引。 */
    private val byKey: Map<String, Def> = CATALOG.associateBy { it.key }

    fun def(key: String): Def? = byKey[key]

    /**
     * 会话启动时应下发的完整键值表：
     * 存储值优先，缺省用目录默认值；空串 = 不下发（留给核心默认）。
     */
    fun effectiveValues(stored: Map<String, String>): List<Pair<String, String>> =
        CATALOG.mapNotNull { def ->
            val v = stored[def.key] ?: def.default
            if (v.isEmpty()) null else def.key to v
        }
}
