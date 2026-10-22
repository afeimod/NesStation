package com.nesstation.app.ui.settings

// ★ v21: 核心设置可读性三阶段 —— 用户 NEON 主题截图反馈：安装驱动/选择密钥等
//   按钮行主体文本（TextButtonRow label）仍是灰字体"一直没有高亮"。根因 =
//   label 颜色分支只有 !enabled/dark/else 三支，Neon 深色主题下落入为浅色
//   面板设计的 0xFF1E2A3A 深灰蓝（深底上即灰暗看不清）。本轮补 Neon 分支
//   （Neon.TextHi），一次覆盖驱动列表/安装/删除/选择密钥/安装固件/
//   导入 Lossless.dll 等全部按钮行 label。

// ★ v19: 核心设置可读性二阶段 —— v17 只亮了各区介绍段落（用户反馈"高亮了个
//   寂寞"），本轮把剩余全部灰色说明文字统一亮黄化（0xFFFDD835）：
//   · 18 处 `if (Neon) Neon.TextDim else Color(0xFF4A5568)` 选项/提示行说明
//     → 直接 Color(0xFFFDD835)（Neon 分支一并替换，Neon 主题不再回落灰字）；
//   · 2 处 0xFF8899AA 说明行（Java 按键映射/滤镜提示）与 2 处 0xFF667788
//     10sp 提示（MMJ 说明）→ 同黄；
//   · 唯一保留灰 0xFF8899AA = TextButtonRow !enabled 禁用态（语义需要）。

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import kotlinx.coroutines.launch
import com.nesstation.app.core.model.GamePlatform
import com.nesstation.app.core.storage.CemuDriverStore
import com.nesstation.app.core.storage.PadLayout
import com.nesstation.app.core.storage.JAVA_MAPPABLE_BUTTONS
import com.nesstation.app.core.storage.JAVA_PHONE_KEY_OPTIONS
import com.nesstation.app.core.storage.javaButtonKeyMapGet
import com.nesstation.app.core.storage.javaButtonKeyMapSet
import com.nesstation.app.ui.emulator.PspCoreOptions
import com.nesstation.app.ui.emulator.Psx2BiosImportSection
import com.nesstation.app.ui.emulator.PspSettingsStore

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
                SettingsSection("FC / NES (FCEUX)") {
                    // ★ FC 设置生效修复：FC 核心已换成 fceux 引擎（非 fceumm），
                    //   本页选项逐一映射到 fceux 原生 API（rom_loader.cpp
                    //   setCoreOption）。NTSC 滤镜/调色板选项值 = fceux 真实
                    //   能力：NTSC 色彩生成（FCEUI_SetNTSCTH）、灰度（黑白）、
                    //   内嵌 FirebrandX 权威色表（nes_palettes.h）。
                    DropdownRow("NTSC 滤镜",
                        listOf("disabled" to "关闭", "ntsc" to "NTSC 色彩生成", "monochrome" to "黑白"),
                        padLayout.ntscFilter
                    ) { updateLayout(padLayout.copy {ntscFilter = it}) }

                    DropdownRow("调色板",
                        listOf(
                            "default" to "默认",
                            "composite-direct-fbx" to "FBX 复合直通",
                            "ntsc-hardware-fbx" to "FBX NTSC 硬件",
                            "nes-classic-fbx" to "FBX NES Classic",
                            "wii-vc" to "Wii VC",
                            "restored-wii-vc" to "Wii VC (修复版)",
                            "pal" to "PAL",
                            "smooth-fbx" to "FBX 平滑",
                            "digital-prime-fbx" to "FBX Digital Prime",
                            "magnum-fbx" to "FBX Magnum",
                            "pvm-style-d93-fbx" to "FBX PVM D93",
                            "royaltea" to "Royal Tea"
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
                        // ★ v19: 剩余说明/提示文字亮黄化（用户反馈"高亮了个寂寞，剩下还是灰色"）
                        color = Color(0xFFFDD835), fontSize = 11.sp, lineHeight = 15.sp
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
                // === NDS 双核心设置分离（melonDS / DraStic 激烈互不掺杂） ===
                // 三个区域：双核心通用（视图层实现，选任一核心都生效）、
                // melonDS 专属（仅选 melonDS 运行时读取）、激烈专属（仅选
                // 激烈运行时读取）。applyCoreOptions 已按引擎类型分流，
                // 两个核心的选项绝不交叉下发。
                SettingsSection("NDS 通用 · 双核心共用") {
                    DropdownRow("屏幕排列",
                        listOf("Top/Bottom" to "上下排列", "Bottom/Top" to "下上排列",
                               "Left/Right" to "左右排列", "Right/Left" to "右左排列",
                               "Top Only" to "仅上方屏", "Bottom Only" to "仅下方屏",
                               "Hybrid Top" to "混合(上屏大)", "Hybrid Bottom" to "混合(下屏大)"),
                        padLayout.ndsScreenLayout
                    ) { updateLayout(padLayout.copy {ndsScreenLayout = it}) }
                    Text(
                        "启动 NDS 游戏时会先选择核心（melonDS / 激烈二选一）。" +
                        "「屏幕排列」由前端视图层实现，对两个核心都生效；" +
                        "画面缩放、滤镜、遮罩等画质设置在「设置 → 画面」中全局配置。" +
                        "下方两个专属区域的设置互相独立：选哪个核心运行，就只读取对应区域的值。",
                        color = Color(0xFFFDD835), fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                    Text(
                        "存档方式为全局设置（所有核心通用），位于「设置 → 存储 → 存档方式」" +
                        "（NDS 写 .sav 兼容官方 melonDS，其他核心写 .srm）。",
                        color = Color(0xFFFDD835), fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
                SettingsSection("melonDS 专属") {
                    DropdownRow("主机模式",
                        listOf("DS" to "DS", "DSi" to "DSi"),
                        padLayout.ndsConsoleMode
                    ) { updateLayout(padLayout.copy {ndsConsoleMode = it}) }
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
                        Text(
                            "★ 选择 xBR/HQx 放大滤镜时，内部分辨率会话内自动按 1x 运行" +
                            "以保证滤镜生效（滤镜优先，此设置原样保留，滤镜关闭后恢复）；" +
                            "若画面未立即变化，重进游戏后必生效。",
                            color = Color(0xFFFDD835), fontSize = 11.sp, lineHeight = 15.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
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
                // DraStic（激烈）核心专属设置 —— 与 melonDS 完全独立。
                // 激烈核心为原生推模型（模拟主循环 + OpenSL 音频 + GL/Canvas 渲染），
                // 下方选项仅在选择激烈核心运行时生效；选 melonDS 时整区忽略。
                // 选项集合/默认值/位域全部对照原版 DraStic r2.6.0.4a APK：
                // jadx 反编译 f0.h（SharedPreferences ↔ config 位域打包 f0.h.n()）
                // + libdrastic_arm64.so 反汇编消费链路验证。
                SettingsSection("DraStic（激烈）专属 · 画面渲染") {
                    // 高清渲染 = config bit41：原生以 512×384（2x）内部分辨率渲染。
                    // GL 显示路径按 2x 纹理上传 —— 3D 游戏画面细腻度提升最直观。
                    DropdownRow("高清渲染 (2x 分辨率)",
                        listOf("disabled" to "关闭 256×192 (默认, 原生分辨率)",
                               "enabled" to "开启 512×384 (2x 高清)"),
                        padLayout.ndsDrasticHdRender
                    ) { updateLayout(padLayout.copy {ndsDrasticHdRender = it}) }
                    Text(
                        "开启后内部分辨率翻倍（256×192 → 512×384），画面细节与 3D 模型边缘显著改善。" +
                        "需重进游戏生效；高清模式自动使用 GL 显示路径。低端设备若帧率下降可关闭。" +
                        "★ 与放大滤镜（xBR/HQx 系）可同时生效：高清会话下原生对帧池做 2:1 降采样供滤镜使用，" +
                        "两个特性无需二选一。",
                        color = Color(0xFFFDD835), fontSize = 11.sp, lineHeight = 15.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                    DropdownRow("显示方式",
                        listOf("gl" to "GL 加速 (默认, 复刻原版显示路径)",
                               "canvas" to "画布位图 (兼容模式)"),
                        padLayout.ndsDrasticDisplayMode
                    ) { updateLayout(padLayout.copy {ndsDrasticDisplayMode = it}) }
                    Text(
                        "GL 加速：原生 renderFrame 直接把帧池上传为 GPU 纹理绘制，" +
                        "零位图拷贝、支持高清纹理与双线性缩放；EGL 异常时自动回退画布。" +
                        "画布：传统位图路径，兼容性兜底。",
                        color = Color(0xFFFDD835), fontSize = 11.sp, lineHeight = 15.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                    DropdownRow("画面平滑滤波",
                        listOf("enabled" to "双线性 (平滑)", "disabled" to "最近邻 (锐利像素)"),
                        padLayout.ndsDrasticSmoothFilter
                    ) { updateLayout(padLayout.copy {ndsDrasticSmoothFilter = it}) }
                    // 跳帧类型 = config bits5-7（_FrameskipType）
                    DropdownRow("跳帧 (Frameskip)",
                        listOf("none" to "关闭 (默认, 满帧渲染)",
                               "manual" to "手动 (按下方跳帧值)",
                               "auto" to "自动 (按性能动态跳帧)"),
                        padLayout.ndsDrasticFrameskipType
                    ) { updateLayout(padLayout.copy {ndsDrasticFrameskipType = it}) }
                    // 跳帧值 = config bits0-3（_FrameskipValue，原版默认 4）
                    DropdownRow("跳帧值",
                        (0..9).map { it.toString() to (if (it == 4) "$it (默认)" else it.toString()) },
                        padLayout.ndsDrasticFrameskipValue
                    ) { updateLayout(padLayout.copy {ndsDrasticFrameskipValue = it}) }
                    // 安全跳帧 = config bit47（_FrameskipSafe）
                    DropdownRow("安全跳帧",
                        listOf("disabled" to "关闭 (默认)", "enabled" to "开启 (跳帧时不撕裂双屏)"),
                        padLayout.ndsDrasticFrameskipSafe
                    ) { updateLayout(padLayout.copy {ndsDrasticFrameskipSafe = it}) }
                    // 多线程 3D = config bit28（_Threaded3D，原版默认关）
                    DropdownRow("多线程 3D 渲染",
                        listOf("disabled" to "关闭 (默认, 兼容性最好)",
                               "enabled" to "开启 (3D 大作提速, 个别游戏可能画面异常)"),
                        padLayout.ndsDrasticThreaded3D
                    ) { updateLayout(padLayout.copy {ndsDrasticThreaded3D = it}) }
                    Text(
                        "原版提示：提升 3D 重度游戏的性能，但部分游戏可能出现画面异常" +
                        "（如双屏互换）；遇到渲染问题请关闭本选项。",
                        color = Color(0xFFFDD835), fontSize = 11.sp, lineHeight = 15.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                    // 16 位渲染 = config bit23（_GlUse16Bit）
                    DropdownRow("16 位渲染",
                        listOf("disabled" to "关闭 32 位 (默认, 画质最好)",
                               "enabled" to "开启 16 位 (老设备提速)"),
                        padLayout.ndsDrastic16Bit
                    ) { updateLayout(padLayout.copy {ndsDrastic16Bit = it}) }
                    // 禁用边缘标记 = config bit40（_DisableEdgeMarking）
                    DropdownRow("禁用边缘标记 (Edge-Marking)",
                        listOf("disabled" to "保留 3D 描边 (默认)",
                               "enabled" to "禁用 (提速; 个别游戏轮廓线消失)"),
                        padLayout.ndsDrasticEdgeMarking
                    ) { updateLayout(padLayout.copy {ndsDrasticEdgeMarking = it}) }
                    // 主屏固定上屏 = config bit35（_FixMainEngineScreen）
                    DropdownRow("主屏固定在上屏",
                        listOf("disabled" to "关闭 (默认, 按游戏自身输出)",
                               "enabled" to "开启 (修复少数游戏主屏错位)"),
                        padLayout.ndsDrasticFixMainScreen
                    ) { updateLayout(padLayout.copy {ndsDrasticFixMainScreen = it}) }
                }
                SettingsSection("DraStic（激烈）专属 · 模拟线程 / 快进 / 金手指") {
                    // 模拟线程数 = config bits16-19（原版按核数自动 1/2/3，threads.cfg 可 1-8）
                    DropdownRow("模拟线程数",
                        listOf("0" to "自动 (默认: ≥4核=3, ≥2核=2, 其余=1)") +
                            (1..8).map { it.toString() to "强制 $it 线程" },
                        padLayout.ndsDrasticThreads
                    ) { updateLayout(padLayout.copy {ndsDrasticThreads = it}) }
                    Text(
                        "3D 游戏按 16 行/段分段光栅化，线程数决定并行度。若 3D 画面只显示" +
                        "顶部一条（其余黑屏），请保持「自动」或调高线程数 —— 原版从不以 " +
                        "0/单线程低并行度启动。修改后重进游戏完全生效。",
                        color = Color(0xFFFDD835), fontSize = 11.sp, lineHeight = 15.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                    // 快进速率 = config bits12-15（_FfwdSpeed，原版默认 200%）
                    DropdownRow("快进速率",
                        listOf("0" to "50% (慢速)",
                               "1" to "150%",
                               "2" to "200% (默认)",
                               "3" to "300%",
                               "4" to "400%",
                               "5" to "无限制"),
                        padLayout.ndsDrasticFfwdRate
                    ) { updateLayout(padLayout.copy {ndsDrasticFfwdRate = it}) }
                    // 连发速度 = config bits32-34（_AutoFireSpeed，原版默认 2）
                    DropdownRow("连发 (Rapid-Fire) 速度",
                        listOf("0" to "速度 1 (最快)", "1" to "速度 2", "2" to "速度 3 (默认)",
                               "3" to "速度 4", "4" to "速度 5 (最慢)"),
                        padLayout.ndsDrasticAutofireSpeed
                    ) { updateLayout(padLayout.copy {ndsDrasticAutofireSpeed = it}) }
                    // 金手指 = config bit27（_CheatsEnabled）
                    DropdownRow("金手指",
                        listOf("enabled" to "启用 (默认, ACTION REPLAY 生效)",
                               "disabled" to "禁用"),
                        padLayout.ndsDrasticCheatsEnabled
                    ) { updateLayout(padLayout.copy {ndsDrasticCheatsEnabled = it}) }
                    // Lua = config bit42（_LuaEnabled）
                    DropdownRow("Lua 脚本",
                        listOf("enabled" to "启用 (默认)", "disabled" to "禁用"),
                        padLayout.ndsDrasticLuaEnabled
                    ) { updateLayout(padLayout.copy {ndsDrasticLuaEnabled = it}) }
                }
                SettingsSection("DraStic（激烈）专属 · 音频 / 麦克风") {
                    DropdownRow("音量",
                        (0..100).step(10)
                            .map { it.toString() to (if (it == 0) "静音" else "$it%") },
                        padLayout.ndsDrasticVolume
                    ) { updateLayout(padLayout.copy {ndsDrasticVolume = it}) }
                    // 声音开关 = config bit31（_SoundEnabled）
                    DropdownRow("声音",
                        listOf("enabled" to "开启", "disabled" to "关闭"),
                        padLayout.ndsDrasticSound
                    ) { updateLayout(padLayout.copy {ndsDrasticSound = it}) }
                    // 音频延迟 = config bits8-9（_AudioLatency，原版默认 3=极高）
                    DropdownRow("音频延迟",
                        listOf("0" to "低 (延迟最小, 易爆音)",
                               "1" to "中",
                               "2" to "高",
                               "3" to "极高 (默认, 最稳定)"),
                        padLayout.ndsDrasticAudioLatency
                    ) { updateLayout(padLayout.copy {ndsDrasticAudioLatency = it}) }
                    // 麦克风启用 = config bit26（_MicEnabled）
                    DropdownRow("设备麦克风",
                        listOf("enabled" to "启用 (默认, 对麦克风吹气/发声有效)",
                               "disabled" to "禁用"),
                        padLayout.ndsDrasticMicEnabled
                    ) { updateLayout(padLayout.copy {ndsDrasticMicEnabled = it}) }
                    // 麦克风等级 = config bits37-38（_MicLevel，原版默认 1）
                    DropdownRow("麦克风灵敏度",
                        listOf("0" to "1 级 (最低)", "1" to "2 级 (默认)",
                               "2" to "3 级", "3" to "4 级 (最高)"),
                        padLayout.ndsDrasticMicLevel
                    ) { updateLayout(padLayout.copy {ndsDrasticMicLevel = it}) }
                }
                SettingsSection("DraStic（激烈）专属 · 系统 / 高级") {
                    // Slot2 卡带 = config bits43-46（_Slot2Type，原版默认 GBA 卡）
                    DropdownRow("Slot2 (GBA 卡槽) 类型",
                        listOf("none" to "无",
                               "gba" to "GBA 卡 (默认, GBA 联动游戏)",
                               "sram" to "SRAM 存储卡",
                               "rumble" to "震动卡",
                               "motion_official" to "体感卡 (官方)",
                               "motion_homebrew" to "体感卡 (Homebrew)"),
                        padLayout.ndsDrasticSlot2Type
                    ) { updateLayout(padLayout.copy {ndsDrasticSlot2Type = it}) }
                    // RTC = config bit39（_RtcSystemTime）
                    DropdownRow("RTC 使用系统时间",
                        listOf("disabled" to "关闭 (默认, 游戏内时钟独立走)",
                               "enabled" to "开启 (跟随设备时钟)"),
                        padLayout.ndsDrasticRtcSystemTime
                    ) { updateLayout(padLayout.copy {ndsDrasticRtcSystemTime = it}) }
                    // 原生 FPS = config bit30（_ShowFPS）
                    DropdownRow("原生 FPS 叠加",
                        listOf("disabled" to "关闭 (默认)", "enabled" to "开启 (画面内左上角)"),
                        padLayout.ndsDrasticShowFps
                    ) { updateLayout(padLayout.copy {ndsDrasticShowFps = it}) }
                    // 即时存档内含游戏存档 = config bit25（_BackupInSavestates）
                    DropdownRow("即时存档内含游戏存档",
                        listOf("enabled" to "包含 (默认, 读档不丢进度)",
                               "disabled" to "不包含"),
                        padLayout.ndsDrasticBackupInSavestates
                    ) { updateLayout(padLayout.copy {ndsDrasticBackupInSavestates = it}) }
                    // 忽略卡带容量 = config bit24（_IgnoreGamecardLimit）
                    DropdownRow("忽略卡带容量限制",
                        listOf("disabled" to "关闭 (默认)", "enabled" to "开启 (超大 ROM 兼容)"),
                        padLayout.ndsDrasticIgnoreCardLimit
                    ) { updateLayout(padLayout.copy {ndsDrasticIgnoreCardLimit = it}) }
                    // ROM 自动裁边 = config bit36（_AutoTrim）
                    DropdownRow("ROM 自动裁边 (Auto-Trim)",
                        listOf("disabled" to "关闭 (默认)", "enabled" to "开启 (节省内存)"),
                        padLayout.ndsDrasticAutoTrim
                    ) { updateLayout(padLayout.copy {ndsDrasticAutoTrim = it}) }
                    // 预解压到内存 = config bit48（_PreloadRoms）
                    DropdownRow("预解压 ROM 到内存",
                        listOf("disabled" to "关闭 (默认)", "enabled" to "开启 (zip ROM 提速, 占内存)"),
                        padLayout.ndsDrasticPreloadRoms
                    ) { updateLayout(padLayout.copy {ndsDrasticPreloadRoms = it}) }
                    // 自动存档间隔（setAutosaveInterval）
                    DropdownRow("自动存档间隔",
                        listOf("0" to "关闭 (默认)", "300" to "5 分钟",
                               "900" to "15 分钟", "1800" to "30 分钟"),
                        padLayout.ndsDrasticAutosave
                    ) { updateLayout(padLayout.copy {ndsDrasticAutosave = it}) }
                    // 注：激烈核心的独立存档格式（sav/dsv）选项已移除 ——
                    // 电池存档恒为裸 .sav，位置与格式完全跟随全局存档方式
                    //（设置 → 存储 → 存档方式），与 melonDS 同一份文件互通。
                    Text(
                        "存档跟随全局设置：电池存档固定为裸 .sav，位置由「设置 → 存储 → 存档方式」决定" +
                        "（统一存档目录 = saves/<gameId>.sav；核心自带位置 = ROM 同目录同名）。" +
                        "与 melonDS 核心共用同一份游戏存档，切换核心进度不丢（旧 .dsv 存档进游戏时自动迁移）。",
                        color = Color(0xFFFDD835), fontSize = 11.sp, lineHeight = 15.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                    Text(
                        "以上设置仅在启动 NDS 游戏时选择「DraStic（激烈）」核心后生效，" +
                        "melonDS 核心完全忽略（反之亦然，两套核心互不读取对方设置）。" +
                        "大部分选项运行中修改即时生效；高清渲染 / 线程数 / Slot2 需重进游戏。" +
                        "全部默认值 = 原版 DraStic 出厂设置。",
                        color = Color(0xFFFDD835), fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
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
                        color = Color(0xFFFDD835), fontSize = 10.sp, lineHeight = 14.sp)
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
                        color = Color(0xFFFDD835), fontSize = 10.sp, lineHeight = 14.sp)
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
                        color = Color(0xFFFDD835), fontSize = 10.sp, lineHeight = 14.sp)
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
            GamePlatform.PS2 -> item {
                // PCEE2 核心 — 上游 PCSX2 (v2.7.523) 的 libretro 官方 Android 构建。
                // 键名/取值已对照 pcee2-libretro 源码 Libretro.cpp definitions[] 表验证
                // (WizzardSK/pcee2-libretro, 与预编译 libpcee2_libretro_android.so 同源)。
                SettingsSection("PS2 (PCSX2) · 画面") {
                    // ARMSX2 核心已启用 Vulkan (USE_VULKAN=ON)。★ v22: 官方
                    // pcsx2_renderer 无 "auto" 值（默认 vulkan，上游选项表实证）
                    // —— applyCoreOptions 发送侧已归一 auto→vulkan；opengl 用
                    // OpenGL ES，vulkan 强制 Vulkan，software 用 CPU 软渲染。均可即时切换。
                    DropdownRow("渲染器",
                        listOf("auto" to "自动 (Vulkan 优先)",
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
                    // ★ v22: PCSX2/pcee2 核心无自定义 HD 贴图选项（上游
                    //   libretro_core_options.h 全表实证）—— 明示等效增强路径，
                    //   避免在不存在的能力上反复试错。
                    Text("PS2 核心不支持自定义 HD 贴图包；等效画面增强 = 分辨率倍数 / 双线性·三线性·各向异性过滤 / 纹理预加载（个别项重进游戏生效）。",
                        color = Color(0xFFFDD835),
                        fontSize = 10.sp, lineHeight = 14.sp)
                }

                SettingsSection("PS2 · 性能 (速度作弊)") {
                    DropdownRow("GPU 回读模式",
                        // ★ v22: 移除 no_readbacks/async —— 官方 pcsx2_hw_download_mode
                        //   仅 accurate|unsynchronized|disabled 三值，旧选项是无效值
                        //   （选了被核心静默忽略 = "没有正确生效"）；apply 侧已归一存量脏值
                        listOf("accurate" to "精准 (慢, 特效完整)",
                               "unsynchronized" to "非同步 (快)",
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
                        // ★ v17: 安装区提示改亮色（用户反馈“灰色看不清”）
                        color = if (com.nesstation.app.ui.neon.NeonUi.isNeon) com.nesstation.app.ui.neon.Neon.Accent else Color(0xFF27AE60), fontSize = 10.sp, lineHeight = 14.sp)
                    Psx2BiosImportSection()
                }
            }
            GamePlatform.DC -> item {
                // libretro Flycast 核心选项（reicast_*）。与 PSX/PS2 面板同一
                // 模式：设置存 PadLayout，启动/运行时经 applyCoreOptions →
                // DcEngine.setCoreOption 下发。键名/取值已对照预编译
                // libflycast_libretro_android.so 字符串提取校验。
                SettingsSection("DC (Flycast) · 画面") {
                    DropdownRow("内部分辨率",
                        listOf(
                            "320x240 (Half)" to "0.5x (320x240, 提速)",
                            "640x480 (Native)" to "1x (640x480 原生, 默认)",
                            "1280x960 (x2)" to "2x (1280x960)",
                            "1600x1200 (x2.5)" to "2.5x (1600x1200)",
                            "1920x1440 (x3)" to "3x (1920x1440)",
                            "2560x1920 (x4)" to "4x (2560x1920)",
                            "3840x2880 (x6)" to "6x (3840x2880 高配专用)"
                        ),
                        padLayout.dcResolution
                    ) { updateLayout(padLayout.copy {dcResolution = it}) }
                    DropdownRow("透明排序精度",
                        listOf(
                            "per-strip (fast, least accurate)" to "按条带 (最快, 可能闪面)",
                            "per-triangle (normal)" to "按三角形 (默认, 推荐)",
                            "per-pixel (accurate)" to "逐像素 (精确, 较慢)"
                        ),
                        padLayout.dcAlphaSorting
                    ) { updateLayout(padLayout.copy {dcAlphaSorting = it}) }
                    DropdownRow("渲染线程化",
                        listOf("enabled" to "开启 (推荐·多核提速明显)", "disabled" to "关闭"),
                        padLayout.dcThreadedRendering
                    ) { updateLayout(padLayout.copy {dcThreadedRendering = it}) }
                    DropdownRow("宽屏 16:9 视锥拉伸",
                        listOf("disabled" to "关闭 (4:3 原生)", "enabled" to "开启 (16:9)"),
                        padLayout.dcWidescreenHack
                    ) { updateLayout(padLayout.copy {dcWidescreenHack = it}) }
                    DropdownRow("宽屏游戏兼容补丁",
                        listOf("disabled" to "关闭", "enabled" to "开启 (部分游戏 16:9 无拉伸)"),
                        padLayout.dcWidescreenCheats
                    ) { updateLayout(padLayout.copy {dcWidescreenCheats = it}) }
                    DropdownRow("延迟帧交换 (防撕裂)",
                        listOf("enabled" to "开启 (推荐)", "disabled" to "关闭 (低延迟)"),
                        padLayout.dcDelayFrameSwapping
                    ) { updateLayout(padLayout.copy {dcDelayFrameSwapping = it}) }
                    DropdownRow("跳帧",
                        listOf("disabled" to "关闭 (默认)", "enabled" to "开启 (保帧率)"),
                        padLayout.dcFrameSkipping
                    ) { updateLayout(padLayout.copy {dcFrameSkipping = it}) }
                    DropdownRow("帧数限制",
                        listOf(
                            "0" to "不限 (跟随游戏制式, 默认)",
                            "60" to "60 FPS",
                            "50" to "50 FPS (PAL 制式)",
                            "30" to "30 FPS (部分游戏原速)"
                        ),
                        padLayout.dcFrameLimit
                    ) { updateLayout(padLayout.copy {dcFrameLimit = it}) }
                }

                SettingsSection("DC (Flycast) · 系统") {
                    DropdownRow("主机区域",
                        listOf(
                            "Default" to "默认 (跟随游戏)",
                            "Japan" to "日本",
                            "USA" to "美国",
                            "Europe" to "欧洲"
                        ),
                        padLayout.dcRegion
                    ) { updateLayout(padLayout.copy {dcRegion = it}) }
                    DropdownRow("主机语言",
                        listOf(
                            "Default" to "默认", "Japanese" to "日语", "English" to "英语 (默认)",
                            "German" to "德语", "French" to "法语", "Spanish" to "西班牙语",
                            "Italian" to "意大利语"
                        ),
                        padLayout.dcLanguage
                    ) { updateLayout(padLayout.copy {dcLanguage = it}) }
                    DropdownRow("电视制式",
                        listOf(
                            "Default" to "默认", "NTSC" to "NTSC (默认)",
                            "PAL" to "PAL", "PAL-M" to "PAL-M", "PAL-N" to "PAL-N"
                        ),
                        padLayout.dcBroadcast
                    ) { updateLayout(padLayout.copy {dcBroadcast = it}) }
                    DropdownRow("视频输出",
                        listOf(
                            "TV (Composite)" to "TV 复合 (默认)",
                            "VGA" to "VGA"
                        ),
                        padLayout.dcCableType
                    ) { updateLayout(padLayout.copy {dcCableType = it}) }
                    DropdownRow("HLE BIOS (免真实 BIOS)",
                        listOf("disabled" to "关闭 (已内置真实 BIOS, 推荐)", "enabled" to "开启"),
                        padLayout.dcHleBios
                    ) { updateLayout(padLayout.copy {dcHleBios = it}) }
                    DropdownRow("32MB 内存改机",
                        listOf("disabled" to "关闭 (16MB 原生)", "enabled" to "开启 (个别游戏/补丁需要)"),
                        padLayout.dcRam32mb
                    ) { updateLayout(padLayout.copy {dcRam32mb = it}) }
                    DropdownRow("强制 WinCE 模式",
                        listOf("disabled" to "关闭 (默认)", "enabled" to "开启 (个别 WinCE 游戏需要)"),
                        padLayout.dcForceWince
                    ) { updateLayout(padLayout.copy {dcForceWince = it}) }
                }

                SettingsSection("DC (Flycast) · 性能 / 音频") {
                    DropdownRow("GD-ROM 快速读盘",
                        listOf("enabled" to "开启 (推荐, 缩短读盘时间)", "disabled" to "关闭 (原机速度)"),
                        padLayout.dcGdromFastLoading
                    ) { updateLayout(padLayout.copy {dcGdromFastLoading = it}) }
                    DropdownRow("AICA DSP 音效",
                        listOf("disabled" to "关闭 (默认)", "enabled" to "开启 (个别游戏音效需要, 较慢)"),
                        padLayout.dcEnableDsp
                    ) { updateLayout(padLayout.copy {dcEnableDsp = it}) }
                    Text(
                        "BIOS 与街机 BIOS 包 (dc_boot.bin / dc_flash.bin / naomi.zip / " +
                        "awbios.zip ...) 已内置在 assets/dc/，首次启动自动识别部署到 " +
                        "filesDir/dc/。NAOMI / AtomisWave 游戏 (.zip) 与 DC 光盘游戏" +
                        " (.gdi/.cdi/.cue/.chd/.iso) 均无需手动导入 BIOS。",
                        color = Color(0xFFFDD835), fontSize = 10.sp, lineHeight = 14.sp)
                }
            }
            GamePlatform.N3DS -> item {
                // Azahar 核心选项 —— 引擎以 "段/键" 复合键直写用户目录
                // config/config.ini 后 reloadSettings()。键名/取值对照
                // azahar-emu/azahar src/android jni/config.cpp（2125.x）。
                SettingsSection("3DS (Azahar) · GPU 驱动") {
                    // ★★ GPU 驱动安装与选择（本轮新增，对齐上游 Azahar）★★：
                    //   本地导入 adrenotools 驱动 zip（Turnip 等）+ 列表选择 +
                    //   删除；AzaharEngine 启动时把选中驱动的 libraryName 传给
                    //   initializeGpuDriver（空 = 系统驱动）。
                    com.nesstation.app.ui.emulator.AzaharDriverSection()
                }
                SettingsSection("3DS (Azahar) · 画面 / 性能") {
                    DropdownRow("图形后端",
                        // ★ VK 标签修正：缺失 adrenotools 钩子链时选 VK 会闪退，
                        //   钩子链由 CMake 从 ARMSX2 vendored adrenotools 源码构建
                        //   （勿放 jniLibs——会与 CMake 输出同名冲突）；仍按实测
                        //   性能中性标注。
                        listOf("opengl" to "OpenGL (兼容, 推荐)", "vulkan" to "Vulkan (需钩子库, 已内置)", "software" to "软件渲染 (慢)"),
                        padLayout.azGraphicsApi
                    ) { updateLayout(padLayout.copy {azGraphicsApi = it}) }
                    DropdownRow("内部分辨率",
                        listOf("0" to "自动 (适配窗口)", "1" to "1x (400x240 原生)", "2" to "2x (800x480)",
                               "3" to "3x (1200x720)", "4" to "4x (1600x960)", "5" to "5x (2000x1200)"),
                        padLayout.azResolution
                    ) { updateLayout(padLayout.copy {azResolution = it}) }
                    DropdownRow("硬件着色器",
                        listOf("enabled" to "开启 (性能关键, 默认)", "disabled" to "关闭"),
                        padLayout.azUseHwShader
                    ) { updateLayout(padLayout.copy {azUseHwShader = it}) }
                    DropdownRow("着色器 JIT",
                        listOf("enabled" to "开启 (默认)", "disabled" to "关闭"),
                        padLayout.azUseShaderJit
                    ) { updateLayout(padLayout.copy {azUseShaderJit = it}) }
                    DropdownRow("垂直同步",
                        // ★★ 标注对齐核心推荐（libazahar.so 配置模板原文：
                        //   "Can cause input delay, so only turn this on if you have
                        //    screen tearing, which is unusual on Android. 0 (default): Off"）。
                        //   VSync 开启时渲染帧率与刷新率不匹配会周期性丢帧
                        //   （"时不时卡顿"元凶），默认关闭。
                        listOf("disabled" to "关闭 (默认·低延迟不卡顿)", "enabled" to "开启 (仅防撕裂·可能卡顿)"),
                        padLayout.azUseVsync
                    ) { updateLayout(padLayout.copy {azUseVsync = it}) }
                    DropdownRow("磁盘着色器缓存",
                        listOf("enabled" to "开启 (推荐, 防卡顿)", "disabled" to "关闭"),
                        padLayout.azUseDiskShaderCache
                    ) { updateLayout(padLayout.copy {azUseDiskShaderCache = it}) }
                    DropdownRow("异步呈现",
                        listOf("enabled" to "开启 (降延迟, 默认)", "disabled" to "关闭"),
                        padLayout.azAsyncPresentation
                    ) { updateLayout(padLayout.copy {azAsyncPresentation = it}) }
                    DropdownRow("异步着色器编译",
                        listOf("enabled" to "开启 (后台编译, 默认)", "disabled" to "关闭"),
                        padLayout.azAsyncShaderCompilation
                    ) { updateLayout(padLayout.copy {azAsyncShaderCompilation = it}) }
                    DropdownRow("精确乘法",
                        listOf("disabled" to "关闭 (默认)", "enabled" to "开启 (个别游戏需要)"),
                        padLayout.azAccurateMultiplication
                    ) { updateLayout(padLayout.copy {azAccurateMultiplication = it}) }
                    // ★★ 阴影渲染开关已删除（本轮需求："阴影渲染关闭也不起作用"根治）：
                    //   strings 扫描 libazahar.so 实证 150 个配置键中不存在
                    //   shadow_rendering —— 阴影由 GLSL/SPIRV 着色器管线无条件渲染
                    //   （DefineShadowHelpers/WriteShadow 为固定代码路径），写入
                    //   config.ini 无任何效果。保留开关只会误导用户，已移除；
                    //   PadLayoutStore.azShadowRendering 字段保留用于存量数据兼容。
                    DropdownRow("纹理过滤",
                        listOf("0" to "无", "1" to "Anime4K", "2" to "双三次", "3" to "ScaleForce", "4" to "xBRZ", "5" to "MMPX"),
                        padLayout.azTextureFilter
                    ) { updateLayout(padLayout.copy {azTextureFilter = it}) }
                    DropdownRow("纹理采样",
                        listOf("0" to "游戏自带", "1" to "最近邻", "2" to "线性"),
                        padLayout.azTextureSampling
                    ) { updateLayout(padLayout.copy {azTextureSampling = it}) }
                    DropdownRow("整数缩放",
                        listOf("disabled" to "关闭 (默认)", "enabled" to "开启 (像素风无畸变)"),
                        padLayout.azIntegerScaling
                    ) { updateLayout(padLayout.copy {azIntegerScaling = it}) }
                    DropdownRow("帧率上限",
                        listOf("50" to "50%", "100" to "100% (默认)", "200" to "200%", "300" to "300%", "400" to "400%"),
                        padLayout.azFrameLimit
                    ) { updateLayout(padLayout.copy {azFrameLimit = it}) }
                }
                SettingsSection("3DS (Azahar) · 3D / 布局") {
                    DropdownRow("立体 3D",
                        listOf("0" to "关", "1" to "并排", "2" to "并排 (全宽)", "3" to "红蓝", "4" to "交错", "5" to "反交错", "6" to "Cardboard VR"),
                        padLayout.azRender3d
                    ) { updateLayout(padLayout.copy {azRender3d = it}) }
                    DropdownRow("立体深度", (0..100 step 10).map { it.toString() to "$it%" },
                        padLayout.azFactor3d
                    ) { updateLayout(padLayout.copy {azFactor3d = it}) }
                    DropdownRow("屏幕布局",
                        listOf("0" to "默认 (上/下)", "1" to "单屏", "2" to "大屏", "3" to "左右", "4" to "混合", "5" to "自定义"),
                        padLayout.azLayoutOption
                    ) { updateLayout(padLayout.copy {azLayoutOption = it}) }
                    DropdownRow("屏幕间距", (0..500 step 100).map { it.toString() to "${it}px" },
                        padLayout.azScreenGap
                    ) { updateLayout(padLayout.copy {azScreenGap = it}) }
                    DropdownRow("大屏占比",
                        listOf("1.25" to "1.25 : 1", "1.5" to "1.5 : 1", "1.75" to "1.75 : 1", "2.0" to "2 : 1", "2.25" to "2.25 : 1 (默认)", "2.5" to "2.5 : 1", "3.0" to "3 : 1", "4.0" to "4 : 1"),
                        padLayout.azLargeScreenProportion
                    ) { updateLayout(padLayout.copy {azLargeScreenProportion = it}) }
                    DropdownRow("上下屏交换",
                        listOf("disabled" to "不交换 (默认)", "enabled" to "交换"),
                        padLayout.azSwapScreens
                    ) { updateLayout(padLayout.copy {azSwapScreens = it}) }
                    DropdownRow("自定义纹理 (HD 包)",
                        listOf("disabled" to "关闭 (默认)", "enabled" to "开启"),
                        padLayout.azCustomTextures
                    ) { updateLayout(padLayout.copy {azCustomTextures = it}) }
                    DropdownRow("预加载纹理",
                        listOf("disabled" to "关闭 (默认)", "enabled" to "开启"),
                        padLayout.azPreloadTextures
                    ) { updateLayout(padLayout.copy {azPreloadTextures = it}) }
                    // ★ v22: 自定义纹理包目录指引 —— 无包时开启无任何画面变化
                    //   （用户反馈 HD 类选项"没有正确生效"的同类主因）
                    Text("「自定义纹理 (HD 包)」需纹理包：解压到 Azahar 数据目录 load/textures/<游戏TitleID>/；无包时开启无画面变化。",
                        color = Color(0xFFFDD835),
                        fontSize = 10.sp, lineHeight = 14.sp)
                }
                SettingsSection("3DS (Azahar) · 系统 / 音频") {
                    DropdownRow("CPU 时钟",
                        listOf("50" to "50%", "100" to "100% (默认)", "200" to "200%", "300" to "300%", "400" to "400% (超频)"),
                        padLayout.azCpuClock
                    ) { updateLayout(padLayout.copy {azCpuClock = it}) }
                    DropdownRow("CPU JIT",
                        listOf("enabled" to "开启 (性能关键, 默认)", "disabled" to "关闭"),
                        padLayout.azUseCpuJit
                    ) { updateLayout(padLayout.copy {azUseCpuJit = it}) }
                    DropdownRow("New 3DS 模式",
                        listOf("enabled" to "开启 (更快 CPU, 默认)", "disabled" to "关闭 (老游戏兼容)"),
                        padLayout.azIsNew3ds
                    ) { updateLayout(padLayout.copy {azIsNew3ds = it}) }
                    DropdownRow("LLE 系统小程序",
                        listOf("disabled" to "关闭 (默认, 存档稳定)", "enabled" to "开启 (需完整 NAND 系统文件)"),
                        padLayout.azLleApplets
                    ) { updateLayout(padLayout.copy {azLleApplets = it}) }
                    DropdownRow("主机区域",
                        listOf("-1" to "自动", "0" to "日本", "1" to "美国", "2" to "欧洲", "3" to "澳大利亚", "4" to "中国", "5" to "韩国", "6" to "台湾"),
                        padLayout.azRegion
                    ) { updateLayout(padLayout.copy {azRegion = it}) }
                    DropdownRow("音频模拟",
                        listOf("0" to "HLE (快, 推荐)", "1" to "LLE", "2" to "LLE 多线程"),
                        padLayout.azAudioEmulation
                    ) { updateLayout(padLayout.copy {azAudioEmulation = it}) }
                    DropdownRow("音量", (0..100 step 10).map { it.toString() to "$it%" },
                        padLayout.azVolume
                    ) { updateLayout(padLayout.copy {azVolume = it}) }
                    DropdownRow("音频拉伸",
                        listOf("enabled" to "开启 (防爆音, 默认)", "disabled" to "关闭"),
                        padLayout.azAudioStretching
                    ) { updateLayout(padLayout.copy {azAudioStretching = it}) }
                    DropdownRow("实时音频",
                        listOf("disabled" to "关闭 (默认)", "enabled" to "开启 (低延迟, 可能卡顿)"),
                        padLayout.azRealtimeAudio
                    ) { updateLayout(padLayout.copy {azRealtimeAudio = it}) }
                }

                // ★★ Citra MMJ 专属设置（本轮新增，需求原话"全部设置加入
                //    进去，包括所有设置哦"）—— 48 个 MMJ 配置键全量接入。
                //    键名 = libmain.so 配置系统真实键（strings 提取）；启动
                //    3DS 游戏选 "Citra MMJ" 核心时生效（CitraMmjEngine 经
                //    setConfig* 直写核心配置）。
                SettingsSection("3DS (Citra MMJ) · 画面 / 性能") {
                    DropdownRow("渲染分辨率",
                        listOf("1" to "1x (原生, 默认)", "2" to "2x", "3" to "3x", "4" to "4x (最高, 原版无 5x)"),
                        padLayout.mmjResolution
                    ) { updateLayout(padLayout.copy {mmjResolution = it}) }
                    DropdownRow("硬件着色器",
                        listOf("enabled" to "开启 (性能关键, 默认)", "disabled" to "关闭"),
                        padLayout.mmjUseHwShader
                    ) { updateLayout(padLayout.copy {mmjUseHwShader = it}) }
                    DropdownRow("着色器 JIT",
                        listOf("enabled" to "开启 (默认)", "disabled" to "关闭"),
                        padLayout.mmjUseShaderJit
                    ) { updateLayout(padLayout.copy {mmjUseShaderJit = it}) }
                    DropdownRow("异步着色器编译",
                        listOf("enabled" to "开启 (后台编译, 默认)", "disabled" to "关闭"),
                        padLayout.mmjAsyncShaderCompile
                    ) { updateLayout(padLayout.copy {mmjAsyncShaderCompile = it}) }
                    DropdownRow("着色器类型",
                        listOf("0" to "GL 标准", "1" to "GL 标准+缓存 (默认, 推荐)", "2" to "GL 分离 (兼容)"),
                        padLayout.mmjShaderType
                    ) { updateLayout(padLayout.copy {mmjShaderType = it}) }
                    // ★ 说明（回应用户"gl和vk咋没了"）：反编译实证 MMJ 核心
                    //   【没有 GL/VK 渲染器切换】—— so 内无 graphics_api 键、
                    //   原版 MMJ 设置也没有该选项（shader_type 是着色器缓存/
                    //   分离模式开关，旧 UI 把它错标成 "GL/Vulkan" 才造成
                    //   有渲染器切换的错觉）。核心固定 GL(ES) 渲染。
                    Text("MMJ 核心固定 GL 渲染，无 Vulkan 切换（核心与原版 MMJ 均无此选项；此项只控制着色器缓存/分离模式）",
                        color = Color(0xFFFDD835), fontSize = 10.sp, lineHeight = 14.sp)
                    DropdownRow("几何着色器 (hw_gs_mode)",
                        listOf("0" to "0 关闭 (默认)", "1" to "1 兼容", "2" to "2 完整"),
                        padLayout.mmjHwGsMode
                    ) { updateLayout(padLayout.copy {mmjHwGsMode = it}) }
                    DropdownRow("精确乘法",
                        listOf("0" to "关闭 (默认, 最快)", "1" to "快速", "2" to "精确 (个别游戏花纹需要)"),
                        padLayout.mmjAccurateMulType
                    ) { updateLayout(padLayout.copy {mmjAccurateMulType = it}) }
                    DropdownRow("阴影渲染 (几何)",
                        listOf("disabled" to "关闭 (默认, 兼容)", "enabled" to "开启 (需几何着色器)"),
                        padLayout.mmjShadowRendering
                    ) { updateLayout(padLayout.copy {mmjShadowRendering = it}) }
                    DropdownRow("强制纹理过滤",
                        listOf("disabled" to "关闭 (默认)", "enabled" to "开启"),
                        padLayout.mmjForceTextureFilter
                    ) { updateLayout(padLayout.copy {mmjForceTextureFilter = it}) }
                    DropdownRow("纹理放大过滤",
                        listOf("0" to "最近邻 (像素风)", "1" to "线性 (平滑, 默认)"),
                        padLayout.mmjMagFilter
                    ) { updateLayout(padLayout.copy {mmjMagFilter = it}) }
                    DropdownRow("纹理缩小过滤",
                        listOf("0" to "最近邻", "1" to "线性 (默认)"),
                        padLayout.mmjMinFilter
                    ) { updateLayout(padLayout.copy {mmjMinFilter = it}) }
                    // ★★★ 闪退根治（本轮）：列表必须与 <userDir>/shaders/ 实际
                    //   存在的 .glsl 一一对应 —— 原生核心对不存在的着色器名
                    //   打开 .glsl 失败会直接空指针崩溃（旧列表里的 Anime4K
                    //   并无对应文件，选了必炸）。已增补 FXAA_natural。
                    // ★ 本轮新增 xBR / 4xBR（Hyllian 原版移植，与全局滤镜
                    //   xbr/4xbr 同款效果；也可单独选用）。
                    DropdownRow("后处理着色器",
                        listOf("(off)" to "关闭 (默认)", "bloom" to "泛光",
                               "brighten" to "提亮", "cartoon" to "卡通", "film" to "胶片",
                               "spline36" to "样条缩放", "FXAA" to "FXAA 抗锯齿",
                               "FXAA_natural" to "FXAA 自然锐化",
                               "Cel" to "赛璐璐", "Dot" to "点阵", "SEDI" to "SEDI 边缘导向",
                               "xBR" to "xBR 像素平滑 (2x)",
                               "4xBR" to "4xBR 像素平滑 (4x 强化)",
                               // ★ fix5 补齐：HQ2X/HQ4X 文件已随 assets/mmj/shaders
                               //   打包（688e75da），但下拉缺失 —— 全局滤镜 hq2x/hq4x
                               //   映射（mmjShaderForGlobalFilter）依赖同名文件，
                               //   核心专属选择也应可选。
                               "HQ2X" to "HQ2X 像素平滑 (2x)",
                               "HQ4X" to "HQ4X 像素平滑 (4x)"),
                        padLayout.mmjPpShaderName
                    ) { updateLayout(padLayout.copy {mmjPpShaderName = it}) }
                    // ★ V12：滤镜由前端 MmjFilterView 消费（SurfaceTexture 中转 +
                    //   我们自己的 GLES 上下文编译 .glsl），不再依赖核心内链。
                    //   游戏中切换滤镜即时生效（开关切换重建滤镜视图；同视图内
                    //   换滤镜名热重建 program），无需重进游戏。
                    Text(
                        "提示：滤镜由前端 GL 层应用（全局滤镜 xbr/hqx 映射与本列表共用同一条链）。\n" +
                            "游戏中切换滤镜即时生效，无需重进游戏；叠加类外观（扫描线/点阵/仿电视）" +
                            "仍由画面叠加层绘制，可与本滤镜同时生效。",
                        // ★ v19: 剩余说明文字亮黄化
                        color = Color(0xFFFDD835),
                        fontSize = 10.sp, lineHeight = 13.sp)
                    DropdownRow("屏幕呈现模式",
                        listOf("0" to "0 兼容 (默认)", "1" to "1 共享上下文", "2" to "2 硬件缓冲"),
                        padLayout.mmjScreenPresentationMode
                    ) { updateLayout(padLayout.copy {mmjScreenPresentationMode = it}) }
                    DropdownRow("自定义纹理",
                        listOf("disabled" to "关闭 (默认)", "enabled" to "开启 (加载 HD 纹理包)"),
                        padLayout.mmjCustomTextures
                    ) { updateLayout(padLayout.copy {mmjCustomTextures = it}) }
                }

                SettingsSection("3DS (Citra MMJ) · 性能 Hack") {
                    DropdownRow("兼容模式",
                        listOf("disabled" to "关闭 (默认)", "enabled" to "开启 (老设备/花屏时)"),
                        padLayout.mmjUseCompatibleMode
                    ) { updateLayout(padLayout.copy {mmjUseCompatibleMode = it}) }
                    DropdownRow("FMV 视频 Hack",
                        listOf("disabled" to "关闭 (默认)", "enabled" to "开启 (过场视频卡顿游戏)"),
                        padLayout.mmjUseFmvHack
                    ) { updateLayout(padLayout.copy {mmjUseFmvHack = it}) }
                    DropdownRow("跳过 CPU 写 (skip_cpu_write)",
                        listOf("disabled" to "关闭 (默认)", "enabled" to "开启 (提速, 兼容性风险)"),
                        padLayout.mmjSkipCpuWrite
                    ) { updateLayout(padLayout.copy {mmjSkipCpuWrite = it}) }
                    DropdownRow("跳过慢速绘制 (skip_slow_draw)",
                        listOf("disabled" to "关闭 (默认)", "enabled" to "开启 (提速, 兼容性风险)"),
                        padLayout.mmjSkipSlowDraw
                    ) { updateLayout(padLayout.copy {mmjSkipSlowDraw = it}) }
                    DropdownRow("跳过纹理拷贝 (skip_texture_copy)",
                        listOf("disabled" to "关闭 (默认)", "enabled" to "开启 (提速, 兼容性风险)"),
                        padLayout.mmjSkipTextureCopy
                    ) { updateLayout(padLayout.copy {mmjSkipTextureCopy = it}) }
                    DropdownRow("Fence 同步",
                        listOf("enabled" to "开启 (默认)", "disabled" to "关闭 (实验)"),
                        padLayout.mmjUseFenceSync
                    ) { updateLayout(padLayout.copy {mmjUseFenceSync = it}) }
                    DropdownRow("呈现线程",
                        listOf("enabled" to "开启 (默认, 降延迟)", "disabled" to "关闭"),
                        padLayout.mmjUsePresentThread
                    ) { updateLayout(padLayout.copy {mmjUsePresentThread = it}) }
                    DropdownRow("帧率限制",
                        listOf("enabled" to "开启 (默认)", "disabled" to "关闭 (不限速, 快进感)"),
                        padLayout.mmjUseFrameLimit
                    ) { updateLayout(padLayout.copy {mmjUseFrameLimit = it}) }
                    DropdownRow("帧率上限",
                        listOf("50" to "50%", "60" to "60%", "100" to "100% (默认)", "200" to "200%", "300" to "300%", "400" to "400%"),
                        padLayout.mmjFrameLimit
                    ) { updateLayout(padLayout.copy {mmjFrameLimit = it}) }
                    DropdownRow("CPU 占用限制",
                        listOf("0" to "不限制 (默认)", "25" to "25%", "50" to "50%", "75" to "75%"),
                        padLayout.mmjCpuUsageLimit
                    ) { updateLayout(padLayout.copy {mmjCpuUsageLimit = it}) }
                }

                SettingsSection("3DS (Citra MMJ) · 3D / 布局") {
                    // ★ 本轮按需求移除 MMJ 独立布局选项（竖屏/横屏布局、
                    //   独立自定义布局开关）：3DS 双屏布局统一跟随全局
                    //   「画面缩放」(videoScale) —— "自定义"时进双屏编辑器。
                    DropdownRow("立体 3D 深度",
                        listOf("0" to "0 关 (默认)", "2" to "2", "4" to "4", "6" to "6", "8" to "8", "10" to "10 (最深)"),
                        padLayout.mmjFactor3d
                    ) { updateLayout(padLayout.copy {mmjFactor3d = it}) }
                    Text("双屏布局跟随全局「画面缩放」设置（游戏内 画面缩放 → 自定义 可自由排布上下屏；此处不再提供独立布局选项）",
                        color = Color(0xFFFDD835), fontSize = 10.sp, lineHeight = 14.sp)
                    DropdownRow("横屏交换上下屏",
                        listOf("disabled" to "关闭 (默认)", "enabled" to "开启"),
                        padLayout.mmjLandscapeSwapScreen
                    ) { updateLayout(padLayout.copy {mmjLandscapeSwapScreen = it}) }
                    DropdownRow("竖屏交换上下屏",
                        listOf("disabled" to "关闭 (默认)", "enabled" to "开启"),
                        padLayout.mmjPortraitSwapScreen
                    ) { updateLayout(padLayout.copy {mmjPortraitSwapScreen = it}) }
                }

                SettingsSection("3DS (Citra MMJ) · 系统 / 音频") {
                    DropdownRow("CPU JIT",
                        listOf("enabled" to "开启 (性能关键, 默认)", "disabled" to "关闭 (慢)"),
                        padLayout.mmjUseCpuJit
                    ) { updateLayout(padLayout.copy {mmjUseCpuJit = it}) }
                    DropdownRow("New 3DS 模式",
                        listOf("enabled" to "开启 (默认)", "disabled" to "关闭 (老游戏兼容)"),
                        padLayout.mmjIsNew3ds
                    ) { updateLayout(padLayout.copy {mmjIsNew3ds = it}) }
                    DropdownRow("主机区域",
                        listOf("-1" to "自动 (默认)", "0" to "日本", "1" to "北美", "2" to "欧洲", "3" to "澳洲", "4" to "中国", "5" to "韩国", "6" to "台湾"),
                        padLayout.mmjRegion
                    ) { updateLayout(padLayout.copy {mmjRegion = it}) }
                    DropdownRow("DSP 音频模拟",
                        listOf("disabled" to "HLE (默认, 快)", "enabled" to "LLE (实验, 需 DSP 固件)"),
                        padLayout.mmjEnableDspLle
                    ) { updateLayout(padLayout.copy {mmjEnableDspLle = it}) }
                    DropdownRow("DSP LLE 多线程",
                        listOf("disabled" to "关闭 (默认)", "enabled" to "开启"),
                        padLayout.mmjDspLleMultithread
                    ) { updateLayout(padLayout.copy {mmjDspLleMultithread = it}) }
                    DropdownRow("音量", (0..100 step 10).map { it.toString() to "$it%" },
                        padLayout.mmjAudioVolume
                    ) { updateLayout(padLayout.copy {mmjAudioVolume = it}) }
                    DropdownRow("音频拉伸",
                        listOf("disabled" to "关闭 (默认)", "enabled" to "开启 (时间拉伸防爆音)"),
                        padLayout.mmjAudioStretching
                    ) { updateLayout(padLayout.copy {mmjAudioStretching = it}) }
                    // ★ 音频输出：原版仅 1=关闭 / 2=Cubeb（默认）两个合法值
                    //   （arrays.xml audioOuputValues 实证）；旧 "0 自动"/"3 兼容"
                    //   是非法值 → 核心输出白噪声（滋滋滋），已迁移到 2。
                    DropdownRow("音频输出",
                        listOf("2" to "Cubeb (默认)", "1" to "关闭 (无声)"),
                        padLayout.mmjAudioOutputType
                    ) { updateLayout(padLayout.copy {mmjAudioOutputType = it}) }
                    DropdownRow("音频输入",
                        listOf("1" to "关闭 (默认)", "2" to "静态噪声", "3" to "真实设备 (麦克风)"),
                        padLayout.mmjAudioInputType
                    ) { updateLayout(padLayout.copy {mmjAudioInputType = it}) }
                    DropdownRow("麦克风音量", (0..100 step 10).map { it.toString() to "$it%" },
                        padLayout.mmjMicVolume
                    ) { updateLayout(padLayout.copy {mmjMicVolume = it}) }
                    DropdownRow("共享字体",
                        listOf("-1" to "自动 (默认)", "0" to "shared_font.bin", "1" to "日文", "2" to "简体中文", "3" to "韩文", "4" to "繁体中文"),
                        padLayout.mmjSharedFontType
                    ) { updateLayout(padLayout.copy {mmjSharedFontType = it}) }
                    DropdownRow("虚拟 SD 卡",
                        listOf("enabled" to "开启 (默认)", "disabled" to "关闭"),
                        padLayout.mmjUseVirtualSd
                    ) { updateLayout(padLayout.copy {mmjUseVirtualSd = it}) }
                    DropdownRow("按游戏配置",
                        listOf("enabled" to "开启 (默认, config-games.ini 兼容补丁)", "disabled" to "关闭"),
                        padLayout.mmjUseGameConfig
                    ) { updateLayout(padLayout.copy {mmjUseGameConfig = it}) }
                    DropdownRow("摄像头",
                        listOf("blank" to "无 (默认)", "image" to "静态图片", "camera" to "设备摄像头"),
                        padLayout.mmjCameraType
                    ) { updateLayout(padLayout.copy {mmjCameraType = it}) }
                }
                // ★★ "3DS (Citra MMJ) · 虚拟按键覆盖层" 区块已删除（本轮需求：
                //   "独立设置里多余的虚拟按键覆盖层去掉，总设置有"）——
                //   覆盖层缩放/透明度/隐藏/摇杆灵敏度 4 项与总设置的
                //   「输入 → 屏幕手柄」+「遮罩/按钮主题（所有核心统一入口）」
                //   完全重复；且 NesStation 自带 Compose 遮罩，MMJ 原生 overlay
                //   被引擎强制关闭（调起会触发 UNREACHABLE 闪退），这些开关
                //   从未真正生效。虚拟按键外观请到 总设置 → 输入 / 遮罩主题 调整。
            }
            GamePlatform.NGCWII -> item {
                // Ishiiruka 核心选项 —— 引擎经 SetConfig(file, section, key, value)
                // 热写 Dolphin.ini / GFX.ini。键名/取值对照上游 SettingsFile.java。
                SettingsSection("NGC/WII (Ishiiruka) · 控制") {
                    DropdownRow("控制模式",
                        listOf("auto" to "自动 (按游戏判定)", "ngc" to "GameCube 手柄", "wii" to "Wii Remote"),
                        padLayout.irControlMode
                    ) { updateLayout(padLayout.copy {irControlMode = it}) }
                    DropdownRow("Wii 扩展手柄",
                        listOf("nunchuk" to "双节棍 (推荐)", "classic" to "经典手柄", "none" to "无"),
                        padLayout.irWiiExtension
                    ) { updateLayout(padLayout.copy {irWiiExtension = it}) }
                    DropdownRow("Wii 手柄方向",
                        listOf("vertical" to "竖持 (双节棍/指向玩法)", "horizontal" to "横持 (NES 式)"),
                        padLayout.irWiiOrientation
                    ) { updateLayout(padLayout.copy {irWiiOrientation = it}) }
                    // ★★ 手机体感模拟 Wii 体感（V8 固定锚点模型）：重力方向
                    //    → 六方向持续输出（左右倾斜=方向盘，顶端推离/拉近=
                    //    前后倾，保持姿态=持续输出）；回正慢慢回原位（滚球式）。
                    //    零点固定 = 手机竖直横持屏幕正对自己，与进游戏时姿态无关，
                    //    永不校准。上下/左右/前后快速挥动=Swing；抖动=Shake。
                    //    无传感器的设备自动退化。默认开启。
                    DropdownRow("手机体感模拟",
                        listOf("disabled" to "关闭", "enabled" to "开启 (默认·重力感应式体感)"),
                        if (padLayout.wiiMotionSensor) "enabled" else "disabled"
                    ) { updateLayout(padLayout.copy {wiiMotionSensor = it == "enabled"}) }
                    // ★ V8 固定锚点模型：以【手机竖直横持、屏幕正对自己】为
                    //   固定中心（方向盘位，无需校准）：往哪倾斜就持续向哪输出
                    //   （角度比例，保持姿态=保持输出）；回正后约 0.3s 平滑归零
                    //   （滚球式）。满程角度随增益缩小：1.0=30° / 1.6≈19°(默认) /
                    //   2.2≈14°。快速挥动/抖动另行触发 Swing/Shake。
                    DropdownRow("体感灵敏度",
                        listOf(
                            "1.0" to "标准 (满程 30°)",
                            "1.6" to "高 (默认, 满程 ≈19°, 轻倾即满)",
                            "2.2" to "极高 (满程 ≈14°, 微倾即反应)"),
                        padLayout.wiiMotionSensitivity
                    ) { updateLayout(padLayout.copy {wiiMotionSensitivity = it}) }
                    Text(
                        "虚拟按键随所选控制器变化：GameCube 手柄 → GC 键组；" +
                        "经典手柄 → ABXY + ZL/ZR + 双摇杆；双节棍 → 竖持 Wiimote + C/Z + 摇杆；" +
                        "无扩展 + 横持 → NES 式十字键 + A/B/1/2 + L/R 摇晃 + HOME + IR±（体感/红外齐全）。" +
                        "体感四键：L2=左倾 / R2=右倾 / L3=前倾 / R3=后倾（实体键位可在按键映射页自定义）。" +
                        "手机体感（V8 固定锚点）：手机竖直横持屏幕正对自己为中心，往哪倾就持续向哪输出" +
                        "（角度比例），回正慢慢归零；快速挥动/抖动触发 Swing/Shake，与按钮叠加。",
                        color = Color(0xFFFDD835), fontSize = 10.sp, lineHeight = 14.sp)
                }
                SettingsSection("NGC/WII (Ishiiruka) · 性能 / 图形") {
                    DropdownRow("CPU 核心",
                        listOf("4" to "JIT ARM64 (推荐)", "1" to "JIT64", "0" to "解释器 (慢)"),
                        padLayout.irCpuCore
                    ) { updateLayout(padLayout.copy {irCpuCore = it}) }
                    // ★ JIT 分支优化（用户反馈缺失项）：Dolphin.ini [Core] JITFollowBranch。
                    DropdownRow("JIT 分支优化",
                        listOf("disabled" to "关闭 (默认，最快)", "enabled" to "开启 (提高兼容性)"),
                        padLayout.irJitFollowBranch
                    ) { updateLayout(padLayout.copy {irJitFollowBranch = it}) }
                    DropdownRow("双核模拟",
                        listOf("enabled" to "开启 (CPU/GPU 分线程, 默认)", "disabled" to "关闭"),
                        padLayout.irDualCore
                    ) { updateLayout(padLayout.copy {irDualCore = it}) }
                    DropdownRow("CPU 超频开关",
                        listOf("disabled" to "关闭 (默认)", "enabled" to "开启"),
                        padLayout.irOverclockEnable
                    ) { updateLayout(padLayout.copy {irOverclockEnable = it}) }
                    DropdownRow("CPU 超频",
                        listOf("100" to "100% (默认)", "150" to "150%", "200" to "200%", "300" to "300%", "400" to "400%"),
                        padLayout.irOverclock
                    ) { updateLayout(padLayout.copy {irOverclock = it}) }
                    DropdownRow("渲染后端",
                        listOf("OGL" to "OpenGL (兼容)", "Vulkan" to "Vulkan (性能)", "Software Renderer" to "软件渲染"),
                        padLayout.irBackend
                    ) { updateLayout(padLayout.copy {irBackend = it}) }
                    // ★ 取值对齐参考 APK（InternalResolution = 百分比标度，
                    //   资源数组 array0018/0019 实测 100/200/300/400）；
                    //   旧值 2/4/6/7 由 normalizeIrResolution 自动迁移。
                    DropdownRow("内部分辨率 (EFB)",
                        listOf("100" to "1x 原生", "200" to "2x", "300" to "3x", "400" to "4x"),
                        padLayout.irResolution
                    ) { updateLayout(padLayout.copy {irResolution = it}) }
                    DropdownRow("多重采样 (MSAA)",
                        listOf("1" to "关闭", "2" to "2x", "4" to "4x", "8" to "8x"),
                        padLayout.irMsaa
                    ) { updateLayout(padLayout.copy {irMsaa = it}) }
                    DropdownRow("各向异性过滤",
                        listOf("0" to "1x (关)", "1" to "2x", "2" to "4x", "3" to "8x", "4" to "16x"),
                        padLayout.irAnisotropy
                    ) { updateLayout(padLayout.copy {irAnisotropy = it}) }
                    DropdownRow("显示 FPS",
                        listOf("disabled" to "关闭", "enabled" to "开启"),
                        padLayout.irShowFps
                    ) { updateLayout(padLayout.copy {irShowFps = it}) }
                    DropdownRow("启动时编译着色器",
                        listOf("disabled" to "关闭 (默认)", "enabled" to "开启 (游戏中防卡顿)"),
                        padLayout.irWaitForShaders
                    ) { updateLayout(padLayout.copy {irWaitForShaders = it}) }
                    // ★★★ 画面比例（irAspect）设置项已删除（本轮）★★★
                    //   需求原话："ngcwii核心设置取消屏幕比例，要根据全局
                    //   屏幕缩放来，保证正常自定义布局"。核心现在恒按
                    //   AspectRatio=拉伸渲染（见 EmulatorScreen.applyCoreOptions），
                    //   画面形状完全由全局「屏幕缩放」决定（拉伸/4:3/16:9/
                    //   自由布局矩形）—— 自定义布局所见即所得，不再有核心
                    //   侧二次黑边/变形。
                    DropdownRow("EFB 纹理复制",
                        listOf("enabled" to "开启 (性能关键, 默认)", "disabled" to "关闭"),
                        padLayout.irEfbToTexture
                    ) { updateLayout(padLayout.copy {irEfbToTexture = it}) }
                    DropdownRow("EFB 缩放复制",
                        listOf("enabled" to "开启 (高清更清晰, 默认)", "disabled" to "关闭"),
                        padLayout.irEfbScaledCopy
                    ) { updateLayout(padLayout.copy {irEfbScaledCopy = it}) }
                    DropdownRow("EFB 访问",
                        listOf("enabled" to "开启 (默认)", "disabled" to "关闭"),
                        padLayout.irEfbAccess
                    ) { updateLayout(padLayout.copy {irEfbAccess = it}) }
                    DropdownRow("垂直同步 (VSync)",
                        listOf("disabled" to "关闭 (默认，性能优先)", "enabled" to "开启 (防画面撕裂)"),
                        padLayout.irVsync
                    ) { updateLayout(padLayout.copy {irVsync = it}) }
                    DropdownRow("Fastmem 快速内存",
                        listOf("enabled" to "开启 (性能关键，默认)", "disabled" to "关闭 (个别游戏兼容)"),
                        padLayout.irFastmem
                    ) { updateLayout(padLayout.copy {irFastmem = it}) }
                    DropdownRow("XFB 即时显示",
                        listOf("enabled" to "开启 (Wii 文本/转场呈现，默认)", "disabled" to "关闭"),
                        padLayout.irXfbImmediate
                    ) { updateLayout(padLayout.copy {irXfbImmediate = it}) }
                    DropdownRow("XFB 到纹理",
                        listOf("enabled" to "开启 (性能，默认)", "disabled" to "关闭"),
                        padLayout.irXfbToTexture
                    ) { updateLayout(padLayout.copy {irXfbToTexture = it}) }
                    // ★★ 本轮补全（用户反馈缺失项）★★
                    DropdownRow("宽屏修正 (16:9)",
                        listOf("disabled" to "关闭 (默认，保持原始比例)", "enabled" to "开启 (强制 16:9)"),
                        padLayout.irWidescreenHack
                    ) { updateLayout(padLayout.copy {irWidescreenHack = it}) }
                    DropdownRow("NGC 主机语言",
                        listOf("0" to "英语", "1" to "德语", "2" to "法语",
                               "3" to "西班牙语", "4" to "意大利语", "5" to "荷兰语"),
                        padLayout.irGcLanguage
                    ) { updateLayout(padLayout.copy {irGcLanguage = it}) }
                    DropdownRow("Wii 主机语言",
                        listOf("0" to "日语", "1" to "英语", "2" to "德语", "3" to "法语",
                               "4" to "西班牙语", "5" to "意大利语", "6" to "荷兰语"),
                        padLayout.irWiiLanguage
                    ) { updateLayout(padLayout.copy {irWiiLanguage = it}) }
                }
                SettingsSection("NGC/WII (Ishiiruka) · 音频 / Wii") {
                    DropdownRow("音量",
                        listOf("100" to "100%", "80" to "80%", "60" to "60%", "40" to "40%", "20" to "20%", "0" to "静音"),
                        padLayout.irAudioVolume
                    ) { updateLayout(padLayout.copy {irAudioVolume = it}) }
                    DropdownRow("音频延迟",
                        listOf("0" to "最低", "1" to "低", "2" to "中 (默认)", "3" to "高 (防爆音)"),
                        padLayout.irAudioLatency
                    ) { updateLayout(padLayout.copy {irAudioLatency = it}) }
                    DropdownRow("音频拉伸",
                        listOf("disabled" to "关闭 (默认)", "enabled" to "开启 (防爆音)"),
                        padLayout.irAudioStretch
                    ) { updateLayout(padLayout.copy {irAudioStretch = it}) }
                    DropdownRow("DSP HLE",
                        listOf("enabled" to "开启 (高速, 默认)", "disabled" to "关闭 (LLE 精确但慢)"),
                        padLayout.irDspHle
                    ) { updateLayout(padLayout.copy {irDspHle = it}) }
                    DropdownRow("Wii 扬声器模拟",
                        listOf("disabled" to "关闭 (默认)", "enabled" to "开启"),
                        padLayout.irWiimoteSpeaker
                    ) { updateLayout(padLayout.copy {irWiimoteSpeaker = it}) }
                    DropdownRow("Wiimote 持续扫描",
                        listOf("disabled" to "关闭 (默认)", "enabled" to "开启 (实体 Wiimote)"),
                        padLayout.irWiimoteScan
                    ) { updateLayout(padLayout.copy {irWiimoteScan = it}) }
                    Text(
                        "提示：Wii 游戏 IR 指针 = 触摸游戏画面（绝对指向）；IR−/IR+ 调进深；" +
                        "摇晃 = L/R 键（wii 模式）。GameCube 游戏自动使用 GC 手柄布局。",
                        color = Color(0xFFFDD835), fontSize = 10.sp, lineHeight = 14.sp)
                }
            }

            // ================================================================
            // ★★ PSP（libretro PPSSPP）核心选项 —— 全量设置 ★★
            //
            // 设置通道与 RetroArch 的核心选项同构：ppsspp_* 键/值与官方
            // libretro.cpp check_variables() 逐字节对齐（大小写敏感），
            // 持久化到 <filesDir>/psp_core_options.ini，loadRom 前整表灌入
            // 核心；会话中修改即时热应用（核心经 GET_VARIABLE_UPDATE 感知）。
            // 旧版直写 ppsspp.ini 的键（图形后端/垂直同步/后处理着色器/
            // 游戏音量等）libretro 核心完全不读取 —— 已全部移除。
            GamePlatform.PSP -> item {
                SettingsSection("PSP · 画面") {
                    PspCoreOptions.group(PspCoreOptions.G_GFX).forEach { def -> PspCoreOptionRow(def) }
                }
                SettingsSection("PSP · 纹理") {
                    PspCoreOptions.group(PspCoreOptions.G_TEX).forEach { def -> PspCoreOptionRow(def) }
                    // ★ v22: 高清纹理替换需纹理包 —— 状态检测 + 目录指引
                    PspTexturePackStatus()
                }
                SettingsSection("PSP · 性能 / CPU（满速 = JIT + 快速内存 + 1x）") {
                    PspCoreOptions.group(PspCoreOptions.G_PERF).forEach { def -> PspCoreOptionRow(def) }
                }
                SettingsSection("PSP · 系统") {
                    PspCoreOptions.group(PspCoreOptions.G_SYS).forEach { def -> PspCoreOptionRow(def) }
                }
                SettingsSection("PSP · 模拟摇杆") {
                    PspCoreOptions.group(PspCoreOptions.G_PAD).forEach { def -> PspCoreOptionRow(def) }
                }
                SettingsSection("PSP · 联机（Ad-hoc / PRO 服务器）") {
                    PspCoreOptions.group(PspCoreOptions.G_NET).forEach { def -> PspCoreOptionRow(def) }
                    PspAdhocServerRow()
                }
                SettingsSection("PSP · 说明") {
                    Text(
                        "★ 满速要点：CPU 核心 = JIT（大小写敏感，此前发小写 \"jit\" 被核心忽略，" +
                        "一直跑解释器导致严重卡顿）；快速内存开启；渲染分辨率 1x 起步；纹理放大关闭。" +
                        "所有设置即时保存并热应用（下次启动自动重放）\n\n" +
                        "虚拟按键：PSP 现有完整 ✕○□△ + L/R + Start/Select + 左摇杆（可在 触控 → 布局编辑器 里调位置/大小/显隐；摇杆输出真模拟轴）。" +
                        "\n\n游戏内系统语言默认简体中文（需 PPSSPP lang 资产，PspSupport 自动播种）。",
                        color = Color(0xFFFDD835),
                        fontSize = 10.sp, lineHeight = 15.sp)
                }
            }
            // ★★ Wii U（Cemu-AZ 源码核心）核心选项 —— cw* 字段经
            //   "CemuEngine/<name>" 门面键直调 NativeSettings（上游 Cemu-AZ
            //   Android 设置接口），游戏启动与运行中热生效。键名/取值对照
            //   上游 info/cemu/cemu/nativeinterface/NativeSettings.kt。
            GamePlatform.WIIU -> item {
                SettingsSection("Wii U · 画面 / 性能") {
                    DropdownRow("渲染分辨率",
                        // ★ R13: >1x 恢复真实生效 —— 与 0.75x 同一条呈现侧缩放路径的
                        //   超采样方向：最终画面（含所选缩放滤镜）画进"屏幕分辨率×倍率"
                        //   的离屏目标后缩小回全尺寸上屏。高分屏上边缘更干净、
                        //   摩尔纹更少；游戏内部渲染恒 1x 原生（光照/阴影零改动）。
                        //   <1x 输出降采样省电。热生效。
                        listOf("2" to "2x (超采样·画面增强)",
                               "1.5" to "1.5x (超采样·画面增强)",
                               "1" to "1x (默认·原生)",
                               "0.75" to "0.75x (输出降采样·省电)",
                               "0.5" to "0.5x (输出降采样·大幅省电)",
                               "0.4" to "0.4x (输出降采样·极速档)",
                               "0.3" to "0.3x (输出降采样·极致省电)"),
                        padLayout.cwRenderScale
                    ) { updateLayout(padLayout.copy {cwRenderScale = it}) }
                    DropdownRow("GamePad 更新频率",
                        // ★ R5: 纯呈现侧节流（不触碰游戏渲染管线）。悬浮 GamePad
                        //   画面以较低频率刷新即可看清地图/物品栏，GPU/功耗显著
                        //   下降；被跳过的帧由合成器保持上一帧画面。
                        listOf("1" to "每帧 (最流畅·较耗电)",
                               "2" to "每2帧 (默认·省电)",
                               "3" to "每3帧 (强省电)"),
                        padLayout.cwPadPresentRate
                    ) { updateLayout(padLayout.copy {cwPadPresentRate = it}) }
                    DropdownRow("帧率限制",
                        // ★ R4: 省电利器 —— 锁步游戏按目标帧率节流，功耗随
                        //   帧率线性下降（30 帧 ≈ 一半 GPU/CPU 占用）。
                        listOf("off" to "不限制 (默认·原生帧率)",
                               "60" to "60 帧 (上限)",
                               "45" to "45 帧 (平衡省电)",
                               "30" to "30 帧 (强力省电)"),
                        padLayout.cwFrameLimit
                    ) { updateLayout(padLayout.copy {cwFrameLimit = it}) }
                    DropdownRow("垂直同步",
                        listOf("0" to "关闭 (默认·低延迟不卡顿)", "1" to "双缓冲 (防撕裂)",
                               "2" to "三缓冲 (最稳·延迟高)"),
                        padLayout.cwVsyncMode
                    ) { updateLayout(padLayout.copy {cwVsyncMode = it}) }
                    DropdownRow("缩放滤镜",
                        listOf("0" to "双线性 (默认·平滑)", "1" to "双三次 (锐利)",
                               "2" to "Hermite (均衡)", "3" to "最近邻 (像素风)"),
                        padLayout.cwUpscaleFilter
                    ) { updateLayout(padLayout.copy {cwUpscaleFilter = it}) }
                    DropdownRow("下采样滤镜",
                        listOf("0" to "双线性 (默认)", "1" to "双三次", "2" to "Hermite", "3" to "最近邻"),
                        padLayout.cwDownscaleFilter
                    ) { updateLayout(padLayout.copy {cwDownscaleFilter = it}) }
                    DropdownRow("画面缩放",
                        // 全局「屏幕缩放 stretch」会联动此项为拉伸（见 applyCoreOptions）
                        listOf("0" to "保持宽高比 (默认)", "1" to "拉伸全屏"),
                        padLayout.cwFullscreenScaling
                    ) { updateLayout(padLayout.copy {cwFullscreenScaling = it}) }
                    DropdownRow("异步着色器编译",
                        listOf("true" to "开启 (默认·防卡顿)", "false" to "关闭"),
                        padLayout.cwAsyncShaderCompile
                    ) { updateLayout(padLayout.copy {cwAsyncShaderCompile = it}) }
                    DropdownRow("精确屏障",
                        listOf("false" to "关闭 (默认·快)", "true" to "开启 (个别花屏游戏修复)"),
                        padLayout.cwAccurateBarriers
                    ) { updateLayout(padLayout.copy {cwAccurateBarriers = it}) }
                    DropdownRow("CPU 核心调度",
                        // ★★★ R11: 移植参考「风之杖HD 重编译版」CPU 核心调度 ——
                        //   游戏与渲染线程运行核心。大核优先：渲染最快，发热更高；
                        //   小核优先：发热更低运行稳定。运行中切换热生效。
                        listOf("0" to "默认 (不干预)",
                               "1" to "大核优先 (最快·发热高)",
                               "2" to "小核优先 (省电·稳定)"),
                        padLayout.cwCoreSched
                    ) { updateLayout(padLayout.copy {cwCoreSched = it}) }
                    DropdownRow("CPU 模式",
                        // ★★★ R11: 核心调用重编译配置。多核重编译最快，
                        //   解释器最兼容。切换后重启游戏完全生效。
                        listOf("auto" to "默认 (自动·多核重编译)",
                               "3" to "强制多核重编译",
                               "1" to "单核重编译",
                               "0" to "单核解释器 (最兼容)"),
                        padLayout.cwCpuMode
                    ) { updateLayout(padLayout.copy {cwCpuMode = it}) }
                    DropdownRow("线程时间片",
                        // ★★★ R15: 上游 Cemu GameProfile::threadQuantum 同款设置 ——
                        //   每个调度时间片授予的 PPC 周期数（上游默认 45000）。值越大
                        //   线程切换越少 = 提速关键（移动端帧率提升明显）；过小调度
                        //   频繁（个别老游戏可用 20000 保兼容）。运行中热生效，无需
                        //   重启游戏；选「跟随默认」则尊重游戏自带档案。
                        listOf("0" to "跟随默认 (游戏档案·45000)",
                               "20000" to "20000 (兼容·时间片短)",
                               "45000" to "45000 (锁定默认值)",
                               "60000" to "60000 (提速·减少切换)",
                               "80000" to "80000 (提速↑)",
                               "100000" to "100000 (提速↑↑)"),
                        padLayout.cwThreadQuantum
                    ) { updateLayout(padLayout.copy {cwThreadQuantum = it}) }
                    DropdownRow("GX2DrawDone 快速模式",
                        // ★★★ R11/R13: drawdone 快速路径（提速关键，默认开）。
                        //   R13 增加有界滞后自适应等待：游戏线程至多领先 GPU
                        //   若干次提交，超出即等一次排空 —— 既保留 CPU/GPU
                        //   重叠提速，又彻底解决"总要开关一次帧数才稳定"的问题。
                        listOf("true" to "快速模式 (默认·提速·帧率稳)",
                               "false" to "严格等待 (兼容优先)"),
                        padLayout.cwDrawdoneFast
                    ) { updateLayout(padLayout.copy {cwDrawdoneFast = it}) }
                    DropdownRow("ADPF 动态性能提示",
                        // ★★★ R12: 移植参考「风之杖HD 重编译版」的 Android 动态性能
                        //   框架语义 —— 游戏运行期间持续向系统调度器上报帧工作时长，
                        //   抑制温控/调度降频抖动（帧率稳定性的系统级手段）。
                        //   Android 12 (API 31+) 生效，老系统静默无感。运行中热切换。
                        listOf("true" to "开启 (默认·推荐)", "false" to "关闭"),
                        padLayout.cwAdpf
                    ) { updateLayout(padLayout.copy {cwAdpf = it}) }
                    DropdownRow("帧生成 (LSFG)",
                        // ★★★ R12/R13: LSFG 3 帧生成（Lossless.dll 光流神经网络）——
                        //   真实帧之间 GPU 插帧，30 帧游戏在 60Hz 屏上观感接近 60fps。
                        //   需要 Lossless.dll（下方导入，或 APK assets 内置自动读取）。
                        //   垂直同步=关闭时自动不生效。运行中热切换。
                        listOf("false" to "关闭 (默认)", "true" to "开启 (LSFG 插帧)"),
                        padLayout.cwFrameGen
                    ) { updateLayout(padLayout.copy {cwFrameGen = it}) }
                    DropdownRow("帧生成倍率",
                        // ★★★ R13: 每两个真实帧间插入 倍率-1 帧。倍率越高越流畅
                        //   但 GPU 负载越大（改后自动重建帧生成上下文）。
                        listOf("2" to "2x (30→感知60·推荐)",
                               "3" to "3x (30→感知90·需强 GPU)",
                               "4" to "4x (30→感知120·高刷屏专用)"),
                        padLayout.cwFrameGenMultiplier
                    ) { updateLayout(padLayout.copy {cwFrameGenMultiplier = it}) }
                    DropdownRow("帧生成光流比例",
                        // ★★★ R13: LSFG 光流估计分辨率 —— 越低越快，越高越准。
                        listOf("0.25" to "0.25 (极速)",
                               "0.5" to "0.5 (平衡·默认)",
                               "0.75" to "0.75 (高质量)",
                               "1.0" to "1.0 (最高质量·最慢)"),
                        padLayout.cwFrameGenFlowScale
                    ) { updateLayout(padLayout.copy {cwFrameGenFlowScale = it}) }
                    DropdownRow("帧生成质量",
                        listOf("performance" to "性能 (默认·快)",
                               "quality" to "质量 (慢·好)"),
                        padLayout.cwFrameGenQuality
                    ) { updateLayout(padLayout.copy {cwFrameGenQuality = it}) }
                    DropdownRow("取消阴影渲染",
                        // ★★★ R7: 阴影比较采样全部替换为“完全受光”—— 阴影消失、
                        //   画面正常亮度（不是变黑）。非原生分辨率阴影发黑的
                        //   替代方案；运行中热切换（短暂重编着色器）。
                        listOf("false" to "正常阴影 (默认)", "true" to "取消阴影渲染"),
                        padLayout.cwDisableShadows
                    ) { updateLayout(padLayout.copy {cwDisableShadows = it}) }
                    DropdownRow("核心 FPS 显示",
                        listOf("false" to "关闭 (默认)", "true" to "开启 (LatteOverlay)"),
                        padLayout.cwOverlayFPS
                    ) { updateLayout(padLayout.copy {cwOverlayFPS = it}) }
                    DropdownRow("覆盖层位置",
                        listOf("0" to "关闭 (默认)", "1" to "左上", "2" to "上中", "3" to "右上",
                               "4" to "左下", "5" to "下中", "6" to "右下"),
                        padLayout.cwOverlayPos
                    ) { updateLayout(padLayout.copy {cwOverlayPos = it}) }
                    DropdownRow("覆盖层文字缩放",
                        listOf("50" to "50%", "75" to "75%", "100" to "100% (默认)",
                               "150" to "150%", "200" to "200%", "300" to "300%"),
                        padLayout.cwOverlayTextScale
                    ) { updateLayout(padLayout.copy {cwOverlayTextScale = it}) }
                    DropdownRow("绘制调用数显示",
                        listOf("false" to "关闭 (默认)", "true" to "开启 (每帧 draw calls)"),
                        padLayout.cwOverlayDrawCalls
                    ) { updateLayout(padLayout.copy {cwOverlayDrawCalls = it}) }
                    DropdownRow("CPU 占用显示",
                        listOf("false" to "关闭 (默认)", "true" to "开启 (CPU 使用率)"),
                        padLayout.cwOverlayCPU
                    ) { updateLayout(padLayout.copy {cwOverlayCPU = it}) }
                    DropdownRow("内存占用显示",
                        listOf("false" to "关闭 (默认)", "true" to "开启 (RAM 使用率)"),
                        padLayout.cwOverlayRAM
                    ) { updateLayout(padLayout.copy {cwOverlayRAM = it}) }
                }
                SettingsSection("Wii U · 悬浮 GamePad 画面") {
                    DropdownRow("悬浮 GamePad 画面",
                        listOf("overlay" to "开启 (默认·小窗悬浮在 TV 角落)", "off" to "关闭 (仅 TV 画面)"),
                        padLayout.cwGamePadView
                    ) { updateLayout(padLayout.copy {cwGamePadView = it}) }
                    // ★ R3: 大小/模糊/角落设置已移除 —— 小窗支持自由拖动摆放
                    //   （画面缩放 → 自定义 双屏编辑器 + 历史拖动坐标），
                    //   固定大小/角落选择与模糊强度不再暴露（存档字段保留兼容）。
                    DropdownRow("悬浮画面不透明度",
                        listOf("20" to "20%", "40" to "40%", "60" to "60% (默认)", "80" to "80%",
                               "90" to "90%", "100" to "100% (不透明)"),
                        padLayout.cwPadOverlayOpacity
                    ) { updateLayout(padLayout.copy {cwPadOverlayOpacity = it}) }
                }
                SettingsSection("Wii U · 音频") {
                    DropdownRow("TV 音频输出",
                        listOf("true" to "开启 (默认)", "false" to "关闭"),
                        padLayout.cwTvAudioEnabled
                    ) { updateLayout(padLayout.copy {cwTvAudioEnabled = it}) }
                    DropdownRow("GamePad 音频输出",
                        listOf("true" to "开启 (默认)", "false" to "关闭"),
                        padLayout.cwPadAudioEnabled
                    ) { updateLayout(padLayout.copy {cwPadAudioEnabled = it}) }
                    DropdownRow("TV 声道",
                        listOf("0" to "单声道", "1" to "立体声 (默认)", "2" to "环绕"),
                        padLayout.cwTvChannels
                    ) { updateLayout(padLayout.copy {cwTvChannels = it}) }
                    DropdownRow("GamePad 声道",
                        listOf("0" to "单声道", "1" to "立体声 (默认)", "2" to "环绕"),
                        padLayout.cwPadChannels
                    ) { updateLayout(padLayout.copy {cwPadChannels = it}) }
                    DropdownRow("TV 音量",
                        listOf("0" to "0%", "20" to "20%", "40" to "40%", "60" to "60%",
                               "80" to "80%", "100" to "100% (默认)"),
                        padLayout.cwTvVolume
                    ) { updateLayout(padLayout.copy {cwTvVolume = it}) }
                    DropdownRow("GamePad 音量",
                        listOf("0" to "0%", "20" to "20%", "40" to "40%", "60" to "60%",
                               "80" to "80%", "100" to "100% (默认)"),
                        padLayout.cwPadVolume
                    ) { updateLayout(padLayout.copy {cwPadVolume = it}) }
                    DropdownRow("音频延迟",
                        listOf("20" to "20ms (低延迟)", "40" to "40ms", "60" to "60ms (默认)",
                               "100" to "100ms", "160" to "160ms", "276" to "276ms (稳定)"),
                        padLayout.cwAudioLatency
                    ) { updateLayout(padLayout.copy {cwAudioLatency = it}) }
                }
                SettingsSection("Wii U · 系统") {
                    DropdownRow("主机语言",
                        listOf("0" to "日语", "1" to "英语 (默认)", "2" to "法语", "3" to "德语",
                               "4" to "意大利语", "5" to "西班牙语", "6" to "中文",
                               "7" to "韩语", "8" to "荷兰语", "9" to "葡萄牙语",
                               "10" to "俄语", "11" to "台语"),
                        padLayout.cwConsoleLanguage
                    ) { updateLayout(padLayout.copy {cwConsoleLanguage = it}) }
                    DropdownRow("游戏速度",
                        listOf("0" to "0.5x (慢动作)", "1" to "1x (正常·默认)", "2" to "2x",
                               "3" to "4x", "4" to "8x (快进·与快进按钮联动)"),
                        padLayout.cwGameSpeed
                    ) { updateLayout(padLayout.copy {cwGameSpeed = it}) }
                    DropdownRow("网络服务",
                        listOf("0" to "离线 (默认)", "1" to "Nintendo 网络",
                               "2" to "Pretendo", "3" to "自定义"),
                        padLayout.cwNetworkService
                    ) { updateLayout(padLayout.copy {cwNetworkService = it}) }
                }
                SettingsSection("Wii U · 模拟 USB 设备") {
                    DropdownRow("Skylanders 传送门",
                        listOf("false" to "关闭 (默认)", "true" to "开启"),
                        padLayout.cwSkylanderPortal
                    ) { updateLayout(padLayout.copy {cwSkylanderPortal = it}) }
                    DropdownRow("Disney Infinity 基座",
                        listOf("false" to "关闭 (默认)", "true" to "开启"),
                        padLayout.cwInfinityBase
                    ) { updateLayout(padLayout.copy {cwInfinityBase = it}) }
                    DropdownRow("LEGO Dimensions Toypad",
                        listOf("false" to "关闭 (默认)", "true" to "开启"),
                        padLayout.cwDimensionsToypad
                    ) { updateLayout(padLayout.copy {cwDimensionsToypad = it}) }
                }
                CemuDriverSection(padLayout, updateLayout)
                LsfgDllSection(padLayout, updateLayout)
                SettingsSection("Wii U · 说明") {
                    Text(
                        "Cemu-AZ 源码核心（core/cemu/ 经 :cemu-core 模块 vcpkg+CMake 编译，" +
                        "非预编译 so），仅支持 arm64-v8a + Android 11(API 30)+，Vulkan 1.1 渲染。" +
                        "\n\n虚拟按键：GamePad 全套（双摇杆 + ABXY + L/R/ZL/ZR + ⧉/−/HOME + TV 切换 + " +
                        "麦克风 + 摇杆按下），游戏区触摸直通 GamePad 触摸屏（Wii U 唯一可触摸屏）；" +
                        "悬浮 GamePad 小窗（默认开启）可直接触摸，TV 按钮切换主画布 TV/GamePad 画面。" +
                        "均可在 触控 → 布局编辑器 里调位置/大小/显隐。" +
                        "\n\n即时存档走核心原生槽位（1..6，带缩略图与自动备份，读档支持安全模式，" +
                        "进程重启保留）；资源（gameProfiles/resources/内置图形包）由应用启动时" +
                        "自动从 assets 播种到 <filesDir>/cemu/。" +
                        "\n\n进阶文件（otp.bin 等在线文件、自装图形包）放 <filesDir>/cemu/ 对应目录。",
                        color = Color(0xFFFDD835),
                        fontSize = 10.sp, lineHeight = 15.sp)
                }
            }

        // ★★ Switch（Eden 源码核心）：ed* 设置字段 → "EdenEngine/<name>" 门面键
        //   → EdenEngine.setCoreOption 直调 NativeConfig（上游 settings.h 序列化键）
        //   热生效；密钥（assets 自动识别 / 手动选择）、固件安装、自定义 GPU
        //   驱动管理为独立分区。
            GamePlatform.NSWITCH -> item {
            SettingsSection("Switch · 密钥与固件") {
                EdenKeysFirmwareSection()
            }
            // ★★ v8 新增：存档数据管理（导出/导入全部存档 zip）
            SettingsSection("Switch · 存档数据管理") {
                EdenSaveDataSection()
            }
            // ★★ v8 新增：安装游戏附加内容（升级包 / DLC → NAND）
            SettingsSection("Switch · 安装升级包 / DLC") {
                EdenContentInstallSection()
            }
            SettingsSection("Switch · 画面 / 性能") {
                DropdownRow("渲染分辨率",
                    // ResolutionSetup 序数（上游 ENUM 顺序）：3=1x 默认。
                    // >3 提升内部渲染分辨率（GPU 填充率按平方增长），
                    // <3 降分辨率提速省电。
                    listOf("0" to "0.25x (省电·模糊)",
                           "1" to "0.5x (省电)",
                           "2" to "0.75x (省电)",
                           "3" to "1x (默认·原生 720p)",
                           "4" to "1.25x",
                           "5" to "1.5x",
                           "6" to "2x (清晰·耗电)",
                           "7" to "3x (高分屏)",
                           "8" to "4x (平板/电视)",
                           "9" to "5x", "10" to "6x", "11" to "7x", "12" to "8x (极限)"),
                    padLayout.edResolution
                ) { updateLayout(padLayout.copy {edResolution = it}) }
                DropdownRow("GPU 精度",
                    listOf("0" to "低 (快·默认)", "1" to "高 (准确·慢)"),
                    padLayout.edGpuAccuracy
                ) { updateLayout(padLayout.copy {edGpuAccuracy = it}) }
                DropdownRow("CPU 精度",
                    listOf("0" to "自动 (默认)", "1" to "精确 (慢·兼容)",
                           "2" to "不安全 (快·个别游戏异常)",
                           "3" to "偏执 (最慢·调试用)"),
                    padLayout.edCpuAccuracy
                ) { updateLayout(padLayout.copy {edCpuAccuracy = it}) }
                DropdownRow("CPU 后端",
                    // NCE = ARM 原生代码执行（性能最佳，arm64 专属）；
                    // Dynarmic = JIT 重编译（兼容性兜底）。
                    listOf("1" to "NCE 原生执行 (默认·最快)",
                           "0" to "Dynarmic 重编译 (兼容优先)"),
                    padLayout.edCpuBackend
                ) { updateLayout(padLayout.copy {edCpuBackend = it}) }
                DropdownRow("各向异性过滤",
                    listOf("0" to "自动 (跟随游戏)", "1" to "默认",
                           "2" to "2x", "3" to "4x", "4" to "8x", "5" to "16x"),
                    padLayout.edAnisotropy
                ) { updateLayout(padLayout.copy {edAnisotropy = it}) }
                DropdownRow("缩放滤镜",
                    listOf("0" to "最近邻 (默认·锐利)", "1" to "双线性",
                           "2" to "双三次", "3" to "高斯", "4" to "Lanczos",
                           "5" to "ScaleForce", "6" to "FSR (推荐)",
                           "7" to "Area", "8" to "ZeroTangent", "9" to "BSpline",
                           "10" to "Mitchell", "11" to "Spline", "12" to "Mmpx",
                           "13" to "SGSR", "14" to "SGSR Edge"),
                    padLayout.edScalingFilter
                ) { updateLayout(padLayout.copy {edScalingFilter = it}) }
                DropdownRow("抗锯齿",
                    listOf("0" to "关闭 (默认·快)", "1" to "FXAA", "2" to "SMAA"),
                    padLayout.edAntiAliasing
                ) { updateLayout(padLayout.copy {edAntiAliasing = it}) }
                // ★★★ v8：画面比例（edAspectRatio）设置项已删除 ★★★
                //   需求原话：“取消switch核心自带的屏幕比例”。核心现在恒按
                //   AspectRatio=Stretch 拉伸渲染（见 EmulatorScreen.applyCoreOptions，
                //   恒写 "4"），画面形状完全由全局「屏幕缩放」决定
                //   （拉伸/4:3/16:9/自定义布局矩形）—— 与 NGC/WII 同款处理。
                //   edAspectRatio 字段保留仅为存量存档兼容，不再被任何 UI 写入。
                DropdownRow("垂直同步",
                    // VSyncMode 序数：0=Immediate 低延迟、1=Mailbox 兼容低撕裂、
                    // 2=FIFO 稳定（默认）、3=FIFO 宽松。
                    listOf("0" to "关闭 (最低延迟·可能撕裂)",
                           "1" to "邮箱 (低延迟·防撕裂)",
                           "2" to "FIFO (默认·最稳)",
                           "3" to "FIFO 宽松 (省电)"),
                    padLayout.edVsync
                ) { updateLayout(padLayout.copy {edVsync = it}) }
                DropdownRow("帧生成步调",
                    listOf("0" to "自动 (默认)", "1" to "锁定 30",
                           "2" to "锁定 60", "3" to "锁定 90", "4" to "锁定 120"),
                    padLayout.edFramePacing
                ) { updateLayout(padLayout.copy {edFramePacing = it}) }
                DropdownRow("GPU 栅栏行为",
                    listOf("0" to "默认 (默认)", "1" to "立即 (快·偶发闪烁)",
                           "2" to "均衡", "3" to "精确 (慢·稳)"),
                    padLayout.edGpuFence
                ) { updateLayout(padLayout.copy {edGpuFence = it}) }
                DropdownRow("NVDEC 视频解码",
                    listOf("0" to "关闭", "1" to "CPU (默认·兼容)",
                           "2" to "GPU (视频解码硬解)"),
                    padLayout.edNvdec
                ) { updateLayout(padLayout.copy {edNvdec = it}) }
                DropdownRow("ASTC 纹理解码",
                    listOf("0" to "CPU (慢·最兼容)", "1" to "GPU (默认·快)",
                           "2" to "CPU 异步 (平衡)"),
                    padLayout.edAstc
                ) { updateLayout(padLayout.copy {edAstc = it}) }
                DropdownRow("显存使用模式",
                    listOf("0" to "保守 (默认)", "1" to "激进 (大显存设备)"),
                    padLayout.edVramUsage
                ) { updateLayout(padLayout.copy {edVramUsage = it}) }
                DropdownRow("异步着色器编译",
                    // 上游代码默认 false；Android 上开启可显著减少
                    // 首次游玩卡顿（代价：新着色器短暂缺几何）。
                    listOf("true" to "开启 (默认·防卡顿)", "false" to "关闭"),
                    padLayout.edAsyncShaders
                ) { updateLayout(padLayout.copy {edAsyncShaders = it}) }
                DropdownRow("磁盘着色器缓存",
                    listOf("true" to "开启 (默认·二次游玩不卡)", "false" to "关闭"),
                    padLayout.edDiskShaderCache
                ) { updateLayout(padLayout.copy {edDiskShaderCache = it}) }
                DropdownRow("异步 GPU 模拟",
                    listOf("true" to "开启 (默认·快)", "false" to "关闭 (慢·兼容)"),
                    padLayout.edAsyncGpu
                ) { updateLayout(padLayout.copy {edAsyncGpu = it}) }
                DropdownRow("反应式回写",
                    listOf("false" to "关闭 (默认·快)", "true" to "开启 (慢·个别游戏修复)"),
                    padLayout.edReactiveFlush
                ) { updateLayout(padLayout.copy {edReactiveFlush = it}) }
                DropdownRow("GPU 最大频率锁",
                    listOf("false" to "关闭 (默认)", "true" to "锁定最大频率 (发热换性能)"),
                    padLayout.edForceMaxClock
                ) { updateLayout(padLayout.copy {edForceMaxClock = it}) }
                // ===== v4 补全：上游 settings.h 暴露的性能项（此前无入口） =====
                DropdownRow("CPU 时钟",
                    // "fast_cpu_time" CpuClock：0=Normal 1=Boost 2=Overclock
                    listOf("0" to "标准 (默认·不发热)", "1" to "Boost (提速)",
                           "2" to "超频 (最快·发热·部分游戏异常)"),
                    padLayout.edCpuClock
                ) { updateLayout(padLayout.copy {edCpuClock = it}) }
                DropdownRow("GPU 时钟",
                    // "fast_gpu_time" GpuClock：上游 Android 默认 Boost
                    listOf("0" to "标准 (省电)", "1" to "Boost (默认)",
                           "2" to "超频 (最快·发热)"),
                    padLayout.edGpuClock
                ) { updateLayout(padLayout.copy {edGpuClock = it}) }
                DropdownRow("速度限制开关",
                    listOf("true" to "开启 (默认)", "false" to "关闭 (全速运行·音画可能偏快)"),
                    padLayout.edSpeedLimitOn
                ) { updateLayout(padLayout.copy {edSpeedLimitOn = it}) }
                DropdownRow("速度限制",
                    listOf("100" to "100% (默认)", "50" to "50%", "75" to "75%",
                           "150" to "150%", "200" to "200%", "300" to "300%", "9999" to "不限速"),
                    padLayout.edSpeedLimit
                ) { updateLayout(padLayout.copy {edSpeedLimit = it}) }
                DropdownRow("核心速度同步",
                    listOf("false" to "关闭 (默认·快)", "true" to "开启 (同步各核心速度)"),
                    padLayout.edSyncCoreSpeed
                ) { updateLayout(padLayout.copy {edSyncCoreSpeed = it}) }
                DropdownRow("FSR 锐化",
                    // "fsr_sharpening_slider" 0..200%，缩放滤镜=FSR 时生效
                    listOf("0" to "0% (默认)", "25" to "25%", "50" to "50%",
                           "75" to "75%", "100" to "100%", "150" to "150%", "200" to "200%"),
                    padLayout.edFsrSharpening
                ) { updateLayout(padLayout.copy {edFsrSharpening = it}) }
                DropdownRow("ASTC 重压缩",
                    // "astc_recompression"：省显存换画质（大显存设备保持不压缩）
                    listOf("0" to "不压缩 (默认·画质最好)", "1" to "BC1 (省一半显存)",
                           "2" to "BC3 (省显存)"),
                    padLayout.edAstcRecompression
                ) { updateLayout(padLayout.copy {edAstcRecompression = it}) }
                DropdownRow("采样着色",
                    // "sample_shading_fraction" 0..100%：额外抗锯齿（GTA/极潜龙类爬纹改善）
                    listOf("0" to "关闭 (默认)", "25" to "25%", "50" to "50%", "100" to "100%"),
                    padLayout.edSampleShading
                ) { updateLayout(padLayout.copy {edSampleShading = it}) }
                DropdownRow("插帧 (实验)",
                    // "frame_gen"：需要较强 GPU；中端机建议锁定 60 后开
                    listOf("false" to "关闭 (默认)", "true" to "开启"),
                    padLayout.edFrameGen
                ) { updateLayout(padLayout.copy {edFrameGen = it}) }
                DropdownRow("插帧目标帧率",
                    // "frame_gen_target_rate"：0=自动
                    listOf("0" to "自动 (默认)", "60" to "60", "90" to "90", "120" to "120"),
                    padLayout.edFrameGenTarget
                ) { updateLayout(padLayout.copy {edFrameGenTarget = it}) }
                DropdownRow("旧高通驱动补丁",
                    // "patch_old_qcom_drivers"：老 Adreno 驱动纹理花屏修复
                    listOf("false" to "关闭 (默认)", "true" to "开启 (旧驱动花屏时试)"),
                    padLayout.edPatchOldQcom
                ) { updateLayout(padLayout.copy {edPatchOldQcom = it}) }
                DropdownRow("DMA 精度",
                    listOf("0" to "默认 (默认)", "1" to "不安全 (快)", "2" to "安全 (慢)"),
                    padLayout.edDmaAccuracy
                ) { updateLayout(padLayout.copy {edDmaAccuracy = it}) }
                DropdownRow("内存布局",
                    // 个别大型模组游戏需要 >4GB 内存布局。
                    listOf("0" to "4GB (默认)", "1" to "6GB", "2" to "8GB",
                           "3" to "10GB", "4" to "12GB"),
                    padLayout.edMemoryLayout
                ) { updateLayout(padLayout.copy {edMemoryLayout = it}) }
            }
            SettingsSection("Switch · 系统") {
                DropdownRow("主机模式",
                    // 掌机 = 720p 性能档（默认·Android），底座 = 高性能档
                    //（部分游戏帧率更高，发热更大）。
                    // ★ v4 修复：旧版 "1".toBoolean() 恒为 false，选底座实际写入
                    //   掌机（永远开不了底座）；现已修正为显式比较，选择即生效
                    //   （运行形态属运行中可切换项，重启游戏完全生效）。
                    listOf("0" to "掌机 (默认·Android)", "1" to "底座 (高性能·发热)"),
                    padLayout.edDocked
                ) { updateLayout(padLayout.copy {edDocked = it}) }
                DropdownRow("主机语言",
                    // Language 枚举序数（★ v5：默认改为 15=简体中文；6=中文(汉)、16=繁中）。
                    listOf("15" to "简体中文 (默认)", "0" to "日语", "6" to "中文",
                           "16" to "繁体中文", "1" to "英语 (美)",
                           "2" to "法语", "3" to "德语", "4" to "意大利语",
                           "5" to "西班牙语", "7" to "韩语", "8" to "荷兰语",
                           "9" to "葡萄牙语", "10" to "俄语"),
                    padLayout.edLanguage
                ) { updateLayout(padLayout.copy {edLanguage = it}) }
                DropdownRow("主机区域",
                    listOf("1" to "美国 (默认)", "0" to "日本", "2" to "欧洲",
                           "3" to "澳大利亚", "4" to "中国", "5" to "韩国", "6" to "台湾"),
                    padLayout.edRegion
                ) { updateLayout(padLayout.copy {edRegion = it}) }
                // ★★ v4：体感（陀螺仪）—— 设备传感器直通 Switch 六轴
                //   （上游 EmulationActivity 同款数据流；无陀螺仪设备自动跳过）
                DropdownRow("体感 (陀螺仪)",
                    listOf("true" to "开启 (默认·赛车/体感瞄准)",
                           "false" to "关闭"),
                    padLayout.edMotion
                ) { updateLayout(padLayout.copy {edMotion = it}) }
                DropdownRow("游戏速度",
                    listOf("1" to "1x (正常·默认)", "0" to "0.5x (慢动作)",
                           "2" to "2x", "3" to "4x", "4" to "8x (快进联动)"),
                    padLayout.edGameSpeed
                ) { updateLayout(padLayout.copy {edGameSpeed = it}) }
            }
            SettingsSection("Switch · 音频") {
                DropdownRow("音量",
                    listOf("0" to "0%", "20" to "20%", "40" to "40%", "60" to "60%",
                           "80" to "80%", "100" to "100% (默认)"),
                    padLayout.edAudioVolume
                ) { updateLayout(padLayout.copy {edAudioVolume = it}) }
                // ★★ v4：音频引擎（"output_engine"，上游音频页同款入口）——
                //   无声/破音时可切换引擎重试（Android 构建未编 Cubeb）
                DropdownRow("音频引擎",
                    listOf("2" to "SDL3 (默认·推荐)", "0" to "自动", "3" to "Null (无声·调试)"),
                    padLayout.edOutputEngine
                ) { updateLayout(padLayout.copy {edOutputEngine = it}) }
                DropdownRow("静音",
                    listOf("false" to "关闭 (默认)", "true" to "开启 (静音)"),
                    padLayout.edAudioMuted
                ) { updateLayout(padLayout.copy {edAudioMuted = it}) }
            }
            EdenDriverSection(padLayout, updateLayout)
            SettingsSection("Switch · 说明") {
                Text(
                    "Eden 源码核心（core/eden/ 经 :eden-core 模块 CPM+CMake 编译，" +
                        "非预编译 so），仅支持 arm64-v8a + Android 9(API 28)+，Vulkan 1.1 渲染。" +
                        "\n\n密钥：把 prod.keys/title.keys 放进 APK assets/eden/keys/ 可自动识别" +
                        "（无需玩家操作），或在上方手动选择文件；固件在上方安装（官方固件 zip，" +
                        "含系统字体 —— 部分游戏与中文显示需要）。" +
                        "\n\n虚拟按键：Pro 手柄全套已补齐（双摇杆 + ABXY + L/R/ZL/ZR + +/−/HOME + 截屏 + L3/R3 摇杆按下），" +
                        "游戏区触摸直通窗口触摸（掌机形态 = Switch 触摸屏）；体感（陀螺仪）默认开（赛车/体感瞄准），" +
                        "组合键支持 A/B/X/Y/L/R/ZL/ZR/L3/R3/HOME/截屏/方向键任意 2-4 键组合；" +
                        "均可在 触控 → 布局编辑器 里调位置/大小/显隐。" +
                        "\n\n存档走游戏内存档（NAND，<filesDir>/eden/nand/）；核心无即时存档。" +
                        "驱动管理见上方分区（自定义驱动经 adrenotools 加载，切换后重启游戏生效）。",
                    color = Color(0xFFFDD835),
                    fontSize = 10.sp, lineHeight = 15.sp)
            }
        }
        } // when (platform)

        // === 遮罩 / 按钮主题（所有核心统一入口，配置按核心独立存储在
        // PadLayout.overlayThemeJson，见 OverlayTheme.kt） ===
        item { OverlayThemeSection(platform, padLayout, updateLayout) }
    }
}

// ---------------------------------------------------------------------------
// PSP（libretro PPSSPP）核心选项行 —— 与 PspCoreOptions 目录联动
//   · 读取：<filesDir>/psp_core_options.ini（PspSettingsStore）
//   · 写入：PspEngine.setCoreOption = 持久化 + PspNative.setCoreOption 直推
//     （游戏会话中改即时热应用；下次启动 applyStoredCoreOptions 整表重放）
// ---------------------------------------------------------------------------
@Composable
private fun PspCoreOptionRow(def: PspCoreOptions.Def) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val state = remember(def.key) {
        androidx.compose.runtime.mutableStateOf(
            PspSettingsStore.readCoreOption(ctx, def.key) ?: def.default
        )
    }
    DropdownRow(
        label = def.label,
        options = def.choices,
        selected = state.value,
        onSelect = { v ->
            state.value = v
            com.nesstation.app.core.engine.PspEngine.get().setCoreOption(def.key, v)
            // ★ v22: 「高清纹理替换」开启时的纹理包状态反馈 —— 该选项是
            //   官方核心选项（PPSSPP v1.20.4 libretro_core_options.h 实证），
            //   但 PSP/textures/<游戏ID>/ 无包时开启不会产生任何画面变化，
            //   此前零提示 = 用户反馈"没有正确生效"的主因。
            if (def.key == "ppsspp_texture_replacement" && v == "enabled") {
                val (packCount, packPath) = scanPspTexturePacks(ctx)
                if (packCount > 0) {
                    android.widget.Toast.makeText(ctx,
                        "已开启高清纹理替换：检测到 ${packCount} 个纹理包（${packPath}），重进游戏后生效",
                        android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(ctx,
                        "已开启，但未检测到 HD 纹理包 —— 不会有画面变化。请把纹理包解压到 PSP/textures/<游戏ID>/（见下方说明）",
                        android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    )
}

/** ★ v22: PSP HD 纹理包扫描 —— 核心 memstick 根由引擎传入的系统目录决定，
 *  按常见布局扫描内置/外置 files 两类根，返回 (包目录数, 命中的纹理目录)。 */
private fun scanPspTexturePacks(ctx: android.content.Context): Pair<Int, String> {
    val roots = listOfNotNull(
        ctx.getExternalFilesDir(null), ctx.filesDir)
    val candidates = roots.flatMap { r ->
        listOf(
            java.io.File(r, "PSP/textures"),
            java.io.File(r, "psp/PSP/textures"),
            java.io.File(r, "psp/textures"),
            java.io.File(r, "PPSSPP/PSP/textures"))
    }.distinctBy { it.absolutePath }
    for (dir in candidates) {
        val packs = dir.listFiles { f -> f.isDirectory } ?: continue
        if (packs.isNotEmpty()) return Pair(packs.size, dir.absolutePath)
    }
    return Pair(0, "")
}

/** ★ v22: PSP HD 纹理包状态行 —— 给出真实检测状态与放置目录，
 *  把"静默无效"变成可行动的提示。 */
@Composable
private fun PspTexturePackStatus() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val (packCount, packPath) = remember { scanPspTexturePacks(ctx) }
    Text(
        if (packCount > 0)
            "已检测到 ${packCount} 个 HD 纹理包（${packPath}）—— 开启「高清纹理替换」并重进游戏即生效。"
        else
            "「高清纹理替换」需要 HD 纹理包才会改变画面：把包解压到 Android/data/com.nesstation.app/files/PSP/textures/<游戏ID>/（无包时开启没有任何效果）。",
        color = Color(0xFFFDD835),
        fontSize = 10.sp, lineHeight = 14.sp)
}

/** PRO ad-hoc 服务器地址（自由文本 —— 接受主机名或 IP；留空 = 不下发）。 */
@Composable
private fun PspAdhocServerRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val state = remember {
        androidx.compose.runtime.mutableStateOf(
            PspSettingsStore.readCoreOption(ctx, "ppsspp_change_pro_ad_hoc_server_address") ?: ""
        )
    }
    val neon = com.nesstation.app.ui.neon.NeonUi.isNeon
    androidx.compose.foundation.layout.Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text("PRO ad-hoc 服务器地址", color = if (neon) com.nesstation.app.ui.neon.Neon.TextHi else Color(0xFF1E2A3A),
             fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        androidx.compose.material3.OutlinedTextField(
            value = state.value,
            onValueChange = { state.value = it },
            placeholder = { Text("如 192.168.1.100 或 myserver.com（留空=禁用）", fontSize = 11.sp) },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                androidx.compose.material3.TextButton(onClick = {
                    val v = state.value.trim()
                    // 空串也写入：覆盖旧地址 = 禁用自定义服务器（核心默认）
                    com.nesstation.app.core.engine.PspEngine.get()
                        .setCoreOption("ppsspp_change_pro_ad_hoc_server_address", v)
                }) { Text("保存") }
            }
        )
    }
}


// ===========================================================================
// ★★ Wii U（Cemu-AZ）自定义 Vulkan 驱动管理 —— 上游 CustomDriversScreen 移植 ★★
//   · 安装：SAF 选 zip → 校验 meta.json（schemaVersion/minApi/libraryName）
//     → 移入 <filesDir>/cemu/customDrivers/<uuid>/
//   · 选择：系统驱动 / 已装驱动单选 → NativeSettings.setCustomDriverPath
//     （写入 settings.xml，adrenotools 在渲染器初始化时加载 → 重启游戏生效）
//   · 删除：卸下驱动目录（当前选中被删时自动回退系统驱动）
// ===========================================================================
@Composable
fun CemuDriverSection(
    padLayout: PadLayout,
    updateLayout: (PadLayout) -> Unit,
    // ★ 游戏内深色面板（EmulatorScreen 核心设置）：不用浅色 SettingsSection
    //   卡片包裹，文字换深色背景可读配色；主设置页保持原样。
    dark: Boolean = false
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var installed by remember { mutableStateOf<List<com.nesstation.app.core.storage.CemuDriverStore.CemuDriver>>(emptyList()) }
    var installing by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }
    var coreAvailable by remember { mutableStateOf(false) }
    var driverSupported by remember { mutableStateOf(false) }
    // 当前选中：核心 settings.xml 里的值优先（selectDriver 即时写入），
    // 核心不可用时回落 PadLayout 记录（cwDriverPath，仅 UI 展示）
    var selectedPath by remember { mutableStateOf<String?>(null) }

    // 初始化：核心可用性 + 驱动列表 + 当前选中（后台线程，避免 IO 卡 UI）；
    // refreshKey 变化（安装/删除后）重新扫描
    androidx.compose.runtime.LaunchedEffect(installing, refreshKey) {
        coreAvailable = com.nesstation.app.core.jni.CemuNative.ensureLoaded()
        installed = if (coreAvailable) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.nesstation.app.core.storage.CemuDriverStore.parseInstalledDrivers(ctx)
            }
        } else emptyList()
        driverSupported = com.nesstation.app.core.storage.CemuDriverStore.supportsLoadingCustomDriver()
        selectedPath = com.nesstation.app.core.storage.CemuDriverStore.getCurrentDriverPath()
            ?: padLayout.cwDriverPath.ifBlank { null }
    }

    val installLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        installing = true
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            val (status, driver) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.nesstation.app.core.storage.CemuDriverStore.installDriverFromZip(ctx, uri)
            }
            installing = false
            refreshKey++
            val msg = when (status) {
                com.nesstation.app.core.storage.CemuDriverStore.InstallStatus.ALREADY_INSTALLED -> "该驱动已安装"
                com.nesstation.app.core.storage.CemuDriverStore.InstallStatus.ERROR_INSTALLING ->
                    "安装失败（zip 缺 meta.json / 版本不匹配 / 库文件缺失）"
                com.nesstation.app.core.storage.CemuDriverStore.InstallStatus.INSTALLED -> "驱动安装成功：${driver?.metadata?.name ?: ""}"
            }
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }

    // 驱动区内容（主设置页 SettingsSection 卡片 / 游戏内深色面板共用）
    val driverContent: @Composable () -> Unit = {
        if (!coreAvailable) {
            Text("Cemu 核心未加载（需要 arm64-v8a + Android 11+），驱动管理不可用。",
                color = Color(0xFFB00020), fontSize = 11.sp, lineHeight = 15.sp)
        } else {
            Text(
                "自定义驱动经 adrenotools 在核心渲染器初始化时加载 —— " +
                "切换驱动后需重启游戏生效。驱动 zip 需含 meta.json（schemaVersion=1）与对应的 .so 库。" +
                (if (!driverSupported) "\n当前环境不支持加载自定义驱动。" else ""),
                // ★ v17: 安装区提示改亮色（用户反馈“灰色看不清”）
                color = if (dark) Color(0xFF6FCF97)
                        else if (com.nesstation.app.ui.neon.NeonUi.isNeon) com.nesstation.app.ui.neon.Neon.Accent
                        else Color(0xFF27AE60),
                fontSize = 10.sp, lineHeight = 15.sp)

            // 系统驱动（默认项）
            val systemSelected = (selectedPath ?: "") == ""
            TextButtonRow(
                label = "系统驱动${if (systemSelected) " ✓" else ""}",
                sub = "使用设备自带 Vulkan 驱动（默认）",
                enabled = !installing,
                dark = dark
            ) {
                com.nesstation.app.core.storage.CemuDriverStore.selectSystemDriver()
                selectedPath = null
                updateLayout(padLayout.copy { cwDriverPath = "" })
            }

            // 已安装驱动列表
            for (driver in installed) {
                val isSel = selectedPath == driver.path
                TextButtonRow(
                    label = "${driver.metadata.name}${if (isSel) " ✓" else ""}",
                    sub = "v${driver.metadata.driverVersion} · ${driver.metadata.vendor} · API ${driver.metadata.minApi}+",
                    enabled = !installing,
                    dark = dark
                ) {
                    if (!isSel) {
                        val ok = com.nesstation.app.core.storage.CemuDriverStore.selectDriver(driver)
                        if (ok) {
                            selectedPath = driver.path
                            updateLayout(padLayout.copy { cwDriverPath = driver.path })
                        } else {
                            Toast.makeText(ctx, "选择失败（核心不可用）", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                if (isSel) {
                    Text(
                        "当前选中（重启游戏后生效）",
                        color = Color(0xFF27AE60), fontSize = 10.sp,
                        modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
                    )
                }
            }

            // 删除按钮行（对选中项）
            val selDriver = installed.firstOrNull { it.path == selectedPath }
            if (selDriver != null) {
                TextButtonRow(
                    label = "删除「${selDriver.metadata.name}」",
                    sub = "移除驱动文件（将回退系统驱动）",
                    enabled = !installing,
                    dark = dark
                ) {
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            com.nesstation.app.core.storage.CemuDriverStore.deleteDriver(ctx, selDriver)
                        }
                        selectedPath = null
                        updateLayout(padLayout.copy { cwDriverPath = "" })
                        refreshKey++
                    }
                }
            }

            // 安装按钮
            TextButtonRow(
                label = if (installing) "正在安装…" else "＋ 从 zip 安装驱动",
                sub = "选择包含 meta.json + .so 的驱动压缩包（如 Turnip/Mesa 构建）",
                enabled = !installing,
                dark = dark
            ) {
                installLauncher.launch(arrayOf("application/zip"))
            }
        }
    }

    if (dark) {
        // 游戏内深色面板：无卡片包裹，直接排布（外层已提供分区标题）
        driverContent()
    } else {
        SettingsSection("Wii U · 驱动管理（Vulkan）") {
            driverContent()
        }
    }
}

/** 简单文字按钮行（驱动管理用；主面板无 Icon 可用，纯文本最简）。 */
@Composable
private fun TextButtonRow(
    label: String,
    sub: String,
    enabled: Boolean = true,
    trailing: String? = null,
    dark: Boolean = false,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 4.dp, vertical = 6.dp)
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                label,
                color = when {
                    !enabled -> Color(0xFF8899AA)
                    dark -> Color.White
                    // ★ v21: Neon 主题下 label 不再落入浅色面板的深灰蓝
                    //   （深底上灰暗看不清 = 用户反馈安装驱动/选择密钥都是灰字体
                    //   的根因），改用主题高亮文本色（与 PSP 区 PRO 地址行同款）。
                    com.nesstation.app.ui.neon.NeonUi.isNeon -> com.nesstation.app.ui.neon.Neon.TextHi
                    else -> Color(0xFF1E2A3A)
                },
                fontSize = 14.sp, fontWeight = FontWeight.Medium
            )
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            if (trailing != null) {
                Text(trailing, color = Color(0xFFB00020), fontSize = 12.sp)
            }
        }
        Text(sub,
            // ★ v17: 安装/管理按钮说明改亮色（用户反馈“灰色看不清”）
            color = when {
                dark -> Color(0xFF6FCF97)
                com.nesstation.app.ui.neon.NeonUi.isNeon -> com.nesstation.app.ui.neon.Neon.Accent
                else -> Color(0xFF27AE60)
            },
            fontSize = 10.sp, lineHeight = 13.sp)
    }
}

/**
 * ★★★ R13: LSFG Lossless.dll 管理区（帧生成着色器容器导入）。
 * - 「导入 DLL」：SAF 选择用户自备的 Lossless.dll → 拷贝到
 *   <filesDir>/cemu/lsfg/Lossless.dll → 核心已加载时即时校验（PE 资源结构
 *   + SPIR-V 魔数 + 必需资源齐全性）。
 * - APK assets 内置：重打包把 Lossless.dll 放进 assets（cemu/lsfg/、
 *   cemu/、lsfg/ 或根目录均可），应用启动时自动解包到同一位置 ——
 *   无需用户任何操作。
 * - 「移除 DLL」：删除文件并清空路径（帧生成自动禁用）。
 */
@Composable
private fun LsfgDllSection(
    padLayout: com.nesstation.app.core.storage.PadLayout,
    updateLayout: (com.nesstation.app.core.storage.PadLayout) -> Unit
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var importing by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableStateOf(0) }

    // DLL 落盘位置（用户导入与 assets 播种共用，导入即覆盖）
    fun dllFile(): java.io.File {
        val dir = java.io.File(ctx.filesDir, "cemu/lsfg")
        if (!dir.exists()) dir.mkdirs()
        return java.io.File(dir, "Lossless.dll")
    }

    // 状态刷新：文件存在性 + 核心校验结果（核心未加载时仅显示文件状态）
    androidx.compose.runtime.LaunchedEffect(refreshKey) {
        val f = dllFile()
        val coreLoaded = try {
            com.nesstation.app.core.jni.CemuNative.ensureLoaded()
        } catch (_: Throwable) { false }
        if (!f.isFile) {
            statusText = if (padLayout.cwFrameGenDllPath.isNotBlank()) "已清空（原导入路径失效）" else null
        } else if (coreLoaded) {
            statusText = try {
                when (info.cemu.cemu.nativeinterface.NativeEmulation.setFrameGenDll(f.absolutePath)) {
                    0 -> "DLL 就绪并通过校验（${f.length() / 1024} KB）"
                    else -> {
                        val err = info.cemu.cemu.nativeinterface.NativeEmulation.getFrameGenDllError()
                        "校验失败：$err"
                    }
                }
            } catch (t: Throwable) { "核心未就绪（文件已就位，进游戏时自动校验）" }
        } else {
            statusText = "文件已就位（${f.length() / 1024} KB，进游戏时自动校验）"
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val f = dllFile()
                    val tmp = java.io.File(f.parentFile, "Lossless.dll.tmp")
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    } ?: return@withContext "读取所选文件失败"
                    if (tmp.length() < 4096) {
                        tmp.delete()
                        return@withContext "文件过小（不是有效的 Lossless.dll）"
                    }
                    if (f.exists()) f.delete()
                    if (!tmp.renameTo(f)) return@withContext "写入失败（存储空间不足？）"
                    // 核心已加载时即时校验
                    val coreLoaded = try {
                        com.nesstation.app.core.jni.CemuNative.ensureLoaded()
                    } catch (_: Throwable) { false }
                    if (coreLoaded) {
                        try {
                            when (info.cemu.cemu.nativeinterface.NativeEmulation.setFrameGenDll(f.absolutePath)) {
                                0 -> null // 成功（状态由 LaunchedEffect 刷新）
                                else -> "导入完成但校验失败：" +
                                    info.cemu.cemu.nativeinterface.NativeEmulation.getFrameGenDllError()
                            }
                        } catch (t: Throwable) { null }
                    } else null
                } catch (e: Exception) { "导入失败：${e.message}" }
            }
            importing = false
            refreshKey++
            updateLayout(padLayout.copy { cwFrameGenDllPath = dllFile().absolutePath })
            result?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
        }
    }

    SettingsSection("Wii U · 帧生成 DLL (Lossless.dll)") {
        Text(
            "LSFG 3 帧生成需要 Lossless.dll（Lossless Scaling 的着色器容器，" +
            "仅作数据解析、不执行其中代码）。\n\n" +
            "· 导入：点击下方按钮选择自备的 Lossless.dll；\n" +
            "· 内置：把 Lossless.dll 放进 APK 的 assets（cemu/lsfg/ 或根目录），" +
            "应用启动时自动读取；\n" +
            "· 导入的 DLL 覆盖内置版本；移除后帧生成自动关闭。",
            // ★ v17: 安装区提示改亮色（用户反馈“灰色看不清”）
            color = if (com.nesstation.app.ui.neon.NeonUi.isNeon) com.nesstation.app.ui.neon.Neon.Accent else Color(0xFF27AE60),
            fontSize = 10.sp, lineHeight = 15.sp)

        TextButtonRow(
            label = "导入 Lossless.dll",
            sub = "从设备存储选择 DLL 文件（覆盖现有）",
            enabled = !importing,
            dark = false
        ) {
            importLauncher.launch(arrayOf("*/*"))
        }

        val f = dllFile()
        if (f.isFile) {
            TextButtonRow(
                label = "移除 Lossless.dll",
                sub = "删除已导入/内置的 DLL（帧生成将不可用）",
                enabled = !importing,
                dark = false
            ) {
                try { f.delete() } catch (_: Exception) {}
                try {
                    info.cemu.cemu.nativeinterface.NativeEmulation.setFrameGenDll("")
                } catch (_: Throwable) {}
                updateLayout(padLayout.copy { cwFrameGenDllPath = "" })
                refreshKey++
                Toast.makeText(ctx, "已移除 Lossless.dll", Toast.LENGTH_SHORT).show()
            }
        }

        statusText?.let {
            Text(it,
                color = when {
                    it.contains("就绪") -> Color(0xFF27AE60)
                    it.contains("失败") || it.contains("清空") -> Color(0xFFB00020)
                    else -> Color(0xFFFFB300)   // ★ v17: 中性/待校验状态改亮琥珀色（原灰 0xFF667788）
                },
                fontSize = 10.sp, lineHeight = 14.sp,
                modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
        }
    }
}

// ===========================================================================
// ★★ Switch（Eden）密钥与固件管理 ★★
//   · 密钥：assets 自动识别（构建者放 assets/eden/keys/，引擎启动时复制到
//     <filesDir>/eden/keys/，无玩家操作）或玩家手动选择（SAF 按钮）；
//     状态实时显示（areKeysPresent）。
//   · 固件：SAF 选官方固件 zip → 解包校验全 .nca →
//     <filesDir>/eden/nand/system/Contents/registered/ → initializeSystem(true)。
// ===========================================================================
@Composable
fun EdenKeysFirmwareSection(dark: Boolean = false) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val engine = remember { com.nesstation.app.core.engine.EdenEngine.get() }
    var keysPresent by remember { mutableStateOf<Boolean?>(null) }
    var fwAvailable by remember { mutableStateOf(false) }
    var fwVersion by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(-1) }

    fun refresh() {
        if (!com.nesstation.app.core.jni.EdenNative.ensureLoaded()) return
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try { engine.coreInitOnce() } catch (_: Throwable) {}
                try {
                    keysPresent = engine.let {
                        if (com.nesstation.app.core.jni.EdenNative.loaded)
                            org.yuzu.yuzu_emu.NativeLibrary.areKeysPresent()
                        else null
                    }
                } catch (_: Throwable) {}
                try { fwAvailable = org.yuzu.yuzu_emu.NativeLibrary.isFirmwareAvailable() } catch (_: Throwable) {}
                try { fwVersion = org.yuzu.yuzu_emu.NativeLibrary.firmwareVersion() ?: "" } catch (_: Throwable) {}
            }
        }
    }
    LaunchedEffect(Unit) { refresh() }

    // ★ v17: 安装/管理区提示改亮色（用户反馈“灰色看不清”）：深色面板 = 亮薄荷绿、
    //   Neon 主题 = 主题强调色（与选项同款）、浅色 = 选项绿（与“已安装/就绪”同色）。
    val dimColor = if (dark) Color(0xFF6FCF97)
        else if (com.nesstation.app.ui.neon.NeonUi.isNeon) com.nesstation.app.ui.neon.Neon.Accent
        else Color(0xFF27AE60)

    if (!com.nesstation.app.core.jni.EdenNative.ensureLoaded()) {
        Text("Eden 核心未加载（需要 arm64-v8a + Android 9+），密钥/固件管理不可用。",
            color = Color(0xFFB00020), fontSize = 11.sp, lineHeight = 15.sp)
        return
    }

    // ---- 密钥状态 + 手动加载 ----
    Text(
        if (keysPresent == true) "密钥：已安装（加密游戏可运行）"
        else if (keysPresent == null) "密钥：核心初始化中…"
        else "密钥：未安装（加密游戏无法启动/解密）",
        color = when (keysPresent) {
            true -> Color(0xFF27AE60); null -> dimColor; else -> Color(0xFFB00020)
        },
        fontSize = 11.sp, lineHeight = 15.sp)
    Text(
        "来源二选一：构建者把 prod.keys / title.keys 放进 APK assets/eden/keys/（自动识别）；" +
            "或点击下方按钮手动选择 .keys 文件。",
        color = dimColor, fontSize = 10.sp, lineHeight = 14.sp)

    val keyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            val err = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                engine.installKeysFromUri(ctx, uri)
            }
            busy = false
            refresh()
            Toast.makeText(
                ctx,
                err ?: "密钥安装成功（${if (keysPresent == true) "已生效" else "待校验"}）",
                if (err == null) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
            ).show()
        }
    }
    TextButtonRow(
        label = "选择密钥文件（prod.keys / title.keys）",
        sub = "SAF 选择 .keys 文件 → 复制到 <filesDir>/eden/keys/ 并即时重载",
        enabled = !busy,
        dark = dark
    ) { keyLauncher.launch(arrayOf("*/*")) }

    // ---- 固件状态 + 安装/卸载 ----
    Text(
        if (fwAvailable) "固件：已安装${if (fwVersion.isNotBlank()) "（$fwVersion）" else ""}"
        else "固件：未安装（部分游戏与系统字体需要）",
        color = if (fwAvailable) Color(0xFF27AE60) else Color(0xFFFFB300),   // ★ v17: 未安装提示改亮琥珀色（原灰 0xFF667788 看不清）
        fontSize = 11.sp, lineHeight = 15.sp)

    val fwLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true; progress = 0
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            val err = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                engine.installFirmwareFromUri(ctx, uri) { p ->
                    // 进度回调发生在 IO 线程的非挂起 lambda 里 ——
                    // 用 Handler 投递到主线程（withContext 是挂起函数，此处不可用）
                    android.os.Handler(android.os.Looper.getMainLooper()).post { progress = p }
                    false
                }
            }
            busy = false; progress = -1
            refresh()
            Toast.makeText(
                ctx, err ?: "固件安装成功（重启游戏生效）",
                if (err == null) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
            ).show()
        }
    }
    TextButtonRow(
        label = "安装固件（官方固件 zip）",
        sub = "解包校验 .nca → <filesDir>/eden/nand/system/Contents/registered/",
        enabled = !busy,
        dark = dark
    ) { fwLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*")) }

    if (busy && progress >= 0) {
        Text("固件安装中… $progress%", color = dimColor, fontSize = 10.sp)
    }

    if (fwAvailable) {
        TextButtonRow(
            label = "卸载固件",
            sub = "清空 registered/ 并重载系统（重启游戏生效）",
            enabled = !busy,
            dark = dark
        ) {
            busy = true
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                val err = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    engine.uninstallFirmware()
                }
                busy = false
                refresh()
                Toast.makeText(ctx, err ?: "固件已卸载", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

// ===========================================================================
// ★★ v8：Switch 存档数据管理（导出 / 导入）★★
//   Switch 存档位于 <filesDir>/eden/nand/user/save/（标准 yuzu NAND 布局，
//   游戏在内存档）。导出 = 该目录打包 zip（条目带 save/ 前缀，可反复
//   导入）；导入 = 校验备份格式 → 覆盖式恢复 → 下次进游戏生效。
//   纯文件操作，不触碰核心/System 状态；有游戏在跑（isLoaded）时按钮禁用。
// ===========================================================================
@Composable
fun EdenSaveDataSection(dark: Boolean = false) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val engine = remember { com.nesstation.app.core.engine.EdenEngine.get() }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(-1) }
    var resultMsg by remember { mutableStateOf<String?>(null) }
    var pendingImportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var saveSummary by remember { mutableStateOf("") }

    // ★ v17: 存档管理区提示改亮色（同 EdenKeysFirmwareSection）
    val dimColor = if (dark) Color(0xFF6FCF97)
        else if (com.nesstation.app.ui.neon.NeonUi.isNeon) com.nesstation.app.ui.neon.Neon.Accent
        else Color(0xFF27AE60)

    // 存档概览（纯文件扫描；busy 变化后刷新）
    LaunchedEffect(busy) {
        val s = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val dir = engine.nandUserSaveDir()
            if (!dir.isDirectory) "存档目录尚未创建（进入游戏后自动生成）"
            else {
                val fs = dir.walkTopDown().filter { it.isFile }.toList()
                if (fs.isEmpty()) "存档目录为空（进入游戏后自动生成）"
                else "当前存档：${fs.size} 个文件，共 ${fs.sumOf { it.length() } / 1024} KB"
            }
        }
        saveSummary = s
    }
    if (saveSummary.isNotBlank()) {
        Text(saveSummary, color = dimColor, fontSize = 10.sp, lineHeight = 14.sp)
    }

    // ---- 导出（SAF CreateDocument → zip 全部存档）----
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true; progress = 0
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            val err = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    ctx.contentResolver.openOutputStream(uri)?.let { os ->
                        edenZipSaveDirToStream(engine.nandUserSaveDir(), os) { p ->
                            android.os.Handler(android.os.Looper.getMainLooper()).post { progress = p }
                        }
                    } ?: "无法打开目标文件写入"
                } catch (e: Exception) { "导出失败：${e.message ?: e.javaClass.simpleName}" }
            }
            busy = false; progress = -1
            resultMsg = err ?: "存档导出成功（zip 内含 save/ 目录，可用「导入存档」随时恢复）"
        }
    }
    TextButtonRow(
        label = "导出全部存档（zip）",
        sub = "NAND 用户存档 (<filesDir>/eden/nand/user/save/) 打包备份",
        enabled = !busy && !engine.isLoaded,
        dark = dark
    ) { exportLauncher.launch("switch-saves-backup.zip") }

    // ---- 导入（SAF 选 zip → 确认覆盖 → 恢复）----
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) pendingImportUri = uri
    }
    TextButtonRow(
        label = "导入存档（zip 恢复）",
        sub = "从本功能导出的备份恢复 —— 会覆盖现有全部存档，不可撤销",
        enabled = !busy && !engine.isLoaded,
        dark = dark
    ) { importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*")) }

    if (busy && progress >= 0) {
        Text(if (progress >= 100) "处理中…" else "处理中… $progress%",
            color = dimColor, fontSize = 10.sp)
    }

    // 导入确认（覆盖不可逆）
    pendingImportUri?.let { uri ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingImportUri = null },
            title = { Text("导入存档？") },
            text = { Text("将清空并覆盖现有全部 Switch 存档，该操作不可撤销。\n\n确认继续？") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    pendingImportUri = null
                    busy = true; progress = 0
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                        val err = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            try {
                                edenRestoreSaveDirFromZip(engine.nandUserSaveDir(), {
                                    ctx.contentResolver.openInputStream(uri)
                                        ?: throw java.io.IOException("无法读取所选文件")
                                }) { p ->
                                    android.os.Handler(android.os.Looper.getMainLooper()).post { progress = p }
                                }
                            } catch (e: Exception) { "导入失败：${e.message ?: e.javaClass.simpleName}" }
                        }
                        busy = false; progress = -1
                        resultMsg = err ?: "存档导入成功（下次进入游戏生效）"
                    }
                }) { Text("覆盖导入") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingImportUri = null }) { Text("取消") }
            }
        )
    }

    // 结果弹窗
    resultMsg?.let { msg ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { resultMsg = null },
            title = { Text("存档数据管理") },
            text = { Text(msg) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { resultMsg = null }) { Text("确定") }
            }
        )
    }
}

/** 把存档目录打包进 zip 输出流（条目名带 save/ 前缀）；返回错误文案或 null=成功。 */
private fun edenZipSaveDirToStream(saveDir: java.io.File, out: java.io.OutputStream,
                                   onProgress: (Int) -> Unit): String? {
    val files = if (saveDir.isDirectory)
        saveDir.walkTopDown().filter { it.isFile }.toList() else emptyList()
    if (files.isEmpty()) return "没有可导出的存档（先进入游戏产生存档）"
    return try {
        java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(out)).use { zos ->
            files.forEachIndexed { i, f ->
                val rel = f.relativeTo(saveDir).invariantSeparatorsPath
                zos.putNextEntry(java.util.zip.ZipEntry("save/$rel"))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
                onProgress((i + 1) * 100 / files.size)
            }
        }
        null
    } catch (e: Exception) { "导出失败：${e.message ?: e.javaClass.simpleName}" }
}

/**
 * 从导出 zip 恢复存档（校验 save/ 前缀 → 清空目录 → 解包，带 Zip Slip 防护）。
 * @param openStream 每次调用打开一个新的输入流（校验/解包各读一遍）。
 */
private fun edenRestoreSaveDirFromZip(saveDir: java.io.File,
                                      openStream: () -> java.io.InputStream,
                                      onProgress: (Int) -> Unit): String? {
    fun norm(name: String) = name.trim().removePrefix("./").removePrefix("/")
    // 第一遍：校验是本功能导出的备份
    val total = try {
        var count = 0
        java.util.zip.ZipInputStream(java.io.BufferedInputStream(openStream())).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory && norm(e.name).startsWith("save/")) count++
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        count
    } catch (e: Exception) { return "读取备份失败：${e.message ?: e.javaClass.simpleName}" }
    if (total == 0) return "不是有效的 Switch 存档备份（zip 内应包含 save/ 目录）"
    // 覆盖式恢复
    saveDir.deleteRecursively()
    saveDir.mkdirs()
    // 第二遍：解包
    val rootCanon = saveDir.canonicalFile.path
    try {
        var done = 0
        java.util.zip.ZipInputStream(java.io.BufferedInputStream(openStream())).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory) {
                    val rel = norm(e.name).removePrefix("save/")
                    if (rel.isNotBlank()) {
                        val target = java.io.File(saveDir, rel)
                        // Zip Slip 防护：目标必须落在存档目录内
                        if (target.canonicalFile.path.startsWith(rootCanon + java.io.File.separator)) {
                            target.parentFile?.mkdirs()
                            java.io.FileOutputStream(target).use { fos -> zis.copyTo(fos) }
                        }
                    }
                }
                zis.closeEntry()
                done++
                onProgress(done * 100 / (total * 2))
                e = zis.nextEntry
            }
        }
    } catch (e: Exception) { return "解包失败：${e.message ?: e.javaClass.simpleName}" }
    return null
}

// ===========================================================================
// ★★ v8：安装游戏附加内容（升级包 / DLC）★★
//   上游 InstallableActions.installContent 同构：SAF 选 .nsp/.xci →
//   先拷到 cacheDir 真实路径（与 installKeysFromUri 同款中转，native 只认
//   文件系统路径）→ NativeLibrary.installFileToNand → 删除临时文件。
//   结果码对照 ContentManager InstallResult（0=成功 1=覆盖 2=失败
//   3=拒绝安装基础包/游戏本体）。安装进 NAND 后，下次启动对应游戏自动
//   启用升级/DLC。前置：密钥已安装（解密需要）；无游戏在跑（isLoaded）。
// ===========================================================================
@Composable
fun EdenContentInstallSection(dark: Boolean = false) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val engine = remember { com.nesstation.app.core.engine.EdenEngine.get() }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(-1) }
    var resultMsg by remember { mutableStateOf<String?>(null) }
    var keysPresent by remember { mutableStateOf<Boolean?>(null) }

    // ★ v17: 升级包/DLC 安装区提示改亮色（同 EdenKeysFirmwareSection）
    val dimColor = if (dark) Color(0xFF6FCF97)
        else if (com.nesstation.app.ui.neon.NeonUi.isNeon) com.nesstation.app.ui.neon.Neon.Accent
        else Color(0xFF27AE60)

    if (!com.nesstation.app.core.jni.EdenNative.ensureLoaded()) {
        Text("Eden 核心未加载（需要 arm64-v8a + Android 9+），内容安装不可用。",
            color = Color(0xFFB00020), fontSize = 11.sp, lineHeight = 15.sp)
        return
    }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try { engine.coreInitOnce() } catch (_: Throwable) {}
            keysPresent = try { org.yuzu.yuzu_emu.NativeLibrary.areKeysPresent() } catch (_: Throwable) { null }
        }
    }

    Text(
        "选择游戏对应的升级包（Update）或 DLC 文件（.nsp / .xci）安装进主机存储，" +
            "下次启动对应游戏自动生效；同一内容重复安装会覆盖。游戏本体请通过" +
            "游戏库导入运行，此处只装附加内容。",
        color = dimColor, fontSize = 10.sp, lineHeight = 14.sp)
    if (keysPresent == false) {
        Text("未检测到密钥 —— 升级包 / DLC 是加密内容，请先在上方「密钥与固件」安装 prod.keys。",
            color = Color(0xFFB00020), fontSize = 10.sp, lineHeight = 14.sp)
    }
    if (engine.isLoaded) {
        Text("检测到有 Switch 游戏在运行 —— 请先退出游戏再安装内容。",
            color = Color(0xFFB00020), fontSize = 10.sp, lineHeight = 14.sp)
    }

    val installLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true; progress = 0
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            val report = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val sb = StringBuilder()
                var success = 0; var overwrite = 0; var fail = 0; var baseGame = 0
                uris.forEach { u ->
                    val name = edenSafDisplayName(ctx, u) ?: "所选文件"
                    val tmp = java.io.File(ctx.cacheDir,
                        "eden_install_${System.identityHashCode(u)}_${name}")
                    try {
                        // 空间预检：cacheDir 剩余空间（留 64MB 余量）
                        val size = edenSafSize(ctx, u)
                        if (size > 0 && size > ctx.cacheDir.usableSpace - (64L shl 20)) {
                            fail++
                            sb.append("失败 ").append(name)
                                .append("：内部空间不足（需 ${size / (1024L * 1024)} MB）\n")
                            return@forEach
                        }
                        // SAF → 真实路径中转（installKeysFromUri 同款）
                        ctx.contentResolver.openInputStream(u)?.use { ins ->
                            tmp.outputStream().use { ins.copyTo(it) }
                        } ?: throw java.io.IOException("无法读取所选文件")
                        val code = org.yuzu.yuzu_emu.NativeLibrary
                            .installFileToNand(tmp.absolutePath) { max, p ->
                                if (max > 0) {
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        progress = (p * 100 / max).toInt().coerceIn(0, 100)
                                    }
                                }
                                false
                            }
                        when (code) {
                            0 -> { success++; sb.append("成功 ").append(name).append("：安装完成\n") }
                            1 -> { overwrite++; sb.append("成功 ").append(name).append("：覆盖安装\n") }
                            3 -> { baseGame++; sb.append("失败 ").append(name)
                                .append("：这是游戏本体（基础包），此处只装升级包/DLC\n") }
                            else -> { fail++; sb.append("失败 ").append(name)
                                .append("：安装失败（文件损坏/非升级包或 DLC/缺密钥）\n") }
                        }
                    } catch (t: Throwable) {
                        fail++
                        sb.append("失败 ").append(name).append("：")
                            .append(t.message ?: t.javaClass.simpleName).append("\n")
                    } finally {
                        try { tmp.delete() } catch (_: Throwable) {}
                    }
                }
                "安装完成：成功 $success 个，覆盖 $overwrite 个，失败 ${fail + baseGame} 个。\n\n$sb".trim()
            }
            busy = false; progress = -1
            resultMsg = report
        }
    }
    TextButtonRow(
        label = "选择并安装升级包 / DLC",
        sub = "SAF 多选 .nsp / .xci → 拷入临时目录安装进 NAND → 自动清理",
        enabled = !busy && keysPresent == true && !engine.isLoaded,
        dark = dark
    ) { installLauncher.launch(arrayOf("*/*")) }

    if (busy && progress >= 0) {
        Text("安装中… $progress%", color = dimColor, fontSize = 10.sp)
    }

    resultMsg?.let { msg ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { resultMsg = null },
            title = { Text("安装结果") },
            text = { Text(msg) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { resultMsg = null }) { Text("确定") }
            }
        )
    }
}

/** SAF 文件显示名（OpenableColumns.DISPLAY_NAME）。 */
private fun edenSafDisplayName(ctx: android.content.Context, uri: android.net.Uri): String? = try {
    ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
    }
} catch (_: Exception) { null }

/** SAF 文件大小（字节；未知返回 -1）。 */
private fun edenSafSize(ctx: android.content.Context, uri: android.net.Uri): Long = try {
    ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
        if (idx >= 0 && cursor.moveToFirst()) cursor.getLong(idx) else -1L
    } ?: -1L
} catch (_: Exception) { -1L }

// ===========================================================================
// ★★ Switch（Eden）自定义 GPU 驱动管理 —— 上游 GpuDriverHelper 同构 ★★
//   · 安装：SAF 选 zip → 校验 meta.json（name/libraryName/minApi）
//     → 解包 <filesDir>/eden/gpu_driver/（adrenotools 即时加载）
//   · 切换：系统驱动（清空解包目录）/ 已导入 zip 重装
//   · 删除：移除归档（启用中的解包不受影响，禁用走系统驱动）
// ===========================================================================
@Composable
fun EdenDriverSection(
    padLayout: PadLayout,
    updateLayout: (PadLayout) -> Unit,
    dark: Boolean = false
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val engine = remember { com.nesstation.app.core.engine.EdenEngine.get() }
    var installed by remember { mutableStateOf<List<Pair<String, org.yuzu.yuzu_emu.utils.GpuDriverMetadata>>>(emptyList()) }
    var activeName by remember { mutableStateOf<String?>(null) }
    var driverSupported by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }
    var infoLine by remember { mutableStateOf("") }

    LaunchedEffect(installing, refreshKey) {
        if (!com.nesstation.app.core.jni.EdenNative.ensureLoaded()) return@LaunchedEffect
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try { engine.coreInitOnce() } catch (_: Throwable) {}
            try {
                installed = org.yuzu.yuzu_emu.utils.GpuDriverHelper.getDrivers()
                activeName = org.yuzu.yuzu_emu.utils.GpuDriverHelper
                    .installedCustomDriverData.name
                driverSupported = try {
                    org.yuzu.yuzu_emu.utils.GpuDriverHelper.supportsCustomDriverLoading()
                } catch (_: Throwable) { false }
                infoLine = "当前驱动：${engine.currentGpuDriver()}\n" +
                    engine.vulkanInfo() + "\nCPU 后端：" + engine.cpuBackend()
            } catch (_: Throwable) {}
        }
    }

    SettingsSection("Switch · 驱动管理（Vulkan）") {
        if (!com.nesstation.app.core.jni.EdenNative.ensureLoaded()) {
            Text("Eden 核心未加载（需要 arm64-v8a + Android 9+），驱动管理不可用。",
                color = Color(0xFFB00020), fontSize = 11.sp, lineHeight = 15.sp)
            return@SettingsSection
        }

        val dimColor = if (dark) Color(0xFF6FCF97)
            else if (com.nesstation.app.ui.neon.NeonUi.isNeon) com.nesstation.app.ui.neon.Neon.Accent
            else Color(0xFF27AE60)   // ★ v17: 亮色化（同上）

        if (infoLine.isNotBlank()) {
            Text(infoLine, color = dimColor, fontSize = 10.sp, lineHeight = 15.sp)
        }
        Text(
            "自定义驱动经 adrenotools 在核心初始化时加载 —— 切换后重启游戏生效。" +
                "驱动 zip 需含 meta.json（name/library/minApi）与对应 .so。" +
                (if (!driverSupported) "\n当前设备不支持加载自定义驱动（非 Adreno/无 KGSL）。" else ""),
            color = dimColor, fontSize = 10.sp, lineHeight = 15.sp)

        // 系统驱动（默认）
        val systemSelected = activeName == null
        TextButtonRow(
            label = "系统驱动${if (systemSelected) " ✓" else ""}",
            sub = "使用设备自带 Vulkan 驱动（默认）",
            enabled = !installing,
            dark = dark
        ) {
            installing = true
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    org.yuzu.yuzu_emu.utils.GpuDriverHelper.installDefaultDriver(
                        ctx, ctx.applicationInfo.nativeLibraryDir
                    )
                }
                installing = false; refreshKey++
                Toast.makeText(ctx, "已切回系统驱动（重启游戏生效）", Toast.LENGTH_SHORT).show()
            }
        }

        // 已导入驱动 zip 列表（点击=重装启用）
        for ((path, meta) in installed) {
            val isSel = activeName == meta.name
            TextButtonRow(
                label = "${meta.name ?: "未知"}${if (isSel) " ✓" else ""}",
                sub = "v${meta.version ?: "?"} · ${meta.vendor ?: "?"} · API ${meta.minApi}+",
                enabled = !installing,
                dark = dark
            ) {
                installing = true
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        org.yuzu.yuzu_emu.utils.GpuDriverHelper.installCustomDriver(
                            ctx, ctx.applicationInfo.nativeLibraryDir, android.net.Uri.fromFile(java.io.File(path))
                        ) == org.yuzu.yuzu_emu.utils.GpuDriverHelper.InstallResult.INSTALLED
                    }
                    installing = false; refreshKey++
                    Toast.makeText(
                        ctx,
                        if (ok) "驱动已启用（重启游戏生效）" else "启用失败（meta.json/库文件不完整）",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            if (isSel) {
                Text("当前启用（重启游戏后生效）",
                    color = Color(0xFF27AE60), fontSize = 10.sp,
                    modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
            }
            TextButtonRow(
                label = "删除「${meta.name ?: path}」",
                sub = "移除驱动 zip 归档",
                enabled = !installing,
                dark = dark
            ) {
                installing = true
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        org.yuzu.yuzu_emu.utils.GpuDriverHelper.deleteDriver(path)
                    }
                    installing = false; refreshKey++
                    Toast.makeText(ctx, "已删除驱动归档", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 从 zip 导入
        val driverLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            installing = true
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    org.yuzu.yuzu_emu.utils.GpuDriverHelper.installCustomDriver(
                        ctx, ctx.applicationInfo.nativeLibraryDir, uri
                    )
                }
                installing = false; refreshKey++
                val msg = when (res) {
                    org.yuzu.yuzu_emu.utils.GpuDriverHelper.InstallResult.INSTALLED ->
                        "驱动安装成功：${org.yuzu.yuzu_emu.utils.GpuDriverHelper.installedCustomDriverData.name ?: ""}"
                    org.yuzu.yuzu_emu.utils.GpuDriverHelper.InstallResult.INVALID_ZIP ->
                        "安装失败（zip 缺 meta.json / 库文件不完整）"
                    org.yuzu.yuzu_emu.utils.GpuDriverHelper.InstallResult.MIN_API ->
                        "安装失败（驱动要求的 Android 版本高于当前系统）"
                }
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            }
        }
        TextButtonRow(
            label = "从 zip 导入驱动",
            sub = "Adreno 系自定义驱动（Turnip/Mesa 等，zip 含 meta.json）",
            enabled = !installing,
            dark = dark
        ) { driverLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*")) }
    }
}
