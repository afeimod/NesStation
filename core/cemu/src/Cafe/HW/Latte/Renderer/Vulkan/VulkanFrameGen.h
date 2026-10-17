#pragma once
// ★★★ NesStation R12: 帧生成（FrameGen，实验性，默认关）
//   参考「风之杖HD 重编译版」APK 的 LSFG 帧生成语义（WWHD_LSFG_* /
//   gfx::request_frame_generation）的自研简化移植：
//   游戏内部渲染节奏完全不变（仍按游戏自己的帧率出帧），仅在最终呈现侧
//   于两个真实帧之间用 GPU compute 插入一帧合成帧：
//     真实帧N(上屏) → 合成帧N+0.5(光流warp混合) → 真实帧N+1(上屏) → ...
//   30fps 游戏在 60Hz 屏上获得逐帧连续的观感（感知流畅度接近 60fps）。
//   铁律约束：只动最终上屏管线（与 R8 呈现侧缩放同一条链路），绝不触碰
//   游戏内部渲染目标/光照/阴影采样。
//   技术形态：
//     - 光流估计：1/4 分辨率 tile 匹配（16x16 tile ±6 搜索，粗+细两级）
//     - 帧合成：前后帧沿光流各 warp 半步线性混合
//     - 呈现：独立 acquire/present 序列（不干扰主交换链状态机），
//       配合 FIFO 垂直同步自然形成 60Hz 节拍
//   安全策略：
//     - 默认关闭；仅垂直同步 != 关闭 时生效（Immediate 无节拍基准）
//     - 设备不支持（compute / R8G8B8A8 storage / 格式）自动禁用
//     - 连续失败 5 次自动停用，真实帧呈现永不因本模块受阻
//     - 合成帧不绘制 ImGui 覆盖层（UI 只在真实帧，避免 UI 拖影）
#include <vulkan/vulkan_core.h>
#include <cstdint>

struct SwapchainInfoVk;

namespace VulkanFrameGen
{
        bool IsEnabled();
        void SetEnabled(bool enable);

        // 真实帧已提交 present 之后调用（GPU/Latte 线程，主画布 only）。
        // realFrameFence：真实帧 command buffer 的 fence（此时已 signaled，
        // 本函数 host 等待它确保拷贝源有效）。
        // 内部流程：等待同步 → 独立 acquire → 录制(拷贝当前帧+光流+合成+blit)
        // → 提交 → present 合成帧。任何失败静默返回（真实帧节奏不受影响）。
        void OnRealFramePresented(VkDevice device, VkQueue graphicsQueue, VkQueue presentQueue,
                SwapchainInfoVk& chainInfo, VkFence realFrameFence);

        // 扩展检测（renderer 初始化后调用一次；不支持时即便开启也无效果）
        void OnRendererInitialized(VkPhysicalDevice physicalDevice);
}
