#pragma once

#include <atomic>

struct LatteSurfaceCopyParam;
struct LatteSurfaceCopyRect;

// savestate support: while set, the GPU thread parks in its idle loop and does
// not touch emulated memory (used while a snapshot overwrites/restores memory)
extern std::atomic<bool> g_savestateFreezeGpuThread;

void LatteAsyncCommands_queueForceTextureReadback(MPTR physAddr, MPTR mipAddr, uint32 swizzle, sint32 format, sint32 width, sint32 height, sint32 depth, uint32 pitch, uint32 slice, sint32 dim, Latte::E_HWTILEMODE tilemode, sint32 aa, sint32 level);
void LatteAsyncCommand_queueTextureCopy(const LatteSurfaceCopyParam& src, const LatteSurfaceCopyParam& dst, const LatteSurfaceCopyRect& rect);
void LatteAsyncCommands_queueDeleteShader(uint64 shaderBaseHash, uint64 shaderAuxHash, LatteConst::ShaderType shaderType);
// savestate support: runs on the GPU thread via the async command queue.
// Always commits all pending occlusion queries and texture readbacks into
// emulated memory. If unloadCaches is set, additionally drops every cached
// GPU resource (textures, vertex/index buffers, shader hashes) so that all
// data is re-uploaded from the current (restored) emulated memory afterwards.
void LatteAsyncCommands_queueSaveStateGpuSync(bool unloadCaches);
void LatteAsyncCommands_waitUntilAllProcessed();
bool LatteAsyncCommands_waitUntilAllProcessedWithTimeout(uint32 timeoutMs);

void LatteAsyncCommands_checkAndExecute();
