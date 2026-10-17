#pragma once
// ★★★ NesStation R12/R13: 帧生成（FrameGen，LSFG 3.1 · Lossless.dll，默认关）
//   R12 初版为自研简化光流（tile 匹配）；R13 依照参考「风之杖HD 重编译版」
//   的实现形态（runtime/src/vk/lsfg.cpp，WWHD_LSFG_*）与开源 lsfg-vk 项目的
//   逆向接口，完整移植 LSFG 3.1 神经网络光流帧生成：
//     - Lossless.dll 作为【数据容器】解析（PE RCDATA 资源内的 SPIR-V
//       compute 着色器 + 网络结构），不在设备上执行任何 PE 代码；
//     - 26 个着色器：mipmaps / generate（字节码修补出 SDR 变体）/
//       alpha0-3 / beta0-4 / gamma0-4 / delta0-4 / epsilon0-4；
//     - 7 级特征金字塔（预 pass 光流估计）+ 逐生成帧主 pass 合成；
//     - UBO{timestamp,iteration,...} + PushConstants{specialFlag,subiteration}；
//     - 帧序与 lsfg-vk 一致：真实帧 present 前生成并先于真实帧上屏，
//       时间戳 (i+1)/(total+1) —— 时序正确无 judder。
//   交付形态：
//     - SetDllPath：用户经设置页「导入 DLL」按钮（SAF）选定的
//       Lossless.dll 路径；APK assets 内置的 DLL 由前端解包到
//       <filesDir>/cemu/lsfg/Lossless.dll 后同样经此路径注入。
//     - SetConfig：倍率(2/3/4) / 光流比例(0.25..1.0) / 性能-质量变体。
//   铁律约束（与 R8 呈现侧缩放同源）：只动最终上屏管线，绝不触碰
//   游戏内部渲染目标/光照/阴影采样。
//   安全策略：
//     - 默认关闭；仅垂直同步 != 关闭 时生效（Immediate 无节拍基准）
//     - DLL 缺失/校验失败自动禁用（真实帧呈现不受影响）
//     - 连续失败 5 次本轮会话自动停用
//     - 需要交换链附加图像（倍率数），配置变更自动请求交换链重建
#include <vulkan/vulkan_core.h>
#include <cstdint>
#include <string>

struct SwapchainInfoVk;

namespace VulkanFrameGen
{
        bool IsEnabled();
        void SetEnabled(bool enable);

        // ---- DLL 管理（JNI 线程调用；内部解析+校验，失败置错误状态） ----
        // 返回 0 = 成功；非 0 = 失败（GetDllError 取原因）。
        int SetDllPath(const std::string& path);
        // 最近一次 DLL 加载错误（空串 = 无错误/已就绪）
        std::string GetDllError();
        // DLL 是否已成功加载并通过校验（UI 状态显示用）
        bool IsDllReady();

        // ---- 运行配置（JNI 线程调用，热生效：下一帧起按新配置重建上下文） ----
        // multiplier: 2/3/4；flowScale: 0.25..1.0；performanceMode: 性能变体
        void SetConfig(int multiplier, float flowScale, bool performanceMode);

        // ---- 交换链配合（SwapchainInfoVk / VulkanRenderer 调用） ----
        // 帧生成启用时主画布交换链需要的附加图像数（= 倍率，含 1 张余量）
        uint32_t GetSwapchainImagePadding();
        // 配置变更后请求交换链重建（GPU 线程在 present 路径消费）
        bool ConsumeSwapchainRecreateRequest();

        // ---- 帧路径钩子（GPU/Latte 线程） ----
        // 扩展检测（renderer 初始化后调用一次）
        void OnRendererInitialized(VkPhysicalDevice physicalDevice);
        // 主画布真实帧 present 之前调用（时序正确的关键：生成帧需引用
        // "当前即将上屏的真实帧"作为插值终点，并先于它上屏）。
        // realFrameFence：真实帧 command buffer 的 fence（此时已 signaled，
        // 本函数 host 等待它确保拷贝源内容完整）。
        // 内部流程：等待上一轮 fence → 拷贝真实帧进源图像 ping-pong 层 →
        // 提交（预pass+逐生成帧主pass+输出 blit）→ 逐帧 present 合成帧。
        // 任何失败静默返回（真实帧呈现节奏不受影响）。
        void OnBeforeRealFramePresent(VkDevice device, VkQueue graphicsQueue, VkQueue presentQueue,
                SwapchainInfoVk& chainInfo, VkFence realFrameFence);
}
