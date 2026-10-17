// ★★★ NesStation R12: 帧生成实现（实验性，默认关）—— 见 VulkanFrameGen.h
//   参考「风之杖HD 重编译版」APK 的呈现侧帧生成语义（WWHD_LSFG_*，
//   gfx::request_frame_generation，LSFG 光流插帧）自研简化移植：
//   不依赖其闭源 LSFG.dll（光流网络 shader 从用户自备 dll 提取），
//   改用轻量 tile 匹配光流 + 双向 warp 混合 compute 管线。
//   全部失败路径静默回退，真实帧呈现节奏永不因本模块受阻。
#include "Cafe/HW/Latte/Renderer/Vulkan/VulkanFrameGen.h"
#include "Cafe/HW/Latte/Renderer/Vulkan/SwapchainInfoVk.h"
#include "Cafe/HW/Latte/Renderer/Vulkan/VulkanAPI.h"
#include "Cemu/Logging/CemuLogging.h" // 本基座日志声明所在（上游原日志头在本基座已移除，勿沿用上游 include 路径）

#include <glslang/Public/ShaderLang.h>
#include <glslang/SPIRV/GlslangToSpv.h>

#include <atomic>
#include <array>
#include <cstring>

namespace VulkanFrameGen
{
        // ================= 状态 =================
        static std::atomic<bool> s_enabled{ false };
        static std::atomic<bool> s_deviceChecked{ false };
        static std::atomic<bool> s_deviceSupported{ false };
        static std::atomic<int> s_failStreak{ 0 };

        static bool s_inited = false;
        static VkDevice s_device = VK_NULL_HANDLE;
        static VkPhysicalDevice s_physDev = VK_NULL_HANDLE;
        static VkExtent2D s_extent{ 0, 0 };
        static VkSwapchainKHR s_swapchain = VK_NULL_HANDLE;

        struct FGImage
        {
                VkImage image = VK_NULL_HANDLE;
                VkDeviceMemory memory = VK_NULL_HANDLE;
                VkImageView view = VK_NULL_HANDLE;
                VkExtent2D extent{ 0, 0 };
                VkFormat format = VK_FORMAT_R8G8B8A8_UNORM;
        };

        // full[2]：前后真实帧全分辨率拷贝（prev/cur 交换）
        // small[2]：对应 1/4 分辨率（光流输入）
        // flow：tile 光流图（tile 网格分辨率，RGBA8 编码 ±24px 位移）
        // synth：合成帧（全分辨率，storage 写入后 blit 上屏）
        static FGImage s_full[2];
        static FGImage s_small[2];
        static FGImage s_flow;
        static FGImage s_synth;
        static int s_curFull = 0;   // 本帧 cur 在 s_full 的下标（prev = ^1）
        static int s_curSmall = 0;

        static VkDescriptorSetLayout s_flowSetLayout = VK_NULL_HANDLE;
        static VkDescriptorSetLayout s_synthSetLayout = VK_NULL_HANDLE;
        static VkPipelineLayout s_flowPipeLayout = VK_NULL_HANDLE;
        static VkPipelineLayout s_synthPipeLayout = VK_NULL_HANDLE;
        static VkPipeline s_flowPipeline = VK_NULL_HANDLE;
        static VkPipeline s_synthPipeline = VK_NULL_HANDLE;
        static VkDescriptorPool s_descPool = VK_NULL_HANDLE;
        static VkDescriptorSet s_flowSet = VK_NULL_HANDLE;
        static VkDescriptorSet s_synthSet = VK_NULL_HANDLE;
        static VkSampler s_samplerLinear = VK_NULL_HANDLE;

        static VkCommandPool s_cmdPool = VK_NULL_HANDLE;
        static VkCommandBuffer s_cmdBuffer = VK_NULL_HANDLE;
        static VkSemaphore s_acquireSem[2] = { VK_NULL_HANDLE, VK_NULL_HANDLE };
        static VkSemaphore s_submitSem[2] = { VK_NULL_HANDLE, VK_NULL_HANDLE };
        static VkFence s_fences[2] = { VK_NULL_HANDLE, VK_NULL_HANDLE };
        static uint32 s_ringIndex = 0;

        // ================= compute shader 源 =================
        // 光流估计：每 invocation 处理一个 16x16 tile（1/4 分辨率坐标），
        // 在 prev 帧内搜索最佳匹配位移（SAD on luma），粗（步长3 ±6）+ 细（步长1 ±2）两级。
        // 输出 RGBA8 编码：位移以全分辨率像素计，范围 ±24px，(f/48+0.5) 映射到 [0,1]。
        static const char* kFlowShaderSource = R"FGSHADER(#version 450
layout(local_size_x = 1, local_size_y = 1, local_size_z = 1) in;
layout(set = 0, binding = 0) uniform sampler2D uPrevSmall;
layout(set = 0, binding = 1) uniform sampler2D uCurSmall;
layout(set = 0, binding = 2, rgba8) writeonly uniform image2D uFlowImg;

float lumaAt(sampler2D tex, ivec2 pix, ivec2 size)
{
        pix = clamp(pix, ivec2(0), size - ivec2(1));
        vec4 c = texelFetch(tex, pix, 0);
        return dot(c.rgb, vec3(0.299, 0.587, 0.114));
}

float tileSAD(sampler2D prevTex, sampler2D curTex, ivec2 base, ivec2 delta, ivec2 size)
{
        float sad = 0.0;
        for (int y = 0; y < 16; y += 2)
        {
                for (int x = 0; x < 16; x += 2)
                {
                        float cp = lumaAt(curTex, base + ivec2(x, y), size);
                        float pp = lumaAt(prevTex, base + ivec2(x, y) + delta, size);
                        sad += abs(cp - pp);
                }
        }
        return sad;
}

void main()
{
        ivec2 smallSize = textureSize(uCurSmall, 0);
        ivec2 tiles = ivec2(glWorkGroupID.xy);
        ivec2 base = tiles * 16;
        if (base.x + 16 > smallSize.x || base.y + 16 > smallSize.y)
        {
                imageStore(uFlowImg, tiles, vec4(0.5, 0.5, 0.0, 1.0));
                return;
        }
        ivec2 best = ivec2(0);
        float bestSAD = 1e30;
        // 粗搜索 ±6 步长 3
        for (int dy = -6; dy <= 6; dy += 3)
        {
                for (int dx = -6; dx <= 6; dx += 3)
                {
                        float sad = tileSAD(uPrevSmall, uCurSmall, base, ivec2(dx, dy), smallSize);
                        if (sad < bestSAD) { bestSAD = sad; best = ivec2(dx, dy); }
                }
        }
        // 细化 ±2 步长 1
        for (int dy = best.y - 2; dy <= best.y + 2; dy++)
        {
                for (int dx = best.x - 2; dx <= best.x + 2; dx++)
                {
                        if (abs(dx - best.x) > 2 || abs(dy - best.y) > 2) continue;
                        float sad = tileSAD(uPrevSmall, uCurSmall, base, ivec2(dx, dy), smallSize);
                        if (sad < bestSAD) { bestSAD = sad; best = ivec2(dx, dy); }
                }
        }
        // small-res 位移 ×4 = 全分辨率位移；编码 (f/48+0.5)
        vec2 flowFullPx = vec2(best) * 4.0;
        vec2 enc = clamp(flowFullPx / 48.0 + 0.5, vec2(0.0), vec2(1.0));
        imageStore(uFlowImg, tiles, vec4(enc, 0.0, 1.0));
}
)FGSHADER";

        // 帧合成：prev/cur 沿光流各 warp 半步线性混合（t=0.5 中点帧）
        static const char* kSynthShaderSource = R"FGSHADER(#version 450
layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;
layout(set = 0, binding = 0) uniform sampler2D uPrevFull;
layout(set = 0, binding = 1) uniform sampler2D uCurFull;
layout(set = 0, binding = 2) uniform sampler2D uFlowTex;
layout(set = 0, binding = 3, rgba8) writeonly uniform image2D uSynthImg;

void main()
{
        ivec2 fullSize = imageSize(uSynthImg);
        ivec2 pix = ivec2(glGlobalInvocationID.xy);
        if (pix.x >= fullSize.x || pix.y >= fullSize.y) return;
        vec2 uv = (vec2(pix) + vec2(0.5)) / vec2(fullSize);
        vec2 flowFullPx = (texture(uFlowTex, uv).rg - vec2(0.5)) * 48.0;
        vec2 halfFlowUv = (flowFullPx * 0.5) / vec2(fullSize);
        vec4 cPrev = texture(uPrevFull, clamp(uv - halfFlowUv, vec2(0.0), vec2(1.0)));
        vec4 cCur = texture(uCurFull, clamp(uv + halfFlowUv, vec2(0.0), vec2(1.0)));
        imageStore(uSynthImg, pix, mix(cPrev, cCur, 0.5));
}
)FGSHADER";

        // ================= glslang 编译辅助（与上游 RendererShaderVk 同款序列） =================
        static consteval TBuiltInResource GetDefaultBuiltInResource()
        {
                TBuiltInResource defaultResource = {};
                defaultResource.maxLights = 32;
                defaultResource.maxClipPlanes = 6;
                defaultResource.maxTextureUnits = 32;
                defaultResource.maxTextureCoords = 32;
                defaultResource.maxVertexAttribs = 64;
                defaultResource.maxVertexUniformComponents = 4096;
                defaultResource.maxVaryingFloats = 64;
                defaultResource.maxVertexTextureImageUnits = 32;
                defaultResource.maxCombinedTextureImageUnits = 80;
                defaultResource.maxTextureImageUnits = 32;
                defaultResource.maxFragmentUniformComponents = 4096;
                defaultResource.maxDrawBuffers = 32;
                defaultResource.maxVertexUniformVectors = 128;
                defaultResource.maxVaryingVectors = 8;
                defaultResource.maxFragmentUniformVectors = 16;
                defaultResource.maxVertexOutputVectors = 16;
                defaultResource.maxFragmentInputVectors = 15;
                defaultResource.minProgramTexelOffset = -8;
                defaultResource.maxProgramTexelOffset = 7;
                defaultResource.maxClipDistances = 8;
                defaultResource.maxComputeWorkGroupCountX = 65535;
                defaultResource.maxComputeWorkGroupCountY = 65535;
                defaultResource.maxComputeWorkGroupCountZ = 65535;
                defaultResource.maxComputeWorkGroupSizeX = 1024;
                defaultResource.maxComputeWorkGroupSizeY = 1024;
                defaultResource.maxComputeWorkGroupSizeZ = 64;
                defaultResource.maxComputeUniformComponents = 1024;
                defaultResource.maxComputeTextureImageUnits = 16;
                defaultResource.maxComputeImageUniforms = 8;
                defaultResource.maxComputeAtomicCounters = 8;
                defaultResource.maxComputeAtomicCounterBuffers = 1;
                defaultResource.maxVaryingComponents = 60;
                defaultResource.maxVertexOutputComponents = 64;
                defaultResource.maxGeometryInputComponents = 64;
                defaultResource.maxGeometryOutputComponents = 128;
                defaultResource.maxFragmentInputComponents = 128;
                defaultResource.maxImageUnits = 8;
                defaultResource.maxCombinedImageUnitsAndFragmentOutputs = 8;
                defaultResource.maxCombinedShaderOutputResources = 8;
                defaultResource.maxImageSamples = 0;
                defaultResource.maxVertexImageUniforms = 0;
                defaultResource.maxTessControlImageUniforms = 0;
                defaultResource.maxTessEvaluationImageUniforms = 0;
                defaultResource.maxGeometryImageUniforms = 0;
                defaultResource.maxFragmentImageUniforms = 8;
                defaultResource.maxCombinedImageUniforms = 8;
                defaultResource.maxGeometryTextureImageUnits = 16;
                defaultResource.maxGeometryOutputVertices = 256;
                defaultResource.maxGeometryTotalOutputComponents = 1024;
                defaultResource.maxGeometryUniformComponents = 1024;
                defaultResource.maxGeometryVaryingComponents = 64;
                defaultResource.maxTessControlInputComponents = 128;
                defaultResource.maxTessControlOutputComponents = 128;
                defaultResource.maxTessControlTextureImageUnits = 16;
                defaultResource.maxTessControlUniformComponents = 1024;
                defaultResource.maxTessControlTotalOutputComponents = 4096;
                defaultResource.maxTessEvaluationInputComponents = 128;
                defaultResource.maxTessEvaluationOutputComponents = 128;
                defaultResource.maxTessEvaluationTextureImageUnits = 16;
                defaultResource.maxTessEvaluationUniformComponents = 1024;
                defaultResource.maxTessPatchComponents = 120;
                defaultResource.maxPatchVertices = 32;
                defaultResource.maxTessGenLevel = 64;
                defaultResource.maxViewports = 16;
                defaultResource.maxVertexAtomicCounters = 0;
                defaultResource.maxTessControlAtomicCounters = 0;
                defaultResource.maxTessEvaluationAtomicCounters = 0;
                defaultResource.maxGeometryAtomicCounters = 0;
                defaultResource.maxFragmentAtomicCounters = 8;
                defaultResource.maxCombinedAtomicCounters = 8;
                defaultResource.maxAtomicCounterBindings = 1;
                defaultResource.maxVertexAtomicCounterBuffers = 0;
                defaultResource.maxTessControlAtomicCounterBuffers = 0;
                defaultResource.maxTessEvaluationAtomicCounterBuffers = 0;
                defaultResource.maxGeometryAtomicCounterBuffers = 0;
                defaultResource.maxFragmentAtomicCounterBuffers = 1;
                defaultResource.maxCombinedAtomicCounterBuffers = 1;
                defaultResource.maxAtomicCounterBufferSize = 16384;
                defaultResource.maxTransformFeedbackBuffers = 4;
                defaultResource.maxTransformFeedbackInterleavedComponents = 64;
                defaultResource.maxCullDistances = 8;
                defaultResource.maxCombinedClipAndCullDistances = 8;
                defaultResource.maxSamples = 4;
                defaultResource.limits.nonInductiveForLoops = true;
                defaultResource.limits.whileLoops = true;
                defaultResource.limits.doWhileLoops = true;
                defaultResource.limits.generalUniformIndexing = true;
                defaultResource.limits.generalAttributeMatrixVectorIndexing = true;
                defaultResource.limits.generalVaryingIndexing = true;
                defaultResource.limits.generalSamplerIndexing = true;
                defaultResource.limits.generalVariableIndexing = true;
                defaultResource.limits.generalConstantMatrixVectorIndexing = true;
                return defaultResource;
        }

        // glslang::InitializeProcess 由 VulkanRenderer 构造完成（FG 仅在其生命周期内工作）
        static bool CompileComputeSpirv(const char* glslSource, std::vector<uint8>& spirvOut)
        {
                glslang::TShader shader(EShLangCompute);
                const char* cstr = glslSource;
                shader.setStrings(&cstr, 1);
                shader.setEnvInput(glslang::EShSourceGlsl, EShLangCompute, glslang::EShClientVulkan, 100);
                shader.setEnvClient(glslang::EShClientVulkan, glslang::EShTargetClientVersion::EShTargetVulkan_1_1);
                shader.setEnvTarget(glslang::EShTargetSpv, glslang::EShTargetLanguageVersion::EShTargetSpv_1_3);
                std::string preprocessed;
                glslang::TShader::ForbidIncluder includer;
                TBuiltInResource resources = GetDefaultBuiltInResource();
                EShMessages messages = (EShMessages)(EShMsgSpvRules | EShMsgVulkanRules);
                if (!shader.preprocess(&resources, 450, ENoProfile, false, false, messages, &preprocessed, includer))
                {
                        cemuLog_log(LogType::Force, "FrameGen: GLSL preprocess failed: {}", shader.getInfoLog());
                        return false;
                }
                const char* preprocessedCStr = preprocessed.c_str();
                shader.setStrings(&preprocessedCStr, 1);
                if (!shader.parse(&resources, 100, false, messages))
                {
                        cemuLog_log(LogType::Force, "FrameGen: GLSL parse failed: {}", shader.getInfoLog());
                        return false;
                }
                glslang::TProgram program;
                program.addShader(&shader);
                if (!program.link(messages))
                {
                        cemuLog_log(LogType::Force, "FrameGen: GLSL link failed: {}", program.getInfoLog());
                        return false;
                }
                if (!program.mapIO())
                        return false;
                std::vector<uint32> spirv;
                spv::SpvBuildLogger logger;
                glslang::SpvOptions options;
                options.disableOptimizer = false;
                options.validate = false;
                options.optimizeSize = true;
                GlslangToSpv(*program.getIntermediate(EShLangCompute), spirv, &logger, &options);
                spirvOut.resize(spirv.size() * sizeof(uint32));
                memcpy(spirvOut.data(), spirv.data(), spirvOut.size());
                return !spirvOut.empty();
        }

        // ================= 资源管理 =================
        static void DestroyImage(VkDevice device, FGImage& img)
        {
                if (img.view != VK_NULL_HANDLE) { vkDestroyImageView(device, img.view, nullptr); img.view = VK_NULL_HANDLE; }
                if (img.image != VK_NULL_HANDLE) { vkDestroyImage(device, img.image, nullptr); img.image = VK_NULL_HANDLE; }
                if (img.memory != VK_NULL_HANDLE) { vkFreeMemory(device, img.memory, nullptr); img.memory = VK_NULL_HANDLE; }
        }

        static bool CreateImage2D(VkDevice device, VkPhysicalDevice physDev, VkFormat format,
                uint32 width, uint32 height, VkImageUsageFlags usage, FGImage& out)
        {
                VkImageCreateInfo imgInfo{};
                imgInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
                imgInfo.imageType = VK_IMAGE_TYPE_2D;
                imgInfo.format = format;
                imgInfo.extent = { width, height, 1 };
                imgInfo.mipLevels = 1;
                imgInfo.arrayLayers = 1;
                imgInfo.samples = VK_SAMPLE_COUNT_1_BIT;
                imgInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
                imgInfo.usage = usage;
                imgInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
                imgInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
                if (vkCreateImage(device, &imgInfo, nullptr, &out.image) != VK_SUCCESS)
                        return false;
                VkMemoryRequirements reqs{};
                vkGetImageMemoryRequirements(device, out.image, &reqs);
                uint32 memTypeIndex = 0xFFFFFFFF;
                VkPhysicalDeviceMemoryProperties memProps{};
                vkGetPhysicalDeviceMemoryProperties(physDev, &memProps);
                for (uint32 i = 0; i < memProps.memoryTypeCount; i++)
                {
                        if ((reqs.memoryTypeBits & (1u << i)) &&
                                (memProps.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT))
                        {
                                memTypeIndex = i;
                                break;
                        }
                }
                if (memTypeIndex == 0xFFFFFFFF)
                {
                        DestroyImage(device, out);
                        return false;
                }
                VkMemoryAllocateInfo allocInfo{};
                allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
                allocInfo.allocationSize = reqs.size;
                allocInfo.memoryTypeIndex = memTypeIndex;
                if (vkAllocateMemory(device, &allocInfo, nullptr, &out.memory) != VK_SUCCESS)
                {
                        DestroyImage(device, out);
                        return false;
                }
                if (vkBindImageMemory(device, out.image, out.memory, 0) != VK_SUCCESS)
                {
                        DestroyImage(device, out);
                        return false;
                }
                VkImageViewCreateInfo viewInfo{};
                viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
                viewInfo.image = out.image;
                viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
                viewInfo.format = format;
                viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
                viewInfo.subresourceRange.levelCount = 1;
                viewInfo.subresourceRange.layerCount = 1;
                if (vkCreateImageView(device, &viewInfo, nullptr, &out.view) != VK_SUCCESS)
                {
                        DestroyImage(device, out);
                        return false;
                }
                out.extent = { width, height };
                out.format = format;
                return true;
        }

        static void DestroyResources(VkDevice device)
        {
                for (auto& img : s_full) DestroyImage(device, img);
                for (auto& img : s_small) DestroyImage(device, img);
                DestroyImage(device, s_flow);
                DestroyImage(device, s_synth);
                if (s_samplerLinear) { vkDestroySampler(device, s_samplerLinear, nullptr); s_samplerLinear = VK_NULL_HANDLE; }
                if (s_descPool) { vkDestroyDescriptorPool(device, s_descPool, nullptr); s_descPool = VK_NULL_HANDLE; }
                s_flowSet = VK_NULL_HANDLE;
                s_synthSet = VK_NULL_HANDLE;
                if (s_flowPipeline) { vkDestroyPipeline(device, s_flowPipeline, nullptr); s_flowPipeline = VK_NULL_HANDLE; }
                if (s_synthPipeline) { vkDestroyPipeline(device, s_synthPipeline, nullptr); s_synthPipeline = VK_NULL_HANDLE; }
                if (s_flowPipeLayout) { vkDestroyPipelineLayout(device, s_flowPipeLayout, nullptr); s_flowPipeLayout = VK_NULL_HANDLE; }
                if (s_synthPipeLayout) { vkDestroyPipelineLayout(device, s_synthPipeLayout, nullptr); s_synthPipeLayout = VK_NULL_HANDLE; }
                if (s_flowSetLayout) { vkDestroyDescriptorSetLayout(device, s_flowSetLayout, nullptr); s_flowSetLayout = VK_NULL_HANDLE; }
                if (s_synthSetLayout) { vkDestroyDescriptorSetLayout(device, s_synthSetLayout, nullptr); s_synthSetLayout = VK_NULL_HANDLE; }
                for (auto& s : s_acquireSem) { if (s) { vkDestroySemaphore(device, s, nullptr); s = VK_NULL_HANDLE; } }
                for (auto& s : s_submitSem) { if (s) { vkDestroySemaphore(device, s, nullptr); s = VK_NULL_HANDLE; } }
                for (auto& f : s_fences) { if (f) { vkDestroyFence(device, f, nullptr); f = VK_NULL_HANDLE; } }
                if (s_cmdPool) { vkDestroyCommandPool(device, s_cmdPool, nullptr); s_cmdPool = VK_NULL_HANDLE; }
                s_cmdBuffer = VK_NULL_HANDLE;
                s_inited = false;
                s_extent = { 0, 0 };
                s_swapchain = VK_NULL_HANDLE;
        }

        static VkPipeline CreateComputePipeline(VkDevice device, VkPipelineLayout layout, const char* source)
        {
                std::vector<uint8> spirv;
                if (!CompileComputeSpirv(source, spirv))
                        return VK_NULL_HANDLE;
                VkShaderModuleCreateInfo smci{};
                smci.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
                smci.codeSize = spirv.size();
                smci.pCode = (const uint32*)spirv.data();
                VkShaderModule module = VK_NULL_HANDLE;
                if (vkCreateShaderModule(device, &smci, nullptr, &module) != VK_SUCCESS)
                        return VK_NULL_HANDLE;
                VkPipelineShaderStageCreateInfo stage{};
                stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
                stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
                stage.module = module;
                stage.pName = "main";
                VkComputePipelineCreateInfo pipeInfo{};
                pipeInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
                pipeInfo.stage = stage;
                pipeInfo.layout = layout;
                VkPipeline pipeline = VK_NULL_HANDLE;
                VkResult r = vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &pipeInfo, nullptr, &pipeline);
                vkDestroyShaderModule(device, module, nullptr);
                if (r != VK_SUCCESS)
                        return VK_NULL_HANDLE;
                return pipeline;
        }

        // ================= 资源初始化 =================
        static bool EnsureResources(VkDevice device, VkPhysicalDevice physDev, VkQueue graphicsQueue, SwapchainInfoVk& chainInfo)
        {
                VkExtent2D extent = chainInfo.m_actualExtent;
                bool needRebuild = !s_inited || extent.width != s_extent.width || extent.height != s_extent.height
                        || chainInfo.m_swapchain != s_swapchain;
                if (!needRebuild)
                        return true;
                if (extent.width < 64 || extent.height < 64)
                        return false;
                if (s_inited)
                        DestroyResources(device);
                s_device = device;
                s_physDev = physDev;

                // 采样器
                VkSamplerCreateInfo samplerInfo{};
                samplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
                samplerInfo.magFilter = VK_FILTER_LINEAR;
                samplerInfo.minFilter = VK_FILTER_LINEAR;
                samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
                samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
                samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
                samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
                if (vkCreateSampler(device, &samplerInfo, nullptr, &s_samplerLinear) != VK_SUCCESS)
                        goto fail;

                // 描述符布局：flow(prev,cur 采样 + flow storage) / synth(prev,cur,flow 采样 + synth storage)
                {
                        VkDescriptorSetLayoutBinding flowBinds[3] = {};
                        flowBinds[0] = { 0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr };
                        flowBinds[1] = { 1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr };
                        flowBinds[2] = { 2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr };
                        VkDescriptorSetLayoutCreateInfo flowLayoutInfo{};
                        flowLayoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
                        flowLayoutInfo.bindingCount = 3;
                        flowLayoutInfo.pBindings = flowBinds;
                        if (vkCreateDescriptorSetLayout(device, &flowLayoutInfo, nullptr, &s_flowSetLayout) != VK_SUCCESS)
                                goto fail;

                        VkDescriptorSetLayoutBinding synthBinds[4] = {};
                        synthBinds[0] = { 0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr };
                        synthBinds[1] = { 1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr };
                        synthBinds[2] = { 2, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr };
                        synthBinds[3] = { 3, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr };
                        VkDescriptorSetLayoutCreateInfo synthLayoutInfo{};
                        synthLayoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
                        synthLayoutInfo.bindingCount = 4;
                        synthLayoutInfo.pBindings = synthBinds;
                        if (vkCreateDescriptorSetLayout(device, &synthLayoutInfo, nullptr, &s_synthSetLayout) != VK_SUCCESS)
                                goto fail;

                        VkPipelineLayoutCreateInfo plInfo{};
                        plInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
                        plInfo.setLayoutCount = 1;
                        plInfo.pSetLayouts = &s_flowSetLayout;
                        if (vkCreatePipelineLayout(device, &plInfo, nullptr, &s_flowPipeLayout) != VK_SUCCESS)
                                goto fail;
                        plInfo.pSetLayouts = &s_synthSetLayout;
                        if (vkCreatePipelineLayout(device, &plInfo, nullptr, &s_synthPipeLayout) != VK_SUCCESS)
                                goto fail;
                }

                s_flowPipeline = CreateComputePipeline(device, s_flowPipeLayout, kFlowShaderSource);
                s_synthPipeline = CreateComputePipeline(device, s_synthPipeLayout, kSynthShaderSource);
                if (s_flowPipeline == VK_NULL_HANDLE || s_synthPipeline == VK_NULL_HANDLE)
                        goto fail;

                // 描述符池 + 集合
                {
                        VkDescriptorPoolSize sizes[2] = {};
                        sizes[0] = { VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 8 };
                        sizes[1] = { VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 4 };
                        VkDescriptorPoolCreateInfo poolInfo{};
                        poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
                        poolInfo.maxSets = 4;
                        poolInfo.poolSizeCount = 2;
                        poolInfo.pPoolSizes = sizes;
                        if (vkCreateDescriptorPool(device, &poolInfo, nullptr, &s_descPool) != VK_SUCCESS)
                                goto fail;
                        VkDescriptorSetAllocateInfo allocSets[1] = {};
                        allocSets[0].sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
                        allocSets[0].descriptorPool = s_descPool;
                        allocSets[0].descriptorSetCount = 1;
                        allocSets[0].pSetLayouts = &s_flowSetLayout;
                        if (vkAllocateDescriptorSets(device, &allocSets[0], &s_flowSet) != VK_SUCCESS)
                                goto fail;
                        allocSets[0].pSetLayouts = &s_synthSetLayout;
                        if (vkAllocateDescriptorSets(device, &allocSets[0], &s_synthSet) != VK_SUCCESS)
                                goto fail;
                }

                // 图像资源（全 R8G8B8A8：storage 支持检测只需此格式）
                {
                        uint32 fullW = extent.width, fullH = extent.height;
                        uint32 smallW = (fullW + 3) / 4, smallH = (fullH + 3) / 4;
                        uint32 flowW = (smallW + 15) / 16, flowH = (smallH + 15) / 16;
                        VkImageUsageFlags fullUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
                        VkImageUsageFlags smallUsage = fullUsage;
                        VkImageUsageFlags flowUsage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
                        VkImageUsageFlags synthUsage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
                        for (int i = 0; i < 2; i++)
                        {
                                if (!CreateImage2D(device, physDev, VK_FORMAT_R8G8B8A8_UNORM, fullW, fullH, fullUsage, s_full[i]))
                                        goto fail;
                                if (!CreateImage2D(device, physDev, VK_FORMAT_R8G8B8A8_UNORM, smallW, smallH, smallUsage, s_small[i]))
                                        goto fail;
                        }
                        if (!CreateImage2D(device, physDev, VK_FORMAT_R8G8B8A8_UNORM, flowW, flowH, flowUsage, s_flow))
                                goto fail;
                        if (!CreateImage2D(device, physDev, VK_FORMAT_R8G8B8A8_UNORM, fullW, fullH, synthUsage, s_synth))
                                goto fail;
                }

                // 命令池/缓冲 + 同步对象
                {
                        VkCommandPoolCreateInfo poolInfo{};
                        poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
                        poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
                        if (vkCreateCommandPool(device, &poolInfo, nullptr, &s_cmdPool) != VK_SUCCESS)
                                goto fail;
                        VkCommandBufferAllocateInfo cmdInfo{};
                        cmdInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
                        cmdInfo.commandPool = s_cmdPool;
                        cmdInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
                        cmdInfo.commandBufferCount = 1;
                        if (vkAllocateCommandBuffers(device, &cmdInfo, &s_cmdBuffer) != VK_SUCCESS)
                                goto fail;
                        for (int i = 0; i < 2; i++)
                        {
                                VkSemaphoreCreateInfo semInfo{};
                                semInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
                                if (vkCreateSemaphore(device, &semInfo, nullptr, &s_acquireSem[i]) != VK_SUCCESS)
                                        goto fail;
                                if (vkCreateSemaphore(device, &semInfo, nullptr, &s_submitSem[i]) != VK_SUCCESS)
                                        goto fail;
                                VkFenceCreateInfo fenceInfo{};
                                fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
                                if (vkCreateFence(device, &fenceInfo, nullptr, &s_fences[i]) != VK_SUCCESS)
                                        goto fail;
                        }
                }

                s_extent = extent;
                s_swapchain = chainInfo.m_swapchain;
                s_inited = true;
                s_failStreak.store(0);
                cemuLog_log(LogType::Force, "FrameGen: ready ({}x{}, flow tiles {}x{})",
                        extent.width, extent.height,
                        s_flow.extent.width, s_flow.extent.height);
                // 首帧布局约定初始化：全部图像 → SHADER_READ（与帧末统一状态一致），
                // 使帧内 barrier 的 oldLayout 恒匹配实际布局
                {
                        VkCommandBufferBeginInfo bi{};
                        bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
                        bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
                        if (vkBeginCommandBuffer(s_cmdBuffer, &bi) == VK_SUCCESS)
                        {
                                VkImage imgs[6] = { s_full[0].image, s_full[1].image, s_small[0].image,
                                        s_small[1].image, s_flow.image, s_synth.image };
                                for (VkImage img : imgs)
                                        RecordImageBarrier(s_cmdBuffer, img,
                                                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                                                (VkAccessFlags)0, (VkAccessFlags)0,
                                                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
                                vkEndCommandBuffer(s_cmdBuffer);
                                VkSubmitInfo si{};
                                si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
                                si.commandBufferCount = 1;
                                si.pCommandBuffers = &s_cmdBuffer;
                                vkQueueSubmit(graphicsQueue, 1, &si, VK_NULL_HANDLE);
                                vkQueueWaitIdle(graphicsQueue);
                        }
                }
                return true;

        fail:
                cemuLog_log(LogType::Force, "FrameGen: resource init failed - frame generation disabled");
                DestroyResources(device);
                s_deviceSupported.store(false);
                return false;
        }

        // ================= 主流程（GPU 线程，真实帧 present 后） =================
        static void RecordImageBarrier(VkCommandBuffer cmd, VkImage image,
                VkImageLayout oldLayout, VkImageLayout newLayout,
                VkAccessFlags srcAccess, VkAccessFlags dstAccess,
                VkPipelineStageFlags srcStage, VkPipelineStageFlags dstStage)
        {
                VkImageMemoryBarrier barrier{};
                barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
                barrier.oldLayout = oldLayout;
                barrier.newLayout = newLayout;
                barrier.srcAccessMask = srcAccess;
                barrier.dstAccessMask = dstAccess;
                barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
                barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
                barrier.image = image;
                barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
                barrier.subresourceRange.levelCount = 1;
                barrier.subresourceRange.layerCount = 1;
                vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, 0, nullptr, 0, nullptr, 1, &barrier);
        }

        static void UpdateDescriptorSets(VkDevice device)
        {
                const FGImage& prevSmall = s_small[s_curSmall ^ 1];
                const FGImage& curSmall = s_small[s_curSmall];
                const FGImage& prevFull = s_full[s_curFull ^ 1];
                const FGImage& curFull = s_full[s_curFull];

                VkDescriptorImageInfo flowSam[2] = {};
                flowSam[0] = { s_samplerLinear, prevSmall.view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL };
                flowSam[1] = { s_samplerLinear, curSmall.view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL };
                VkDescriptorImageInfo flowStore = { VK_NULL_HANDLE, s_flow.view, VK_IMAGE_LAYOUT_GENERAL };

                VkWriteDescriptorSet writes[3] = {};
                writes[0] = {}; writes[1] = {}; writes[2] = {};
                writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
                writes[0].dstSet = s_flowSet;
                writes[0].dstBinding = 0;
                writes[0].descriptorCount = 1;
                writes[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
                writes[0].pImageInfo = &flowSam[0];
                writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
                writes[1].dstSet = s_flowSet;
                writes[1].dstBinding = 1;
                writes[1].descriptorCount = 1;
                writes[1].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
                writes[1].pImageInfo = &flowSam[1];
                writes[2].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
                writes[2].dstSet = s_flowSet;
                writes[2].dstBinding = 2;
                writes[2].descriptorCount = 1;
                writes[2].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
                writes[2].pImageInfo = &flowStore;
                vkUpdateDescriptorSets(device, 3, writes, 0, nullptr);

                VkDescriptorImageInfo synthSam[3] = {};
                synthSam[0] = { s_samplerLinear, prevFull.view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL };
                synthSam[1] = { s_samplerLinear, curFull.view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL };
                synthSam[2] = { s_samplerLinear, s_flow.view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL };
                VkDescriptorImageInfo synthStore = { VK_NULL_HANDLE, s_synth.view, VK_IMAGE_LAYOUT_GENERAL };

                VkWriteDescriptorSet swrites[4] = {};
                for (int i = 0; i < 3; i++)
                {
                        swrites[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
                        swrites[i].dstSet = s_synthSet;
                        swrites[i].dstBinding = i;
                        swrites[i].descriptorCount = 1;
                        swrites[i].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
                        swrites[i].pImageInfo = &synthSam[i];
                }
                swrites[3].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
                swrites[3].dstSet = s_synthSet;
                swrites[3].dstBinding = 3;
                swrites[3].descriptorCount = 1;
                swrites[3].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
                swrites[3].pImageInfo = &synthStore;
                vkUpdateDescriptorSets(device, 4, swrites, 0, nullptr);
        }

        void OnRealFramePresented(VkDevice device, VkQueue graphicsQueue, VkQueue presentQueue,
                SwapchainInfoVk& chainInfo, VkFence realFrameFence)
        {
                if (!s_enabled.load(std::memory_order_relaxed))
                        return;
                if (!s_deviceSupported.load(std::memory_order_relaxed))
                        return;
                if (s_failStreak.load(std::memory_order_relaxed) >= 5)
                        return; // 连续失败停用（真实帧照常呈现）
                // 仅垂直同步开启时有稳定节拍基准（Immediate 背靠背提交无意义）
                if (chainInfo.m_vsyncState == SwapchainInfoVk::VSync::Immediate)
                        return;
                if (chainInfo.m_actualExtent.width == 0 || chainInfo.m_actualExtent.height == 0)
                        return;
                if (!EnsureResources(device, s_physDev, graphicsQueue, chainInfo))
                        return;
                if (chainInfo.m_swapchainImageIndex == (uint32)-1)
                        return; // 主路径本帧未呈现

                VkImage realImage = chainInfo.m_swapchainImages[chainInfo.m_swapchainImageIndex];

                // 等待上一合成帧与真实帧完成（host fence 等待，有限超时防死锁）
                VkFence prevFence = s_fences[s_ringIndex ^ 1];
                if (vkWaitForFences(device, 1, &prevFence, VK_TRUE, 100'000'000ull) != VK_SUCCESS)
                {
                        s_failStreak.fetch_add(1);
                        return;
                }
                if (vkWaitForFences(device, 1, &realFrameFence, VK_TRUE, 100'000'000ull) != VK_SUCCESS)
                {
                        s_failStreak.fetch_add(1);
                        return;
                }

                // 独立 acquire（不触碰主路径 acquire 状态机）
                uint32 synthImageIndex = 0;
                VkResult acq = vkAcquireNextImageKHR(device, chainInfo.m_swapchain, 50'000'000ull,
                        s_acquireSem[s_ringIndex], VK_NULL_HANDLE, &synthImageIndex);
                if (acq != VK_SUCCESS && acq != VK_SUBOPTIMAL_KHR)
                {
                        if (acq == VK_ERROR_OUT_OF_DATE_KHR)
                                chainInfo.m_shouldRecreate = true;
                        s_failStreak.fetch_add(1);
                        return;
                }
                VkImage synthImage = chainInfo.m_swapchainImages[synthImageIndex];
                VkFence thisFence = s_fences[s_ringIndex];
                vkResetFences(device, 1, &thisFence);

                VkResult vr = vkResetCommandBuffer(s_cmdBuffer, 0);
                if (vr != VK_SUCCESS)
                {
                        s_failStreak.fetch_add(1);
                        return;
                }
                VkCommandBufferBeginInfo beginInfo{};
                beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
                beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
                if (vkBeginCommandBuffer(s_cmdBuffer, &beginInfo) != VK_SUCCESS)
                {
                        s_failStreak.fetch_add(1);
                        return;
                }

                const FGImage& curFull = s_full[s_curFull];
                const FGImage& curSmall = s_small[s_curSmall];

                // 0) 写目标布局准备（上帧末均为 SHADER_READ，转写入布局）
                RecordImageBarrier(s_cmdBuffer, curFull.image,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
                RecordImageBarrier(s_cmdBuffer, curSmall.image,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
                // 1) 真实帧图像（present 后仍归应用）：PRESENT_SRC → TRANSFER_SRC
                RecordImageBarrier(s_cmdBuffer, realImage,
                        VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        (VkAccessFlags)0, VK_ACCESS_TRANSFER_READ_BIT,
                        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
                // 2) blit 真实帧 → curFull（全分辨率拷贝，支持格式转换）
                {
                        VkImageBlit blit{};
                        blit.srcSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
                        blit.dstSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
                        blit.srcOffsets[1] = { (sint32)chainInfo.m_actualExtent.width, (sint32)chainInfo.m_actualExtent.height, 1 };
                        blit.dstOffsets[1] = { (sint32)curFull.extent.width, (sint32)curFull.extent.height, 1 };
                        vkCmdBlitImage(s_cmdBuffer, realImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                                curFull.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_NEAREST);
                }
                // 3) 真实帧图像恢复 PRESENT_SRC（主路径 render pass initialLayout 依赖）
                RecordImageBarrier(s_cmdBuffer, realImage,
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                        VK_ACCESS_TRANSFER_READ_BIT, (VkAccessFlags)0,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
                // 4) blit curFull → curSmall（1/4 光流输入）
                RecordImageBarrier(s_cmdBuffer, curFull.image,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
                {
                        VkImageBlit blit{};
                        blit.srcSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
                        blit.dstSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
                        blit.srcOffsets[1] = { (sint32)curFull.extent.width, (sint32)curFull.extent.height, 1 };
                        blit.dstOffsets[1] = { (sint32)curSmall.extent.width, (sint32)curSmall.extent.height, 1 };
                        vkCmdBlitImage(s_cmdBuffer, curFull.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                                curSmall.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_LINEAR);
                }
                // 5) 光流 compute（prevSmall+curSmall → flow）
                RecordImageBarrier(s_cmdBuffer, curSmall.image,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
                RecordImageBarrier(s_cmdBuffer, s_flow.image,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
                        VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
                vkCmdBindPipeline(s_cmdBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, s_flowPipeline);
                vkCmdBindDescriptorSets(s_cmdBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, s_flowPipeLayout,
                        0, 1, &s_flowSet, 0, nullptr);
                vkCmdDispatch(s_cmdBuffer, s_flow.extent.width, s_flow.extent.height, 1);
                // 6) 合成 compute（prevFull+curFull+flow → synth）
                RecordImageBarrier(s_cmdBuffer, s_flow.image,
                        VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
                RecordImageBarrier(s_cmdBuffer, s_synth.image,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
                        VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
                vkCmdBindPipeline(s_cmdBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, s_synthPipeline);
                vkCmdBindDescriptorSets(s_cmdBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, s_synthPipeLayout,
                        0, 1, &s_synthSet, 0, nullptr);
                vkCmdDispatch(s_cmdBuffer, (s_synth.extent.width + 7) / 8, (s_synth.extent.height + 7) / 8, 1);
                // 7) blit 合成帧 → 合成帧交换链图像 → PRESENT_SRC
                RecordImageBarrier(s_cmdBuffer, s_synth.image,
                        VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
                RecordImageBarrier(s_cmdBuffer, synthImage,
                        VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        (VkAccessFlags)0, VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
                {
                        VkImageBlit blit{};
                        blit.srcSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
                        blit.dstSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
                        blit.srcOffsets[1] = { (sint32)s_synth.extent.width, (sint32)s_synth.extent.height, 1 };
                        blit.dstOffsets[1] = { (sint32)chainInfo.m_actualExtent.width, (sint32)chainInfo.m_actualExtent.height, 1 };
                        vkCmdBlitImage(s_cmdBuffer, s_synth.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                                synthImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_LINEAR);
                }
                RecordImageBarrier(s_cmdBuffer, synthImage,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                        VK_ACCESS_TRANSFER_WRITE_BIT, (VkAccessFlags)0,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
                // 8) 帧末统一布局：curFull/curSmall/synth → SHADER_READ（下帧作为 prev 或重复使用）
                RecordImageBarrier(s_cmdBuffer, curFull.image,
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_SHADER_READ_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
                RecordImageBarrier(s_cmdBuffer, s_synth.image,
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_SHADER_READ_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

                if (vkEndCommandBuffer(s_cmdBuffer) != VK_SUCCESS)
                {
                        s_failStreak.fetch_add(1);
                        return;
                }

                UpdateDescriptorSets(device);

                VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
                VkSubmitInfo submitInfo{};
                submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
                submitInfo.commandBufferCount = 1;
                submitInfo.pCommandBuffers = &s_cmdBuffer;
                submitInfo.waitSemaphoreCount = 1;
                submitInfo.pWaitSemaphores = &s_acquireSem[s_ringIndex];
                submitInfo.pWaitDstStageMask = &waitStage;
                submitInfo.signalSemaphoreCount = 1;
                submitInfo.pSignalSemaphores = &s_submitSem[s_ringIndex];
                if (vkQueueSubmit(graphicsQueue, 1, &submitInfo, thisFence) != VK_SUCCESS)
                {
                        s_failStreak.fetch_add(1);
                        return;
                }

                VkPresentInfoKHR presentInfo{};
                presentInfo.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
                presentInfo.swapchainCount = 1;
                presentInfo.pSwapchains = &chainInfo.m_swapchain;
                presentInfo.pImageIndices = &synthImageIndex;
                presentInfo.waitSemaphoreCount = 1;
                presentInfo.pWaitSemaphores = &s_submitSem[s_ringIndex];
                VkResult pres = vkQueuePresentKHR(presentQueue, &presentInfo);
                if (pres < 0 && pres != VK_ERROR_OUT_OF_DATE_KHR)
                {
                        s_failStreak.fetch_add(1);
                        return;
                }
                if (pres == VK_ERROR_OUT_OF_DATE_KHR)
                        chainInfo.m_shouldRecreate = true;

                // prev/cur 交换（本帧 cur 即为下帧 prev）
                s_curFull ^= 1;
                s_curSmall ^= 1;
                s_ringIndex ^= 1;
                s_failStreak.store(0);
        }

        // ================= 公共接口 =================
        bool IsEnabled()
        {
                return s_enabled.load(std::memory_order_relaxed);
        }

        void SetEnabled(bool enable)
        {
                s_enabled.store(enable, std::memory_order_relaxed);
                if (enable)
                        s_failStreak.store(0);
        }

        void OnRendererInitialized(VkPhysicalDevice physicalDevice)
        {
                bool expected = false;
                if (!s_deviceChecked.compare_exchange_strong(expected, true))
                        return;
                s_physDev = physicalDevice;
                VkFormatProperties props{};
                vkGetPhysicalDeviceFormatProperties(physicalDevice, VK_FORMAT_R8G8B8A8_UNORM, &props);
                VkFormatFeatureFlags needed = VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT
                        | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT
                        | VK_FORMAT_FEATURE_TRANSFER_DST_BIT
                        | VK_FORMAT_FEATURE_TRANSFER_SRC_BIT;
                bool ok = (props.optimalTilingFeatures & needed) == needed;
                s_deviceSupported.store(ok, std::memory_order_relaxed);
                cemuLog_log(LogType::Force, "FrameGen: device support = {}", ok ? "yes" : "no (missing RGBA8 storage/sampled/transfer features)");
        }
}
