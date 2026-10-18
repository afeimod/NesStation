// ★★★ NesStation R12/R13: 帧生成实现 —— 见 VulkanFrameGen.h
//   R13: LSFG 3.1 完整移植（Lossless.dll 数据容器 + 26 着色器神经网络管线）。
//   接口形态参考开源 lsfg-vk（parseDll / ShaderLibrary / PipelineSignature /
//   v3_1 签名 / dispatch-acquire 两段式），按 Cemu 原生 Vulkan C API 重写：
//     - Lossless.dll 仅作数据容器解析（PE RT_RCDATA → SPIR-V 字节码），
//       不加载、不执行任何 PE 代码 —— 与参考 APK 同款安全形态；
//     - fp32 着色器变体（Cemu 逻辑设备未启用 shaderFloat16）；
//     - 提交模型：每轮 [head(拷贝+预pass) → gen×(mult-1)(UBO+主pass+blit)]
//       信号量链 + 末尾 fence，全部失败路径静默回退，真实帧节奏永不受阻。
#include "Cafe/HW/Latte/Renderer/Vulkan/VulkanFrameGen.h"
#include "Cafe/HW/Latte/Renderer/Vulkan/SwapchainInfoVk.h"
#include "Cafe/HW/Latte/Renderer/Vulkan/VulkanAPI.h"
#include "Cemu/Logging/CemuLogging.h" // 本基座日志声明所在（上游原日志头在本基座已移除，勿沿用上游 include 路径）

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <fstream>
#include <memory>
#include <mutex>
#include <optional>
#include <unordered_map>
#include <utility>
#include <vector>

namespace VulkanFrameGen
{
        // ================= 前置声明 =================
        struct LsfgContext;

        // ================= 全局状态 =================
        static std::mutex g_mutex;                                   // DLL/上下文变更锁
        static std::atomic<bool> s_enabled{ false };
        static std::atomic<bool> s_deviceChecked{ false };
        static std::atomic<bool> s_deviceSupported{ false };
        static std::atomic<int> s_failStreak{ 0 };
        static std::atomic<int> s_multiplier{ 2 };
        static std::atomic<float> s_flowScale{ 0.5f };
        static std::atomic<bool> s_perfMode{ true };
        static std::atomic<bool> s_configDirty{ true };              // 触发上下文重建
        static std::atomic<uint32_t> s_swapchainRecreateRequest{ 0 };
        static std::atomic<uint64> s_contextSerial{ 0 };             // 上下文代数（重建计数）

        static VkPhysicalDevice s_physDev = VK_NULL_HANDLE;
        static std::shared_ptr<LsfgContext> s_context;               // 受 g_mutex 保护

        // DLL 资源缓存（JNI 线程解析，GPU 线程构建时消费）
        struct DllResources
        {
                std::unordered_map<uint32_t, std::vector<uint32_t>> shaders;
        };
        static std::shared_ptr<const DllResources> s_dllResources;   // 受 g_mutex 保护
        static std::string s_dllError = "未加载 Lossless.dll";        // 受 g_mutex 保护
        static bool s_dllReady = false;                              // 受 g_mutex 保护

        // ================= Lossless.dll PE 解析（数据容器） =================
        // 移植 lsfg-vk parseDll：DOS头 → PE头 → 资源表 → RT_RCDATA 目录 →
        // 逐资源 ID 提取 U32 数组（SPIR-V 字节码）。全程只读文件，无任何执行。
        namespace
        {
                struct PEDosHeader
                {
                        uint16_t magic;          // 0x5A4D
                        uint16_t pad[29];
                        int32_t peOffset;
                };
                struct PEHeader
                {
                        uint32_t signature;      // 'PE\0\0'
                        uint16_t pad1[1];
                        uint16_t sectCount;
                        uint16_t pad2[6];
                        uint16_t optHdrSize;
                        uint16_t pad3[1];
                };
                struct PEOptionalHeader
                {
                        uint16_t magic;          // 0x20B (PE32+)
                        uint16_t pad4[63];
                        uint32_t rsrcRva;
                        uint32_t rsrcSize;
                };
                struct PESectionHeader
                {
                        uint16_t pad1[4];
                        uint32_t vsize;
                        uint32_t vaddress;
                        uint32_t fsize;
                        uint32_t foffset;
                        uint16_t pad2[8];
                };
                struct ResourceDirectory
                {
                        uint16_t pad[6];
                        uint16_t nameCount;
                        uint16_t idCount;
                };
                struct ResourceDirectoryEntry
                {
                        uint32_t id;
                        uint32_t offset;         // 高位 = 目录
                };
                struct ResourceDataEntry
                {
                        uint32_t offset;
                        uint32_t size;
                        uint32_t pad[2];
                };

                template<typename T>
                bool SafeRead(const std::vector<char>& data, size_t offset, const T** out)
                {
                        size_t end = offset + sizeof(T);
                        if (end > data.size() || end < offset)
                                return false;
                        *out = reinterpret_cast<const T*>(data.data() + offset);
                        return true;
                }
        } // namespace

        static bool ParseLosslessDll(const std::string& path, std::unordered_map<uint32_t, std::vector<uint32_t>>& out, std::string& err)
        {
                std::ifstream file(path, std::ios::binary | std::ios::ate);
                if (!file.is_open())
                {
                        err = "无法打开文件";
                        return false;
                }
                const auto size = static_cast<size_t>(file.tellg());
                file.seekg(0, std::ios::beg);
                std::vector<char> data(size);
                if (!file.read(data.data(), static_cast<std::streamsize>(size)))
                {
                        err = "无法读取文件";
                        return false;
                }

                size_t fileOffset = 0;
                const PEDosHeader* dosHdr = nullptr;
                if (!SafeRead(data, 0, &dosHdr) || dosHdr->magic != 0x5A4D)
                {
                        err = "DOS 头校验失败（不是有效的 PE 文件）";
                        return false;
                }
                fileOffset += static_cast<size_t>(dosHdr->peOffset);
                const PEHeader* peHdr = nullptr;
                if (!SafeRead(data, fileOffset, &peHdr) || peHdr->signature != 0x00004550)
                {
                        err = "PE 头校验失败";
                        return false;
                }
                fileOffset += sizeof(PEHeader);
                const PEOptionalHeader* optHdr = nullptr;
                if (!SafeRead(data, fileOffset, &optHdr) || optHdr->magic != 0x20B)
                {
                        err = "不是 PE32+ 格式（需要 64 位 Lossless.dll）";
                        return false;
                }
                const uint32_t rsrcRva = optHdr->rsrcRva;
                const uint32_t rsrcSize = optHdr->rsrcSize;

                // 定位资源所在节
                std::optional<size_t> rsrcOffset;
                fileOffset += peHdr->optHdrSize;
                for (uint32_t i = 0; i < peHdr->sectCount; i++)
                {
                        const PESectionHeader* sect = nullptr;
                        if (!SafeRead(data, fileOffset + i * sizeof(PESectionHeader), &sect))
                                break;
                        if (rsrcRva < sect->vaddress || rsrcRva > (sect->vaddress + sect->vsize))
                                continue;
                        rsrcOffset = (rsrcRva - sect->vaddress) + sect->foffset;
                        break;
                }
                if (!rsrcOffset)
                {
                        err = "找不到资源节（不是 Lossless.dll）";
                        return false;
                }

                // 资源根目录 → RT_RCDATA (类型 10)
                fileOffset = *rsrcOffset;
                const ResourceDirectory* rsrcDir = nullptr;
                if (!SafeRead(data, fileOffset, &rsrcDir) || rsrcDir->idCount < 3)
                {
                        err = "资源目录结构异常";
                        return false;
                }
                std::optional<size_t> rsrcTblOffset;
                const size_t entryCount = rsrcDir->nameCount + rsrcDir->idCount;
                for (size_t i = 0; i < entryCount; i++)
                {
                        const ResourceDirectoryEntry* e = nullptr;
                        if (!SafeRead(data, fileOffset + sizeof(ResourceDirectory) + i * sizeof(ResourceDirectoryEntry), &e))
                                break;
                        if (e->id != 10) // RT_RCDATA
                                continue;
                        if ((e->offset & 0x80000000) == 0)
                        {
                                err = "资源目录结构异常（期望子目录）";
                                return false;
                        }
                        rsrcTblOffset = e->offset & 0x7FFFFFFF;
                }
                if (!rsrcTblOffset)
                {
                        err = "找不到 RT_RCDATA 资源（不是 Lossless.dll）";
                        return false;
                }

                // 逐资源提取
                fileOffset = *rsrcOffset + *rsrcTblOffset;
                const ResourceDirectory* rsrcTbl = nullptr;
                if (!SafeRead(data, fileOffset, &rsrcTbl) || rsrcTbl->idCount < 1)
                {
                        err = "RT_RCDATA 目录为空";
                        return false;
                }
                const size_t tblEntries = rsrcTbl->nameCount + rsrcTbl->idCount;
                for (size_t i = 0; i < tblEntries; i++)
                {
                        const ResourceDirectoryEntry* te = nullptr;
                        if (!SafeRead(data, fileOffset + sizeof(ResourceDirectory) + i * sizeof(ResourceDirectoryEntry), &te))
                                break;
                        if ((te->offset & 0x80000000) == 0)
                                continue;
                        // 跳过语言层目录
                        const ResourceDirectory* langDir = nullptr;
                        if (!SafeRead(data, *rsrcOffset + (te->offset & 0x7FFFFFFF), &langDir) || langDir->idCount < 1)
                                continue;
                        const ResourceDirectoryEntry* langEntry = nullptr;
                        if (!SafeRead(data, *rsrcOffset + (te->offset & 0x7FFFFFFF) + sizeof(ResourceDirectory), &langEntry))
                                continue;
                        if ((langEntry->offset & 0x80000000) != 0)
                                continue;
                        const ResourceDataEntry* entry = nullptr;
                        if (!SafeRead(data, *rsrcOffset + (langEntry->offset & 0x7FFFFFFF), &entry))
                                continue;
                        if (entry->offset < rsrcRva || entry->offset > (rsrcRva + rsrcSize))
                                continue;
                        size_t dataOff = (entry->offset - rsrcRva) + *rsrcOffset;
                        if (entry->size < 4 || dataOff + entry->size > data.size())
                                continue;
                        const size_t words = entry->size / sizeof(uint32_t);
                        std::vector<uint32_t> wordsVec(words);
                        std::memcpy(wordsVec.data(), data.data() + dataOff, words * sizeof(uint32_t));
                        // SPIR-V 魔数校验（0x07230203）
                        if (wordsVec.size() >= 5 && wordsVec[0] == 0x07230203)
                                out.emplace(te->id, std::move(wordsVec));
                }
                return true;
        }

        // ================= 管线签名（lsfg-vk Signature 系列移植） =================
        namespace
        {
                // ExtentOp：基准（全尺寸 or 光流比例尺寸）+ 逐级 (加数, 右移) 运算
                struct ExtentOp
                {
                        bool useFlow = false;
                        std::vector<std::pair<uint32_t, uint32_t>> ops;
                        ExtentOp() = default;
                        ExtentOp(bool flow) : useFlow(flow) {}
                        ExtentOp(uint32_t add, uint32_t shift) : useFlow(true) { ops.emplace_back(add, shift); }
                        ExtentOp& operator+=(const ExtentOp& other)
                        {
                                for (auto& op : other.ops)
                                        ops.push_back(op);
                                return *this;
                        }
                        ExtentOp operator+(const ExtentOp& other) const
                        {
                                ExtentOp r = *this;
                                r += other;
                                return r;
                        }
                        VkExtent2D Apply(VkExtent2D base, VkExtent2D flow) const
                        {
                                VkExtent2D r = useFlow ? flow : base;
                                for (auto& [add, shift] : ops)
                                {
                                        r.width = (r.width + add) >> shift;
                                        r.height = (r.height + add) >> shift;
                                }
                                return r;
                        }
                };

                // 图像格式（枚举值 = VkFormat）
                enum class FgFormat : uint32_t
                {
                        RGBA8888 = 37,     // VK_FORMAT_R8G8B8A8_UNORM
                        R8 = 9,            // VK_FORMAT_R8_UNORM
                        ABGR1010102 = 64,  // VK_FORMAT_A2B10G10R10_UNORM_PACK32
                        RGBA16161616 = 97, // VK_FORMAT_R16G16B16A16_SFLOAT
                };

                enum FgImageFlag : uint32_t
                {
                        FG_NONE = 0,
                        FG_MIPMAPS = 1 << 0,          // 多张独立递减尺寸图（Texture2D[]）
                        FG_PINNED = 1 << 1,           // 常驻（我们全部常驻，仅保留语义）
                        FG_EXTERNAL_INPUT = 1 << 2,   // 外部写入：真实帧 ping-pong 层
                        FG_EXTERNAL_OUTPUT = 1 << 3,  // 外部读取：生成帧输出
                };

                struct ImageSignature
                {
                        FgFormat format = FgFormat::RGBA8888;
                        uint32_t flags = FG_NONE;
                        ExtentOp extentOp;
                        uint32_t count = 1;
                };

                struct FgResource
                {
                        size_t idx = SIZE_MAX; // SIZE_MAX = 无效
                        uint32_t layer = 0;
                        bool valid() const { return idx != SIZE_MAX; }
                };
                static const FgResource FG_INVALID{};

                enum FgPassFlag : uint32_t
                {
                        PASS_NONE = 0,
                        PASS_AGGREGATE = 1 << 0,   // 着色器多次复用，资源聚合成数组经 push constant 索引
                        PASS_SPECIAL = 1 << 1,     // 首层特殊标记（push constant）
                        PASS_HBD_VARIANT = 1 << 2, // 高位深变体（我们固定 SDR → generate0）
                };

                struct PassSignature
                {
                        const char* shader = nullptr;
                        uint32_t flags = PASS_NONE;
                        std::vector<FgResource> inputs;
                        std::vector<FgResource> outputs;
                        ExtentOp dispatchOp;
                };

                struct BindingSignature
                {
                        bool isStorage = false;
                        std::vector<size_t> resources;
                };

                struct Subiteration
                {
                        uint32_t iterationIndex = 0;
                        VkExtent2D dispatch{ 1, 1 };
                        bool isSpecial = false;
                };
                struct Substage
                {
                        std::string pipeline;
                        std::vector<Subiteration> subiterations;
                };
                struct Stage
                {
                        std::vector<size_t> sampledImages;
                        std::vector<size_t> storageImages;
                        std::vector<Substage> substages;
                };

                struct PipelineSignature
                {
                        std::vector<std::pair<const char*, bool>> shaders; // 名字, 是否 hbd 变体
                        std::vector<ImageSignature> images;
                        std::vector<BindingSignature> descriptors;
                        std::vector<PassSignature> passes;
                        std::vector<std::vector<size_t>> stagePasses;      // 每 stage 的 pass 索引
                        std::vector<size_t> splitIndices;                  // cmdbuf 分段（stage 索引边界）
                };

                // ---- 签名构建器（finalize 移植：描述符顺序 + 依赖重排）----
                class SignatureBuilder
                {
                public:
                        size_t RegisterImage(const ImageSignature& image)
                        {
                                m_images.push_back(image);
                                return m_images.size() - 1;
                        }
                        void AppendPass(const PassSignature& pass)
                        {
                                m_passes.push_back(pass);
                        }
                        void Split() { m_splitIndices.push_back(m_passes.size()); }

                        PipelineSignature Finalize()
                        {
                                PipelineSignature s;

                                // 着色器信息（按首次出现顺序 —— 决定描述符绑定顺序！）
                                struct ShaderInfo
                                {
                                        const char* id = nullptr;
                                        bool hasHbdVariant = false;
                                        size_t sampledImageBindings = 0;
                                        std::vector<std::vector<size_t>> storageImageBindings;
                                };
                                std::vector<ShaderInfo> shaderInfos;
                                for (auto& pass : m_passes)
                                {
                                        auto it = std::find_if(shaderInfos.begin(), shaderInfos.end(),
                                                [&](const ShaderInfo& si) { return strcmp(si.id, pass.shader) == 0; });
                                        bool first = it == shaderInfos.end();
                                        bool aggregate = (pass.flags & PASS_AGGREGATE) != 0;
                                        if (first)
                                        {
                                                ShaderInfo si;
                                                si.id = pass.shader;
                                                si.hasHbdVariant = (pass.flags & PASS_HBD_VARIANT) != 0;
                                                si.sampledImageBindings = pass.inputs.size();
                                                si.storageImageBindings.resize(pass.outputs.size());
                                                shaderInfos.push_back(std::move(si));
                                                it = shaderInfos.end() - 1;
                                        }
                                        if (it->sampledImageBindings != pass.inputs.size() ||
                                                it->storageImageBindings.size() != pass.outputs.size())
                                                continue; // 结构不一致（不该发生）
                                        for (size_t i = 0; i < pass.outputs.size(); i++)
                                        {
                                                if (!pass.outputs[i].valid())
                                                        continue;
                                                it->storageImageBindings[i].push_back(pass.outputs[i].idx);
                                        }
                                }

                                // 描述符绑定（顺序 = ExternalInput 全部 → 每着色器 [存储 + 采样]）
                                for (size_t i = 0; i < m_images.size(); i++)
                                {
                                        if (m_images[i].flags & FG_EXTERNAL_INPUT)
                                        {
                                                BindingSignature b;
                                                b.isStorage = false;
                                                b.resources.push_back(i);
                                                s.descriptors.push_back(std::move(b));
                                        }
                                }
                                for (auto& shader : shaderInfos)
                                {
                                        for (auto& resources : shader.storageImageBindings)
                                        {
                                                if (resources.empty())
                                                        continue;
                                                BindingSignature bs;
                                                bs.isStorage = true;
                                                bs.resources = resources;
                                                s.descriptors.push_back(bs);
                                                if (m_images[resources.front()].flags & FG_EXTERNAL_OUTPUT)
                                                        continue;
                                                BindingSignature bi;
                                                bi.isStorage = false;
                                                bi.resources = resources;
                                                s.descriptors.push_back(std::move(bi));
                                        }
                                }

                                // 依赖重排 → stages（split 边界内按序，写后读约束）
                                std::vector<size_t> writtenImages;
                                for (size_t i = 0; i < m_images.size(); i++)
                                        if (m_images[i].flags & FG_EXTERNAL_INPUT)
                                                writtenImages.push_back(i);
                                std::vector<size_t> remaining(m_passes.size());
                                for (size_t i = 0; i < m_passes.size(); i++)
                                        remaining[i] = i;
                                size_t splitCursor = 0;
                                std::pair<size_t, size_t> bounds{ 0,
                                        m_splitIndices.empty() ? m_passes.size() : m_splitIndices.front() };
                                while (!remaining.empty())
                                {
                                        std::vector<size_t> validPasses;
                                        for (size_t passIdx : remaining)
                                        {
                                                if (passIdx < bounds.first || passIdx >= bounds.second)
                                                        continue;
                                                bool ok = true;
                                                for (auto& res : m_passes[passIdx].inputs)
                                                {
                                                        if (!res.valid())
                                                                continue;
                                                        if (std::find(writtenImages.begin(), writtenImages.end(), res.idx) != writtenImages.end())
                                                                continue;
                                                        ok = false;
                                                        break;
                                                }
                                                if (ok)
                                                        validPasses.push_back(passIdx);
                                        }
                                        if (validPasses.empty())
                                        {
                                                if (splitCursor < m_splitIndices.size())
                                                {
                                                        size_t nextBound = splitCursor + 1 < m_splitIndices.size() ?
                                                                m_splitIndices[splitCursor + 1] : m_passes.size();
                                                        bounds = { bounds.second, nextBound };
                                                        splitCursor++;
                                                        s.splitIndices.push_back(s.stagePasses.size());
                                                        continue;
                                                }
                                                break; // 死锁保护（不该发生）
                                        }
                                        // 按着色器名稳定排序（插入排序）
                                        for (size_t i = 1; i < validPasses.size(); i++)
                                        {
                                                size_t key = validPasses[i];
                                                size_t j = i;
                                                while (j > 0 && strcmp(m_passes[validPasses[j - 1]].shader, m_passes[key].shader) > 0)
                                                {
                                                        validPasses[j] = validPasses[j - 1];
                                                        j--;
                                                }
                                                validPasses[j] = key;
                                        }
                                        auto& stage = s.stagePasses.emplace_back();
                                        for (size_t passIdx : validPasses)
                                        {
                                                for (auto& res : m_passes[passIdx].outputs)
                                                {
                                                        if (res.valid())
                                                                writtenImages.push_back(res.idx);
                                                }
                                                stage.push_back(passIdx);
                                                remaining.erase(std::find(remaining.begin(), remaining.end(), passIdx));
                                        }
                                }

                                for (auto& shader : shaderInfos)
                                        s.shaders.emplace_back(shader.id, shader.hasHbdVariant);
                                for (auto& image : m_images)
                                        s.images.push_back(image);
                                for (auto& pass : m_passes)
                                        s.passes.push_back(pass);
                                return s;
                        }

                private:
                        std::vector<ImageSignature> m_images;
                        std::vector<PassSignature> m_passes;
                        std::vector<size_t> m_splitIndices;
                };
        } // namespace

        // ================= v3.1 管线签名（lsfg-vk pipelines/v3_1.cpp 移植） =================
        // LSFG 3.1 网络结构：mipmaps 预处理 → alpha(7级下采样特征) → beta(融合) →
        // [split: 预pass结束] → gamma(7级上采样流场) + delta/epsilon(细层辅助) →
        // generate(最终合成)。perf 模式中间图像层数减半。
        static PipelineSignature BuildSignatureV31(bool perf)
        {
                SignatureBuilder s;
                const uint32_t mul = perf ? 1u : 2u;

                // 外部输入：前后两个真实帧（2 层 ping-pong，RGBA8，全尺寸）
                size_t sourceImageArray = s.RegisterImage({
                        FgFormat::RGBA8888, FG_PINNED | FG_EXTERNAL_INPUT, ExtentOp(), 2 });

                /* -------- 预 pass -------- */
                size_t mipmapImageArray = s.RegisterImage({
                        FgFormat::R8, FG_MIPMAPS, ExtentOp(true), 7 });

                {
                        PassSignature p;
                        p.shader = "mipmaps";
                        p.inputs.push_back({ sourceImageArray });
                        p.outputs.push_back({ mipmapImageArray, 0 });
                        p.dispatchOp = ExtentOp(true) + ExtentOp(63, 6);
                        s.AppendPass(p);
                }

                std::vector<size_t> alphaArray(7);
                std::vector<ExtentOp> alphaExtents(7);
                for (uint32_t i = 0; i < 7; i++)
                {
                        const ExtentOp dispatch = ExtentOp(7, 3);
                        ExtentOp extent = ExtentOp(0, 6 - i);
                        extent += ExtentOp(1, 1);

                        size_t flipflop0 = s.RegisterImage({ FgFormat::RGBA8888, FG_NONE, extent, 1 * mul });
                        {
                                PassSignature p;
                                p.shader = "alpha0";
                                p.flags = PASS_AGGREGATE;
                                p.inputs.push_back({ mipmapImageArray, 6 - i });
                                p.outputs.push_back({ flipflop0 });
                                p.dispatchOp = extent + dispatch;
                                s.AppendPass(p);
                        }
                        size_t flipflop1 = s.RegisterImage({ FgFormat::RGBA8888, FG_NONE, extent, 1 * mul });
                        {
                                PassSignature p;
                                p.shader = "alpha1";
                                p.flags = PASS_AGGREGATE;
                                p.inputs.push_back({ flipflop0 });
                                p.outputs.push_back({ flipflop1 });
                                p.dispatchOp = extent + dispatch;
                                s.AppendPass(p);
                        }
                        extent += ExtentOp(1, 1);
                        size_t flipflop2 = s.RegisterImage({ FgFormat::RGBA8888, FG_NONE, extent, 2 * mul });
                        {
                                PassSignature p;
                                p.shader = "alpha2";
                                p.flags = PASS_AGGREGATE;
                                p.inputs.push_back({ flipflop1 });
                                p.outputs.push_back({ flipflop2 });
                                p.dispatchOp = extent + dispatch;
                                s.AppendPass(p);
                        }
                        size_t result = s.RegisterImage({ FgFormat::RGBA8888, FG_PINNED, extent, (2 * mul) * 3 });
                        {
                                PassSignature p;
                                p.shader = "alpha3";
                                p.flags = PASS_AGGREGATE;
                                p.inputs.push_back({ flipflop2 });
                                p.outputs.push_back({ result });
                                p.dispatchOp = extent + dispatch;
                                s.AppendPass(p);
                        }
                        alphaArray[6 - i] = result;
                        alphaExtents[6 - i] = extent;
                }

                {
                        ExtentOp extent = alphaExtents[0];
                        ExtentOp dispatch = ExtentOp(7, 3);

                        size_t flipflop0 = s.RegisterImage({ FgFormat::RGBA8888, FG_NONE, extent, 2 });
                        {
                                PassSignature p;
                                p.shader = "beta0";
                                p.inputs.push_back({ alphaArray[0] });
                                p.outputs.push_back({ flipflop0 });
                                p.dispatchOp = extent + dispatch;
                                s.AppendPass(p);
                        }
                        size_t flipflop1 = s.RegisterImage({ FgFormat::RGBA8888, FG_NONE, extent, 2 });
                        {
                                PassSignature p;
                                p.shader = "beta1";
                                p.inputs.push_back({ flipflop0 });
                                p.outputs.push_back({ flipflop1 });
                                p.dispatchOp = extent + dispatch;
                                s.AppendPass(p);
                        }
                        size_t flipflop2 = s.RegisterImage({ FgFormat::RGBA8888, FG_NONE, extent, 2 });
                        {
                                PassSignature p;
                                p.shader = "beta2";
                                p.inputs.push_back({ flipflop1 });
                                p.outputs.push_back({ flipflop2 });
                                p.dispatchOp = extent + dispatch;
                                s.AppendPass(p);
                        }
                        size_t flipflop3 = s.RegisterImage({ FgFormat::RGBA8888, FG_NONE, extent, 2 });
                        {
                                PassSignature p;
                                p.shader = "beta3";
                                p.inputs.push_back({ flipflop2 });
                                p.outputs.push_back({ flipflop3 });
                                p.dispatchOp = extent + dispatch;
                                s.AppendPass(p);
                        }
                        size_t betaImageArray = s.RegisterImage({ FgFormat::R8, FG_MIPMAPS, extent, 6 });
                        {
                                PassSignature p;
                                p.shader = "beta4";
                                p.inputs.push_back({ flipflop3 });
                                p.outputs.push_back({ betaImageArray, 0 });
                                p.dispatchOp = extent + ExtentOp(31, 5);
                                s.AppendPass(p);
                        }

                        /* -------- 主 pass -------- */
                        s.Split();

                        std::vector<size_t> gammaArray(7);
                        std::vector<size_t> deltaArray(3);
                        std::vector<size_t> epsilonArray(3);
                        for (uint32_t i = 0; i < 7; i++)
                        {
                                const ExtentOp dispatch = ExtentOp(7, 3);
                                const ExtentOp extent = alphaExtents[6 - i];

                                size_t ff0 = s.RegisterImage({ FgFormat::RGBA8888, FG_NONE, extent, 3 });
                                {
                                        PassSignature p;
                                        p.shader = "gamma0";
                                        p.flags = PASS_AGGREGATE | (i == 0 ? PASS_SPECIAL : PASS_NONE);
                                        p.inputs.push_back({ alphaArray[6 - i] });
                                        if (i == 0) p.inputs.push_back(FG_INVALID);
                                        else p.inputs.push_back({ gammaArray[i - 1] });
                                        p.outputs.push_back({ ff0 });
                                        p.dispatchOp = extent + dispatch;
                                        s.AppendPass(p);
                                }
                                size_t ff1 = s.RegisterImage({ FgFormat::RGBA8888, FG_NONE, extent, 2 * mul });
                                {
                                        PassSignature p;
                                        p.shader = "gamma1";
                                        p.flags = PASS_AGGREGATE;
                                        p.inputs.push_back({ ff0 });
                                        p.outputs.push_back({ ff1 });
                                        p.dispatchOp = extent + dispatch;
                                        s.AppendPass(p);
                                }
                                size_t ff2 = s.RegisterImage({ FgFormat::RGBA8888, FG_NONE, extent, 2 * mul });
                                {
                                        PassSignature p;
                                        p.shader = "gamma2";
                                        p.flags = PASS_AGGREGATE;
                                        p.inputs.push_back({ ff1 });
                                        p.outputs.push_back({ ff2 });
                                        p.dispatchOp = extent + dispatch;
                                        s.AppendPass(p);
                                }
                                size_t ff3 = s.RegisterImage({ FgFormat::RGBA8888, FG_NONE, extent, 2 * mul });
                                {
                                        PassSignature p;
                                        p.shader = "gamma3";
                                        p.flags = PASS_AGGREGATE;
                                        p.inputs.push_back({ ff2 });
                                        p.outputs.push_back({ ff3 });
                                        p.dispatchOp = extent + dispatch;
                                        s.AppendPass(p);
                                }
                                size_t gres = s.RegisterImage({ FgFormat::RGBA16161616, FG_NONE, extent, 1 });
                                {
                                        PassSignature p;
                                        p.shader = "gamma4";
                                        p.flags = PASS_AGGREGATE | (i == 0 ? PASS_SPECIAL : PASS_NONE);
                                        p.inputs.push_back({ ff3 });
                                        if (i == 0) p.inputs.push_back(FG_INVALID);
                                        else p.inputs.push_back({ gammaArray[i - 1] });
                                        p.inputs.push_back({ betaImageArray, i == 0 ? 5u : (6 - i) });
                                        p.outputs.push_back({ gres });
                                        p.dispatchOp = extent + dispatch;
                                        s.AppendPass(p);
                                }
                                gammaArray[i] = gres;

                                if (i >= 4)
                                {
                                        size_t d0 = s.RegisterImage({ FgFormat::RGBA8888, FG_NONE, extent, 3 });
                                        {
                                                PassSignature p;
                                                p.shader = "delta0";
                                                p.flags = PASS_AGGREGATE | (i == 4 ? PASS_SPECIAL : PASS_NONE);
                                                p.inputs.push_back({ alphaArray[6 - i] });
                                                if (i == 4) p.inputs.push_back(FG_INVALID);
                                                else p.inputs.push_back({ deltaArray[i - 5] });
                                                p.outputs.push_back({ d0 });
                                                p.dispatchOp = extent + dispatch;
                                                s.AppendPass(p);
                                        }
                                        size_t d1 = s.RegisterImage({ FgFormat::RGBA8888, FG_NONE, extent, 2 * mul });
                                        {
                                                PassSignature p;
                                                p.shader = "delta1";
                                                p.flags = PASS_AGGREGATE;
                                                p.inputs.push_back({ d0 });
                                                p.outputs.push_back({ d1 });
                                                p.dispatchOp = extent + dispatch;
                                                s.AppendPass(p);
                                        }
                                        size_t d2 = s.RegisterImage({ FgFormat::RGBA8888, FG_NONE, extent, 2 * mul });
                                        {
                                                PassSignature p;
                                                p.shader = "delta2";
                                                p.flags = PASS_AGGREGATE;
                                                p.inputs.push_back({ d1 });
                                                p.outputs.push_back({ d2 });
                                                p.dispatchOp = extent + dispatch;
                                                s.AppendPass(p);
                                        }
                                        size_t d3 = s.RegisterImage({ FgFormat::RGBA8888, FG_NONE, extent, 2 * mul });
                                        {
                                                PassSignature p;
                                                p.shader = "delta3";
                                                p.flags = PASS_AGGREGATE;
                                                p.inputs.push_back({ d2 });
                                                p.outputs.push_back({ d3 });
                                                p.dispatchOp = extent + dispatch;
                                                s.AppendPass(p);
                                        }
                                        size_t dres = s.RegisterImage({ FgFormat::RGBA16161616, FG_NONE, extent, 1 });
                                        {
                                                PassSignature p;
                                                p.shader = "delta4";
                                                p.flags = PASS_AGGREGATE | (i == 4 ? PASS_SPECIAL : PASS_NONE);
                                                p.inputs.push_back({ d3 });
                                                if (i == 4) p.inputs.push_back(FG_INVALID);
                                                else p.inputs.push_back({ deltaArray[i - 5] });
                                                p.inputs.push_back({ betaImageArray, 6 - i });
                                                p.outputs.push_back({ dres });
                                                p.dispatchOp = extent + dispatch;
                                                s.AppendPass(p);
                                        }
                                        deltaArray[i - 4] = dres;
                                }

                                if (i >= 4)
                                {
                                        size_t e0 = s.RegisterImage({ FgFormat::RGBA8888, FG_NONE, extent, mul });
                                        {
                                                PassSignature p;
                                                p.shader = "epsilon0";
                                                p.flags = PASS_AGGREGATE | (i == 4 ? PASS_SPECIAL : PASS_NONE);
                                                p.inputs.push_back({ alphaArray[6 - i] });
                                                p.inputs.push_back({ gammaArray[i - 1] });
                                                if (i == 4) p.inputs.push_back(FG_INVALID);
                                                else p.inputs.push_back({ deltaArray[i - 5] });
                                                p.outputs.push_back({ e0 });
                                                p.dispatchOp = extent + dispatch;
                                                s.AppendPass(p);
                                        }
                                        size_t e1 = s.RegisterImage({ FgFormat::RGBA8888, FG_NONE, extent, mul });
                                        {
                                                PassSignature p;
                                                p.shader = "epsilon1";
                                                p.flags = PASS_AGGREGATE;
                                                p.inputs.push_back({ e0 });
                                                p.outputs.push_back({ e1 });
                                                p.dispatchOp = extent + dispatch;
                                                s.AppendPass(p);
                                        }
                                        size_t e2 = s.RegisterImage({ FgFormat::RGBA8888, FG_NONE, extent, mul });
                                        {
                                                PassSignature p;
                                                p.shader = "epsilon2";
                                                p.flags = PASS_AGGREGATE;
                                                p.inputs.push_back({ e1 });
                                                p.outputs.push_back({ e2 });
                                                p.dispatchOp = extent + dispatch;
                                                s.AppendPass(p);
                                        }
                                        size_t e3 = s.RegisterImage({ FgFormat::RGBA8888, FG_NONE, extent, mul });
                                        {
                                                PassSignature p;
                                                p.shader = "epsilon3";
                                                p.flags = PASS_AGGREGATE;
                                                p.inputs.push_back({ e2 });
                                                p.outputs.push_back({ e3 });
                                                p.dispatchOp = extent + dispatch;
                                                s.AppendPass(p);
                                        }
                                        size_t eres = s.RegisterImage({ FgFormat::RGBA16161616, FG_NONE, extent, 1 });
                                        {
                                                PassSignature p;
                                                p.shader = "epsilon4";
                                                p.flags = PASS_AGGREGATE | (i == 4 ? PASS_SPECIAL : PASS_NONE);
                                                p.inputs.push_back({ e3 });
                                                if (i == 4) p.inputs.push_back(FG_INVALID);
                                                else p.inputs.push_back({ epsilonArray[i - 5] });
                                                p.outputs.push_back({ eres });
                                                p.dispatchOp = extent + dispatch;
                                                s.AppendPass(p);
                                        }
                                        epsilonArray[i - 4] = eres;
                                }
                        }

                        // 生成：源双帧 + 网络输出 → 单帧结果（外部输出图像）
                        extent = ExtentOp(false);
                        dispatch = ExtentOp(15, 4);

                        size_t result = s.RegisterImage({
                                FgFormat::RGBA8888, FG_PINNED | FG_EXTERNAL_OUTPUT, extent, 1 });
                        {
                                PassSignature p;
                                p.shader = "generate";
                                p.flags = PASS_HBD_VARIANT;
                                p.inputs.push_back({ sourceImageArray });
                                p.inputs.push_back({ gammaArray[6] });
                                p.inputs.push_back({ deltaArray[2] });
                                p.inputs.push_back({ epsilonArray[2] });
                                p.outputs.push_back({ result });
                                p.dispatchOp = extent + dispatch;
                                s.AppendPass(p);
                        }
                }

                return s.Finalize();
        }

        // ================= UBO / PushConstants =================
        struct FgUniformBuffer
        {
                float timestamp;              // 插值位置 (i+1)/(total+1)
                uint32_t iteration;           // 帧计数（ping-pong 索引）
                uint32_t transferFunction;    // 0 = sRGB
                uint32_t colorSpace;          // 0 = sRGB
                float resolutionInvScale;     // 1 / flowScale
                float uiThreshold;            // 0.5
        };
        struct FgPushConstants
        {
                uint32_t specialFlag;
                uint32_t subiteration;
        };

        // ================= 工具 =================
        static uint32_t FindMemoryType(VkPhysicalDevice phys, uint32_t typeBits, VkMemoryPropertyFlags props)
        {
                VkPhysicalDeviceMemoryProperties mem{};
                vkGetPhysicalDeviceMemoryProperties(phys, &mem);
                for (uint32_t i = 0; i < mem.memoryTypeCount; i++)
                {
                        if ((typeBits & (1u << i)) && (mem.memoryTypes[i].propertyFlags & props) == props)
                                return i;
                }
                return 0xFFFFFFFFu;
        }

        // ================= LSFG 上下文 =================
        struct FgSubimage
        {
                VkImage image = VK_NULL_HANDLE;
                VkDeviceMemory memory = VK_NULL_HANDLE;
                VkImageView view = VK_NULL_HANDLE;
        };
        struct FgImage
        {
                ImageSignature sig;
                std::vector<FgSubimage> subimages;
        };

        struct LsfgContext
        {
                VkDevice device = VK_NULL_HANDLE;
                VkQueue queue = VK_NULL_HANDLE;
                uint32_t width = 0, height = 0;
                float flow = 0.5f;
                bool perf = true;
                int multiplier = 2;

                std::unordered_map<std::string, VkShaderModule> shaderModules;
                std::unordered_map<std::string, VkPipeline> pipelines;
                VkDescriptorSetLayout dsetLayout = VK_NULL_HANDLE;
                VkPipelineLayout pipeLayout = VK_NULL_HANDLE;
                VkDescriptorPool descPool = VK_NULL_HANDLE;
                VkDescriptorSet descSet = VK_NULL_HANDLE;
                VkBuffer ubo = VK_NULL_HANDLE;
                VkDeviceMemory uboMem = VK_NULL_HANDLE;
                FgUniformBuffer* mapped = nullptr;
                VkSampler samplers[3]{};
                std::vector<FgImage> images;

                VkCommandPool cmdPool = VK_NULL_HANDLE;
                VkCommandBuffer headCmdbuf = VK_NULL_HANDLE;      // 每轮重录：拷贝真实帧 + 预 pass
                std::vector<VkCommandBuffer> genCmdbufs;          // 每轮重录：UBO + 主 pass + 输出 blit
                std::vector<VkSemaphore> chainSems;               // 提交链（head→gen0→gen1..）
                std::vector<VkSemaphore> presentSems;             // gen_i → present_i
                std::vector<VkSemaphore> acquireSems;             // 交换链获取
                VkFence roundFence = VK_NULL_HANDLE;
                bool fencePending = false;

                std::vector<Stage> stages;
                std::vector<size_t> splitIndices;
                uint64_t iteration = 0;

                ~LsfgContext() { Destroy(); }

                void Destroy()
                {
                        if (device == VK_NULL_HANDLE)
                                return;
                        if (fencePending && roundFence != VK_NULL_HANDLE)
                                vkWaitForFences(device, 1, &roundFence, VK_TRUE, 100'000'000ull);
                        vkDeviceWaitIdle(device);
                        for (auto& kv : pipelines)
                                if (kv.second) vkDestroyPipeline(device, kv.second, nullptr);
                        pipelines.clear();
                        for (auto& kv : shaderModules)
                                if (kv.second) vkDestroyShaderModule(device, kv.second, nullptr);
                        shaderModules.clear();
                        for (auto& img : images)
                        {
                                for (auto& sub : img.subimages)
                                {
                                        if (sub.view) vkDestroyImageView(device, sub.view, nullptr);
                                        if (sub.image) vkDestroyImage(device, sub.image, nullptr);
                                        if (sub.memory) vkFreeMemory(device, sub.memory, nullptr);
                                }
                        }
                        images.clear();
                        if (descSet != VK_NULL_HANDLE && descPool != VK_NULL_HANDLE)
                                vkFreeDescriptorSets(device, descPool, 1, &descSet);
                        if (descPool) vkDestroyDescriptorPool(device, descPool, nullptr);
                        if (pipeLayout) vkDestroyPipelineLayout(device, pipeLayout, nullptr);
                        if (dsetLayout) vkDestroyDescriptorSetLayout(device, dsetLayout, nullptr);
                        if (samplers[0]) vkDestroySampler(device, samplers[0], nullptr);
                        if (samplers[1]) vkDestroySampler(device, samplers[1], nullptr);
                        if (samplers[2]) vkDestroySampler(device, samplers[2], nullptr);
                        if (mapped) vkUnmapMemory(device, uboMem);
                        if (ubo) vkDestroyBuffer(device, ubo, nullptr);
                        if (uboMem) vkFreeMemory(device, uboMem, nullptr);
                        if (headCmdbuf) vkFreeCommandBuffers(device, cmdPool, 1, &headCmdbuf);
                        if (!genCmdbufs.empty()) vkFreeCommandBuffers(device, cmdPool, (uint32_t)genCmdbufs.size(), genCmdbufs.data());
                        if (roundFence) vkDestroyFence(device, roundFence, nullptr);
                        for (auto s : chainSems) if (s) vkDestroySemaphore(device, s, nullptr);
                        for (auto s : presentSems) if (s) vkDestroySemaphore(device, s, nullptr);
                        for (auto s : acquireSems) if (s) vkDestroySemaphore(device, s, nullptr);
                        if (cmdPool) vkDestroyCommandPool(device, cmdPool, nullptr);
                        device = VK_NULL_HANDLE;
                }

                // ---- 录制辅助：某段的全部 stage（屏障 + dispatch） ----
                void RecordSegment(VkCommandBuffer cmd, size_t firstStage, size_t lastStageExclusive)
                {
                        for (size_t i = firstStage; i < lastStageExclusive; i++)
                        {
                                auto& stage = stages[i];
                                // 起始屏障：本 stage 读的图 → ShaderRead；写的图 → ShaderWrite
                                {
                                        std::vector<VkImageMemoryBarrier> barriers;
                                        std::vector<VkImage> seen;
                                        auto push = [&](size_t imgIdx, VkAccessFlags dst)
                                        {
                                                auto& img = images[imgIdx];
                                                for (auto& sub : img.subimages)
                                                {
                                                        auto it = std::find(seen.begin(), seen.end(), sub.image);
                                                        if (it != seen.end())
                                                        {
                                                                size_t bi = std::distance(seen.begin(), it);
                                                                barriers[bi].dstAccessMask |= dst;
                                                                continue;
                                                        }
                                                        VkImageMemoryBarrier b{};
                                                        b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
                                                        b.srcAccessMask = 0;
                                                        b.dstAccessMask = dst;
                                                        b.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
                                                        b.newLayout = VK_IMAGE_LAYOUT_GENERAL;
                                                        b.image = sub.image;
                                                        b.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0,
                                                                img.subimages.size() == 1 ? img.sig.count : 1 };
                                                        seen.push_back(sub.image);
                                                        barriers.push_back(b);
                                                }
                                        };
                                        for (size_t si : stage.sampledImages) push(si, VK_ACCESS_SHADER_READ_BIT);
                                        for (size_t si : stage.storageImages) push(si, VK_ACCESS_SHADER_WRITE_BIT);
                                        if (!barriers.empty())
                                                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                                        0, 0, nullptr, 0, nullptr, (uint32_t)barriers.size(), barriers.data());
                                }
                                // dispatch
                                for (auto& substage : stage.substages)
                                {
                                        auto it = pipelines.find(substage.pipeline);
                                        if (it == pipelines.end())
                                                continue;
                                        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, it->second);
                                        for (auto& subit : substage.subiterations)
                                        {
                                                FgPushConstants pc{ subit.isSpecial ? 1u : 0u, subit.iterationIndex };
                                                vkCmdPushConstants(cmd, pipeLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pc), &pc);
                                                vkCmdDispatch(cmd, subit.dispatch.width, subit.dispatch.height, 1);
                                        }
                                }
                                // 末尾屏障：写完的图 → ShaderRead（段末跳过）
                                bool segmentEnd = std::find(splitIndices.begin(), splitIndices.end(), i + 1) != splitIndices.end();
                                if (!segmentEnd)
                                {
                                        std::vector<VkImageMemoryBarrier> barriers;
                                        std::vector<VkImage> seen;
                                        auto push = [&](size_t imgIdx, VkAccessFlags src, VkAccessFlags dst)
                                        {
                                                auto& img = images[imgIdx];
                                                for (auto& sub : img.subimages)
                                                {
                                                        auto it = std::find(seen.begin(), seen.end(), sub.image);
                                                        if (it != seen.end())
                                                        {
                                                                size_t bi = std::distance(seen.begin(), it);
                                                                barriers[bi].srcAccessMask |= src;
                                                                barriers[bi].dstAccessMask |= dst;
                                                                continue;
                                                        }
                                                        VkImageMemoryBarrier b{};
                                                        b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
                                                        b.srcAccessMask = src;
                                                        b.dstAccessMask = dst;
                                                        b.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
                                                        b.newLayout = VK_IMAGE_LAYOUT_GENERAL;
                                                        b.image = sub.image;
                                                        b.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0,
                                                                img.subimages.size() == 1 ? img.sig.count : 1 };
                                                        seen.push_back(sub.image);
                                                        barriers.push_back(b);
                                                }
                                        };
                                        for (size_t si : stage.sampledImages) push(si, VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_READ_BIT);
                                        for (size_t si : stage.storageImages) push(si, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);
                                        if (!barriers.empty())
                                                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                                        0, 0, nullptr, 0, nullptr, (uint32_t)barriers.size(), barriers.data());
                                }
                        }
                }
        };

        // ================= 上下文构建 =================
        // 网络着色器名 → DLL 资源 ID（quality=idx，performance=idx+24；fp32 变体）
        static const std::pair<const char*, uint32_t> kNetworkShaders[24] = {
                { "gamma0", 3 },   { "gamma1", 4 },   { "gamma2", 5 },   { "gamma3", 6 },
                { "gamma4", 7 },   { "delta0", 8 },   { "delta1", 9 },   { "delta2", 10 },
                { "delta3", 11 },  { "delta4", 12 },  { "alpha0", 13 },  { "alpha1", 14 },
                { "alpha2", 15 },  { "alpha3", 16 },  { "epsilon0", 17 },{ "epsilon1", 18 },
                { "epsilon2", 19 },{ "epsilon3", 20 },{ "epsilon4", 21 },{ "beta0", 22 },
                { "beta1", 23 },   { "beta2", 24 },   { "beta3", 25 },   { "beta4", 26 },
        };
        // mipmaps 基础着色器的特殊资源 ID（PE 资源树高位编号）
        static constexpr uint32_t kRidMipmaps = 2147488584u;
        static constexpr uint32_t kRidGenerate = 1u;

        static bool BuildLsfgContext(LsfgContext* ctx, VkDevice device, VkQueue queue,
                const DllResources& dll, uint32_t width, uint32_t height,
                float flow, bool perf, int multiplier, std::string& err)
        {
                ctx->device = device;
                ctx->queue = queue;
                ctx->width = width;
                ctx->height = height;
                ctx->flow = flow;
                ctx->perf = perf;
                ctx->multiplier = multiplier;

                const uint32_t inserted = (uint32_t)(multiplier - 1);

                // ---- 着色器模块 ----
                auto createModule = [&](const std::vector<uint32_t>& code, VkShaderModule* out) -> bool
                {
                        VkShaderModuleCreateInfo ci{};
                        ci.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
                        ci.codeSize = code.size() * sizeof(uint32_t);
                        ci.pCode = code.data();
                        return vkCreateShaderModule(device, &ci, nullptr, out) == VK_SUCCESS;
                };

                auto itMip = dll.shaders.find(kRidMipmaps);
                if (itMip == dll.shaders.end())
                {
                        err = "DLL 缺少 mipmaps 着色器（资源 2147488584）";
                        return false;
                }
                if (!createModule(itMip->second, &ctx->shaderModules["mipmaps"]))
                {
                        err = "mipmaps 着色器模块创建失败";
                        return false;
                }

                auto itGen = dll.shaders.find(kRidGenerate);
                if (itGen == dll.shaders.end())
                {
                        err = "DLL 缺少 generate 着色器（资源 1）";
                        return false;
                }
                {
                        // generate → generate0（SDR/Rgba8）字节码修补（照抄 lsfg-vk）：
                        // 1) 在第 8 字前插入 OpCapability(49)，将后续指令流保持合法；
                        // 2) 从第 5 字走指令流，找到首个 (操作码==25 且 word[i+8]!=0) 的位置，
                        //    把 word[i+8] 改为 4（Rgba8 存储格式）。
                        std::vector<uint32_t> bytecode = itGen->second;
                        if (bytecode.size() < 16)
                        {
                                err = "generate 着色器过小（非有效 SPIR-V）";
                                return false;
                        }
                        bytecode.insert(bytecode.begin() + 8, { 0x00000031u, 0x00020011u });
                        bool patched = false;
                        for (size_t i = 5; i < bytecode.size(); )
                        {
                                const uint32_t word = bytecode[i];
                                if ((word & 0xFFFF) == 25 && (i + 8) < bytecode.size() && bytecode[i + 8] != 0)
                                {
                                        bytecode[i + 8] = 0x04u;
                                        patched = true;
                                        break;
                                }
                                i += std::max<uint32_t>((word >> 16) & 0xFFFF, 1u);
                        }
                        if (!patched)
                        {
                                err = "generate 着色器修补点未找到（DLL 版本不兼容）";
                                return false;
                        }
                        if (!createModule(bytecode, &ctx->shaderModules["generate0"]))
                        {
                                err = "generate0 着色器模块创建失败";
                                return false;
                        }
                }

                for (auto& [name, idx] : kNetworkShaders)
                {
                        uint32_t ridQ = idx;          // fp32 quality
                        uint32_t ridP = idx + 24;     // fp32 performance
                        auto itQ = dll.shaders.find(ridQ);
                        auto itP = dll.shaders.find(ridP);
                        if (itQ == dll.shaders.end() || itP == dll.shaders.end())
                        {
                                err = std::string("DLL 缺少着色器资源：") + name;
                                return false;
                        }
                        if (!createModule(itQ->second, &ctx->shaderModules[std::string(name) + "#q"]) ||
                                !createModule(itP->second, &ctx->shaderModules[std::string(name) + "#p"]))
                        {
                                err = std::string("着色器模块创建失败：") + name;
                                return false;
                        }
                }

                // ---- 签名 ----
                PipelineSignature sig = BuildSignatureV31(perf);
                ctx->splitIndices = sig.splitIndices;

                const VkExtent2D base{ width, height };
                const VkExtent2D flowExtent{
                        std::max(1u, (uint32_t)((float)width * flow)),
                        std::max(1u, (uint32_t)((float)height * flow)) };

                // ---- 描述符布局（顺序：UBO / 3 采样器 / 图像绑定） ----
                {
                        std::vector<VkDescriptorSetLayoutBinding> bindings;
                        VkDescriptorSetLayoutBinding ub{};
                        ub.binding = 0;
                        ub.descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
                        ub.descriptorCount = 1;
                        ub.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
                        bindings.push_back(ub);
                        for (uint32_t i = 1; i <= 3; i++)
                        {
                                VkDescriptorSetLayoutBinding sb{};
                                sb.binding = i;
                                sb.descriptorType = VK_DESCRIPTOR_TYPE_SAMPLER;
                                sb.descriptorCount = 1;
                                sb.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
                                bindings.push_back(sb);
                        }
                        uint32_t bindingIdx = 4;
                        for (auto& b : sig.descriptors)
                        {
                                uint32_t count = (uint32_t)b.resources.size();
                                if (count == 1)
                                {
                                        auto& image = sig.images[b.resources.front()];
                                        if (image.flags & FG_MIPMAPS)
                                                count = image.count;
                                }
                                VkDescriptorSetLayoutBinding ib{};
                                ib.binding = bindingIdx++;
                                ib.descriptorType = b.isStorage ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE : VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
                                ib.descriptorCount = count;
                                ib.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
                                bindings.push_back(ib);
                        }
                        VkDescriptorSetLayoutCreateInfo li{};
                        li.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
                        li.bindingCount = (uint32_t)bindings.size();
                        li.pBindings = bindings.data();
                        if (vkCreateDescriptorSetLayout(device, &li, nullptr, &ctx->dsetLayout) != VK_SUCCESS)
                        {
                                err = "描述符布局创建失败";
                                return false;
                        }
                        VkPushConstantRange pcr{};
                        pcr.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
                        pcr.offset = 0;
                        pcr.size = sizeof(FgPushConstants);
                        VkPipelineLayoutCreateInfo pl{};
                        pl.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
                        pl.setLayoutCount = 1;
                        pl.pSetLayouts = &ctx->dsetLayout;
                        pl.pushConstantRangeCount = 1;
                        pl.pPushConstantRanges = &pcr;
                        if (vkCreatePipelineLayout(device, &pl, nullptr, &ctx->pipeLayout) != VK_SUCCESS)
                        {
                                err = "管线布局创建失败";
                                return false;
                        }
                }

                // ---- 图像 ----
                ctx->images.resize(sig.images.size());
                for (size_t i = 0; i < sig.images.size(); i++)
                {
                        auto& image = ctx->images[i];
                        image.sig = sig.images[i];
                        const VkExtent2D imageExtent = sig.images[i].extentOp.Apply(base, flowExtent);
                        const bool isMip = (sig.images[i].flags & FG_MIPMAPS) != 0;
                        const uint32_t subimageCount = isMip ? sig.images[i].count : 1;
                        VkImageUsageFlags usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
                        if (sig.images[i].flags & FG_EXTERNAL_INPUT)
                                usage |= VK_IMAGE_USAGE_TRANSFER_DST_BIT;
                        if (sig.images[i].flags & FG_EXTERNAL_OUTPUT)
                                usage |= VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
                        for (uint32_t sub = 0; sub < subimageCount; sub++)
                        {
                                VkExtent2D ext = imageExtent;
                                if (isMip)
                                {
                                        ext.width = std::max(imageExtent.width >> sub, 1u);
                                        ext.height = std::max(imageExtent.height >> sub, 1u);
                                }
                                FgSubimage& s = image.subimages.emplace_back();
                                VkImageCreateInfo ici{};
                                ici.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
                                ici.imageType = VK_IMAGE_TYPE_2D;
                                ici.format = (VkFormat)sig.images[i].format;
                                ici.extent = { ext.width, ext.height, 1 };
                                ici.mipLevels = 1;
                                ici.arrayLayers = isMip ? 1 : sig.images[i].count;
                                ici.samples = VK_SAMPLE_COUNT_1_BIT;
                                ici.tiling = VK_IMAGE_TILING_OPTIMAL;
                                ici.usage = usage;
                                ici.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
                                if (vkCreateImage(device, &ici, nullptr, &s.image) != VK_SUCCESS)
                                {
                                        err = "图像创建失败";
                                        return false;
                                }
                                VkMemoryRequirements req{};
                                vkGetImageMemoryRequirements(device, s.image, &req);
                                uint32_t memType = FindMemoryType(s_physDev, req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
                                if (memType == 0xFFFFFFFFu)
                                        memType = FindMemoryType(s_physDev, req.memoryTypeBits, 0);
                                VkMemoryAllocateInfo mai{};
                                mai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
                                mai.allocationSize = req.size;
                                mai.memoryTypeIndex = memType;
                                if (vkAllocateMemory(device, &mai, nullptr, &s.memory) != VK_SUCCESS ||
                                        vkBindImageMemory(device, s.image, s.memory, 0) != VK_SUCCESS)
                                {
                                        err = "图像内存分配失败";
                                        return false;
                                }
                                VkImageViewCreateInfo vci{};
                                vci.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
                                vci.image = s.image;
                                vci.viewType = (!isMip && sig.images[i].count > 1) ? VK_IMAGE_VIEW_TYPE_2D_ARRAY : VK_IMAGE_VIEW_TYPE_2D;
                                vci.format = (VkFormat)sig.images[i].format;
                                vci.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, isMip ? 1 : sig.images[i].count };
                                if (vkCreateImageView(device, &vci, nullptr, &s.view) != VK_SUCCESS)
                                {
                                        err = "图像视图创建失败";
                                        return false;
                                }
                        }
                }

                // ---- UBO（host 可见 + coherent，24 字节） ----
                {
                        VkBufferCreateInfo bci{};
                        bci.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
                        bci.size = sizeof(FgUniformBuffer);
                        bci.usage = VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
                        if (vkCreateBuffer(device, &bci, nullptr, &ctx->ubo) != VK_SUCCESS)
                        {
                                err = "UBO 创建失败";
                                return false;
                        }
                        VkMemoryRequirements req{};
                        vkGetBufferMemoryRequirements(device, ctx->ubo, &req);
                        uint32_t memType = FindMemoryType(s_physDev, req.memoryTypeBits,
                                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
                        VkMemoryAllocateInfo mai{};
                        mai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
                        mai.allocationSize = req.size;
                        mai.memoryTypeIndex = memType;
                        if (vkAllocateMemory(device, &mai, nullptr, &ctx->uboMem) != VK_SUCCESS ||
                                vkBindBufferMemory(device, ctx->ubo, ctx->uboMem, 0) != VK_SUCCESS)
                        {
                                err = "UBO 内存分配失败";
                                return false;
                        }
                        if (vkMapMemory(device, ctx->uboMem, 0, VK_WHOLE_SIZE, 0, (void**)&ctx->mapped) != VK_SUCCESS)
                        {
                                err = "UBO 映射失败";
                                return false;
                        }
                        ctx->mapped->timestamp = 0.5f;
                        ctx->mapped->iteration = 0;
                        ctx->mapped->transferFunction = 0; // sRGB
                        ctx->mapped->colorSpace = 0;       // sRGB
                        ctx->mapped->resolutionInvScale = flow > 0.0f ? 1.0f / flow : 1.0f;
                        ctx->mapped->uiThreshold = 0.5f;
                }

                // ---- 采样器 ×3（ClampToBorder黑 / ClampToBorder白 / ClampToEdge，均线性） ----
                {
                        const VkSamplerAddressMode modes[3] = {
                                VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER,
                                VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER,
                                VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE };
                        const VkBorderColor borders[3] = {
                                VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK,
                                VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE,
                                VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK };
                        for (int i = 0; i < 3; i++)
                        {
                                VkSamplerCreateInfo sci{};
                                sci.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
                                sci.magFilter = VK_FILTER_LINEAR;
                                sci.minFilter = VK_FILTER_LINEAR;
                                sci.mipmapMode = VK_SAMPLER_MIPMAP_MODE_LINEAR;
                                sci.addressModeU = modes[i];
                                sci.addressModeV = modes[i];
                                sci.addressModeW = modes[i];
                                sci.borderColor = borders[i];
                                sci.maxLod = VK_LOD_CLAMP_NONE;
                                if (vkCreateSampler(device, &sci, nullptr, &ctx->samplers[i]) != VK_SUCCESS)
                                {
                                        err = "采样器创建失败";
                                        return false;
                                }
                        }
                }

                // ---- 描述符池与写入 ----
                {
                        uint32_t sampledCount = 0, storageCount = 0;
                        for (auto& b : sig.descriptors)
                        {
                                uint32_t count = (uint32_t)b.resources.size();
                                if (count == 1)
                                {
                                        auto& image = sig.images[b.resources.front()];
                                        if (image.flags & FG_MIPMAPS)
                                                count = image.count;
                                }
                                if (b.isStorage) storageCount += count;
                                else sampledCount += count;
                        }
                        VkDescriptorPoolSize poolSizes[4] = {
                                { VK_DESCRIPTOR_TYPE_SAMPLER, 3 },
                                { VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, sampledCount },
                                { VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, storageCount },
                                { VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1 } };
                        VkDescriptorPoolCreateInfo pci{};
                        pci.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
                        pci.maxSets = 1;
                        pci.poolSizeCount = 4;
                        pci.pPoolSizes = poolSizes;
                        if (vkCreateDescriptorPool(device, &pci, nullptr, &ctx->descPool) != VK_SUCCESS)
                        {
                                err = "描述符池创建失败";
                                return false;
                        }
                        VkDescriptorSetAllocateInfo ai{};
                        ai.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
                        ai.descriptorPool = ctx->descPool;
                        ai.descriptorSetCount = 1;
                        ai.pSetLayouts = &ctx->dsetLayout;
                        if (vkAllocateDescriptorSets(device, &ai, &ctx->descSet) != VK_SUCCESS)
                        {
                                err = "描述符集分配失败";
                                return false;
                        }
                        std::vector<VkWriteDescriptorSet> writes;
                        VkDescriptorBufferInfo uboInfo{ ctx->ubo, 0, VK_WHOLE_SIZE };
                        {
                                VkWriteDescriptorSet w{};
                                w.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
                                w.dstSet = ctx->descSet;
                                w.dstBinding = 0;
                                w.descriptorCount = 1;
                                w.descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
                                w.pBufferInfo = &uboInfo;
                                writes.push_back(w);
                        }
                        std::vector<VkDescriptorImageInfo> samplerInfos(3);
                        for (int i = 0; i < 3; i++)
                        {
                                samplerInfos[i] = { ctx->samplers[i], VK_NULL_HANDLE, VK_IMAGE_LAYOUT_UNDEFINED };
                                VkWriteDescriptorSet w{};
                                w.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
                                w.dstSet = ctx->descSet;
                                w.dstBinding = 1 + i;
                                w.descriptorCount = 1;
                                w.descriptorType = VK_DESCRIPTOR_TYPE_SAMPLER;
                                w.pImageInfo = &samplerInfos[i];
                                writes.push_back(w);
                        }
                        // 图像绑定（视图数组 —— 生命周期需覆盖 update 调用）
                        std::vector<std::vector<VkDescriptorImageInfo>> imageInfos2D;
                        uint32_t bindingIdx = 4;
                        for (auto& b : sig.descriptors)
                        {
                                auto& infos = imageInfos2D.emplace_back();
                                for (size_t resIdx : b.resources)
                                {
                                        auto& img = ctx->images[resIdx];
                                        for (auto& sub : img.subimages)
                                                infos.push_back({ VK_NULL_HANDLE, sub.view, VK_IMAGE_LAYOUT_GENERAL });
                                }
                                VkWriteDescriptorSet w{};
                                w.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
                                w.dstSet = ctx->descSet;
                                w.dstBinding = bindingIdx++;
                                w.descriptorCount = (uint32_t)infos.size();
                                w.descriptorType = b.isStorage ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE : VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
                                w.pImageInfo = infos.data();
                                writes.push_back(w);
                        }
                        vkUpdateDescriptorSets(device, (uint32_t)writes.size(), writes.data(), 0, nullptr);
                }

                // ---- 计算管线 ----
                {
                        for (auto& [name, hasHbd] : sig.shaders)
                        {
                                std::string moduleName = name;
                                if (hasHbd)
                                        moduleName += "0"; // SDR 变体 generate0
                                std::string finalName = moduleName;
                                VkShaderModule module = VK_NULL_HANDLE;
                                auto it = ctx->shaderModules.find(moduleName);
                                if (it != ctx->shaderModules.end())
                                {
                                        module = it->second;
                                }
                                else
                                {
                                        // 网络着色器：#q / #p 变体
                                        std::string q = std::string(name) + (ctx->perf ? "#p" : "#q");
                                        auto it2 = ctx->shaderModules.find(q);
                                        if (it2 == ctx->shaderModules.end())
                                        {
                                                err = std::string("找不到着色器模块：") + name;
                                                return false;
                                        }
                                        module = it2->second;
                                        finalName = q;
                                }
                                VkComputePipelineCreateInfo ci{};
                                ci.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
                                ci.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
                                ci.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
                                ci.stage.module = module;
                                ci.stage.pName = "main";
                                ci.layout = ctx->pipeLayout;
                                VkPipeline pipe = VK_NULL_HANDLE;
                                if (vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &ci, nullptr, &pipe) != VK_SUCCESS)
                                {
                                        err = std::string("计算管线创建失败：") + name;
                                        return false;
                                }
                                ctx->pipelines[finalName] = pipe;
                        }
                }

                // ---- stages / substages 构建（依赖重排结果 → 执行序列） ----
                {
                        std::unordered_map<std::string, uint32_t> indices;
                        for (auto& stagePasses : sig.stagePasses)
                        {
                                Stage& stage = ctx->stages.emplace_back();
                                stage.substages.emplace_back();
                                for (size_t passIdx : stagePasses)
                                {
                                        auto& pass = sig.passes[passIdx];
                                        for (auto& res : pass.inputs)
                                                if (res.valid()) stage.sampledImages.push_back(res.idx);
                                        for (auto& res : pass.outputs)
                                                if (res.valid()) stage.storageImages.push_back(res.idx);
                                        // 解析最终着色器名（mipmaps/generate0 直查，网络着色器带 #q/#p）
                                        std::string finalName = pass.shader;
                                        if (pass.flags & PASS_HBD_VARIANT)
                                                finalName += "0";
                                        if (ctx->shaderModules.find(finalName) == ctx->shaderModules.end())
                                                finalName = std::string(pass.shader) + (ctx->perf ? "#p" : "#q");
                                        auto& sub = stage.substages.back();
                                        if (!sub.pipeline.empty() && sub.pipeline != finalName)
                                                stage.substages.emplace_back();
                                        auto& sub2 = stage.substages.back();
                                        sub2.pipeline = finalName;
                                        VkExtent2D dispatch = pass.dispatchOp.Apply(base, flowExtent);
                                        dispatch.width = std::max(dispatch.width, 1u);
                                        dispatch.height = std::max(dispatch.height, 1u);
                                        sub2.subiterations.push_back({ indices[finalName]++, dispatch,
                                                (pass.flags & PASS_SPECIAL) != 0 });
                                }
                        }
                }

                // ---- 命令池 / 每轮命令缓冲 / 同步原语 ----
                {
                        VkCommandPoolCreateInfo pci{};
                        pci.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
                        pci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
                        if (vkCreateCommandPool(device, &pci, nullptr, &ctx->cmdPool) != VK_SUCCESS)
                        {
                                err = "命令池创建失败";
                                return false;
                        }
                        VkCommandBufferAllocateInfo cai{};
                        cai.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
                        cai.commandPool = ctx->cmdPool;
                        cai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
                        cai.commandBufferCount = 1 + inserted;
                        std::vector<VkCommandBuffer> bufs(1 + inserted);
                        if (vkAllocateCommandBuffers(device, &cai, bufs.data()) != VK_SUCCESS)
                        {
                                err = "命令缓冲分配失败";
                                return false;
                        }
                        ctx->headCmdbuf = bufs[0];
                        ctx->genCmdbufs.assign(bufs.begin() + 1, bufs.end());

                        VkSemaphoreCreateInfo sci{};
                        sci.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
                        VkFenceCreateInfo fci{};
                        fci.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
                        for (uint32_t i = 0; i < inserted; i++)
                        {
                                VkSemaphore s = VK_NULL_HANDLE;
                                vkCreateSemaphore(device, &sci, nullptr, &s); ctx->chainSems.push_back(s);
                                vkCreateSemaphore(device, &sci, nullptr, &s); ctx->presentSems.push_back(s);
                                vkCreateSemaphore(device, &sci, nullptr, &s); ctx->acquireSems.push_back(s);
                        }
                        if (vkCreateFence(device, &fci, nullptr, &ctx->roundFence) != VK_SUCCESS)
                        {
                                err = "fence 创建失败";
                                return false;
                        }
                }

                // ---- 初始布局转换：全部图像 UNDEFINED → GENERAL ----
                {
                        VkCommandBufferAllocateInfo cai{};
                        cai.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
                        cai.commandPool = ctx->cmdPool;
                        cai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
                        cai.commandBufferCount = 1;
                        VkCommandBuffer initCmd = VK_NULL_HANDLE;
                        if (vkAllocateCommandBuffers(device, &cai, &initCmd) != VK_SUCCESS)
                        {
                                err = "初始化命令缓冲分配失败";
                                return false;
                        }
                        VkCommandBufferBeginInfo bi{};
                        bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
                        bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
                        vkBeginCommandBuffer(initCmd, &bi);
                        std::vector<VkImageMemoryBarrier> barriers;
                        for (auto& img : ctx->images)
                        {
                                for (auto& sub : img.subimages)
                                {
                                        VkImageMemoryBarrier b{};
                                        b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
                                        b.srcAccessMask = 0;
                                        b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
                                        b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
                                        b.newLayout = VK_IMAGE_LAYOUT_GENERAL;
                                        b.image = sub.image;
                                        b.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0,
                                                img.subimages.size() == 1 ? img.sig.count : 1 };
                                        barriers.push_back(b);
                                }
                        }
                        vkCmdPipelineBarrier(initCmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                0, 0, nullptr, 0, nullptr, (uint32_t)barriers.size(), barriers.data());
                        vkEndCommandBuffer(initCmd);
                        VkSubmitInfo si{};
                        si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
                        si.commandBufferCount = 1;
                        si.pCommandBuffers = &initCmd;
                        vkQueueSubmit(queue, 1, &si, VK_NULL_HANDLE);
                        vkQueueWaitIdle(queue);
                        vkFreeCommandBuffers(device, ctx->cmdPool, 1, &initCmd);
                }

                cemuLog_log(LogType::Force, "[FG] LSFG 3.1 上下文就绪: {}x{} x{}, flow {:.2f}, {} ({} 着色器 / {} 图像 / {} 段)",
                        width, height, multiplier, flow, perf ? "性能" : "质量",
                        ctx->shaderModules.size(), ctx->images.size(), ctx->splitIndices.size() + 1);
                return true;
        }

        // ================= 上下文保障（GPU 线程调用） =================
        static std::shared_ptr<LsfgContext> EnsureContext(VkDevice device, VkQueue queue, uint32_t width, uint32_t height)
        {
                std::lock_guard<std::mutex> lock(g_mutex);
                const int wantMult = std::max(2, std::min(4, s_multiplier.load(std::memory_order_relaxed)));
                const float wantFlow = s_flowScale.load(std::memory_order_relaxed);
                const bool wantPerf = s_perfMode.load(std::memory_order_relaxed);

                auto dll = s_dllResources;
                bool needBuild = !s_context || s_configDirty.exchange(false) ||
                        s_context->width != width || s_context->height != height ||
                        s_context->multiplier != wantMult || std::abs(s_context->flow - wantFlow) > 0.001f ||
                        s_context->perf != wantPerf || !dll;

                if (needBuild)
                {
                        s_context.reset();
                        if (!dll)
                                return nullptr;
                        auto ctx = std::make_shared<LsfgContext>();
                        std::string err;
                        if (!BuildLsfgContext(ctx.get(), device, queue, *dll, width, height, wantFlow, wantPerf, wantMult, err))
                        {
                                cemuLog_log(LogType::Force, "[FG] 上下文构建失败: {}", err);
                                return nullptr;
                        }
                        s_context = ctx;
                        s_contextSerial.fetch_add(1, std::memory_order_relaxed);
                        s_failStreak.store(0, std::memory_order_relaxed);
                }
                return s_context;
        }

        // ================= 帧路径钩子 =================
        void OnBeforeRealFramePresent(VkDevice device, VkQueue graphicsQueue, VkQueue presentQueue,
                SwapchainInfoVk& chainInfo, VkFence realFrameFence)
        {
                if (!s_enabled.load(std::memory_order_relaxed))
                        return;
                if (!s_deviceSupported.load(std::memory_order_relaxed))
                        return;
                if (s_failStreak.load(std::memory_order_relaxed) >= 5)
                        return;
                // 仅垂直同步开启时有稳定节拍基准（Immediate 背靠背提交无意义）
                if (chainInfo.m_vsyncState == SwapchainInfoVk::VSync::Immediate)
                        return;
                if (chainInfo.m_actualExtent.width == 0 || chainInfo.m_actualExtent.height == 0)
                        return;
                if (chainInfo.swapchainImageIndex == (uint32_t)-1)
                        return; // 主路径本帧未获取图像

                const uint32_t width = chainInfo.m_actualExtent.width;
                const uint32_t height = chainInfo.m_actualExtent.height;

                auto ctx = EnsureContext(device, graphicsQueue, width, height);
                if (!ctx)
                {
                        s_failStreak.fetch_add(1);
                        return;
                }
                const uint32_t inserted = (uint32_t)(ctx->multiplier - 1);
                if (inserted == 0 || ctx->genCmdbufs.size() < inserted)
                        return;
                if (ctx->stages.empty() || ctx->splitIndices.empty())
                        return;
                const size_t firstSplitStage = ctx->splitIndices.front();
                if (firstSplitStage == 0 || firstSplitStage >= ctx->stages.size())
                        return; // 段边界异常（防预 pass 为空）

                VkImage realImage = chainInfo.m_swapchainImages[chainInfo.swapchainImageIndex];

                // 等待上一轮合成完成 + 真实帧内容完成（host fence 等待，有限超时防死锁）
                if (ctx->fencePending)
                {
                        if (vkWaitForFences(device, 1, &ctx->roundFence, VK_TRUE, 100'000'000ull) != VK_SUCCESS)
                        {
                                s_failStreak.fetch_add(1);
                                return;
                        }
                        vkResetFences(device, 1, &ctx->roundFence);
                        ctx->fencePending = false;
                }
                if (vkWaitForFences(device, 1, &realFrameFence, VK_TRUE, 100'000'000ull) != VK_SUCCESS)
                {
                        s_failStreak.fetch_add(1);
                        return;
                }

                // ---- 获取 (倍率-1) 张交换链图像 ----
                std::vector<uint32_t> imageIndices(inserted, (uint32_t)-1);
                for (uint32_t i = 0; i < inserted; i++)
                {
                        VkResult acq = vkAcquireNextImageKHR(device, chainInfo.m_swapchain, 50'000'000ull,
                                ctx->acquireSems[i], VK_NULL_HANDLE, &imageIndices[i]);
                        if (acq != VK_SUCCESS && acq != VK_SUBOPTIMAL_KHR)
                        {
                                if (acq == VK_ERROR_OUT_OF_DATE_KHR)
                                        chainInfo.m_shouldRecreate = true;
                                s_failStreak.fetch_add(1);
                                return;
                        }
                }

                // ---- UBO：iteration host 写（ping-pong 索引） ----
                ctx->mapped->iteration = (uint32_t)ctx->iteration;
                const uint64_t iter = ctx->iteration;
                ctx->iteration++;

                const bool headOk =
                        vkResetCommandBuffer(ctx->headCmdbuf, 0) == VK_SUCCESS;
                if (!headOk)
                {
                        s_failStreak.fetch_add(1);
                        return;
                }
                VkCommandBufferBeginInfo bi{};
                bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
                bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
                if (vkBeginCommandBuffer(ctx->headCmdbuf, &bi) != VK_SUCCESS)
                {
                        s_failStreak.fetch_add(1);
                        return;
                }

                // 找到源图像（第一个 ExternalInput）与输出图像（ExternalOutput）
                VkImage srcImage = VK_NULL_HANDLE;
                uint32_t srcLayers = 2;
                VkImage outImage = VK_NULL_HANDLE;
                for (auto& img : ctx->images)
                {
                        if ((img.sig.flags & FG_EXTERNAL_INPUT) && srcImage == VK_NULL_HANDLE)
                        {
                                srcImage = img.subimages[0].image;
                                srcLayers = img.subimages.size() == 1 ? img.sig.count : 1;
                        }
                        if ((img.sig.flags & FG_EXTERNAL_OUTPUT) && outImage == VK_NULL_HANDLE)
                                outImage = img.subimages[0].image;
                }
                if (srcImage == VK_NULL_HANDLE || outImage == VK_NULL_HANDLE)
                {
                        vkEndCommandBuffer(ctx->headCmdbuf);
                        s_failStreak.fetch_add(1);
                        return;
                }

                // ---- head：真实帧 blit → src[layer = iter%2] + 预 pass ----
                {
                        VkCommandBuffer cmd = ctx->headCmdbuf;
                        // 真实帧：PRESENT_SRC → TRANSFER_SRC
                        VkImageMemoryBarrier rb{};
                        rb.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
                        rb.srcAccessMask = 0;
                        rb.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
                        rb.oldLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
                        rb.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
                        rb.image = realImage;
                        rb.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
                        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &rb);
                        // 源图像（双帧 ping-pong）：GENERAL(SHADER_READ) → GENERAL(TRANSFER_WRITE)
                        VkImageMemoryBarrier sb{};
                        sb.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
                        sb.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
                        sb.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
                        sb.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
                        sb.newLayout = VK_IMAGE_LAYOUT_GENERAL;
                        sb.image = srcImage;
                        sb.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, srcLayers };
                        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &sb);
                        // blit（支持格式转换 BGRA8↔RGBA8）
                        VkImageBlit blit{};
                        blit.srcSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
                        blit.dstSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, (uint32_t)(iter % 2), 1 };
                        blit.srcOffsets[1] = { (int32_t)width, (int32_t)height, 1 };
                        blit.dstOffsets[1] = { (int32_t)width, (int32_t)height, 1 };
                        vkCmdBlitImage(cmd, realImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                                srcImage, VK_IMAGE_LAYOUT_GENERAL, 1, &blit, VK_FILTER_NEAREST);
                        // 还原
                        sb.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
                        sb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
                        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &sb);
                        rb.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
                        rb.dstAccessMask = 0;
                        rb.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
                        rb.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
                        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr, 0, nullptr, 1, &rb);

                        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipeLayout, 0, 1, &ctx->descSet, 0, nullptr);
                        ctx->RecordSegment(cmd, 0, firstSplitStage);
                        vkEndCommandBuffer(cmd);
                }

                // ---- 提交 head → 信号 chain[0] ----
                {
                        VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
                        VkSubmitInfo si{};
                        si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
                        si.commandBufferCount = 1;
                        si.pCommandBuffers = &ctx->headCmdbuf;
                        si.signalSemaphoreCount = 1;
                        si.pSignalSemaphores = &ctx->chainSems[0];
                        if (vkQueueSubmit(graphicsQueue, 1, &si, VK_NULL_HANDLE) != VK_SUCCESS)
                        {
                                s_failStreak.fetch_add(1);
                                return;
                        }
                }

                // ---- 逐生成帧：UBO timestamp + 主 pass + 输出 blit → 提交 → present ----
                for (uint32_t i = 0; i < inserted; i++)
                {
                        VkImage dstImage = chainInfo.m_swapchainImages[imageIndices[i]];
                        const float ts = (float)(i + 1) / (float)(inserted + 1);
                        VkCommandBuffer cmd = ctx->genCmdbufs[i];
                        if (vkResetCommandBuffer(cmd, 0) != VK_SUCCESS ||
                                vkBeginCommandBuffer(cmd, &bi) != VK_SUCCESS)
                        {
                                s_failStreak.fetch_add(1);
                                return;
                        }
                        // UBO timestamp 更新（只覆盖前 4 字节，barrier 同 lsfg-vk trans cmdbuf）
                        {
                                VkBufferMemoryBarrier bb{};
                                bb.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
                                bb.srcAccessMask = VK_ACCESS_UNIFORM_READ_BIT;
                                bb.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
                                bb.srcQueueFamilyIndex = bb.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
                                bb.buffer = ctx->ubo;
                                bb.offset = 0;
                                bb.size = 4;
                                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 1, &bb, 0, nullptr);
                                vkCmdUpdateBuffer(cmd, ctx->ubo, 0, 4, &ts);
                                bb.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
                                bb.dstAccessMask = VK_ACCESS_UNIFORM_READ_BIT;
                                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 1, &bb, 0, nullptr);
                        }
                        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipeLayout, 0, 1, &ctx->descSet, 0, nullptr);
                        ctx->RecordSegment(cmd, firstSplitStage, ctx->stages.size());
                        // 输出图像 → 获取的交换链图像
                        {
                                VkImageMemoryBarrier ob{};
                                ob.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
                                ob.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
                                ob.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
                                ob.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
                                ob.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
                                ob.image = outImage;
                                ob.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
                                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &ob);

                                VkImageMemoryBarrier db{};
                                db.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
                                db.srcAccessMask = 0;
                                db.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
                                db.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
                                db.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
                                db.image = dstImage;
                                db.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
                                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &db);

                                VkImageBlit blit{};
                                blit.srcSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
                                blit.dstSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
                                blit.srcOffsets[1] = { (int32_t)width, (int32_t)height, 1 };
                                blit.dstOffsets[1] = { (int32_t)width, (int32_t)height, 1 };
                                vkCmdBlitImage(cmd, outImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                                        dstImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_LINEAR);

                                db.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
                                db.dstAccessMask = 0;
                                db.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
                                db.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
                                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr, 0, nullptr, 1, &db);

                                // 输出图像回 GENERAL 供下一轮写入
                                ob.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
                                ob.dstAccessMask = 0;
                                ob.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
                                ob.newLayout = VK_IMAGE_LAYOUT_GENERAL;
                                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &ob);
                        }
                        vkEndCommandBuffer(cmd);

                        // 提交：等 chain[i] + acquireSems[i] → 信号 presentSems[i] (+chain[i+1])
                        VkSemaphore waitSems[2] = { ctx->chainSems[i], ctx->acquireSems[i] };
                        VkPipelineStageFlags waitStages[2] = { VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT };
                        VkSemaphore signalSems[2] = { ctx->presentSems[i], VK_NULL_HANDLE };
                        uint32_t signalCount = 1;
                        if (i + 1 < inserted)
                                signalSems[signalCount++] = ctx->chainSems[i + 1];
                        VkSubmitInfo si{};
                        si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
                        si.waitSemaphoreCount = 2;
                        si.pWaitSemaphores = waitSems;
                        si.pWaitDstStageMask = waitStages;
                        si.commandBufferCount = 1;
                        si.pCommandBuffers = &cmd;
                        si.signalSemaphoreCount = signalCount;
                        si.pSignalSemaphores = signalSems;
                        VkFence fence = (i + 1 == inserted) ? ctx->roundFence : VK_NULL_HANDLE;
                        if (vkQueueSubmit(graphicsQueue, 1, &si, fence) != VK_SUCCESS)
                        {
                                s_failStreak.fetch_add(1);
                                return;
                        }
                        if (i + 1 == inserted)
                                ctx->fencePending = true;

                        // present 合成帧（真实帧在主路径随后上屏 —— 时序正确）
                        VkPresentInfoKHR pi{};
                        pi.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
                        pi.waitSemaphoreCount = 1;
                        pi.pWaitSemaphores = &ctx->presentSems[i];
                        pi.swapchainCount = 1;
                        pi.pSwapchains = &chainInfo.m_swapchain;
                        pi.pImageIndices = &imageIndices[i];
                        VkResult pr = vkQueuePresentKHR(presentQueue, &pi);
                        if (pr == VK_ERROR_OUT_OF_DATE_KHR)
                                chainInfo.m_shouldRecreate = true;
                        else if (pr < 0)
                        {
                                // 记失败但继续 present 剩余已获取图像（保持
                                // acquire/present 平衡，避免图像被永久占用）
                                s_failStreak.fetch_add(1);
                                continue;
                        }
                }

                s_failStreak.store(0, std::memory_order_relaxed);
        }

        // ================= 对外 API =================
        bool IsEnabled()
        {
                return s_enabled.load(std::memory_order_relaxed);
        }

        void SetEnabled(bool enable)
        {
                if (s_enabled.exchange(enable) != enable)
                {
                        s_configDirty.store(true, std::memory_order_relaxed);
                        s_swapchainRecreateRequest.store(1, std::memory_order_relaxed);
                        if (enable)
                                s_failStreak.store(0, std::memory_order_relaxed);
                }
        }

        int SetDllPath(const std::string& path)
        {
                std::lock_guard<std::mutex> lock(g_mutex);
                s_dllResources.reset();
                s_dllReady = false;
                if (path.empty())
                {
                        s_dllError = "未加载 Lossless.dll";
                        return 1;
                }
                std::unordered_map<uint32_t, std::vector<uint32_t>> shaders;
                std::string err;
                if (!ParseLosslessDll(path, shaders, err))
                {
                        s_dllError = err;
                        cemuLog_log(LogType::Force, "[FG] Lossless.dll 解析失败: {}", err);
                        return 2;
                }
                // 必需资源齐全性校验（mipmaps / generate / 24×2 网络着色器）
                if (shaders.find(kRidMipmaps) == shaders.end() || shaders.find(kRidGenerate) == shaders.end())
                {
                        s_dllError = "DLL 资源不完整（缺少 mipmaps/generate，不是有效的 Lossless.dll）";
                        return 3;
                }
                for (auto& [name, idx] : kNetworkShaders)
                {
                        if (shaders.find(idx) == shaders.end() || shaders.find(idx + 24) == shaders.end())
                        {
                                s_dllError = std::string("DLL 缺少着色器资源 ") + name + "（版本不兼容）";
                                return 3;
                        }
                }
                auto res = std::make_shared<DllResources>();
                res->shaders = std::move(shaders);
                s_dllResources = res;
                s_dllReady = true;
                s_dllError.clear();
                s_configDirty.store(true, std::memory_order_relaxed);
                s_failStreak.store(0, std::memory_order_relaxed);
                cemuLog_log(LogType::Force, "[FG] Lossless.dll 加载成功: {}（{} 个 SPIR-V 资源）", path, s_dllResources->shaders.size());
                return 0;
        }

        std::string GetDllError()
        {
                std::lock_guard<std::mutex> lock(g_mutex);
                return s_dllError;
        }

        bool IsDllReady()
        {
                std::lock_guard<std::mutex> lock(g_mutex);
                return s_dllReady;
        }

        void SetConfig(int multiplier, float flowScale, bool performanceMode)
        {
                multiplier = std::max(2, std::min(4, multiplier));
                if (flowScale < 0.25f) flowScale = 0.25f;
                if (flowScale > 1.0f) flowScale = 1.0f;
                const int oldMult = s_multiplier.exchange(multiplier);
                const float oldFlow = s_flowScale.exchange(flowScale);
                const bool oldPerf = s_perfMode.exchange(performanceMode);
                if (oldMult != multiplier || std::abs(oldFlow - flowScale) > 0.001f || oldPerf != performanceMode)
                {
                        s_configDirty.store(true, std::memory_order_relaxed);
                        s_swapchainRecreateRequest.store(1, std::memory_order_relaxed);
                }
        }

        uint32_t GetSwapchainImagePadding()
        {
                if (!s_enabled.load(std::memory_order_relaxed))
                        return 0;
                if (!IsDllReady())
                        return 0;
                // 启用时附加 倍率 张（插入帧 + 1 张余量，同 lsfg-vk）
                return (uint32_t)std::max(2, std::min(4, s_multiplier.load(std::memory_order_relaxed)));
        }

        bool ConsumeSwapchainRecreateRequest()
        {
                return s_swapchainRecreateRequest.exchange(0, std::memory_order_relaxed) != 0;
        }

        void OnRendererInitialized(VkPhysicalDevice physicalDevice)
        {
                s_physDev = physicalDevice;
                s_deviceChecked.store(true, std::memory_order_relaxed);
                if (physicalDevice == VK_NULL_HANDLE)
                {
                        s_deviceSupported.store(false, std::memory_order_relaxed);
                        return;
                }
                // compute 队列 + R8 storage 图像（mipmaps 特征图用）支持检测
                uint32_t qfiCount = 0;
                vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, &qfiCount, nullptr);
                std::vector<VkQueueFamilyProperties> qfis(qfiCount);
                vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, &qfiCount, qfis.data());
                bool hasCompute = false;
                for (auto& q : qfis)
                        if (q.queueFlags & VK_QUEUE_COMPUTE_BIT)
                        {
                                hasCompute = true;
                                break;
                        }
                VkImageFormatProperties ifp{};
                bool r8Storage = vkGetPhysicalDeviceImageFormatProperties(physicalDevice, VK_FORMAT_R8_UNORM,
                        VK_IMAGE_TYPE_2D, VK_IMAGE_TILING_OPTIMAL, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, 0, &ifp) == VK_SUCCESS;
                s_deviceSupported.store(hasCompute && r8Storage, std::memory_order_relaxed);
                if (!s_deviceSupported.load(std::memory_order_relaxed))
                        cemuLog_log(LogType::Force, "[FG] 设备能力不足（compute={} r8storage={}），帧生成不可用", hasCompute, r8Storage);
        }
} // namespace VulkanFrameGen
