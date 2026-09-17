/*
    Copyright 2016-2025 melonDS team

    This file is part of melonDS.

    melonDS is free software: you can redistribute it and/or modify it under
    the terms of the GNU General Public License as published by the Free
    Software Foundation, either version 3 of the License, or (at your option)
    any later version.

    melonDS is distributed in the hope that it will be useful, but WITHOUT ANY
    WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
    FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.

    You should have received a copy of the GNU General Public License along
    with melonDS. If not, see http://www.gnu.org/licenses/.
*/

#ifndef ARMJIT_GLOBAL_H
#define ARMJIT_GLOBAL_H

#include "types.h"

#include <stdlib.h>

namespace melonDS
{

namespace ARMJIT_Global
{

static constexpr size_t CodeMemorySliceSize = 1024*1024*32;

void Init();
void DeInit();

void* AllocateCodeMem();
void FreeCodeMem(void* codeMem);

// 返回"可写视图"指针对应的"可执行视图"别名。
//  * W^X 双映射模式（Android 12+ / memfd）：返回同一物理内存的 RX 别名，
//    编译器经 SetCodeBase(rw, rx) 写读分离；
//  * 单视图 RWX 模式（旧设备回退）：原样返回。
void* GetExecAlias(void* codeMem);

// Frontend-facing capability probe: true when the process may map
// executable pages (i.e. the JIT's BSS code pool can actually be made RWX).
// Android frontends call this BEFORE constructing NDS so they can fall back
// to the interpreter with a clear log on W^X-restricted devices.
bool ProbeCodeMemory();

}

}

#endif