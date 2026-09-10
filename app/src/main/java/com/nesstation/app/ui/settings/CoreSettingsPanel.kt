package com.nesstation.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nesstation.app.core.model.GamePlatform
import com.nesstation.app.core.storage.PadLayout
import com.nesstation.app.core.storage.JAVA_MAPPABLE_BUTTONS
import com.nesstation.app.core.storage.JAVA_PHONE_KEY_OPTIONS
import com.nesstation.app.core.storage.javaButtonKeyMapGet
import com.nesstation.app.core.storage.javaButtonKeyMapSet
import com.nesstation.app.ui.emulator.Psx2BiosImportSection

/**
 * 核心设置子页：进入后展示该核心专属的模拟器选项。
 * 每个 DropdownRow 修改后通过 updateLayout 保存到 PadLayoutStore，
 * 并在游戏启动/运行时应用到对应核心引擎。
 */
@Composable
fun CoreSettingsPanel(
    platform: GamePlatform,
    padLayout: PadLayout,
    updateLayout: (PadLayout) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        when (platform) {
            GamePlatform.NES -> item {
                SettingsSection("FC / NES (FCEUmm)") {
                    DropdownRow("NTSC 滤镜",
                        listOf("disabled" to "关闭", "composite" to "复合", "svideo" to "S-Video",
                               "rgb" to "RGB", "monochrome" to "黑白"),
                        padLayout.ntscFilter
                    ) { updateLayout(padLayout.copy {ntscFilter = it}) }

                    DropdownRow("调色板",
                        listOf(
                            "default" to "默认", "asqrealc" to "AspiringSquire", "wii-vc" to "Wii VC",
                            "rgb" to "Nintendo RGB", "yuv-v3" to "FBX YUV-V3", "unsaturated-final" to "Unsaturated",
                            "sony-cxa2025as-us" to "Sony CXA", "pal" to "PAL", "bmf-final2" to "BMF Final 2",
                            "smooth-fbx" to "FBX Smooth", "composite-direct-fbx" to "FBX Composite",
                            "ntsc-hardware-fbx" to "FBX NTSC HW", "nes-classic-fbx" to "FBX NES Classic"
                        ),
                        padLayout.palette
                    ) { updateLayout(padLayout.copy {palette = it}) }

                    DropdownRow("裁剪过扫描",
                        listOf("disabled" to "关闭", "enabled" to "开启"),
                        padLayout.cropOverscan
                    ) { updateLayout(padLayout.copy {cropOverscan = it}) }

                    DropdownRow("区域",
                        listOf("Auto" to "自动", "NTSC" to "NTSC", "PAL" to "PAL", "Dendy" to "Dendy"),
                        padLayout.region
                    ) { updateLayout(padLayout.copy {region = it}) }

                    DropdownRow("超频(减少慢动作)",
                        listOf("disabled" to "关闭", "2x-Postrender" to "后渲染(兼容性好)", "2x-VBlank" to "VBlank(推荐·魂斗罗力量)"),
                        padLayout.overclocking
                    ) { updateLayout(padLayout.copy {overclocking = it}) }
                }
            }
            GamePlatform.SFC -> item {
                SettingsSection("SFC / SNES (Snes9x)") {
                    DropdownRow("画面比例",
                        listOf("4:3" to "4:3 (标准)", "uncorrected" to "8:7 (原始像素比)",
                               "auto" to "自动", "ntsc" to "NTSC", "pal" to "PAL"),
                        padLayout.aspectRatio
                    ) { updateLayout(padLayout.copy {aspectRatio = it}) }

                    DropdownRow("NTSC 滤镜",
                        listOf("disabled" to "关闭", "monochrome" to "黑白", "rf" to "RF",
                               "composite" to "复合", "s-video" to "S-Video", "rgb" to "RGB"),
                        padLayout.ntscFilter
                    ) { updateLayout(padLayout.copy {ntscFilter = it}) }

                    DropdownRow("裁剪过扫描",
                        listOf("enabled" to "开启", "disabled" to "关闭", "auto" to "自动"),
                        padLayout.sfcOverscan
                    ) { updateLayout(padLayout.copy {sfcOverscan = it}) }

                    DropdownRow("高分辨率模式",
                        listOf("enabled" to "开启", "disabled" to "关闭"),
                        padLayout.sfcGfxHires
                    ) { updateLayout(padLayout.copy {sfcGfxHires = it}) }

                    DropdownRow("透明效果",
                        listOf("enabled" to "开启", "disabled" to "关闭"),
                        padLayout.sfcGfxTransparency
                    ) { updateLayout(padLayout.copy {sfcGfxTransparency = it}) }

                    DropdownRow("图形裁剪",
                        listOf("enabled" to "开启", "disabled" to "关闭"),
                        padLayout.sfcGfxClip
                    ) { updateLayout(padLayout.copy {sfcGfxClip = it}) }

                    DropdownRow("允许无效VRAM访问",
                        listOf("disabled" to "开启 (允许)", "enabled" to "关闭 (禁止)"),
                        padLayout.sfcBlockInvalidVram
                    ) { updateLayout(padLayout.copy {sfcBlockInvalidVram = it}) }

                    DropdownRow("高分辨率混合",
                        listOf("disabled" to "关闭", "merge" to "合并", "blur" to "模糊"),
                        padLayout.sfcSideBySide
                    ) { updateLayout(padLayout.copy {sfcSideBySide = it}) }

                    DropdownRow("超频(SuperFX)",
                        listOf("100%" to "100% (默认)", "150%" to "150%", "200%" to "200%",
                               "300%" to "300%", "400%" to "400%", "500%" to "500%"),
                        padLayout.sfcOverclock
                    ) { updateLayout(padLayout.copy {sfcOverclock = it}) }

                    DropdownRow("减少精灵闪烁",
                        listOf("disabled" to "关闭", "enabled" to "开启"),
                        padLayout.sfcReduceSpriteFlicker
                    ) { updateLayout(padLayout.copy {sfcReduceSpriteFlicker = it}) }

                    DropdownRow("减少慢动作",
                        listOf("disabled" to "关闭", "light" to "轻微",
                               "compatible" to "兼容", "max" to "最大"),
                        padLayout.sfcReduceSlowdown
                    ) { updateLayout(padLayout.copy {sfcReduceSlowdown = it}) }

                    DropdownRow("音频插值",
                        listOf("gaussian" to "高斯(默认)", "cubic" to "三次", "sinc" to "Sinc",
                               "linear" to "线性", "none" to "无"),
                        padLayout.sfcAudioInterpolation
                    ) { updateLayout(padLayout.copy {sfcAudioInterpolation = it}) }

                    DropdownRow("回声缓冲Hack",
                        listOf("disabled" to "关闭", "enabled" to "开启(旧版Addmusic)"),
                        padLayout.sfcSoundOutput
                    ) { updateLayout(padLayout.copy {sfcSoundOutput = it}) }

                    DropdownRow("上下方向同时输入",
                        listOf("disabled" to "关闭", "enabled" to "开启"),
                        padLayout.sfcUpDownAllowed
                    ) { updateLayout(padLayout.copy {sfcUpDownAllowed = it}) }

                    DropdownRow("随机内存(不安全)",
                        listOf("disabled" to "关闭", "enabled" to "开启"),
                        padLayout.sfcSuperScope
                    ) { updateLayout(padLayout.copy {sfcSuperScope = it}) }

                    DropdownRow("BG图层 1",
                        listOf("enabled" to "显示", "disabled" to "隐藏"),
                        padLayout.sfcLayer1
                    ) { updateLayout(padLayout.copy {sfcLayer1 = it}) }

                    DropdownRow("BG图层 2",
                        listOf("enabled" to "显示", "disabled" to "隐藏"),
                        padLayout.sfcLayer2
                    ) { updateLayout(padLayout.copy {sfcLayer2 = it}) }

                    DropdownRow("BG图层 3",
                        listOf("enabled" to "显示", "disabled" to "隐藏"),
                        padLayout.sfcLayer3
                    ) { updateLayout(padLayout.copy {sfcLayer3 = it}) }

                    DropdownRow("BG图层 4",
                        listOf("enabled" to "显示", "disabled" to "隐藏"),
                        padLayout.sfcLayer4
                    ) { updateLayout(padLayout.copy {sfcLayer4 = it}) }

                    DropdownRow("精灵图层",
                        listOf("enabled" to "显示", "disabled" to "隐藏"),
                        padLayout.sfcLayer5
                    ) { updateLayout(padLayout.copy {sfcLayer5 = it}) }
                }
            }
            GamePlatform.GB, GamePlatform.GBA -> item {
                SettingsSection("GB / GBA (mGBA)") {
                    DropdownRow("主机型号",
                        listOf("Autodetect" to "自动", "Game Boy" to "Game Boy (DMG)",
                               "Super Game Boy" to "Super Game Boy", "Game Boy Color" to "Game Boy Color",
                               "Game Boy Advance" to "Game Boy Advance"),
                        padLayout.gbModel
                    ) { updateLayout(padLayout.copy {gbModel = it}) }

                    DropdownRow("SGB 边框",
                        listOf("ON" to "显示", "OFF" to "隐藏"),
                        padLayout.gbSgbBorders
                    ) { updateLayout(padLayout.copy {gbSgbBorders = it}) }

                    DropdownRow("GB色彩校正",
                        listOf("enabled" to "开启", "disabled" to "关闭"),
                        padLayout.gbColorCorrection
                    ) { updateLayout(padLayout.copy {gbColorCorrection = it}) }

                    DropdownRow("GB色彩预设",
                        listOf("default" to "默认", "AGB" to "GBA风格", "GB Pocket" to "Pocket风格",
                               "GB Light" to "亮色", "GB Original" to "原始"),
                        padLayout.gbcColorPreset
                    ) { updateLayout(padLayout.copy {gbcColorPreset = it}) }

                    DropdownRow("GBA色彩校正",
                        listOf("enabled" to "开启", "disabled" to "关闭"),
                        padLayout.gbaColorCorrection
                    ) { updateLayout(padLayout.copy {gbaColorCorrection = it}) }

                    DropdownRow("GBA色彩预设",
                        listOf("default" to "默认", "AGB" to "GBA原机", "GBA SP" to "GBA SP风格",
                               "GB Micro" to "GB Micro风格"),
                        padLayout.gbaColorPreset
                    ) { updateLayout(padLayout.copy {gbaColorPreset = it}) }

                    DropdownRow("帧混合",
                        listOf("OFF" to "关闭", "ON" to "开启", "fast" to "快速"),
                        padLayout.gbaFrameBlending
                    ) { updateLayout(padLayout.copy {gbaFrameBlending = it}) }

                    DropdownRow("音频重采样器",
                        listOf("nearest" to "最近邻(快速)", "sinc" to "Sinc(高质量)",
                               "cosine" to "余弦(均衡)", "cubic" to "三次(高质量)"),
                        padLayout.gbaAudioResampler
                    ) { updateLayout(padLayout.copy {gbaAudioResampler = it}) }

                    DropdownRow("低通滤波",
                        listOf("disabled" to "关闭", "enabled" to "开启"),
                        padLayout.gbaAudioLowPass
                    ) { updateLayout(padLayout.copy {gbaAudioLowPass = it}) }

                    DropdownRow("低通滤波范围",
                        listOf("20" to "20", "40" to "40", "60" to "60 (默认)",
                               "80" to "80", "100" to "100"),
                        padLayout.gbaAudioLowPassRange
                    ) { updateLayout(padLayout.copy {gbaAudioLowPassRange = it}) }

                    DropdownRow("跳帧类型",
                        listOf("disabled" to "关闭", "auto" to "自动跳帧", "fixed" to "固定跳帧"),
                        padLayout.gbaFrameskipType
                    ) { updateLayout(padLayout.copy {gbaFrameskipType = it}) }

                    DropdownRow("跳帧数量",
                        listOf("0" to "0", "1" to "1", "2" to "2", "3" to "3",
                               "4" to "4", "5" to "5", "6" to "6", "7" to "7",
                               "8" to "8", "9" to "9", "10" to "10"),
                        padLayout.gbaFrameskipCount
                    ) { updateLayout(padLayout.copy {gbaFrameskipCount = it}) }

                    DropdownRow("跳帧阈值(自动)",
                        listOf("10" to "10", "20" to "20", "33" to "33 (默认)",
                               "50" to "50", "70" to "70", "90" to "90"),
                        padLayout.gbaFrameskipThreshold
                    ) { updateLayout(padLayout.copy {gbaFrameskipThreshold = it}) }

                    DropdownRow("空闲优化",
                        listOf("disabled" to "关闭", "enabled" to "开启"),
                        padLayout.gbaIdleOptimization
                    ) { updateLayout(padLayout.copy {gbaIdleOptimization = it}) }

                    DropdownRow("允许相反方向",
                        listOf("OFF" to "关闭", "ON" to "开启"),
                        padLayout.gbaAllowOpposite
                    ) { updateLayout(padLayout.copy {gbaAllowOpposite = it}) }

                    DropdownRow("太阳能传感器",
                        listOf("0" to "0 (黑暗)", "1" to "1", "2" to "2", "3" to "3",
                               "4" to "4", "5" to "5 (中等)", "6" to "6", "7" to "7",
                               "8" to "8", "9" to "9", "10" to "10 (明亮)"),
                        padLayout.gbaSolarSensor
                    ) { updateLayout(padLayout.copy {gbaSolarSensor = it}) }

                    DropdownRow("强制RTC",
                        listOf("disabled" to "关闭", "enabled" to "开启"),
                        padLayout.gbaForceRTC
                    ) { updateLayout(padLayout.copy {gbaForceRTC = it}) }
                }
            }
            GamePlatform.MD -> item {
                SettingsSection("MD / SEGA (Genesis-Plus-GX)") {
                    DropdownRow("区域",
                        listOf("auto" to "自动", "ntsc-u" to "NTSC-U(美)",
                               "pal" to "PAL(欧)", "ntsc-j" to "NTSC-J(日)"),
                        padLayout.mdRegion
                    ) { updateLayout(padLayout.copy {mdRegion = it}) }

                    DropdownRow("系统型号",
                        listOf("auto" to "自动", "md" to "Mega Drive",
                               "sms" to "Master System", "gg" to "Game Gear", "sg" to "SG-1000"),
                        padLayout.mdSystem
                    ) { updateLayout(padLayout.copy {mdSystem = it}) }

                    DropdownRow("画面比例",
                        listOf("auto" to "自动", "4:3" to "4:3 (标准)",
                               "16:9" to "16:9", "stretch" to "全屏拉伸"),
                        padLayout.mdAspect
                    ) { updateLayout(padLayout.copy {mdAspect = it}) }

                    DropdownRow("渲染模式",
                        listOf("normal" to "普通", "double" to "双倍",
                               "interlaced" to "隔行扫描"),
                        padLayout.mdRender
                    ) { updateLayout(padLayout.copy {mdRender = it}) }

                    DropdownRow("NTSC滤镜",
                        listOf("disabled" to "关闭", "monochrome" to "黑白", "rf" to "RF",
                               "composite" to "复合", "s-video" to "S-Video", "rgb" to "RGB"),
                        padLayout.mdNtscFilter
                    ) { updateLayout(padLayout.copy {mdNtscFilter = it}) }

                    DropdownRow("LCD滤镜",
                        listOf("disabled" to "关闭", "enabled" to "开启"),
                        padLayout.mdLcdFilter
                    ) { updateLayout(padLayout.copy {mdLcdFilter = it}) }

                    DropdownRow("过扫描",
                        listOf("disabled" to "关闭", "enabled" to "开启"),
                        padLayout.mdOverscan
                    ) { updateLayout(padLayout.copy {mdOverscan = it}) }

                    DropdownRow("GG扩展屏幕",
                        listOf("disabled" to "关闭(原始160x144)", "enabled" to "开启(扩展256x144)"),
                        padLayout.mdGgExtra
                    ) { updateLayout(padLayout.copy {mdGgExtra = it}) }

                    DropdownRow("GG画面拉伸",
                        listOf("disabled" to "关闭", "enabled" to "开启"),
                        padLayout.mdGgStretch
                    ) { updateLayout(padLayout.copy {mdGgStretch = it}) }

                    DropdownRow("左侧边框",
                        listOf("disabled" to "关闭", "enabled" to "开启"),
                        padLayout.mdLeftBorder
                    ) { updateLayout(padLayout.copy {mdLeftBorder = it}) }

                    DropdownRow("手柄类型",
                        listOf("3 button" to "3键手柄(经典)", "6 button" to "6键手柄(街机)"),
                        padLayout.mdInput
                    ) { updateLayout(padLayout.copy {mdInput = it}) }

                    DropdownRow("允许上下同时输入",
                        listOf("disabled" to "关闭", "enabled" to "开启"),
                        padLayout.mdAllowUpDown
                    ) { updateLayout(padLayout.copy {mdAllowUpDown = it}) }

                    DropdownRow("超频",
                        listOf("100%" to "100%", "125%" to "125%",
                               "150%" to "150%", "200%" to "200%"),
                        padLayout.mdOverclock
                    ) { updateLayout(padLayout.copy {mdOverclock = it}) }

                    DropdownRow("跳帧",
                        listOf("0" to "0", "1" to "1", "2" to "2", "3" to "3", "4" to "4", "5" to "5"),
                        padLayout.mdFrameskip
                    ) { updateLayout(padLayout.copy {mdFrameskip = it}) }

                    DropdownRow("CD快速启动",
                        listOf("enabled" to "开启(跳过BIOS动画)", "disabled" to "关闭"),
                        padLayout.mdCdFastboot
                    ) { updateLayout(padLayout.copy {mdCdFastboot = it}) }

                    DropdownRow("FM音源",
                        listOf("auto" to "自动", "on" to "开启", "off" to "关闭"),
                        padLayout.mdSmsFm
                    ) { updateLayout(padLayout.copy {mdSmsFm = it}) }
                }
            }
            GamePlatform.PCE -> item {
                SettingsSection("PCE / TG16 (Geargrafx)") {
                    DropdownRow("主机型号",
                        listOf("Auto" to "自动", "PC Engine (JAP)" to "PC-Engine(日)",
                               "SuperGrafx (JAP)" to "SuperGrafx(日)",
                               "TurboGrafx-16 (USA)" to "TurboGrafx-16(美)"),
                        padLayout.pceConsoleType
                    ) { updateLayout(padLayout.copy {pceConsoleType = it}) }

                    DropdownRow("画面比例",
                        listOf("1:1 PAR" to "1:1 (像素方形)",
                               "4:3 DAR" to "4:3 (标准)",
                               "6:5 DAR" to "6:5",
                               "16:9 DAR" to "16:9", "16:10 DAR" to "16:10"),
                        padLayout.pceAspect
                    ) { updateLayout(padLayout.copy {pceAspect = it}) }

                    DropdownRow("过扫描",
                        listOf("Disabled" to "关闭", "Enabled" to "开启"),
                        padLayout.pceOverscan
                    ) { updateLayout(padLayout.copy {pceOverscan = it}) }

                    DropdownRow("精灵数限制",
                        listOf("Disabled" to "关闭(原始,可能有闪烁)", "Enabled" to "开启(消除闪烁)"),
                        padLayout.pceNoSpriteLimit
                    ) { updateLayout(padLayout.copy {pceNoSpriteLimit = it}) }

                    DropdownRow("调色板",
                        listOf("Standard RGB" to "标准RGB", "Turboxray" to "Turboxray", "Kitrinx" to "Kitrinx"),
                        padLayout.pcePalette
                    ) { updateLayout(padLayout.copy {pcePalette = it}) }

                    DropdownRow("允许上下同时输入",
                        listOf("Disabled" to "关闭", "Enabled" to "开启"),
                        padLayout.pceAllowUpDown
                    ) { updateLayout(padLayout.copy {pceAllowUpDown = it}) }

                    DropdownRow("TurboTap(5人多人)",
                        listOf("Disabled" to "关闭", "Enabled" to "开启"),
                        padLayout.pceTurbotap
                    ) { updateLayout(padLayout.copy {pceTurbotap = it}) }

                    DropdownRow("Memory Base 128",
                        listOf("Auto" to "自动", "Enabled" to "开启", "Disabled" to "关闭"),
                        padLayout.pceMb128
                    ) { updateLayout(padLayout.copy {pceMb128 = it}) }

                    DropdownRow("CD BIOS",
                        listOf("Auto" to "自动",
                               "System Card 1" to "System Card 1",
                               "System Card 2" to "System Card 2",
                               "System Card 3" to "System Card 3 (推荐)",
                               "Game Express" to "Games Express"),
                        padLayout.pceCdromBios
                    ) { updateLayout(padLayout.copy {pceCdromBios = it}) }
                }
            }
            GamePlatform.DOS -> item {
                // 键名/取值均已对照预编译 libdosbox_pure_libretro_android.so 与
                // 上游 dosbox-pure core_options.h 校验，无效旧项已移除。
                SettingsSection("DOS (DOSBox-Pure)") {
                    DropdownRow("显示芯片",
                        listOf(
                            "svga_s3" to "SVGA (S3 Trio64, 推荐)",
                            "vesa_nolfb" to "S3 Trio64 VESA 1.3",
                            "vesa_oldvbe" to "S3 Trio64 VESA 旧版",
                            "svga_et3000" to "Tseng ET3000",
                            "svga_et4000" to "Tseng ET4000",
                            "svga_paradise" to "Paradise PVGA1A",
                            "vgaonly" to "VGA Only",
                            "ega" to "EGA",
                            "cga" to "CGA",
                            "tandy" to "Tandy",
                            "pcjr" to "PCjr",
                            "hercules" to "Hercules"
                        ),
                        padLayout.dosMachine
                    ) { updateLayout(padLayout.copy {dosMachine = it}) }

                    DropdownRow("CPU 核心(性能)",
                        listOf(
                            "auto" to "自动(推荐)",
                            "dynamic" to "动态重编译(最快)",
                            "normal" to "普通解释器",
                            "simple" to "简化解释器(老游戏)"
                        ),
                        padLayout.dosCpuCore
                    ) { updateLayout(padLayout.copy {dosCpuCore = it}) }

                    DropdownRow("CPU 类型",
                        listOf(
                            "auto" to "自动 (推荐)",
                            "386" to "386 (快速)",
                            "386_slow" to "386 (带特权检查)",
                            "386_prefetch" to "386 (预取队列, 兼容老游戏)",
                            "486_slow" to "486 (慢速)",
                            "pentium_slow" to "Pentium (慢速)"
                        ),
                        padLayout.dosCpuType
                    ) { updateLayout(padLayout.copy {dosCpuType = it}) }

                    DropdownRow("内存大小(重启生效)",
                        listOf(
                            "4" to "4 MB", "8" to "8 MB", "16" to "16 MB (推荐)",
                            "24" to "24 MB", "32" to "32 MB"
                        ),
                        padLayout.dosMemorySize
                    ) { updateLayout(padLayout.copy {dosMemorySize = it}) }

                    DropdownRow("CPU 周期",
                        listOf(
                            "auto" to "自动(推荐)",
                            "max" to "最大",
                            "6000" to "6000 (80386)",
                            "10000" to "10000 (80486)",
                            "20000" to "20000 (Pentium)",
                            "40000" to "40000 (Pentium II)",
                            "80000" to "80000 (Pentium III)",
                            "custom" to "自定义"
                        ),
                        padLayout.dosCycles
                    ) { updateLayout(padLayout.copy {dosCycles = it}) }

                    if (padLayout.dosCycles == "custom") {
                        DropdownRow("自定义周期",
                            listOf("10000" to "10000", "20000" to "20000",
                                   "30000" to "30000", "50000" to "50000",
                                   "80000" to "80000", "100000" to "100000"),
                            padLayout.dosCyclesMax
                        ) { updateLayout(padLayout.copy {dosCyclesMax = it}) }
                    }

                    DropdownRow("声霸卡类型",
                        listOf(
                            "sb16" to "Sound Blaster 16 (推荐·默认)",
                            "sbpro2" to "Sound Blaster Pro 2",
                            "sbpro1" to "Sound Blaster Pro",
                            "sb2" to "Sound Blaster 2.0",
                            "none" to "关闭声音"
                        ),
                        padLayout.dosSbType
                    ) { updateLayout(padLayout.copy {dosSbType = it}) }

                    DropdownRow("混音器采样率(核心)",
                        listOf(
                            "48000" to "48000 Hz (推荐)",
                            "44100" to "44100 Hz",
                            "32000" to "32000 Hz",
                            "22050" to "22050 Hz",
                            "11025" to "11025 Hz",
                            "8000" to "8000 Hz",
                            "49716" to "49716 Hz (OPL 完美还原)"
                        ),
                        padLayout.dosAudiorate
                    ) { updateLayout(padLayout.copy {dosAudiorate = it}) }

                    DropdownRow("立体声反转",
                        listOf("false" to "关闭", "true" to "开启"),
                        padLayout.dosSwapStereo
                    ) { updateLayout(padLayout.copy {dosSwapStereo = it}) }

                    DropdownRow("Tandy 声卡",
                        listOf("auto" to "自动", "on" to "开启", "off" to "关闭"),
                        padLayout.dosTandySound
                    ) { updateLayout(padLayout.copy {dosTandySound = it}) }

                    DropdownRow("鼠标输入模式",
                        listOf(
                            "touchpad" to "触控板(推荐·默认)",
                            "auto" to "自动",
                            "virtual" to "虚拟鼠标",
                            "direct" to "直接控制",
                            "off" to "关闭"
                        ),
                        padLayout.dosMouseInput
                    ) { updateLayout(padLayout.copy {dosMouseInput = it}) }

                    DropdownRow("键盘布局",
                        listOf(
                            "us" to "US (美式)", "uk" to "UK (英式)",
                            "de" to "德语", "fr" to "法语", "it" to "意大利语",
                            "es" to "西班牙语", "br" to "巴西", "ru" to "俄语",
                            "jp" to "日语"
                        ),
                        padLayout.dosKeyboardLayout
                    ) { updateLayout(padLayout.copy {dosKeyboardLayout = it}) }

                    DropdownRow("宽高比修正(CRT)",
                        listOf("false" to "关闭", "true" to "开启"),
                        padLayout.dosAspectCorrection
                    ) { updateLayout(padLayout.copy {dosAspectCorrection = it}) }

                    DropdownRow("CGA 模式",
                        listOf(
                            "early_auto" to "早期型 · 复合自动 (默认)",
                            "early_on" to "早期型 · 复合开",
                            "early_off" to "早期型 · 复合关",
                            "late_auto" to "后期型 · 复合自动",
                            "late_on" to "后期型 · 复合开",
                            "late_off" to "后期型 · 复合关"
                        ),
                        padLayout.dosCgaMode
                    ) { updateLayout(padLayout.copy {dosCgaMode = it}) }

                    DropdownRow("自动键位映射",
                        listOf("on" to "开启(推荐)", "off" to "关闭"),
                        padLayout.dosAutoMapping
                    ) { updateLayout(padLayout.copy {dosAutoMapping = it}) }

                    DropdownRow("Voodoo 显卡",
                        listOf("off" to "关闭", "on" to "开启"),
                        padLayout.dosVoodoo
                    ) { updateLayout(padLayout.copy {dosVoodoo = it}) }

                    DropdownRow("强制 60fps",
                        listOf("on" to "开启(推荐)", "off" to "关闭"),
                        padLayout.dosForce60fps
                    ) { updateLayout(padLayout.copy {dosForce60fps = it}) }

                    DropdownRow("存档大小",
                        listOf("on" to "默认", "500" to "500MB", "1000" to "1GB",
                               "2000" to "2GB", "4000" to "4GB", "8000" to "8GB", "0" to "关闭"),
                        padLayout.dosSavestate
                    ) { updateLayout(padLayout.copy {dosSavestate = it}) }

                    DropdownRow("虚拟按键模式",
                        listOf("gamepad" to "手柄(圆形按钮)", "keyboard" to "全键盘(QWERTY)"),
                        padLayout.dosInputMode
                    ) { updateLayout(padLayout.copy {dosInputMode = it}) }
                }
            }
            GamePlatform.ARCADE -> item {
                SettingsSection("街机 (FBNeo)") {
                    DropdownRow("方向控制",
                        listOf("dpad" to "十字键 D-Pad", "analog" to "摇杆 Analog Stick"),
                        padLayout.arcadeInputMode
                    ) { updateLayout(padLayout.copy {arcadeInputMode = it}) }

                    DropdownRow("显示 L2/R2 按键",
                        listOf("false" to "关闭 (4键默认)", "true" to "开启 (6键格斗)"),
                        padLayout.arcadeShowL2R2.toString()
                    ) { updateLayout(padLayout.copy {arcadeShowL2R2 = it.toBoolean()}) }

                    DropdownRow("画面比例",
                        listOf("auto" to "自动", "4:3" to "4:3 (标准)",
                               "3:4" to "3:4 (竖屏)", "16:9" to "16:9", "16:15" to "16:15"),
                        padLayout.arcadeAspect
                    ) { updateLayout(padLayout.copy {arcadeAspect = it}) }

                    DropdownRow("画面旋转",
                        listOf("norotate" to "不旋转", "cw" to "顺时针90°",
                               "ccw" to "逆时针90°", "flip" to "翻转180°"),
                        padLayout.arcadeRotate
                    ) { updateLayout(padLayout.copy {arcadeRotate = it}) }

                    DropdownRow("竖屏模式",
                        listOf("disabled" to "关闭", "enabled" to "开启"),
                        padLayout.arcadeVerticalMode
                    ) { updateLayout(padLayout.copy {arcadeVerticalMode = it}) }

                    DropdownRow("裁剪过扫描",
                        listOf("enabled" to "开启", "disabled" to "关闭"),
                        padLayout.arcadeCropOverscan
                    ) { updateLayout(padLayout.copy {arcadeCropOverscan = it}) }

                    DropdownRow("CPU速度",
                        listOf("100" to "100%", "75" to "75%", "50" to "50%",
                               "150" to "150%", "200" to "200%", "250" to "250%"),
                        padLayout.arcadeCpuSpeed
                    ) { updateLayout(padLayout.copy {arcadeCpuSpeed = it}) }

                    DropdownRow("跳帧",
                        listOf("0" to "0", "1" to "1", "2" to "2", "3" to "3",
                               "4" to "4", "5" to "5", "6" to "6", "8" to "8", "10" to "10"),
                        padLayout.arcadeFrameskip
                    ) { updateLayout(padLayout.copy {arcadeFrameskip = it}) }

                    DropdownRow("强制60Hz",
                        listOf("disabled" to "关闭", "enabled" to "开启"),
                        padLayout.arcadeForce60hz
                    ) { updateLayout(padLayout.copy {arcadeForce60hz = it}) }

                    DropdownRow("采样率",
                        listOf("48000" to "48000 Hz", "44100" to "44100 Hz",
                               "22050" to "22050 Hz"),
                        padLayout.arcadeSampleRate
                    ) { updateLayout(padLayout.copy {arcadeSampleRate = it}) }

                    DropdownRow("音频插值",
                        listOf("0" to "关闭", "1" to "最近邻", "2" to "线性(推荐)", "3" to "三次"),
                        padLayout.arcadeAudioInterp
                    ) { updateLayout(padLayout.copy {arcadeAudioInterp = it}) }

                    DropdownRow("低通滤波",
                        listOf("disabled" to "关闭", "enabled" to "开启"),
                        padLayout.arcadeLowpass
                    ) { updateLayout(padLayout.copy {arcadeLowpass = it}) }

                    DropdownRow("NeoGeo模式",
                        listOf("MVS" to "MVS(街机)", "AES" to "AES(家用)"),
                        padLayout.arcadeNeogeomode
                    ) { updateLayout(padLayout.copy {arcadeNeogeomode = it}) }

                    DropdownRow("记忆卡",
                        listOf("enabled" to "开启", "disabled" to "关闭"),
                        padLayout.arcadeMemcard
                    ) { updateLayout(padLayout.copy {arcadeMemcard = it}) }
                }
            }
            GamePlatform.JAVA -> item {
                SettingsSection("Java (J2ME)") {
                    DropdownRow("虚拟按键模式",
                        listOf("gamepad" to "手柄 (D-pad + A/B/X/Y)", "phone" to "手机键盘 (12键)"),
                        padLayout.javaInputMode
                    ) { updateLayout(padLayout.copy { javaInputMode = it }) }
                    DropdownRow("画面缩放",
                        listOf("fit" to "适应 (保持比例)", "stretch" to "拉伸 (填满屏幕)", "center" to "原始分辨率居中"),
                        padLayout.javaScaleType
                    ) { updateLayout(padLayout.copy { javaScaleType = it }) }
                    // 游戏逻辑分辨率（MIDlet 看到的屏幕尺寸）
                    DropdownRow("游戏分辨率",
                        listOf(
                            "default" to "默认 (跟随游戏配置)",
                            "auto" to "自动 (跟随设备屏幕)",
                            "128x128" to "128 × 128",
                            "176x208" to "176 × 208 (S60 经典)",
                            "176x220" to "176 × 220",
                            "208x208" to "208 × 208",
                            "240x320" to "240 × 320 (最常见)",
                            "240x400" to "240 × 400",
                            "320x240" to "320 × 240 (横屏)",
                            "360x640" to "360 × 640",
                            "480x800" to "480 × 800",
                            "640x360" to "640 × 360 (横屏)"
                        ),
                        padLayout.javaResolution
                    ) { updateLayout(padLayout.copy { javaResolution = it }) }
                    // 画面缩放比例（百分比），center 模式下可放大/缩小
                    DropdownRow("画面缩放比例",
                        listOf(
                            "25" to "25%", "50" to "50%", "75" to "75%",
                            "100" to "100% (默认)", "125" to "125%", "150" to "150%",
                            "175" to "175%", "200" to "200%", "300" to "300%", "400" to "400%"
                        ),
                        padLayout.javaScaleRatio
                    ) { updateLayout(padLayout.copy { javaScaleRatio = it }) }
                    DropdownRow("帧率限制",
                        listOf(
                            "0" to "不限制 (默认)", "60" to "60 FPS", "50" to "50 FPS",
                            "40" to "40 FPS", "30" to "30 FPS", "25" to "25 FPS", "15" to "15 FPS"
                        ),
                        padLayout.javaFpsLimit
                    ) { updateLayout(padLayout.copy { javaFpsLimit = it }) }
                    DropdownRow("显示 FPS",
                        listOf("enabled" to "开启", "disabled" to "关闭"),
                        if (padLayout.javaShowFps) "enabled" else "disabled"
                    ) { updateLayout(padLayout.copy { javaShowFps = (it == "enabled") }) }
                    DropdownRow("即时渲染模式",
                        listOf("enabled" to "开启 (菜单更流畅)", "disabled" to "关闭 (减少闪烁)"),
                        if (padLayout.javaImmediateMode) "enabled" else "disabled"
                    ) { updateLayout(padLayout.copy { javaImmediateMode = (it == "enabled") }) }
                    DropdownRow("触摸输入支持",
                        listOf("enabled" to "开启", "disabled" to "关闭 (强制键盘操作)"),
                        if (padLayout.javaTouchInput) "enabled" else "disabled"
                    ) { updateLayout(padLayout.copy { javaTouchInput = (it == "enabled") }) }
                    DropdownRow("数字键兼作方向键",
                        listOf("enabled" to "开启 (真机行为，推荐)", "disabled" to "关闭 (仅发送数字)"),
                        if (padLayout.javaNumDualDispatch) "enabled" else "disabled"
                    ) { updateLayout(padLayout.copy { javaNumDualDispatch = (it == "enabled") }) }
                    // 按键映射：虚拟手柄按键 → 手机任意按键
                    Text(
                        "按键映射 (虚拟按键 → 手机按键)：把手柄 A/B/X/Y、START、SELECT 和方向键映射到手机的数字键、方向键、软键等任意按键。",
                        color = Color(0xFF8899AA), fontSize = 11.sp, lineHeight = 15.sp
                    )
                    JAVA_MAPPABLE_BUTTONS.forEach { (buttonId, buttonLabel) ->
                        val currentCode = javaButtonKeyMapGet(padLayout.javaButtonKeyMap, buttonId)
                        DropdownRow("映射 $buttonLabel", JAVA_PHONE_KEY_OPTIONS, currentCode.toString()) { code ->
                            val newMap = javaButtonKeyMapSet(padLayout.javaButtonKeyMap, buttonId, code.toIntOrNull() ?: 0)
                            updateLayout(padLayout.copy { javaButtonKeyMap = newMap })
                        }
                    }
                }
            }
            GamePlatform.NDS -> item {
                SettingsSection("NDS / DSi (melonDS)") {
                    DropdownRow("主机模式",
                        listOf("DS" to "DS", "DSi" to "DSi"),
                        padLayout.ndsConsoleMode
                    ) { updateLayout(padLayout.copy {ndsConsoleMode = it}) }
                    DropdownRow("屏幕布局",
                        listOf("Top/Bottom" to "上下排列", "Bottom/Top" to "下上排列",
                               "Left/Right" to "左右排列", "Right/Left" to "右左排列",
                               "Top Only" to "仅上方屏", "Bottom Only" to "仅下方屏",
                               "Hybrid Top" to "混合(上屏大)", "Hybrid Bottom" to "混合(下屏大)"),
                        padLayout.ndsScreenLayout
                    ) { updateLayout(padLayout.copy {ndsScreenLayout = it}) }
                    DropdownRow("屏幕间距",
                        (0..20).map { it.toString() to "${it}px" },
                        padLayout.ndsScreenGap
                    ) { updateLayout(padLayout.copy {ndsScreenGap = it}) }
                    DropdownRow("混合小屏模式",
                        listOf("Bottom" to "下方", "Top" to "上方", "Duplicate" to "复制双屏"),
                        padLayout.ndsHybridSmallScreen
                    ) { updateLayout(padLayout.copy {ndsHybridSmallScreen = it}) }
                    DropdownRow("触摸模式",
                        listOf("Touch" to "触摸", "Mouse" to "鼠标", "Joystick" to "摇杆", "disabled" to "关闭"),
                        padLayout.ndsTouchMode
                    ) { updateLayout(padLayout.copy {ndsTouchMode = it}) }
                    DropdownRow("DSi SD 卡",
                        listOf("disabled" to "关闭", "enabled" to "开启"),
                        padLayout.ndsDsiSdcard
                    ) { updateLayout(padLayout.copy {ndsDsiSdcard = it}) }
                    DropdownRow("随机 MAC 地址",
                        listOf("disabled" to "关闭", "enabled" to "开启"),
                        padLayout.ndsRandomizeMac
                    ) { updateLayout(padLayout.copy {ndsRandomizeMac = it}) }
                    DropdownRow("换屏模式",
                        listOf("Toggle" to "切换", "Hold" to "按住"),
                        padLayout.ndsSwapscreenMode
                    ) { updateLayout(padLayout.copy {ndsSwapscreenMode = it}) }
                    // NDS 存档方式已升级为全局设置（所有核心通用）：
                    // 位于 设置 → 存储 → 存档方式。
                    Text(
                        "存档方式现已移至「设置 → 存储 → 存档方式」，对所有核心生效" +
                        "（NDS 写 .sav 兼容官方 melonDS，其他核心写 .srm）。",
                        color = Color(0xFF4A5568), fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                    DropdownRow("OpenGL 渲染器",
                        listOf("enabled" to "开启(硬件加速,推荐)", "disabled" to "关闭(软件渲染)"),
                        padLayout.ndsOpenGlRenderer
                    ) { updateLayout(padLayout.copy {ndsOpenGlRenderer = it}) }
                    // 仅当 OpenGL 渲染器启用时显示分辨率选项
                    if (padLayout.ndsOpenGlRenderer == "enabled") {
                        DropdownRow("3D 渲染分辨率",
                            listOf(
                                "1x native (256x192)" to "1x (256x192, 原生)",
                                "2x native (512x384)" to "2x (512x384)",
                                "3x native (768x576)" to "3x (768x576)",
                                "4x native (1024x768)" to "4x (1024x768)",
                                "5x native (1280x960)" to "5x (1280x960)",
                                "6x native (1536x1152)" to "6x (1536x1152)",
                                "7x native (1792x1344)" to "7x (1792x1344)",
                                "8x native (2048x1536)" to "8x (2048x1536)"
                            ),
                            padLayout.ndsResolution
                        ) { updateLayout(padLayout.copy {ndsResolution = it}) }
                        DropdownRow("OpenGL 多边形优化",
                            listOf("disabled" to "关闭", "enabled" to "开启(减少图形错误)"),
                            padLayout.ndsOpenGlBetterPolygons
                        ) { updateLayout(padLayout.copy {ndsOpenGlBetterPolygons = it}) }
                        DropdownRow("OpenGL 纹理过滤",
                            listOf("nearest" to "最近邻(锐利)", "linear" to "线性(平滑)"),
                            padLayout.ndsOpenGlFiltering
                        ) { updateLayout(padLayout.copy {ndsOpenGlFiltering = it}) }
                    }
                    DropdownRow("JIT 编译器",
                        listOf("enabled" to "开启(加速)", "disabled" to "关闭(解释器)"),
                        padLayout.ndsJitEnable
                    ) { updateLayout(padLayout.copy {ndsJitEnable = it}) }
                    DropdownRow("JIT 块大小",
                        (1..24).map { it.toString() to it.toString() },
                        padLayout.ndsJitBlockSize
                    ) { updateLayout(padLayout.copy {ndsJitBlockSize = it}) }
                    DropdownRow("JIT 快速内存",
                        listOf("enabled" to "开启", "disabled" to "关闭"),
                        padLayout.ndsJitFastMemory
                    ) { updateLayout(padLayout.copy {ndsJitFastMemory = it}) }
                    DropdownRow("JIT 分支优化",
                        listOf("enabled" to "开启", "disabled" to "关闭"),
                        padLayout.ndsJitBranchOptimisations
                    ) { updateLayout(padLayout.copy {ndsJitBranchOptimisations = it}) }
                    DropdownRow("JIT 字面量优化",
                        listOf("enabled" to "开启", "disabled" to "关闭"),
                        padLayout.ndsJitLiteralOptimisations
                    ) { updateLayout(padLayout.copy {ndsJitLiteralOptimisations = it}) }
                    DropdownRow("音频插值",
                        listOf("Cosine" to "余弦(高质量)", "Linear" to "线性(中等)",
                               "Cubic" to "三次(最高质量)", "None" to "无(低质量)"),
                        padLayout.ndsAudioInterpolation
                    ) { updateLayout(padLayout.copy {ndsAudioInterpolation = it}) }
                    DropdownRow("音频比特率",
                        listOf("Automatic" to "自动", "10-bit" to "10-bit", "16-bit" to "16-bit"),
                        padLayout.ndsAudioBitrate
                    ) { updateLayout(padLayout.copy {ndsAudioBitrate = it}) }
                    DropdownRow("麦克风输入",
                        listOf("Blow Noise" to "吹气声", "White Noise" to "白噪声"),
                        padLayout.ndsMicInput
                    ) { updateLayout(padLayout.copy {ndsMicInput = it}) }
                    DropdownRow("语言",
                        listOf("Japanese" to "日本語", "English" to "English",
                               "French" to "Français", "German" to "Deutsch",
                               "Italian" to "Italiano", "Spanish" to "Español"),
                        padLayout.ndsLanguage
                    ) { updateLayout(padLayout.copy {ndsLanguage = it}) }
                    DropdownRow("使用固件设置",
                        listOf("disabled" to "关闭(推荐)", "enabled" to "开启"),
                        padLayout.ndsUseFwSettings
                    ) { updateLayout(padLayout.copy {ndsUseFwSettings = it}) }
                }
            }
            GamePlatform.PSX -> item {
                // 键名/取值已对照预编译 libpcsx_rearmed_libretro_android.so
                // 与 notaz/pcsx_rearmed 上游 libretro_core_options.h 校验。
                // 说明: PCSX-ReARMed 的 libretro 核心只有软件 GPU 插件
                // (gpu_neon/gpu_unai/gpu_peops)，没有 Vulkan/OpenGL 硬件渲染；
                // 性能主要靠 动态重编译(DRC)、DRC/GPU 线程化、跳帧 与 CPU 频率。
                SettingsSection("PSX (PCSX-ReARMed) · 系统") {
                    DropdownRow("BIOS",
                        listOf("auto" to "自动", "HLE" to "HLE(无 BIOS)",
                               "scph1000" to "SCPH-1000(日)", "scph1001" to "SCPH-1001(美)",
                               "scph1002" to "SCPH-1002(欧)", "scph5500" to "SCPH-5500(日)",
                               "scph5501" to "SCPH-5501(美)", "scph5502" to "SCPH-5502(欧)",
                               "psxonpsp660" to "PSP-660"),
                        padLayout.pscxBios
                    ) { updateLayout(padLayout.copy {pscxBios = it}) }
                    DropdownRow("区域",
                        listOf("auto" to "自动", "ntsc" to "NTSC", "pal" to "PAL"),
                        padLayout.pscxRegion
                    ) { updateLayout(padLayout.copy {pscxRegion = it}) }
                    DropdownRow("显示 BIOS 启动画面",
                        listOf("disabled" to "关闭", "enabled" to "开启"),
                        padLayout.pscxShowBootlogo
                    ) { updateLayout(padLayout.copy {pscxShowBootlogo = it}) }
                    DropdownRow("记忆卡 1",
                        listOf("libretro" to "Libretro (每游戏独立)", "serial" to "串行槽位",
                               "shared" to "共享 (全部游戏共用)", "none" to "关闭"),
                        padLayout.pscxMemcard1
                    ) { updateLayout(padLayout.copy {pscxMemcard1 = it}) }
                    DropdownRow("记忆卡 2",
                        listOf("shared" to "共享 (默认)", "libretro" to "Libretro",
                               "none" to "关闭"),
                        padLayout.pscxMemcard2
                    ) { updateLayout(padLayout.copy {pscxMemcard2 = it}) }
                    DropdownRow("CD 预读扇区",
                        listOf("0" to "0", "4" to "4", "8" to "8", "12" to "12(默认)",
                               "16" to "16", "20" to "20", "30" to "30"),
                        padLayout.pscxCdReadahead
                    ) { updateLayout(padLayout.copy {pscxCdReadahead = it}) }
                    DropdownRow("屏幕居中",
                        listOf("auto" to "自动(推荐)", "game" to "游戏内容",
                               "borderless" to "无边框"),
                        padLayout.pscxCentering
                    ) { updateLayout(padLayout.copy {pscxCentering = it}) }
                }

                SettingsSection("PSX · 性能") {
                    DropdownRow("动态重编译 DRC",
                        listOf("enabled" to "开启 (推荐·性能关键)", "disabled" to "关闭(慢速解释器)"),
                        padLayout.pscxDrc
                    ) { updateLayout(padLayout.copy {pscxDrc = it}) }
                    DropdownRow("DRC 线程化",
                        listOf("auto" to "自动 (推荐)", "enabled" to "开启",
                               "disabled" to "关闭"),
                        padLayout.pscxDrcThread
                    ) { updateLayout(padLayout.copy {pscxDrcThread = it}) }
                    DropdownRow("GPU 渲染线程化",
                        listOf("auto" to "自动 (推荐·多核提速明显)", "enabled" to "开启",
                               "disabled" to "关闭"),
                        padLayout.pscxGpuThreadRendering
                    ) { updateLayout(padLayout.copy {pscxGpuThreadRendering = it}) }
                    DropdownRow("PSX CPU 频率",
                        listOf(
                            "auto" to "自动 (推荐)",
                            "50" to "50%", "57" to "57% (标准实测值)",
                            "60" to "60%", "70" to "70%", "80" to "80%",
                            "90" to "90%", "100" to "100%"
                        ),
                        padLayout.pscxClock
                    ) { updateLayout(padLayout.copy {pscxClock = it}) }
                    Text(
                        "超频可减少部分游戏慢动作，但过高容易死机/花屏；一般保持「自动」。",
                        color = Color(0xFF4A5568), fontSize = 10.sp, lineHeight = 14.sp)
                    DropdownRow("跳帧类型",
                        listOf("disabled" to "关闭", "auto" to "自动",
                               "auto_threshold" to "自动(按阈值)", "fixed_interval" to "固定间隔"),
                        padLayout.pscxFrameskipType
                    ) { updateLayout(padLayout.copy {pscxFrameskipType = it}) }
                    if (padLayout.pscxFrameskipType == "fixed_interval") {
                        DropdownRow("固定跳帧数",
                            (1..10).map { it.toString() to it.toString() },
                            padLayout.pscxFrameskip
                        ) { updateLayout(padLayout.copy {pscxFrameskip = it}) }
                    }
                    if (padLayout.pscxFrameskipType == "auto_threshold") {
                        DropdownRow("跳帧阈值 %",
                            listOf("15","18","21","24","27","30","33","36","39","42",
                                   "45","48","51","54","57","60","65","70","75","80")
                                .map {
                                    v -> v to if (v=="33") "33 (默认)" else v },
                            padLayout.pscxFrameskipThreshold
                        ) { updateLayout(padLayout.copy {pscxFrameskipThreshold = it}) }
                    }
                    Text(
                        "跳帧可在弱设备上保证声音流畅（避免缓冲欠载的爆音），代价是画面不连贯。",
                        color = Color(0xFF4A5568), fontSize = 10.sp, lineHeight = 14.sp)
                    DropdownRow("小数帧率(PAL 准确速度)",
                        listOf("auto" to "自动", "enabled" to "强制开", "disabled" to "禁用"),
                        padLayout.pscxFractionalFps
                    ) { updateLayout(padLayout.copy {pscxFractionalFps = it}) }
                    DropdownRow("CD 加速(不安全)",
                        listOf("disabled" to "关闭", "enabled" to "开启(提速·兼容性风险)"),
                        padLayout.pscxCdTurbo
                    ) { updateLayout(padLayout.copy {pscxCdTurbo = it}) }
                }

                SettingsSection("PSX · 画面") {
                    DropdownRow("抖动(色彩过渡)",
                        listOf("enabled" to "开启(原始效果)", "disabled" to "关闭"),
                        padLayout.pscxDithering
                    ) { updateLayout(padLayout.copy {pscxDithering = it}) }
                    DropdownRow("32 位色彩输出",
                        listOf("disabled" to "关闭 (16位·更快)", "enabled" to "开启 (32位·更准)"),
                        padLayout.pscxRgb32
                    ) { updateLayout(padLayout.copy {pscxRgb32 = it}) }
                    DropdownRow("高分辨率降采样",
                        listOf("disabled" to "关闭", "enabled" to "开启 (480i→240p, 提速)"),
                        padLayout.pscxScaleHires
                    ) { updateLayout(padLayout.copy {pscxScaleHires = it}) }
                    DropdownRow("过扫描区域",
                        listOf("disabled" to "隐藏", "enabled" to "显示"),
                        padLayout.pscxShowOverscan
                    ) { updateLayout(padLayout.copy {pscxShowOverscan = it}) }
                    DropdownRow("NEON 隔行优化",
                        listOf("auto" to "自动", "enabled" to "开启", "disabled" to "关闭"),
                        padLayout.pscxNeonInterlace
                    ) { updateLayout(padLayout.copy {pscxNeonInterlace = it}) }
                    DropdownRow("NEON 高分辨率增强(慢)",
                        listOf("disabled" to "关闭", "enabled" to "开启 (2倍分辨率)"),
                        padLayout.pscxNeonEnhance
                    ) { updateLayout(padLayout.copy {pscxNeonEnhance = it}) }
                    DropdownRow("交替翻转方式",
                        listOf("auto" to "自动", "early" to "早翻转", "late" to "晚翻转"),
                        padLayout.pscxAltFlip
                    ) { updateLayout(padLayout.copy {pscxAltFlip = it}) }
                }

                SettingsSection("PSX · 音频 SPU") {
                    DropdownRow("SPU 插值",
                        listOf("simple" to "简单 (推荐·最快)", "gaussian" to "高斯(最接近原机)",
                               "cubic" to "立方 (高质量·较慢)", "off" to "关闭 (最快·音质差)"),
                        padLayout.pscxSpuInterp
                    ) { updateLayout(padLayout.copy {pscxSpuInterp = it}) }
                    DropdownRow("SPU 混响",
                        listOf("enabled" to "开启 (原机效果)", "disabled" to "关闭 (提速)"),
                        padLayout.pscxSpuReverb
                    ) { updateLayout(padLayout.copy {pscxSpuReverb = it}) }
                    DropdownRow("CD 音轨 (CDDA)",
                        listOf("enabled" to "播放 (默认)", "disabled" to "关闭 (提速)"),
                        padLayout.pscxCdAudio
                    ) { updateLayout(padLayout.copy {pscxCdAudio = it}) }
                    DropdownRow("XA 音频解码",
                        listOf("enabled" to "播放 (默认)", "disabled" to "关闭 (提速)"),
                        padLayout.pscxXaAudio
                    ) { updateLayout(padLayout.copy {pscxXaAudio = it}) }
                    DropdownRow("SPU 线程化",
                        listOf("disabled" to "关闭 (默认)", "enabled" to "开启"),
                        padLayout.pscxSpuThread
                    ) { updateLayout(padLayout.copy {pscxSpuThread = it}) }
                }

                SettingsSection("PSX · 手柄 / 输入") {
                    DropdownRow("1P 手柄类型",
                        listOf("standard" to "标准数字手柄", "analog" to "DualShock 模拟手柄",
                               "negcon" to "neGcon 旋柄", "gun" to "光枪 G-Con"),
                        padLayout.pscxPad1Type
                    ) { updateLayout(padLayout.copy {pscxPad1Type = it}) }
                    DropdownRow("2P 手柄类型",
                        listOf("standard" to "标准数字手柄", "analog" to "DualShock 模拟手柄",
                               "negcon" to "neGcon 旋柄", "gun" to "光枪 G-Con"),
                        padLayout.pscxPad2Type
                    ) { updateLayout(padLayout.copy {pscxPad2Type = it}) }
                    Text(
                        "DualShock 模式面向需要摇杆的游戏 (如 Ape Escape)；切换后需重进游戏。",
                        color = Color(0xFF4A5568), fontSize = 10.sp, lineHeight = 14.sp)
                    DropdownRow("震动反馈",
                        listOf("enabled" to "开启", "disabled" to "关闭"),
                        padLayout.pscxVibration
                    ) { updateLayout(padLayout.copy {pscxVibration = it}) }
                    DropdownRow("多重手柄 Multitap",
                        listOf("disabled" to "关闭", "port 1" to "接口 1 (最多5人)",
                               "port 2" to "接口 2 (最多5人)", "ports 1 and 2" to "双接口 (最多8人)"),
                        padLayout.pscxMultitap
                    ) { updateLayout(padLayout.copy {pscxMultitap = it}) }
                    DropdownRow("模拟摇杆边界",
                        listOf("square" to "方形 (推荐)", "circle" to "圆形"),
                        padLayout.pscxAnalogAxis
                    ) { updateLayout(padLayout.copy {pscxAnalogAxis = it}) }
                    DropdownRow("neGcon 扭转响应",
                        listOf("linear" to "线性", "quadratic" to "二次 (推荐)",
                               "cubic" to "三次"),
                        padLayout.pscxNegconResponse
                    ) { updateLayout(padLayout.copy {pscxNegconResponse = it}) }
                    DropdownRow("neGcon 死区 %",
                        listOf("0","3","5","7","10","13","15","17","20","23","25","27","30")
                            .map { v -> v to if (v=="0") "0 (默认)" else v },
                        padLayout.pscxNegconDeadzone
                    ) { updateLayout(padLayout.copy {pscxNegconDeadzone = it}) }
                }

                SettingsSection("PSX · 游戏兼容修正") {
                    DropdownRow("Peops 奇偶位 Hack",
                        listOf("disabled" to "关闭", "enabled" to "开启 (时空之轮需要)"),
                        padLayout.pscxGpuOddEven
                    ) { updateLayout(padLayout.copy {pscxGpuOddEven = it}) }
                    DropdownRow("iCache 模拟",
                        listOf("enabled" to "开启 (F1 系列需要)", "disabled" to "关闭 (稍快)"),
                        padLayout.pscxIcache
                    ) { updateLayout(padLayout.copy {pscxIcache = it}) }
                }
            }
            GamePlatform.DC -> item {
                // Flycast 核心 — Dreamcast / Naomi / Atomiswave。
                // 键名/取值对照 flycast 上游 shell/libretro/libretro_core_options.h
                // （CORE_OPTION_NAME = "reicast"），与 buildbot 预编译核心同源。
                SettingsSection("DC (Flycast) · 系统") {
                    DropdownRow("区域",
                        listOf("Japan" to "日本", "USA" to "美国", "Europe" to "欧洲", "Default" to "跟随游戏"),
                        padLayout.dcRegion
                    ) { updateLayout(padLayout.copy {dcRegion = it}) }

                    DropdownRow("语言",
                        listOf("Japanese" to "日语", "English" to "英语", "German" to "德语",
                               "French" to "法语", "Spanish" to "西班牙语", "Italian" to "意大利语",
                               "Default" to "跟随区域"),
                        padLayout.dcLanguage
                    ) { updateLayout(padLayout.copy {dcLanguage = it}) }

                    DropdownRow("HLE BIOS (需重启)",
                        listOf("disabled" to "关闭 (需真实 BIOS)", "enabled" to "开启 (无需 BIOS, 兼容性低)"),
                        padLayout.dcHleBios
                    ) { updateLayout(padLayout.copy {dcHleBios = it}) }

                    DropdownRow("广播制式",
                        listOf("NTSC" to "NTSC", "PAL" to "PAL (欧洲)",
                               "PAL_N" to "PAL-N (南美)", "PAL_M" to "PAL-M (巴西)", "Default" to "自动"),
                        padLayout.dcBroadcast
                    ) { updateLayout(padLayout.copy {dcBroadcast = it}) }

                    DropdownRow("视频输出",
                        listOf("VGA" to "VGA (清晰)", "TV (RGB)" to "TV (RGB)", "TV (Composite)" to "TV (复合, 默认)"),
                        padLayout.dcCableType
                    ) { updateLayout(padLayout.copy {dcCableType = it}) }

                    DropdownRow("音频 DSP",
                        listOf("enabled" to "开启 (音质更好)", "disabled" to "关闭 (更快)"),
                        padLayout.dcEnableDsp
                    ) { updateLayout(padLayout.copy {dcEnableDsp = it}) }

                    DropdownRow("32MB 内存改造",
                        listOf("disabled" to "关闭", "enabled" to "开启 (仅部分自制软件需要)"),
                        padLayout.dc32MbMod
                    ) { updateLayout(padLayout.copy {dc32MbMod = it}) }
                }

                SettingsSection("DC · 画面") {
                    DropdownRow("内部分辨率",
                        listOf("320x240" to "320x240 (半分辨率)", "640x480" to "640x480 (原生)",
                               "800x600" to "800x600 (x1.25)", "960x720" to "960x720 (x1.5)",
                               "1280x960" to "1280x960 (x2)", "1600x1200" to "1600x1200 (x2.5)",
                               "1920x1440" to "1920x1440 (x3)", "2560x1920" to "2560x1920 (x4)"),
                        padLayout.dcInternalRes
                    ) { updateLayout(padLayout.copy {dcInternalRes = it}) }

                    DropdownRow("透明排序精度",
                        listOf("per-strip (fast, least accurate)" to "按条带 (最快, 最不准)",
                               "per-triangle (normal)" to "按三角形 (正常, 推荐)",
                               "per-pixel (accurate)" to "按像素 (最准, 最慢)"),
                        padLayout.dcAlphaSorting
                    ) { updateLayout(padLayout.copy {dcAlphaSorting = it}) }

                    DropdownRow("各向异性过滤",
                        listOf("off" to "关闭", "2" to "2x", "4" to "4x", "8" to "8x", "16" to "16x"),
                        padLayout.dcAnisotropic
                    ) { updateLayout(padLayout.copy {dcAnisotropic = it}) }

                    DropdownRow("纹理过滤",
                        listOf("0" to "默认", "1" to "强制最近邻 (锐利)", "2" to "强制线性 (平滑)"),
                        padLayout.dcTextureFiltering
                    ) { updateLayout(padLayout.copy {dcTextureFiltering = it}) }

                    DropdownRow("Mipmapping",
                        listOf("enabled" to "开启", "disabled" to "关闭"),
                        padLayout.dcMipmapping
                    ) { updateLayout(padLayout.copy {dcMipmapping = it}) }

                    DropdownRow("雾效",
                        listOf("enabled" to "开启", "disabled" to "关闭"),
                        padLayout.dcFog
                    ) { updateLayout(padLayout.copy {dcFog = it}) }

                    DropdownRow("半透明修正",
                        listOf("enabled" to "开启", "disabled" to "关闭"),
                        padLayout.dcVolumeModifier
                    ) { updateLayout(padLayout.copy {dcVolumeModifier = it}) }

                    DropdownRow("宽屏变形",
                        listOf("disabled" to "关闭", "enabled" to "开启 (画面拉伸到 16:9)"),
                        padLayout.dcWidescreenHack
                    ) { updateLayout(padLayout.copy {dcWidescreenHack = it}) }

                    DropdownRow("PVR2 后处理滤镜",
                        listOf("disabled" to "关闭", "enabled" to "开启 (模糊/辉光)"),
                        padLayout.dcPvr2Filtering
                    ) { updateLayout(padLayout.copy {dcPvr2Filtering = it}) }

                    DropdownRow("帧交换延迟",
                        listOf("disabled" to "关闭", "enabled" to "开启 (减少闪屏)"),
                        padLayout.dcDelayFrameSwapping
                    ) { updateLayout(padLayout.copy {dcDelayFrameSwapping = it}) }

                    DropdownRow("屏幕旋转",
                        listOf("horizontal" to "横向", "vertical" to "纵向 (竖版游戏)"),
                        padLayout.dcScreenRotation
                    ) { updateLayout(padLayout.copy {dcScreenRotation = it}) }

                    DropdownRow("纹理放大 (xBRZ)",
                        listOf("1" to "关闭", "2" to "2x", "4" to "4x", "6" to "6x"),
                        padLayout.dcTexUpscale
                    ) { updateLayout(padLayout.copy {dcTexUpscale = it}) }

                    DropdownRow("纹理放大上限",
                        listOf("256" to "256px", "512" to "512px", "1024" to "1024px"),
                        padLayout.dcTexUpscaleMaxSize
                    ) { updateLayout(padLayout.copy {dcTexUpscaleMaxSize = it}) }
                }

                SettingsSection("DC · 性能") {
                    DropdownRow("线程化渲染",
                        listOf("enabled" to "开启 (推荐, GPU 独立线程)",
                               "disabled" to "关闭 (兼容性优先)"),
                        padLayout.dcThreadedRendering
                    ) { updateLayout(padLayout.copy {dcThreadedRendering = it}) }

                    DropdownRow("自动跳帧",
                        listOf("disabled" to "关闭", "some" to "普通", "more" to "激进"),
                        padLayout.dcAutoSkipFrame
                    ) { updateLayout(padLayout.copy {dcAutoSkipFrame = it}) }

                    DropdownRow("强制跳帧",
                        listOf("disabled" to "关闭", "1" to "每 2 帧跳 1", "2" to "每 3 帧跳 1",
                               "3" to "每 4 帧跳 1", "4" to "每 5 帧跳 1",
                               "5" to "每 6 帧跳 1", "6" to "每 7 帧跳 1"),
                        padLayout.dcFrameSkipping
                    ) { updateLayout(padLayout.copy {dcFrameSkipping = it}) }

                    DropdownRow("GD-ROM 读盘提速",
                        listOf("enabled" to "开启 (推荐)", "disabled" to "关闭 (原速)"),
                        padLayout.dcGdromFastLoading
                    ) { updateLayout(padLayout.copy {dcGdromFastLoading = it}) }

                    DropdownRow("SH4 CPU 主频 (MHz)",
                        listOf("100" to "100", "200" to "200 (原生)", "300" to "300 (超频)",
                               "330" to "330 (极限超频)"),
                        padLayout.dcSh4Clock
                    ) { updateLayout(padLayout.copy {dcSh4Clock = it}) }
                }

                SettingsSection("DC · 高级渲染修正") {
                    DropdownRow("全帧缓冲模拟 (慢)",
                        listOf("disabled" to "关闭 (推荐)", "enabled" to "开启 (部分 2D 游戏/光枪游戏需要)"),
                        padLayout.dcEmulateFramebuffer
                    ) { updateLayout(padLayout.copy {dcEmulateFramebuffer = it}) }

                    DropdownRow("RTT 缩解 (RTTB)",
                        listOf("disabled" to "关闭", "enabled" to "开启 (少数游戏需要)"),
                        padLayout.dcEnableRttb
                    ) { updateLayout(padLayout.copy {dcEnableRttb = it}) }

                    DropdownRow("原生深度插值",
                        listOf("disabled" to "关闭", "enabled" to "开启 (少数游戏需要)"),
                        padLayout.dcDepthInterpolation
                    ) { updateLayout(padLayout.copy {dcDepthInterpolation = it}) }

                    DropdownRow("高分辨率边缘修复",
                        listOf("disabled" to "关闭", "enabled" to "开启 (高分辨率纹理出血)"),
                        padLayout.dcFixUpscaleBleeding
                    ) { updateLayout(padLayout.copy {dcFixUpscaleBleeding = it}) }
                }

                SettingsSection("DC · VMU 与扩展槽") {
                    DropdownRow("VMU 蜂鸣",
                        listOf("disabled" to "关闭", "enabled" to "开启 (VMU 小游戏提示音)"),
                        padLayout.dcVmuSound
                    ) { updateLayout(padLayout.copy {dcVmuSound = it}) }

                    DropdownRow("每游戏独立 VMU",
                        listOf("disabled" to "关闭 (共享存储)", "VMU A1" to "每游戏独立 (A1)",
                               "All VMUs" to "全部独立"),
                        padLayout.dcPerContentVmus
                    ) { updateLayout(padLayout.copy {dcPerContentVmus = it}) }

                    DropdownRow("接口 1 · 扩展槽 1",
                        listOf("VMU" to "VMU 存储卡", "Purupuru" to "震动包",
                               "DreamPotato" to "DreamPotato", "None" to "空"),
                        padLayout.dcPort1Slot1
                    ) { updateLayout(padLayout.copy {dcPort1Slot1 = it}) }

                    DropdownRow("接口 1 · 扩展槽 2",
                        listOf("VMU" to "VMU 存储卡", "Purupuru" to "震动包", "None" to "空"),
                        padLayout.dcPort1Slot2
                    ) { updateLayout(padLayout.copy {dcPort1Slot2 = it}) }

                    DropdownRow("接口 2 · 扩展槽 1",
                        listOf("VMU" to "VMU 存储卡", "Purupuru" to "震动包",
                               "DreamPotato" to "DreamPotato", "None" to "空"),
                        padLayout.dcPort2Slot1
                    ) { updateLayout(padLayout.copy {dcPort2Slot1 = it}) }

                    DropdownRow("接口 3 · 扩展槽 1",
                        listOf("VMU" to "VMU 存储卡", "Purupuru" to "震动包",
                               "DreamPotato" to "DreamPotato", "None" to "空"),
                        padLayout.dcPort3Slot1
                    ) { updateLayout(padLayout.copy {dcPort3Slot1 = it}) }

                    DropdownRow("接口 4 · 扩展槽 1",
                        listOf("VMU" to "VMU 存储卡", "Purupuru" to "震动包",
                               "DreamPotato" to "DreamPotato", "None" to "空"),
                        padLayout.dcPort4Slot1
                    ) { updateLayout(padLayout.copy {dcPort4Slot1 = it}) }
                }

                SettingsSection("DC · 街机 (Naomi / Atomiswave)") {
                    DropdownRow("允许服务按键",
                        listOf("disabled" to "关闭", "enabled" to "开启 (摇杆 Service/Test 菜单)"),
                        padLayout.dcAllowServiceButtons
                    ) { updateLayout(padLayout.copy {dcAllowServiceButtons = it}) }

                    DropdownRow("强制 Freeplay",
                        listOf("disabled" to "关闭 (正常投币)", "enabled" to "开启 (免投币直接开始)"),
                        padLayout.dcForceFreeplay
                    ) { updateLayout(padLayout.copy {dcForceFreeplay = it}) }

                    DropdownRow("投币上限",
                        (0..20).map { v -> v.toString() to (if (v == 0) "不限制" else v.toString()) },
                        padLayout.dcCoinLimit
                    ) { updateLayout(padLayout.copy {dcCoinLimit = it}) }

                    Text(
                        "Naomi / Atomiswave 游戏为 MAME romset (.zip)；Select=投币，" +
                        "L3=Test 菜单，R3=Service（可在布局编辑器/键位映射中查看）。",
                        color = Color(0xFF4A5568), fontSize = 10.sp, lineHeight = 14.sp)
                }

                SettingsSection("DC · 输入") {
                    DropdownRow("摇杆死区",
                        listOf("0%" to "0 (无死区)", "5%" to "5%", "10%" to "10%",
                               "15%" to "15% (默认)", "20%" to "20%", "25%" to "25%", "30%" to "30%"),
                        padLayout.dcStickDeadzone
                    ) { updateLayout(padLayout.copy {dcStickDeadzone = it}) }

                    DropdownRow("扳机死区",
                        listOf("0%" to "0 (无死区)", "5%" to "5%", "10%" to "10%",
                               "15%" to "15%", "20%" to "20%", "25%" to "25%", "30%" to "30%"),
                        padLayout.dcTriggerDeadzone
                    ) { updateLayout(padLayout.copy {dcTriggerDeadzone = it}) }

                    DropdownRow("数字扳机",
                        listOf("disabled" to "关闭 (模拟扳机, 推荐)",
                               "enabled" to "开启 (轻触即全扣, 老游戏手感)"),
                        padLayout.dcDigitalTriggers
                    ) { updateLayout(padLayout.copy {dcDigitalTriggers = it}) }
                }
            }
            GamePlatform.PS2 -> item {
                // PCEE2 核心 — 上游 PCSX2 (v2.7.523) 的 libretro 官方 Android 构建。
                // 键名/取值已对照 pcee2-libretro 源码 Libretro.cpp definitions[] 表验证
                // (WizzardSK/pcee2-libretro, 与预编译 libpcee2_libretro_android.so 同源)。
                SettingsSection("PS2 (PCSX2) · 画面") {
                    // ARMSX2 核心已启用 Vulkan (USE_VULKAN=ON)。auto 由核心按设备
                    // 支持选择（Vulkan 优先），opengl 用 OpenGL ES，vulkan 强制 Vulkan，
                    // software 用 CPU 软渲染。均可即时切换。
                    DropdownRow("渲染器",
                        listOf("auto" to "Auto (按设备选择)",
                               "vulkan" to "Vulkan (硬件加速)",
                               "opengl" to "OpenGL (硬件加速)",
                               "software" to "Software (软渲染)"),
                        padLayout.ps2Renderer
                    ) { updateLayout(padLayout.copy {ps2Renderer = it}) }
                    // 分辨率倍数 — 1x 原生 640x448，4x = 2560x1792。
                    DropdownRow("分辨率倍数",
                        listOf("1" to "1x (原生 640x448)", "2" to "2x (1280x896)",
                               "3" to "3x (1920x1344)", "4" to "4x (2560x1792 高配专用)"),
                        padLayout.ps2ResMulti
                    ) { updateLayout(padLayout.copy {ps2ResMulti = it}) }
                    DropdownRow("双线性过滤",
                        listOf("enabled" to "开启 (PS2 原生平滑)", "disabled" to "关闭 (像素风)"),
                        padLayout.ps2Bilinear
                    ) { updateLayout(padLayout.copy {ps2Bilinear = it}) }
                    DropdownRow("三线性过滤",
                        listOf("auto" to "自动 (默认)", "off" to "关闭",
                               "ps2" to "PS2 原生", "forced" to "强制"),
                        padLayout.ps2Trilinear
                    ) { updateLayout(padLayout.copy {ps2Trilinear = it}) }
                    DropdownRow("各向异性过滤",
                        listOf("0" to "关闭", "2" to "2x", "4" to "4x", "8" to "8x", "16" to "16x"),
                        padLayout.ps2Anisotropic
                    ) { updateLayout(padLayout.copy {ps2Anisotropic = it}) }
                    DropdownRow("抖动 (Dithering)",
                        listOf("2" to "不缩放 (PS2 默认)", "1" to "缩放", "0" to "关闭 (去色带)"),
                        padLayout.ps2Dithering
                    ) { updateLayout(padLayout.copy {ps2Dithering = it}) }
                    DropdownRow("硬件 Mipmapping",
                        listOf("enabled" to "开启", "disabled" to "关闭 (提速)"),
                        padLayout.ps2Mipmapping
                    ) { updateLayout(padLayout.copy {ps2Mipmapping = it}) }
                    DropdownRow("去隔行模式",
                        listOf("0" to "自动 (默认)", "1" to "关闭", "4" to "Bob (TFF)",
                               "5" to "Bob (BFF)", "8" to "Adaptive (TFF)", "9" to "Adaptive (BFF)"),
                        padLayout.ps2Deinterlace
                    ) { updateLayout(padLayout.copy {ps2Deinterlace = it}) }
                    // 以下为 ARMSX2 native 渲染增强设置 — TVShader/HalfPixelOffset/
                    // TexturePreloadingLevel 枚举值对照 ARMSX2 全屏设置 UI。
                    DropdownRow("电视滤镜 (TV Shader)",
                        listOf("0" to "无 (默认)", "1" to "扫描线 (Scanline)",
                               "2" to "对角线 (Diagonal)", "3" to "三角 (Triangular)",
                               "4" to "波浪 (Wave)", "5" to "Lottes CRT",
                               "6" to "4xRGSS", "7" to "NxAGSS"),
                        padLayout.ps2TvShader
                    ) { updateLayout(padLayout.copy {ps2TvShader = it}) }
                    DropdownRow("画面增强 (ShadeBoost)",
                        listOf("disabled" to "关闭 (默认)", "enabled" to "开启 (亮度对比度饱和度伽马=50)"),
                        padLayout.ps2ShadeBoost
                    ) { updateLayout(padLayout.copy {ps2ShadeBoost = it}) }
                    DropdownRow("半像素偏移 (Half-pixel)",
                        listOf("0" to "关闭 (默认)", "1" to "普通 (Normal)",
                               "2" to "特殊 (Special)", "3" to "特殊激进 (Special Aggressive)",
                               "4" to "原生 (Native)", "5" to "原生+纹理偏移"),
                        padLayout.ps2HalfPixelOffset
                    ) { updateLayout(padLayout.copy {ps2HalfPixelOffset = it}) }
                    DropdownRow("纹理预加载",
                        listOf("0" to "关闭 (Off)", "1" to "部分 (Partial, 推荐)",
                               "2" to "完整 (Full, 最吃显存)"),
                        padLayout.ps2TexturePreloading
                    ) { updateLayout(padLayout.copy {ps2TexturePreloading = it}) }
                    DropdownRow("画面比例",
                        listOf("auto" to "跟随全局画面缩放", "4:3" to "4:3 (锁定)", "16:9" to "16:9 (锁定)"),
                        padLayout.ps2AspectRatio
                    ) { updateLayout(padLayout.copy {ps2AspectRatio = it}) }
                }

                SettingsSection("PS2 · 性能 (速度作弊)") {
                    DropdownRow("GPU 回读模式",
                        listOf("accurate" to "精准 (慢, 特效完整)",
                               "no_readbacks" to "禁用回读 (同步GS线程, 加速)",
                               "unsynchronized" to "非同步 (快)",
                               "async" to "异步 (实验性, 最快, 滞后1帧)",
                               "disabled" to "禁用/忽略 (最快, 部分特效异常)"),
                        padLayout.ps2HwDownloadMode
                    ) { updateLayout(padLayout.copy {ps2HwDownloadMode = it}) }
                    DropdownRow("混色精度",
                        listOf("minimum" to "最低 (最快)", "basic" to "基础 (推荐)",
                               "medium" to "中等", "high" to "高", "full" to "全 (慢)",
                               "maximum" to "最高 (很慢)"),
                        padLayout.ps2BlendingAccuracy
                    ) { updateLayout(padLayout.copy {ps2BlendingAccuracy = it}) }
                    DropdownRow("MTVU (多线程 VU1)",
                        listOf("enabled" to "开启 (推荐)", "disabled" to "关闭 (个别游戏)"),
                        padLayout.ps2Mtvu
                    ) { updateLayout(padLayout.copy {ps2Mtvu = it}) }
                    DropdownRow("Instant VU1",
                        listOf("enabled" to "开启 (推荐)", "disabled" to "关闭"),
                        padLayout.ps2InstantVu1
                    ) { updateLayout(padLayout.copy {ps2InstantVu1 = it}) }
                    DropdownRow("EE 周期率",
                        listOf("-3" to "50% (降频)", "-2" to "60% (降频)", "-1" to "75% (降频)",
                               "0" to "100% (默认)", "1" to "130% (超频)", "2" to "180% (超频)",
                               "3" to "300% (超频)"),
                        padLayout.ps2EeCycleRate
                    ) { updateLayout(padLayout.copy {ps2EeCycleRate = it}) }
                    DropdownRow("EE 周期跳过",
                        listOf("0" to "关闭 (默认)", "1" to "轻度", "2" to "中度", "3" to "最大"),
                        padLayout.ps2EeCycleSkip
                    ) { updateLayout(padLayout.copy {ps2EeCycleSkip = it}) }
                }

                SettingsSection("PS2 · 系统 / 补丁") {
                    DropdownRow("快速启动 (跳过 BIOS 动画)",
                        listOf("enabled" to "开启 (推荐)", "disabled" to "关闭 (看 BIOS 画面)"),
                        padLayout.ps2FastBoot
                    ) { updateLayout(padLayout.copy {ps2FastBoot = it}) }
                    DropdownRow("手柄震动",
                        listOf("enabled" to "开启", "disabled" to "关闭"),
                        padLayout.ps2Rumble
                    ) { updateLayout(padLayout.copy {ps2Rumble = it}) }
                    DropdownRow("宽屏补丁",
                        listOf("disabled" to "关闭", "enabled" to "开启 (16:9)"),
                        padLayout.ps2Widescreen
                    ) { updateLayout(padLayout.copy {ps2Widescreen = it}) }
                    DropdownRow("去隔行补丁 (逐行输出)",
                        listOf("disabled" to "关闭", "enabled" to "开启"),
                        padLayout.ps2NoInterlace
                    ) { updateLayout(padLayout.copy {ps2NoInterlace = it}) }
                }

                SettingsSection("PS2 · BIOS 管理") {
                    Text(
                        "PCSX2 必须要真实 PS2 BIOS 才能启动游戏。下方可导入 BIOS 文件到 " +
                        "ps2/pcsx2/bios/ 目录 (旧版 ps2/bios/ 的文件会自动迁移)。\n" +
                        "支持镜像: .iso / .chd / .cso / .zso / .cue+bin / .gz / .mdf / .mds / " +
                        ".nrg / .elf；MDF/MDS 组合镜像在 PS2 平台页导入会自动识别。",
                        color = Color(0xFF4A5568), fontSize = 10.sp, lineHeight = 14.sp)
                    Psx2BiosImportSection()
                }
            }
        }

        // === 遮罩 / 按钮主题（所有核心统一入口，配置按核心独立存储在
        // PadLayout.overlayThemeJson，见 OverlayTheme.kt） ===
        item { OverlayThemeSection(platform, padLayout, updateLayout) }
    }
}
