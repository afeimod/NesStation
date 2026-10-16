#pragma once
#include "Cafe/HW/Latte/Core/LatteConst.h"

struct LatteSamplerState
{
        uint8 clampS;
        uint8 clampT;
        uint8 clampR;
        uint32 filterMag; // openGL constant
        uint32 filterMin; // openGL constant
        uint8 maxAniso;
        uint8 maxMipLevels;
        uint8 depthCompareMode;
        uint8 depthCompareFunc;
        uint16 minLod;
        uint16 maxLod;
        sint16 lodBias;
        uint8 borderType;
        float borderColor[4];
};

#include "Cafe/HW/Latte/Core/LatteTextureView.h"

class LatteTexture
{
public:
        LatteTexture(Latte::E_DIM dim, MPTR physAddress, MPTR physMipAddress, Latte::E_GX2SURFFMT format, uint32 width, uint32 height, uint32 depth, uint32 pitch, uint32 mipLevels, uint32 swizzle, Latte::E_HWTILEMODE tileMode, bool isDepth);
        virtual ~LatteTexture();

        virtual void AllocateOnHost() = 0;

        LatteTextureView* GetOrCreateView(Latte::E_DIM dim, Latte::E_GX2SURFFMT format, sint32 firstMip, sint32 mipCount, sint32 firstSlice, sint32 sliceCount)
        {
                for (auto& itr : views)
                {
                        if (itr->firstMip == firstMip && itr->numMip == mipCount && itr->firstSlice == firstSlice && itr->numSlice == sliceCount &&
                                itr->dim == dim && itr->format == format)
                                return itr;
                }
                return CreateView(dim, format, firstMip, mipCount, firstSlice, sliceCount);
        }

        LatteTextureView* GetOrCreateView(sint32 firstMip, sint32 mipCount, sint32 firstSlice, sint32 sliceCount)
        {
                return GetOrCreateView(this->dim, this->format, firstMip, mipCount, firstSlice, sliceCount);
        }

        bool IsCompressedFormat() const
        {
                return Latte::IsCompressedFormat(format);
        }

        uint32 GetBPP() const
        {
                return Latte::GetFormatBits(format);
        }

        bool Is3DTexture() const { return dim == Latte::E_DIM::DIM_3D; };

        void GetSize(sint32& width, sint32& height, sint32 mipLevel) const
        {
                width = std::max(1, this->width >> mipLevel);
                height = std::max(1, this->height >> mipLevel);
        }

        // similar to GetSize, but returns the real size of the texture taking into account any resolution overwrite by gfx pack rules
        void GetEffectiveSize(sint32& effectiveWidth, sint32& effectiveHeight, sint32 mipLevel) const
        {
                if( overwriteInfo.hasResolutionOverwrite )
                {
                        effectiveWidth = overwriteInfo.width;
                        effectiveHeight = overwriteInfo.height;
                }
                else
                {
                        effectiveWidth = this->width;
                        effectiveHeight = this->height;
                }
                effectiveWidth = std::max(1, effectiveWidth >> mipLevel);
                effectiveHeight = std::max(1, effectiveHeight >> mipLevel);
        }

        sint32 GetMipDepth(sint32 mipIndex)
        {
                cemu_assert_debug(mipIndex >= 0 && mipIndex < this->mipLevels);
                if (Is3DTexture())
                        return std::max(depth >> mipIndex, 1);
                return depth;
        }

        sint32 GetMipWidth(sint32 mipIndex)
        {
                cemu_assert_debug(mipIndex >= 0 && mipIndex < this->mipLevels);
                return std::max(width >> mipIndex, 1);
        }

        sint32 GetMipHeight(sint32 mipIndex)
        {
                cemu_assert_debug(mipIndex >= 0 && mipIndex < this->mipLevels);
                return std::max(height >> mipIndex, 1);
        }

        // return the size necessary for a 1D array to store one entry per slice/mip combo (using getSliceMipArrayIndex)
        sint32 GetSliceMipArraySize()
        {
                cemu_assert_debug(this->depth > 0);
                cemu_assert_debug(this->mipLevels > 0);
                return this->depth * this->mipLevels;
        }

        // calculate index within slice/mip array
        sint32 GetSliceMipArrayIndex(sint32 sliceIndex, sint32 mipIndex)
        {
                cemu_assert_debug(sliceIndex < depth);
                cemu_assert_debug(mipIndex < mipLevels);
                // to keep the computation fast, we ignore that 3D textures have decreasing slice count per mip and leave some indices empty
                return this->depth * mipIndex + sliceIndex;
        }

        struct LatteTextureSliceMipInfo* GetSliceMipArrayEntry(sint32 sliceIndex, sint32 mipIndex);
        static std::vector<LatteTexture*>& GetAllTextures(); // note: can contain nullptr entries

protected:
        virtual LatteTextureView* CreateView(Latte::E_DIM dim, Latte::E_GX2SURFFMT format, sint32 firstMip, sint32 mipCount, sint32 firstSlice, sint32 sliceCount) = 0;

public:

        // Latte texture info
        MPTR physAddress;
        MPTR physMipAddress;
        Latte::E_DIM dim;
        Latte::E_GX2SURFFMT format;
        Latte::E_HWTILEMODE tileMode;
        sint32 width;
        sint32 height;
        sint32 depth;
        sint32 pitch;
        sint32 mipLevels; // number of used mip levels (must be at least 1)
        sint32 maxPossibleMipLevels{}; // number of mips that can potentially be used (uses resolution from texture rules)
        uint32 swizzle;
        uint32 lastRenderTargetSwizzle{}; // set to rt swizzle bits whenever the texture is being rendered to
        // data info
        bool isDataDefined{};
        bool isDepth;
        bool hasStencil{}; // for depth textures
        // info per mip/slice
        struct LatteTextureSliceMipInfo* sliceMipInfo{};
        // physical offsets for start and end of data (calculated on texture load)
        MPTR texDataPtrLow{};
        MPTR texDataPtrHigh{};
        uint32 texDataHash2{};
        // state
        bool isUpdatedOnGPU{ false }; // set if any GPU-side operation modified this texture and strict one-way RAM->VRAM memory mirroring no longer applies
        bool enableReadback{ false }; // if true, texture will be mirrored back to CPU RAM under specific circumstances
        // invalidation
        bool forceInvalidate{};
        // cache control
        bool reloadFromDynamicTextures{};
        // last update (dynamic)
        uint64 lastWriteEventCounter;
        uint64 lastUpdateEventCounter;
        uint32 lastUpdateFrameCounter{};
        uint32 reloadCount{};
        // last update (from RAM data)
        uint32 lastDataUpdateFrameCounter{};
        // optimization
        bool useLightHash{};
        // overwrite info
        struct
        {
                // resolution
                bool hasResolutionOverwrite;
                sint32 width;
                sint32 height;
                sint32 depth;
                // format
                bool hasFormatOverwrite;
                sint32 format;
                // lod bias
                sint16 lodBias; // in 1/64th steps
                bool hasLodBias;
                // relative lod bias
                sint16 relativeLodBias; // in 1/64th steps
                bool hasRelativeLodBias;
                // anisotropic
                sint8 anisotropicLevel{ -1 }; // 1<<n, 0 is disabled
        }overwriteInfo{};
        // for detecting multiple references during the same drawcall
        uint32 lockTextureUpdateId{}; // set to current texture update id whenever texture is bound and used
        uint32 lockCount{};
        // usage
        uint32 lastAccessTick{};
        uint32 lastAccessFrameCount{};
        // detection of render feedback loops (see OpenGL 4.5 spec, 9.3)
        uint32 lastUnflushedRTDrawcallIndex{};
        // views
        LatteTextureView* baseView{};
        std::vector<LatteTextureView*> views; // list of all views created for this texture, including baseView
        // texture relations (overlapping memory)
        std::vector<struct LatteTextureRelation*> list_compatibleRelations; // list of other data-compatible textures that share the same memory ranges
        // index in global texture list
        size_t globalListIndex;
};

struct LatteTextureDefinition // todo - actually use this in LatteTexture class
{
        MPTR physAddress;
        MPTR physMipAddress;
        Latte::E_DIM dim;
        Latte::E_GX2SURFFMT format;
        uint32 width;
        uint32 height;
        uint32 depth;
        uint32 pitch;
        uint32 mipLevels;
        uint32 swizzle;
        Latte::E_HWTILEMODE tileMode;
        bool isDepth;

        LatteTextureDefinition() = default;
        LatteTextureDefinition(const LatteTexture* tex)
        {
                physAddress = tex->physAddress;
                physMipAddress = tex->physMipAddress;
                dim = tex->dim;
                format = tex->format;
                width = tex->width;
                height = tex->height;
                depth = tex->depth;
                pitch = tex->pitch;
                mipLevels = tex->mipLevels;
                swizzle = tex->swizzle;
                tileMode = tex->tileMode;
                isDepth = tex->isDepth;
        }
};

class LatteTextureView;

struct LatteTextureSliceMipDataOverlap_t
{
        LatteTexture* destTexture;
        struct LatteTextureSliceMipInfo* destMipSliceInfo;
};

struct LatteTextureSliceMipInfo
{
        uint32 addrStart; // same as physAddr if this mip were it's own texture
        uint32 addrEnd;
        uint32 subIndex; // for thick tiles, multiple (4) slices can be interleaved into the same data range. This stores the index
        LatteTexture* texture;
        sint32 sliceIndex;
        sint32 mipIndex;
        // change tracking
        uint32 dataChecksum;
        uint64 lastDynamicUpdate;
        // format info
        Latte::E_HWTILEMODE tileMode;
        sint32 pitch;
        // data overlap tracking
        uint32 estDataAddrStart;
        uint32 estDataAddrEnd;
        std::vector<LatteTextureSliceMipDataOverlap_t> list_dataOverlap;
};

struct LatteTextureRelation
{
        LatteTexture* baseTexture;
        LatteTexture* subTexture; // texture which is contained within baseTexture
        sint32 baseSliceIndex;
        sint32 baseMipIndex;
        sint32 sliceCount;
        sint32 mipCount;
        //sint32 xOffset;
        sint32 yOffset;
};

// used if external modules want to retrieve texture cache information
struct LatteTextureViewInformation
{
        MPTR physAddress;
        MPTR physMipAddress;
        sint32 width;
        sint32 height;
        sint32 pitch;
        sint32 firstMip;
        sint32 numMip;
        sint32 firstSlice;
        sint32 numSlice;
        Latte::E_GX2SURFFMT format;
        Latte::E_DIM dim;
};

struct LatteTextureInformation 
{
        MPTR physAddress;
        MPTR physMipAddress;
        sint32 width;
        sint32 height;
        sint32 depth;
        sint32 pitch;
        sint32 mipLevels;
        Latte::E_GX2SURFFMT format;
        bool isDepth;
        Latte::E_DIM dim;
        Latte::E_HWTILEMODE tileMode;
        // access
        uint32 lastAccessTick;
        uint32 lastAccessFrameCount;
        bool isUpdatedOnGPU;
        // misc
        uint32 alternativeViewCount;
        // overwrite info
        struct
        {
                // resolution
                bool hasResolutionOverwrite;
                sint32 width;
                sint32 height;
                sint32 depth;
        }overwriteInfo{};

        std::vector<LatteTextureViewInformation> views;
};

void LatteTexture_init();
void LatteTexture_updateTextures();

std::vector<LatteTextureInformation> LatteTexture_QueryCacheInfo();

float* LatteTexture_getEffectiveTextureScale(LatteConst::ShaderType shaderType, sint32 texUnit);

LatteTextureView* LatteTexture_CreateTexture(Latte::E_DIM dim, MPTR physAddress, MPTR physMipAddress, Latte::E_GX2SURFFMT format, uint32 width, uint32 height, uint32 depth, uint32 pitch, uint32 mipLevels, uint32 swizzle, Latte::E_HWTILEMODE tileMode, bool isDepth);
void LatteTexture_Delete(LatteTexture* texture);

void LatteTextureLoader_writeReadbackTextureToMemory(LatteTextureDefinition* textureData, uint32 sliceIndex, uint32 mipIndex, uint8* linearPixelData);

sint32 LatteTexture_getEffectiveWidth(LatteTexture* texture);
bool LatteTexture_doesEffectiveRescaleRatioMatch(LatteTexture* texture1, sint32 mipLevel1, LatteTexture* texture2, sint32 mipLevel2);
void LatteTexture_scaleToEffectiveSize(LatteTexture* texture, sint32* x, sint32* y, sint32 mipLevel);
uint64 LatteTexture_getNextUpdateEventCounter();

void LatteTexture_UpdateCacheFromDynamicTextures(LatteTexture* texture);
void LatteTexture_MarkConnectedTexturesForReloadFromDynamicTextures(LatteTexture* texture);
void LatteTexture_TrackTextureGPUWrite(LatteTexture* texture, uint32 slice, uint32 mip, uint64 eventCounter);

void LatteTexture_InitSliceAndMipInfo(LatteTexture* texture);
void LatteTexture_RegisterTextureMemoryOccupancy(LatteTexture* texture);
void LatteTexture_UnregisterTextureMemoryOccupancy(LatteTexture* texture);

void LatteTexture_DeleteTextureRelations(LatteTexture* texture);
void LatteTexture_DeleteDataOverlapTracking(LatteTexture* texture);

LatteTextureView* LatteTexture_CreateMapping(MPTR physAddr, MPTR physMipAddr, sint32 width, sint32 height, sint32 depth, sint32 pitch, Latte::E_HWTILEMODE tileMode, uint32 swizzle, sint32 firstMip, sint32 numMip, sint32 firstSlice, sint32 numSlice, Latte::E_GX2SURFFMT format, Latte::E_DIM dimBase, Latte::E_DIM dimView, bool isDepth, bool allowCreateNewDataTexture = true);

LatteTextureView* LatteTC_LookupTextureByData(MPTR physAddr, sint32 width, sint32 height, sint32 pitch, sint32 firstMip, sint32 numMip, sint32 firstSlice, sint32 numSlice, sint32* searchIndex);
void LatteTC_LookupTexturesByPhysAddr(MPTR physAddr, std::vector<LatteTexture*>& list_textures);

LatteTextureView* LatteTC_GetTextureSliceViewOrTryCreate(MPTR srcImagePtr, MPTR srcMipPtr, Latte::E_GX2SURFFMT srcFormat, Latte::E_HWTILEMODE srcTileMode, uint32 srcWidth, uint32 srcHeight, uint32 srcDepth, uint32 srcPitch, uint32 srcSwizzle, uint32 srcSlice, uint32 srcMip, const bool requireExactResolution = false);

void LatteTexture_MarkDynamicTextureAsChanged(LatteTextureView* textureView, sint32 sliceIndex, sint32 mipIndex, uint64 eventCounter);
void LatteTexture_UpdateTextureFromDynamicChanges(LatteTexture* texture);

void LatteTexture_UpdateDataToLatest(LatteTexture* texture);

// NesStation R3: 全局内部分辨率缩放 —— 渲染目标纹理按比例创建（等效自动
// [TextureRedefine] 规则）：scale<1 真实降低游戏内部渲染分辨率（填充率/
// 带宽按 scale^2 下降），scale>1 超采样；视口/剪裁/fragCoord/拷贝由
// GetEffectiveSize 管线自动联动，最终输出画面大小不变。
void LatteTexture_SetInternalRenderScale(float scale); // 任意线程：记录待生效比例
void LatteTexture_SetShadowRenderScale(float scale);   // NesStation R4: 阴影图比例（独立深度目标，1.0=原生）
void LatteTexture_SetAuxRenderScale(float scale);      // NesStation R4: 辅助小目标（泛光/光照）比例，1.0=原生
bool LatteTexture_ApplyPendingInternalRenderScale();   // 仅 GPU 线程帧边界调用：生效 + 重建纹理缓存
void LatteTexture_ResetRtScaleTracking();              // NesStation R4: 清空缩放追踪（换游戏/重建缓存时调用）
// NesStation R4: 渲染目标缩放分类提示 —— 包住渲染目标纹理创建点（同线程内配对调用）。
// 全局降分辨率不再波及阴影图（阴影发黑根因：阴影图被同步缩小后深度自遮挡
// 走样/比较精度下降，表现为阴影越来越黑），主帧缓冲深度-颜色对保持同比例。
#define RT_HINT_NONE       0 // 非渲染目标（普通纹理，不缩放）
#define RT_HINT_COLOR      1 // 颜色渲染目标（全局比例；辅助小目标叠加光照档位）
#define RT_HINT_DEPTH_MAIN 2 // 与活跃颜色缓冲配对的主深度缓冲（全局比例，附件尺寸一致性）
#define RT_HINT_SHADOW     3 // 独立深度目标（阴影图/深度 prepass —— 只跟随阴影档位）
void LatteTexture_SetRTCreationHint(int hint);
// NesStation R4: 帧率限制 —— 自定义 vsync 频率（定义于 LatteTiming.cpp）。
// 翻转锁步游戏按该频率节流，GPU/CPU 占用随帧率线性下降（省电利器）。
void LatteTiming_setCustomVsyncFrequency(sint32 frequency);
void LatteTiming_disableCustomVsyncFrequency();