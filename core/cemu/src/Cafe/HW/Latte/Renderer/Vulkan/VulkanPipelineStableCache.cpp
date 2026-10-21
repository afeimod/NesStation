// ★★★ NesStation v16 —— 本文件为上游 afeimod/Cemu-AZ（SSimco Android 移植）
//   VulkanPipelineStableCache.cpp 的覆盖件：除下方 UpdateLoading() 内的
//   Android 启动期预编译门控外，其余代码与上游逐字节一致（上游与
//   cemu-project/Cemu main 完全一致，已 diff 验证）。
//
//   修复背景（上游已知问题 cemu-project/Cemu#396 同类）：boot 阶段
//   "Loading cached pipelines... x/y" 会把 transferable/{titleId}_vkpipeline.bin
//   里的全量缓存管线逐条反序列化并喂给当前驱动的 vkCreateGraphicsPipelines
//   （最多 8 线程并发）。管线条目跨会话只增不减，驱动内部缓存结构随之增长
//   （实测 1147 条加载至 82% 时进程 RSS 449->908MB），超过移动设备承受
//   阈值后在驱动内 SIGSEGV —— 桌面端同样存在（约 4180 条阈值），上游社区
//   的既有 workaround 即绕过该阶段；PR #1211 曾提议同款禁用。
//
//   Android 门控（仅 BOOST_PLAT_ANDROID 生效）：启动期不再批量重编译缓存
//   管线 —— 管线改由运行期首次绘制时按上游 async_compile 语义创建，
//   跨启动的快速重建由驱动级 VkPipelineCache（shaderCache/driver/vk，
//   pipelineCacheUUID 由驱动校验，异驱动数据自动拒绝）提供。transferable
//   文件保持打开，运行期新管线照常写入（FileCache 按内容哈希覆盖同条目，
//   不会膨胀）；不删除任何缓存（上游从不删除缓存）。

#include "Cafe/HW/Latte/Renderer/Vulkan/VulkanRenderer.h"
#include "Cafe/HW/Latte/Renderer/Vulkan/VulkanPipelineCompiler.h"
#include "Cafe/HW/Latte/Renderer/Vulkan/VulkanPipelineStableCache.h"
#include "Cafe/HW/Latte/Core/LatteShader.h"
#include "Cafe/HW/Latte/Core/LattePerformanceMonitor.h"
#include "Cafe/HW/Latte/Core/LatteCachedFBO.h"
#include "Cafe/OS/libs/gx2/GX2.h"
#include "config/ActiveSettings.h"
#include "util/helpers/Serializer.h"
#include "Cafe/HW/Latte/Common/RegisterSerializer.h"
#include "Cemu/FileCache/FileCache.h"
#include "Cafe/HW/Latte/Core/LatteShaderCache.h"
#include "util/helpers/helpers.h"
#include <openssl/sha.h>

struct
{
	uint32 pipelineLoadIndex;
	uint32 pipelineMaxFileIndex;
	
	std::atomic_uint32_t pipelinesQueued;
	std::atomic_uint32_t pipelinesLoaded;
}g_vkCacheState;

VulkanPipelineStableCache g_vkPipelineStableCacheInstance;

VulkanPipelineStableCache& VulkanPipelineStableCache::GetInstance()
{
	return g_vkPipelineStableCacheInstance;
}

uint32 VulkanPipelineStableCache::BeginLoading(uint64 cacheTitleId)
{
	std::error_code ec;
	fs::create_directories(ActiveSettings::GetCachePath("shaderCache/transferable"), ec);
	const auto pathCacheFile = ActiveSettings::GetCachePath("shaderCache/transferable/{:016x}_vkpipeline.bin", cacheTitleId);
	
	// init cache loader state
	g_vkCacheState.pipelineLoadIndex = 0;
	g_vkCacheState.pipelineMaxFileIndex = 0;
	g_vkCacheState.pipelinesLoaded = 0;
	g_vkCacheState.pipelinesQueued = 0;
	
	// start async compilation threads
	m_compilationCount.store(0);	
	m_compilationQueue.clear();

	// get core count
	uint32 cpuCoreCount = GetPhysicalCoreCount();
	m_numCompilationThreads = std::clamp(cpuCoreCount, 1u, 8u);
	if (VulkanRenderer::GetInstance()->GetDisableMultithreadedCompilation())
		m_numCompilationThreads = 1;

	for (uint32 i = 0; i < m_numCompilationThreads; i++)
	{
		std::thread compileThread(&VulkanPipelineStableCache::CompilerThread, this);
		compileThread.detach();
	}

	// open cache file or create it
	cemu_assert_debug(s_cache == nullptr);
	s_cache = FileCache::Open(pathCacheFile, true, LatteShaderCache_getPipelineCacheExtraVersion(cacheTitleId));
	if (!s_cache)
	{
		cemuLog_log(LogType::Force, "Failed to open or create Vulkan pipeline cache file: {}", _pathToUtf8(pathCacheFile));
		return 0;
	}
	else
	{
		s_cache->UseCompression(false);
		g_vkCacheState.pipelineMaxFileIndex = s_cache->GetMaximumFileIndex();
	}
	return s_cache->GetFileCount();
}

bool VulkanPipelineStableCache::UpdateLoading(uint32& pipelinesLoadedTotal, uint32& pipelinesMissingShaders)
{
#if BOOST_PLAT_ANDROID
	// ★ NesStation v16: Android 不做启动期批量管线预编译（上游 #396 同类
	//   加载阶段闪退的根治点）。立即返回完成：进度循环不渲染任何帧，
	//   EndLoading() 随后照常关闭编译线程，s_cache 保持打开供运行期
	//   AddFileAsync 写入新管线。管线在首次绘制时按上游 async_compile
	//   语义创建（驱动级 VkPipelineCache 提供跨启动快速命中）。
	pipelinesLoadedTotal = g_vkCacheState.pipelinesQueued;
	pipelinesMissingShaders = 0;
	return false;
#else
	pipelinesLoadedTotal = g_vkCacheState.pipelinesLoaded;
	pipelinesMissingShaders = 0;
	while (g_vkCacheState.pipelineLoadIndex <= g_vkCacheState.pipelineMaxFileIndex)
	{
		if (m_compilationQueue.size() >= 50)
		{
			std::this_thread::sleep_for(std::chrono::milliseconds(10));
			return true; // queue up to 50 entries at a time
		}

		uint64 fileNameA, fileNameB;
		std::vector<uint8> fileData;
		if (s_cache->GetFileByIndex(g_vkCacheState.pipelineLoadIndex, &fileNameA, &fileNameB, fileData))
		{
			// queue for async compilation
			g_vkCacheState.pipelinesQueued++;
			m_compilationQueue.push(std::move(fileData));
			g_vkCacheState.pipelineLoadIndex++;
			return true;
		}
		g_vkCacheState.pipelineLoadIndex++;
	}
	if (g_vkCacheState.pipelinesLoaded != g_vkCacheState.pipelinesQueued)
	{
		std::this_thread::sleep_for(std::chrono::milliseconds(10));
		return true; // pipelines still compiling
	}
	return false; // done
#endif // BOOST_PLAT_ANDROID
}

void VulkanPipelineStableCache::EndLoading()
{
	// shut down compilation threads
	uint32 threadCount = m_numCompilationThreads;
	m_numCompilationThreads = 0; // signal thread shutdown
	for (uint32 i = 0; i < threadCount; i++)
	{
		m_compilationQueue.push({}); // push empty workload for every thread. Threads then will shutdown after checking for m_numCompilationThreads == 0
	}
	// keep cache file open for writing of new pipelines
}

void VulkanPipelineStableCache::Close()
{
    if(s_cache)
    {
        delete s_cache;
        s_cache = nullptr;
    }
}

struct CachedPipeline
{
	struct ShaderHash
	{
		uint64 baseHash;
		uint64 auxHash;
		bool isPresent{};

		void set(uint64 baseHash, uint64 auxHash)
		{
			this->baseHash = baseHash;
			this->auxHash = auxHash;
			this->isPresent = true;
		}
	};

	ShaderHash vsHash; // includes fetch shader
	ShaderHash gsHash;
	ShaderHash psHash;

	Latte::GPUCompactedRegisterState gpuState;
};

VkFormat __getColorBufferVkFormat(const uint32 index, const LatteContextRegister& lcr)
{
	Latte::E_GX2SURFFMT colorBufferFormat = LatteMRT::GetColorBufferFormat(index, lcr);
	VulkanRenderer::FormatInfoVK texFormatInfo;
	VulkanRenderer::GetInstance()->GetTextureFormatInfoVK(colorBufferFormat, false, Latte::E_DIM::DIM_2D, 1280, 720, &texFormatInfo);
	return texFormatInfo.vkImageFormat;
}

void __getDepthBufferVkFormat(const LatteContextRegister& lcr, VkFormat& dbFormat, bool& hasStencil)
{
	Latte::E_GX2SURFFMT format = LatteMRT::GetDepthBufferFormat(lcr);
	VulkanRenderer::FormatInfoVK texFormatInfo;
	VulkanRenderer::GetInstance()->GetTextureFormatInfoVK(format, true, Latte::E_DIM::DIM_2D, 1280, 720, &texFormatInfo);
	dbFormat = texFormatInfo.vkImageFormat;
	hasStencil = (texFormatInfo.vkImageAspect & VK_IMAGE_ASPECT_STENCIL_BIT) != 0;
}

// create placeholder renderpass for cached pipeline
VKRObjectRenderPass* __CreateTemporaryRenderPass(const LatteDecompilerShader* pixelShader, const LatteContextRegister& lcr)
{
	VKRObjectRenderPass::AttachmentInfo_t attachmentInfo;

	uint8 cbMask = LatteMRT::GetActiveColorBufferMask(pixelShader, lcr);
	bool dbMask = LatteMRT::GetActiveDepthBufferMask(lcr);

	for (int i = 0; i < 8; ++i)
	{
		if ((cbMask & (1 << i)) == 0)
		{
			attachmentInfo.colorAttachment[i].viewObj = nullptr;
			continue;
		}
		// setup color attachment
		attachmentInfo.colorAttachment[i].viewObj = nullptr;
		attachmentInfo.colorAttachment[i].isPresent = true;
		attachmentInfo.colorAttachment[i].format = __getColorBufferVkFormat(i, lcr);
	}

	// setup depth attachment
	if (dbMask)
	{
		attachmentInfo.depthAttachment.viewObj = nullptr;
		attachmentInfo.depthAttachment.isPresent = true;
		VkFormat dbFormat;
		bool hasStencil;
		__getDepthBufferVkFormat(lcr, dbFormat, hasStencil);
		attachmentInfo.depthAttachment.format = dbFormat;
		attachmentInfo.depthAttachment.hasStencil = hasStencil;
	}
	else
	{
		// no depth attachment
		attachmentInfo.depthAttachment.viewObj = nullptr;
		attachmentInfo.depthAttachment.isPresent = false;
	}

	return new VKRObjectRenderPass(attachmentInfo);
}

void VulkanPipelineStableCache::LoadPipelineFromCache(std::span<uint8> fileData)
{
	static FSpinlock s_spinlockSharedInternal;

	// deserialize file
	LatteContextRegister* lcr = new LatteContextRegister();
	s_spinlockSharedInternal.lock();
	CachedPipeline* cachedPipeline = new CachedPipeline();
	s_spinlockSharedInternal.unlock();

	MemStreamReader streamReader(fileData.data(), fileData.size());
	if (!DeserializePipeline(streamReader, *cachedPipeline))
	{
		// failed to deserialize
		s_spinlockSharedInternal.lock();
		delete lcr;
		delete cachedPipeline;
		s_spinlockSharedInternal.unlock();
		return;
	}
	// restored register view from compacted state
	Latte::LoadGPURegisterState(*lcr, cachedPipeline->gpuState);

	LatteDecompilerShader* vertexShader = nullptr;
	LatteDecompilerShader* geometryShader = nullptr;
	LatteDecompilerShader* pixelShader = nullptr;
	// find vertex shader
	if (cachedPipeline->vsHash.isPresent)
	{
		vertexShader = LatteSHRC_FindVertexShader(cachedPipeline->vsHash.baseHash, cachedPipeline->vsHash.auxHash);
		if (!vertexShader)
		{
			cemuLog_logDebug(LogType::Force, "Vertex shader not found in cache");
			return;
		}
	}
	// find geometry shader
	if (cachedPipeline->gsHash.isPresent)
	{
		geometryShader = LatteSHRC_FindGeometryShader(cachedPipeline->gsHash.baseHash, cachedPipeline->gsHash.auxHash);
		if (!geometryShader)
		{
			cemuLog_logDebug(LogType::Force, "Geometry shader not found in cache");
			return;
		}
	}
	// find pixel shader
	if (cachedPipeline->psHash.isPresent)
	{
		pixelShader = LatteSHRC_FindPixelShader(cachedPipeline->psHash.baseHash, cachedPipeline->psHash.auxHash);
		if (!pixelShader)
		{
			cemuLog_logDebug(LogType::Force, "Pixel shader not found in cache");
			return;
		}
	}
	// create temporary renderpass
	if (!pixelShader)
	{
		cemu_assert_debug(false);
		return;
	}
	auto renderPass = __CreateTemporaryRenderPass(pixelShader, *lcr);
	// create pipeline info
	m_pipelineIsCachedLock.lock();
	PipelineInfo* pipelineInfo = new PipelineInfo(0, 0, vertexShader->compatibleFetchShader, vertexShader, pixelShader, geometryShader);
	m_pipelineIsCachedLock.unlock();
	// compile
	{
		PipelineCompiler pipelineCompiler;
		bool requiresRobustBufferAccess = PipelineCompiler::CalcRobustBufferAccessRequirement(vertexShader, pixelShader, geometryShader);
		if (!pipelineCompiler.InitFromCurrentGPUState(pipelineInfo, *lcr, renderPass, requiresRobustBufferAccess))
		{
			s_spinlockSharedInternal.lock();
			delete lcr;
			delete cachedPipeline;
			s_spinlockSharedInternal.unlock();
			return;
		}
		pipelineCompiler.Compile(true, true, false);
	}
	// on success, calculate pipeline hash and flag as present in cache
	uint64 pipelineBaseHash = vertexShader->baseHash;
	uint64 pipelineStateHash = VulkanRenderer::draw_calculateGraphicsPipelineHash(vertexShader->compatibleFetchShader, vertexShader, geometryShader, pixelShader, renderPass, *lcr);
	m_pipelineIsCachedLock.lock();
	m_pipelineIsCached.emplace(pipelineBaseHash, pipelineStateHash);
	m_pipelineIsCachedLock.unlock();
	// clean up
	s_spinlockSharedInternal.lock();
	delete pipelineInfo;
	delete lcr;
	delete cachedPipeline;
	VulkanRenderer::GetInstance()->ReleaseDestructibleObject(renderPass);
	s_spinlockSharedInternal.unlock();
}

bool VulkanPipelineStableCache::HasPipelineCached(uint64 baseHash, uint64 pipelineStateHash)
{
	PipelineHash ph(baseHash, pipelineStateHash);
	return m_pipelineIsCached.find(ph) != m_pipelineIsCached.end();
}

ConcurrentQueue<CachedPipeline*> g_pipelineCachingQueue;

void VulkanPipelineStableCache::AddCurrentStateToCache(uint64 baseHash, uint64 pipelineStateHash)
{
	m_pipelineIsCached.emplace(baseHash, pipelineStateHash);
	if (!m_pipelineCacheStoreThread)
	{
		m_pipelineCacheStoreThread = new std::thread(&VulkanPipelineStableCache::WorkerThread, this);
		m_pipelineCacheStoreThread->detach();
	}
	// fill job structure with cached GPU state
	// for each cached pipeline we store:
	// - Active shaders (referenced by hash)
	// - An almost-complete register state of the GPU (minus some ALU uniform constants which aren't relevant)
	CachedPipeline* job = new CachedPipeline();
	auto vs = LatteSHRC_GetActiveVertexShader();
	auto gs = LatteSHRC_GetActiveGeometryShader();
	auto ps = LatteSHRC_GetActivePixelShader();
	if (vs)
		job->vsHash.set(vs->baseHash, vs->auxHash);
	if (gs)
		job->gsHash.set(gs->baseHash, gs->auxHash);
	if (ps)
		job->psHash.set(ps->baseHash, ps->auxHash);
	Latte::StoreGPURegisterState(LatteGPUState.contextNew, job->gpuState);
	// queue job
	g_pipelineCachingQueue.push(job);
}

bool VulkanPipelineStableCache::SerializePipeline(MemStreamWriter& memWriter, CachedPipeline& cachedPipeline)
{
	memWriter.writeBE<uint8>(0x01); // version
	uint8 presentMask = 0;
	if (cachedPipeline.vsHash.isPresent)
		presentMask |= 1;
	if (cachedPipeline.gsHash.isPresent)
		presentMask |= 2;
	if (cachedPipeline.psHash.isPresent)
		presentMask |= 4;
	memWriter.writeBE<uint8>(presentMask);
	if (cachedPipeline.vsHash.isPresent)
	{
		memWriter.writeBE<uint64>(cachedPipeline.vsHash.baseHash);
		memWriter.writeBE<uint64>(cachedPipeline.vsHash.auxHash);
	}
	if (cachedPipeline.gsHash.isPresent)
	{
		memWriter.writeBE<uint64>(cachedPipeline.gsHash.baseHash);
		memWriter.writeBE<uint64>(cachedPipeline.gsHash.auxHash);
	}
	if (cachedPipeline.psHash.isPresent)
	{
		memWriter.writeBE<uint64>(cachedPipeline.psHash.baseHash);
		memWriter.writeBE<uint64>(cachedPipeline.psHash.auxHash);
	}
	Latte::SerializeRegisterState(cachedPipeline.gpuState, memWriter);
	return true;
}

bool VulkanPipelineStableCache::DeserializePipeline(MemStreamReader& memReader, CachedPipeline& cachedPipeline)
{
	// version
	if (memReader.readBE<uint8>() != 1)
	{
		cemuLog_log(LogType::Force, "Cached Vulkan pipeline corrupted or has unknown version");
		return false;
	}
	// shader hashes
	uint8 presentMask = memReader.readBE<uint8>();
	if (presentMask & 1)
	{
		uint64 baseHash = memReader.readBE<uint64>();
		uint64 auxHash = memReader.readBE<uint64>();
		cachedPipeline.vsHash.set(baseHash, auxHash);
	}
	if (presentMask & 2)
	{
		uint64 baseHash = memReader.readBE<uint64>();
		uint64 auxHash = memReader.readBE<uint64>();
		cachedPipeline.gsHash.set(baseHash, auxHash);
	}
	if (presentMask & 4)
	{
		uint64 baseHash = memReader.readBE<uint64>();
		uint64 auxHash = memReader.readBE<uint64>();
		cachedPipeline.psHash.set(baseHash, auxHash);
	}
	// deserialize GPU state
	if (!Latte::DeserializeRegisterState(cachedPipeline.gpuState, memReader))
	{
		return false;
	}
	cemu_assert_debug(!memReader.hasError());
	return true;
}

int VulkanPipelineStableCache::CompilerThread()
{
	SetThreadName("plCacheCompiler");
	while (m_numCompilationThreads != 0)
	{
		std::vector<uint8> pipelineData = m_compilationQueue.pop();
		if(pipelineData.empty())
			continue;
		LoadPipelineFromCache(pipelineData);
		++g_vkCacheState.pipelinesLoaded;
	}
	return 0;
}

void VulkanPipelineStableCache::WorkerThread()
{
	SetThreadName("plCacheWriter");
	while (true)
	{
		CachedPipeline* job;
		g_pipelineCachingQueue.pop(job);
		if (!s_cache)
		{
			delete job;
			continue;
		}
		// serialize
		MemStreamWriter memWriter(1024 * 4);
		SerializePipeline(memWriter, *job);
		auto blob = memWriter.getResult();
		// file name is derived from data hash
		uint8 hash[SHA256_DIGEST_LENGTH];
		SHA256(blob.data(), blob.size(), hash);
		uint64 nameA = *(uint64be*)(hash + 0);
		uint64 nameB = *(uint64be*)(hash + 8);
		s_cache->AddFileAsync({ nameA, nameB }, blob.data(), blob.size());
		delete job;
	}
}
