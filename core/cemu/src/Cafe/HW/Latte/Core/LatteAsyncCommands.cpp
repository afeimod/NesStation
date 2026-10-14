#include "Cafe/HW/Latte/Renderer/Renderer.h" // must precede LatteIndices.h (Renderer::INDEX_TYPE in signatures)
#include "Cafe/HW/Latte/Core/Latte.h"
#include "Cafe/HW/Latte/Core/LatteAsyncCommands.h"
#include "Cafe/HW/Latte/Core/LatteShader.h"
#include "Cafe/HW/Latte/Core/LatteSurfaceCopy.h"
#include "Cafe/HW/Latte/Core/LatteTexture.h"
#include "Cafe/HW/Latte/Core/LatteBufferCache.h"
#include "Cafe/HW/Latte/Core/LatteIndices.h"
#include <chrono>
#include <thread>

void LatteThread_Exit();

SlimRWLock swl_gpuAsyncCommands;

typedef struct  
{
        uint32 type;
        union
        {
                struct
                {
                        MPTR physAddr;
                        MPTR mipAddr;
                        uint32 swizzle;
                        sint32 format;
                        sint32 width;
                        sint32 height;
                        sint32 depth;
                        uint32 pitch;
                        uint32 slice;
                        sint32 dim;
                        Latte::E_HWTILEMODE tilemode;
                        sint32 aa;
                        sint32 level;
                }forceTextureReadback;

                struct
                {
                        uint64 shaderBaseHash; 
                        uint64 shaderAuxHash; 
                        LatteConst::ShaderType shaderType;
                }deleteShader;

                struct
                {
                        LatteSurfaceCopyParam src;
                        LatteSurfaceCopyParam dst;
                        LatteSurfaceCopyRect rect;
                }textureCopy;

                struct
                {
                        uint32 unloadCaches;
                }savestateSync;
        };
}LatteAsyncCommand_t;

#define ASYNC_CMD_FORCE_TEXTURE_READBACK                1
#define ASYNC_CMD_DELETE_SHADER                                 2
#define ASYNC_CMD_TEXTURE_COPY                                  3
#define ASYNC_CMD_SAVESTATE_GPU_SYNC                    4

std::queue<LatteAsyncCommand_t> LatteAsyncCommandQueue;

std::atomic<bool> g_savestateFreezeGpuThread{ false };

void LatteAsyncCommands_queueForceTextureReadback(MPTR physAddr, MPTR mipAddr, uint32 swizzle, sint32 format, sint32 width, sint32 height, sint32 depth, uint32 pitch, uint32 slice, sint32 dim, Latte::E_HWTILEMODE tilemode, sint32 aa, sint32 level)
{
        LatteAsyncCommand_t asyncCommand = {};
        // setup command
        asyncCommand.type = ASYNC_CMD_FORCE_TEXTURE_READBACK;
        
        asyncCommand.forceTextureReadback.physAddr = physAddr;
        asyncCommand.forceTextureReadback.mipAddr = mipAddr;
        asyncCommand.forceTextureReadback.swizzle = swizzle;
        asyncCommand.forceTextureReadback.format = format;
        asyncCommand.forceTextureReadback.width = width;
        asyncCommand.forceTextureReadback.height = height;
        asyncCommand.forceTextureReadback.depth = depth;
        asyncCommand.forceTextureReadback.pitch = pitch;
        asyncCommand.forceTextureReadback.slice = slice;
        asyncCommand.forceTextureReadback.dim = dim;
        asyncCommand.forceTextureReadback.tilemode = tilemode;
        asyncCommand.forceTextureReadback.aa = aa;
        asyncCommand.forceTextureReadback.level = level;
        swl_gpuAsyncCommands.LockWrite();
        LatteAsyncCommandQueue.push(asyncCommand);
        swl_gpuAsyncCommands.UnlockWrite();
}

void LatteAsyncCommands_queueDeleteShader(uint64 shaderBaseHash, uint64 shaderAuxHash, LatteConst::ShaderType shaderType)
{
        LatteAsyncCommand_t asyncCommand = {};
        // setup command
        asyncCommand.type = ASYNC_CMD_DELETE_SHADER;

        asyncCommand.deleteShader.shaderBaseHash = shaderBaseHash;
        asyncCommand.deleteShader.shaderAuxHash = shaderAuxHash;
        asyncCommand.deleteShader.shaderType = shaderType;

        swl_gpuAsyncCommands.LockWrite();
        LatteAsyncCommandQueue.push(asyncCommand);
        swl_gpuAsyncCommands.UnlockWrite();
}

void LatteAsyncCommand_queueTextureCopy(const LatteSurfaceCopyParam& src, const LatteSurfaceCopyParam& dst, const LatteSurfaceCopyRect& rect)
{
        LatteAsyncCommand_t asyncCommand = {};
        // setup command
        asyncCommand.type = ASYNC_CMD_TEXTURE_COPY;
        asyncCommand.textureCopy.src = src;
        asyncCommand.textureCopy.dst = dst;
        asyncCommand.textureCopy.rect = rect;

        swl_gpuAsyncCommands.LockWrite();
        LatteAsyncCommandQueue.push(asyncCommand);
        swl_gpuAsyncCommands.UnlockWrite();
}

void LatteAsyncCommands_queueSaveStateGpuSync(bool unloadCaches)
{
        LatteAsyncCommand_t asyncCommand = {};
        asyncCommand.type = ASYNC_CMD_SAVESTATE_GPU_SYNC;
        asyncCommand.savestateSync.unloadCaches = unloadCaches ? 1 : 0;
        swl_gpuAsyncCommands.LockWrite();
        LatteAsyncCommandQueue.push(asyncCommand);
        swl_gpuAsyncCommands.UnlockWrite();
}

void LatteAsyncCommands_waitUntilAllProcessed()
{
        while (LatteAsyncCommandQueue.empty() == false)
        {
                _mm_pause();
        }
}

// bounded variant for the savestate paths: returns false when the GPU
// command processor did not drain the queue within the timeout, so a
// parked or wedged GPU thread can never hang the caller forever
bool LatteAsyncCommands_waitUntilAllProcessedWithTimeout(uint32 timeoutMs)
{
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs);
        while (LatteAsyncCommandQueue.empty() == false)
        {
                if (std::chrono::steady_clock::now() >= deadline)
                        return false;
                std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
        return true;
}

/*
 * Called by the GPU command processor frequently
 */
void LatteAsyncCommands_checkAndExecute()
{
        // quick check if queue is empty (requires no lock)
        if (Latte_GetStopSignal())
                LatteThread_Exit();
        if (LatteAsyncCommandQueue.empty())
                return;
        swl_gpuAsyncCommands.LockWrite();
        while (LatteAsyncCommandQueue.empty() == false)
        {
                // get first command in queue
                LatteAsyncCommand_t asyncCommand = LatteAsyncCommandQueue.front();
                swl_gpuAsyncCommands.UnlockWrite();
                if (asyncCommand.type == ASYNC_CMD_FORCE_TEXTURE_READBACK)
                {
                        cemu_assert_debug(asyncCommand.forceTextureReadback.level == 0); // implement mip swizzle and verify
                        LatteTextureView* textureView = LatteTC_GetTextureSliceViewOrTryCreate(asyncCommand.forceTextureReadback.physAddr, asyncCommand.forceTextureReadback.mipAddr, (Latte::E_GX2SURFFMT)asyncCommand.forceTextureReadback.format, asyncCommand.forceTextureReadback.tilemode, asyncCommand.forceTextureReadback.width, asyncCommand.forceTextureReadback.height, asyncCommand.forceTextureReadback.depth, asyncCommand.forceTextureReadback.pitch, 0, asyncCommand.forceTextureReadback.slice, asyncCommand.forceTextureReadback.level);
                        if (textureView != nullptr)
                        {
                                LatteTexture_UpdateDataToLatest(textureView->baseTexture);
                                // start transfer
                                LatteTextureReadback_StartTransfer(textureView);
                                // wait until finished
                                LatteTextureReadback_UpdateFinishedTransfers(true);
                        }
                        else
                        {
                                cemuLog_logDebug(LogType::Force, "Texture not found for readback");
                        }
                }
                else if (asyncCommand.type == ASYNC_CMD_DELETE_SHADER)
                {
                        LatteSHRC_RemoveFromCacheByHash(asyncCommand.deleteShader.shaderBaseHash, asyncCommand.deleteShader.shaderAuxHash, asyncCommand.deleteShader.shaderType);
                }
                else if (asyncCommand.type == ASYNC_CMD_TEXTURE_COPY)
                {
                        LatteSurfaceCopy_copySurfaceNew(asyncCommand.textureCopy.src, asyncCommand.textureCopy.dst, asyncCommand.textureCopy.rect);
                }
                else if (asyncCommand.type == ASYNC_CMD_SAVESTATE_GPU_SYNC)
                {
                        LatteQuery_UpdateFinishedQueriesForceFinishAll();
                        LatteTextureReadback_UpdateFinishedTransfers(true);
                        if (asyncCommand.savestateSync.unloadCaches != 0)
                        {
                                LatteTC_UnloadAllTextures();
                                LatteBufferCache_UnloadAll();
                                LatteSHRC_ResetCachedShaderHash();
                                LatteIndices_invalidateAll();
                        }
                }
                else
                {
                        cemu_assert_unimplemented();
                }
                swl_gpuAsyncCommands.LockWrite();
                LatteAsyncCommandQueue.pop();
        }
        swl_gpuAsyncCommands.UnlockWrite();
}
