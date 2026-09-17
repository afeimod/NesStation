/*
    Copyright 2016-2025 melonDS team

    This file is part of melonDS.

    melonDS is free software: you can redistribute it and/or modify it under
    the terms of the GNU General Public License as published by the Free
    Software Foundation, either version 3 of the License, or (at your option)
    any later version.

    melonDS is distributed in the hope that it will be useful, but WITHOUT ANY
    WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
    FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
    details.

    You should have received a copy of the GNU General Public License along
    with melonDS. If not, see http://www.gnu.org/licenses/.
*/

#include "ARMJIT_Global.h"
#include "ARMJIT_Memory.h"

#ifdef _WIN32
#include <windows.h>
#else
#include <sys/mman.h>
#include <unistd.h>
#endif

#include <stdio.h>
#include <stdint.h>

#include <mutex>

// ---------------------------------------------------------------------------
// 修复（"JIT 好像也没作用"）—— 仅影响 Android arm64（本项目唯一启用
// JIT_ENABLED 的 ABI），其余平台保留上游原实现，零行为变化。
//
// 旧实现对静态 CodeMemory[] 数组 mprotect 成 RWX（可写+可执行）。在
// targetSdkVersion >= 31 的应用里，Android 12+ 的 W^X 强制策略会拒绝
// 一切"同时可写且可执行"的内存（mprotect/mmap + PROT_WRITE|PROT_EXEC
// 一律 EPERM），于是：
//   1) mprotect(RWX) 失败 → 代码页不可执行，重编译块无法运行；
//   2) 前端的 ProbeCodeMemory() 探测同样失败 → JIT 被静默降级为解释器。
// 用户在设置里打开 JIT 也完全无感 —— 即"JIT 好像也没作用"的根因。
//
// Android 12+ 并不禁止"只读+执行"(RX) 映射，只禁止 RWX。因此对
// Android arm64 采用与 ART / Dart VM 相同的 W^X 兼容方案 —— memfd
// 双映射：
//   * memfd_create 建立一块匿名内存文件；
//   * 两次 mmap 映射同一块物理内存：RW 视图（编译器写入）+ RX 视图
//     （CPU 执行），任何时刻都不存在 RWX 页面；
//   * melonDS 的 ARM64XEmitter 本来就支持 RW/RX 分离基址
//     （SetCodeBase(rwbase, rxbase)，Switch 平台即如此使用），
//     icache 刷新已经走 rxbase —— 双映射零改动接入。
//
// 回退链（老内核 / 老策略设备保持旧行为）：
//   1. memfd 双映射（Android 12+ / W^X 设备的正确路径）
//   2. 匿名 mmap RWX（旧设备，等效旧 mprotect 方案）
//   3. 都失败 → 池不可用，ProbeCodeMemory() 返回 false，前端禁用 JIT
//      并给出明确日志（解释器兜底，不会再静默）。
//
// x86_64（桌面 Linux/Android）说明：Xbyak 编译器经同一指针写入代码，
// 需要 RWX 单视图。桌面平台不走本文件的池化改动（见下方平台宏）；
// Android x86_64 本就不构建 JIT（JIT_ENABLED 仅 arm64-v8a），且在
// W^X 策略下 RWX 方案本就不可用，无回归。
// ---------------------------------------------------------------------------

#if defined(__APPLE__) && defined(__aarch64__)
#define APPLE_AARCH64
#endif

// W^X 双映射池：仅 Android arm64 启用。
#if defined(__ANDROID__) && defined(__aarch64__) && !defined(APPLE_AARCH64)
#ifndef MELONDS_WX_POOL
#define MELONDS_WX_POOL 1
#endif
#endif

#ifdef _WIN32
// Windows 走 VirtualAlloc RWX，无 W^X 问题。
#else
#if defined(MELONDS_WX_POOL)
#include <fcntl.h>
#include <sys/syscall.h>

#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#endif
#ifndef MFD_ALLOW_SEALING
#define MFD_ALLOW_SEALING 0x0002U
#endif
#ifndef __NR_memfd_create
#define __NR_memfd_create 279 /* aarch64 */
#endif
#endif // MELONDS_WX_POOL
#endif // _WIN32

namespace melonDS
{

namespace ARMJIT_Global
{

std::mutex globalMutex;

#if !defined(APPLE_AARCH64) && !defined(__NetBSD__) && !defined(__OpenBSD__)
static constexpr size_t NumCodeMemSlices = 4;
static constexpr size_t CodeMemoryAlignedSize = NumCodeMemSlices * CodeMemorySliceSize;
static u32 AvailableCodeMemSlices = (1 << NumCodeMemSlices) - 1;

// 上游遗留：静态代码池（BSS）+ mprotect(RWX)。仅非 W^X 平台使用；
// Android arm64 改走 memfd 双映射池（见 MELONDS_WX_POOL）。
#if !defined(MELONDS_WX_POOL)
// I haven't heard of pages larger than 16 KB
u8 CodeMemory[CodeMemoryAlignedSize + 16*1024];

u8* GetAlignedCodeMemoryStart()
{
    return reinterpret_cast<u8*>((reinterpret_cast<intptr_t>(CodeMemory) + (16*1024-1)) & ~static_cast<intptr_t>(16*1024-1));
}
#endif

// ---------------------------------------------------------------------------
// 池状态 —— 两种模式共用同一套切片簿记：
//   PoolMode 1 = memfd 双映射（W^X 兼容；rwMap 可写 / rxMap 可执行）
//   PoolMode 2 = 单视图 RWX 匿名映射（旧设备回退；rwMap == rxMap）
//   PoolMode 3 = 初始化失败（JIT 不可用）
// 非池化平台（Windows / Apple / BSD）两个指针恒为 null，切片簿记
// 保持上游原语义。
// ---------------------------------------------------------------------------
#if defined(MELONDS_WX_POOL)
static u8* rwMap = nullptr;
static u8* rxMap = nullptr;
static size_t PoolSize = 0;
static int PoolMode = 0;

static bool IsPoolSlice(u8* p)
{
    if (!rwMap) return false;
    return p >= rwMap && p < rwMap + PoolSize;
}

// 尝试建立可用的代码内存池（幂等；成功返回 true）。须持有 globalMutex。
static bool EnsurePoolLocked()
{
    if (PoolMode == 1 || PoolMode == 2) return true;
    if (PoolMode == 3) return false;

    // --- 方案 1：memfd 双映射（W^X 兼容，ART/Dart VM 同款方案） ------
    {
        int fd = (int)syscall(__NR_memfd_create, "melonds-jit",
                              (unsigned)(MFD_CLOEXEC | MFD_ALLOW_SEALING));
        if (fd >= 0)
        {
            PoolSize = CodeMemoryAlignedSize;
            if (ftruncate(fd, (off_t)PoolSize) == 0)
            {
                u8* rw = (u8*)mmap(nullptr, PoolSize,
                                   PROT_READ | PROT_WRITE,
                                   MAP_SHARED, fd, 0);
                u8* rx = (u8*)mmap(nullptr, PoolSize,
                                   PROT_READ | PROT_EXEC,
                                   MAP_SHARED, fd, 0);
                if (rw != MAP_FAILED && rx != MAP_FAILED)
                {
                    rwMap = rw;
                    rxMap = rx;
                    PoolMode = 1;
                    close(fd);
                    return true;
                }
                if (rw != MAP_FAILED) munmap(rw, PoolSize);
                if (rx != MAP_FAILED) munmap(rx, PoolSize);
            }
            close(fd);
            // memfd 不可用（内核太老 / 策略拒绝）→ 落到方案 2
        }
    }

    // --- 方案 2：单视图 RWX 匿名映射（旧设备回退，等效旧行为） -------
    {
        PoolSize = CodeMemoryAlignedSize;
        u8* p = (u8*)mmap(nullptr, PoolSize,
                          PROT_READ | PROT_WRITE | PROT_EXEC,
                          MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (p != MAP_FAILED && p != nullptr)
        {
            rwMap = p;
            rxMap = p;
            PoolMode = 2;
            return true;
        }
        PoolSize = 0;
    }

    // --- 都失败：JIT 不可用 ------------------------------------------
    PoolMode = 3;
    return false;
}
#endif // MELONDS_WX_POOL
#endif // !APPLE_AARCH64 && !NetBSD && !OpenBSD

int RefCounter = 0;

void* AllocateCodeMem()
{
    std::lock_guard guard(globalMutex);

#if !defined(APPLE_AARCH64) && !defined(__NetBSD__) && !defined(__OpenBSD__)
#if defined(MELONDS_WX_POOL)
    if (AvailableCodeMemSlices && (PoolMode == 1 || PoolMode == 2))
    {
        int slice = __builtin_ctz(AvailableCodeMemSlices);
        AvailableCodeMemSlices &= ~(1 << slice);
        return &rwMap[slice * CodeMemorySliceSize];
    }
#else
    if (AvailableCodeMemSlices)
    {
        int slice = __builtin_ctz(AvailableCodeMemSlices);
        AvailableCodeMemSlices &= ~(1 << slice);
        //printf("allocating slice %d\n", slice);
        u8* base = GetAlignedCodeMemoryStart();
        return &base[slice * CodeMemorySliceSize];
    }
#endif
#endif

    // allocate
#ifdef _WIN32
    return VirtualAlloc(nullptr, CodeMemorySliceSize, MEM_RESERVE|MEM_COMMIT, PAGE_EXECUTE_READWRITE);
#elif defined(APPLE_AARCH64)
    return mmap(NULL, CodeMemorySliceSize, PROT_READ | PROT_WRITE | PROT_EXEC, MAP_PRIVATE | MAP_ANONYMOUS | MAP_JIT,-1, 0);
#elif defined(__NetBSD__)
    return mmap(nullptr, CodeMemorySliceSize, PROT_MPROTECT(PROT_READ | PROT_WRITE | PROT_EXEC), MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
#else
    // 池外溢出分配：保持上游 RWX 行为。W^X 设备上 4 个池切片足够
    // melonDS 使用，正常到不了这里。
    //printf("mmaping...\n");
    return mmap(nullptr, CodeMemorySliceSize, PROT_READ | PROT_WRITE | PROT_EXEC, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
#endif
}

void FreeCodeMem(void* codeMem)
{
    std::lock_guard guard(globalMutex);

#if !defined(APPLE_AARCH64) && !defined(__NetBSD__) && !defined(__OpenBSD__)
#if defined(MELONDS_WX_POOL)
    if (IsPoolSlice((u8*)codeMem))
    {
        int slice = (int)(((u8*)codeMem - rwMap) / CodeMemorySliceSize);
        if (slice >= 0 && slice < (int)NumCodeMemSlices)
            AvailableCodeMemSlices |= (1u << slice);
        return;
    }
#else
    u8* base = GetAlignedCodeMemoryStart();
    if ((u8*)codeMem >= base && (u8*)codeMem < base + CodeMemoryAlignedSize)
    {
        int slice = (int)(((u8*)codeMem - base) / CodeMemorySliceSize);
        if (slice >= 0 && slice < (int)NumCodeMemSlices)
            AvailableCodeMemSlices |= (1u << slice);
        return;
    }
#endif
#endif

#ifdef _WIN32
    VirtualFree(codeMem, CodeMemorySliceSize, MEM_RELEASE|MEM_DECOMMIT);
#else
    munmap(codeMem, CodeMemorySliceSize);
#endif
}

// ---------------------------------------------------------------------------
// 新增 API：把"可写视图"指针换算为"可执行视图"别名。
//  * 双映射模式：返回 rxMap + 同一偏移 —— 同一块物理内存。
//  * 单视图 RWX 模式 / 池外分配 / 非池化平台：原样返回。
// ARMJIT_Compiler 用它调用 SetCodeBase(rw, rx)（Switch 路径同构）。
// ---------------------------------------------------------------------------
void* GetExecAlias(void* codeMem)
{
#if defined(MELONDS_WX_POOL)
    std::lock_guard guard(globalMutex);
    if (PoolMode == 1 && IsPoolSlice((u8*)codeMem))
        return &rxMap[(u8*)codeMem - rwMap];
#endif
    return codeMem;
}

void Init()
{
    std::lock_guard guard(globalMutex);

    RefCounter++;
    if (RefCounter == 1)
    {
        #ifdef _WIN32
            DWORD dummy;
            VirtualProtect(GetAlignedCodeMemoryStart(), CodeMemoryAlignedSize, PAGE_EXECUTE_READWRITE, &dummy);
        #elif defined(APPLE_AARCH64) || defined(__NetBSD__) || defined(__OpenBSD__)
            // Apple aarch64 always uses dynamic allocation
        #elif defined(MELONDS_WX_POOL)
            // 修复：不再对静态数组 mprotect(RWX)（Android 12+ W^X 策略下
            // 必然失败）。改为建立 memfd 双映射 / RWX mmap 池。
            if (!EnsurePoolLocked())
            {
                fprintf(stderr, "[melonDS] ARMJIT: no executable memory available "
                                "(W^X policy denied both memfd dual-mapping and "
                                "RWX mmap) — JIT unusable\n");
            }
        #else
            mprotect(GetAlignedCodeMemoryStart(), CodeMemoryAlignedSize, PROT_EXEC | PROT_READ | PROT_WRITE);
        #endif

        ARMJIT_Memory::RegisterFaultHandler();
    }
}

void DeInit()
{
    std::lock_guard guard(globalMutex);

    RefCounter--;
    if (RefCounter == 0)
    {
        ARMJIT_Memory::UnregisterFaultHandler();
        // W^X 池保留（进程生命周期内复用）：melonDS 每个 ROM 会话都会
        // Init/DeInit 一次，反复建立/销毁 128MB VA 池没有收益，保留还能
        // 让后续会话的 ProbeCodeMemory 直接命中已有池。
    }
}

bool ProbeCodeMemory()
{
    // 前端能力探测：真正建立一次代码内存池（幂等）。
    //  * memfd 双映射成功 → Android 12+ W^X 设备上 JIT 可用（修复点）；
    //  * 双映射不可用但 RWX mmap 成功 → 旧设备行为不变；
    //  * 都失败 → 返回 false，前端以解释器运行并给出日志。
    // 旧实现对静态页 mprotect(RWX) 探测 —— 在 targetSdk>=31 的
    // Android 12+ 上永远失败，JIT 因此被静默禁用。
#if defined(MELONDS_WX_POOL)
    std::lock_guard guard(globalMutex);
    return EnsurePoolLocked();
#elif defined(_WIN32) || defined(APPLE_AARCH64) || defined(__NetBSD__) || defined(__OpenBSD__)
    return true;
#else
    // 非 Android arm64 的 POSIX 平台（桌面 Linux 等）：保留上游原探测
    // —— 验证进程是否允许把自身数据段页改为 RWX。
    long pagesz = sysconf(_SC_PAGESIZE);
    if (pagesz <= 0 || pagesz > 65536)
        pagesz = 4096;

    // Double-size buffer so we can always find a page-aligned address inside
    // our own data segment regardless of the runtime page size.
    static uint8_t probeBuf[131072] __attribute__((aligned(4096)));

    uintptr_t base = reinterpret_cast<uintptr_t>(probeBuf);
    base = (base + (uintptr_t)pagesz - 1) & ~((uintptr_t)pagesz - 1);

    void* page = reinterpret_cast<void*>(base);
    if (mprotect(page, (size_t)pagesz, PROT_READ | PROT_WRITE | PROT_EXEC) != 0)
        return false;

    // Restore — the page belongs to a plain static buffer, not code memory.
    mprotect(page, (size_t)pagesz, PROT_READ | PROT_WRITE);
    return true;
#endif
}

}

}
