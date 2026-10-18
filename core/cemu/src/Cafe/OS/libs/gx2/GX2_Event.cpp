#include "Cafe/OS/common/OSCommon.h"
#include "Cafe/Savestate/SavestateFnRegistry.h"
#include "GX2_Command.h"
#include "GX2_Event.h"
#include "Cafe/HW/Latte/Core/LattePM4.h"
#include "Cafe/HW/MMU/MMU.h"
#include "Cafe/OS/libs/coreinit/coreinit_Thread.h"
#include "Cafe/HW/Latte/Core/Latte.h"
#include "GX2.h"
#include "Cafe/HW/Latte/Renderer/Renderer.h"
#include "config/ActiveSettings.h"
#include "util/helpers/ConcurrentQueue.h"
#include <atomic>

namespace GX2
{

// ★★★ NesStation R11: GX2DrawDone 快速模式（参考「风之杖HD 重编译版」
//   drawdone_mode 实现）。
//
// ★★★ NesStation R14 重写（对应用户反馈"启动后过一会就失效，要重新开关
//   一次"）：R13 的固定阈值（submitted-retired > 8 就全排空）有两个缺陷：
//   ① 阈值按 retire-marker 计数，而每帧 marker 数随场景波动（忙碌场景
//   一帧可产生十几个），慢性超限后每次 DrawDone 都退化为全排空 —— 提速
//   收益消失（"失效"）；② 排空目标是 submitted（排到零），把 CPU/GPU
//   重叠也清了。
//   参考项目（反汇编确认 set_drawdone_mode）的默认档是
//   "one GX2DrawDone behind"：每次 DrawDone 等待的是【上一次 DrawDone
//   时已提交】的时间戳 —— 无论中间发生了多少次 flush/marker，游戏线程
//   恒定领先 GPU 恰好一个 DrawDone 周期，队列深度自平衡、开机即稳。
//   三档语义（同参考项目 set_drawdone_mode）:
//     0 = 严格等待（"for the render thread"，上游语义）
//     1 = 滞后等待（"one GX2DrawDone behind"，默认；滞后深度 1..4 可调，
//         深度越大 CPU/GPU 重叠越多、输入延迟越高）
//     2 = 纯跳过（"never"；附安全阀防队列无界增长）
static std::atomic<int> s_drawDoneMode{ 1 };   // 默认：滞后等待（提速开）
static std::atomic<int> s_drawDoneLag{ 1 };    // 档1的滞后深度（1..4）

void GX2SetDrawDoneMode(int mode, int lag)
{
        if (mode < 0 || mode > 2)
                mode = 1;
        if (lag < 1)
                lag = 1;
        if (lag > 4)
                lag = 4;
        s_drawDoneMode.store(mode, std::memory_order_relaxed);
        s_drawDoneLag.store(lag, std::memory_order_relaxed);
}

// 兼容旧接口（bool 快速开关）：true = 档1（滞后等待），false = 档0（严格）
void GX2SetDrawDoneSkipWait(bool skip)
{
        s_drawDoneMode.store(skip ? 1 : 0, std::memory_order_relaxed);
}

        SysAllocator<coreinit::OSThreadQueue> g_vsyncThreadQueue;
        SysAllocator<coreinit::OSThreadQueue> g_flipThreadQueue;

        void GX2SetGPUFence(uint32be* fencePtr, uint32 mask, uint32 compareOp, uint32 compareValue)
        {
                GX2ReserveCmdSpace(7);
                uint8 compareOpTable[] = { 0x7,0x1,0x3,0x2,0x6,0x4,0x5,0x0 };
                gx2WriteGather_submitU32AsBE(pm4HeaderType3(IT_WAIT_REG_MEM, 6));
                gx2WriteGather_submitU32AsBE((uint32)(compareOpTable[compareOp & 7]) | 0x10); // compare operand + memory select (0x10)
                gx2WriteGather_submitU32AsBE(memory_virtualToPhysical(memory_getVirtualOffsetFromPointer(fencePtr)) | 2); // physical address + type size flag(?)
                gx2WriteGather_submitU32AsBE(0); // ukn, always set to 0
                gx2WriteGather_submitU32AsBE(compareValue); // fence value
                gx2WriteGather_submitU32AsBE(mask); // fence mask
                gx2WriteGather_submitU32AsBE(0xA); // unknown purpose
        }

        enum class GX2PipeEventType : uint32
        {
                TOP = 0,
                BOTTOM = 1,
                BOTTOM_AFTER_FLUSH = 2
        };

        void GX2SubmitUserTimeStamp(uint64* timestampOut, uint64 value, GX2PipeEventType eventType, uint32 triggerInterrupt)
        {
                GX2ReserveCmdSpace(11);

                MPTR physTimestampAddr = memory_virtualToPhysical(memory_getVirtualOffsetFromPointer(timestampOut));
                uint32 valHigh = (uint32)(value >> 32);
                uint32 valLow = (uint32)(value & 0xffffffff);

                if (eventType == GX2PipeEventType::TOP)
                {
                        // write when on top of pipe
                        gx2WriteGather_submitU32AsBE(pm4HeaderType3(IT_MEM_WRITE, 4));
                        gx2WriteGather_submitU32AsBE(physTimestampAddr | 0x2);
                        gx2WriteGather_submitU32AsBE(0); // 0x40000 -> 32bit write, 0x0 -> 64bit write?
                        gx2WriteGather_submitU32AsBE(valLow); // low
                        gx2WriteGather_submitU32AsBE(valHigh); // high
                        if (triggerInterrupt != 0)
                        {
                                // top callback
                                gx2WriteGather_submitU32AsBE(0x0000304A);
                                gx2WriteGather_submitU32AsBE(0x40000000);
                        }
                }
                else if (eventType == GX2PipeEventType::BOTTOM_AFTER_FLUSH)
                {
                        // write when on bottom of pipe
                        gx2WriteGather_submitU32AsBE(pm4HeaderType3(IT_MEM_WRITE, 4));
                        gx2WriteGather_submitU32AsBE(physTimestampAddr | 0x2);
                        gx2WriteGather_submitU32AsBE(0); // 0x40000 -> 32bit write, 0x0 -> 64bit write?
                        gx2WriteGather_submitU32AsBE(valLow); // low
                        gx2WriteGather_submitU32AsBE(valHigh); // high
                        // trigger CB
                        if (triggerInterrupt != 0)
                        {
                                // bottom callback
                                // todo -> Fix this
                                gx2WriteGather_submitU32AsBE(0x0000304B); // hax -> This event is handled differently and uses a different packet?
                                gx2WriteGather_submitU32AsBE(0x40000000);
                                // trigger bottom of pipe callback
                                // used by Mario & Sonic Rio
                                gx2WriteGather_submitU32AsBE(pm4HeaderType3(IT_HLE_BOTTOM_OF_PIPE_CB, 3));
                                gx2WriteGather_submitU32AsBE(physTimestampAddr);
                                gx2WriteGather_submitU32AsBE(valLow); // low
                                gx2WriteGather_submitU32AsBE(valHigh); // high
                        }
                }
                else if (eventType == GX2PipeEventType::BOTTOM)
                {
                        // fix this
                        // write timestamp when on bottom of pipe
                        if (triggerInterrupt != 0)
                        {
                                // write value and trigger CB
                                // todo: Use correct packet
                                gx2WriteGather_submitU32AsBE(pm4HeaderType3(IT_HLE_BOTTOM_OF_PIPE_CB, 3));
                                gx2WriteGather_submitU32AsBE(physTimestampAddr);
                                gx2WriteGather_submitU32AsBE(valLow); // low
                                gx2WriteGather_submitU32AsBE(valHigh); // high
                        }
                        else
                        {
                                // write value but don't trigger CB
                                gx2WriteGather_submitU32AsBE(pm4HeaderType3(IT_MEM_WRITE, 4));
                                gx2WriteGather_submitU32AsBE(physTimestampAddr | 0x2);
                                gx2WriteGather_submitU32AsBE(0); // 0x40000 -> 32bit write, 0x0 -> 64bit write?
                                gx2WriteGather_submitU32AsBE(valLow); // low
                                gx2WriteGather_submitU32AsBE(valHigh); // high
                        }
                }
                else
                {
                        cemu_assert_debug(false);
                }
        }

        struct GX2EventFunc
        {
                MEMPTR<void> callbackFuncPtr;
                MEMPTR<void> userData;
        }s_eventCallback[GX2CallbackEventTypeCount]{};

        void GX2SetEventCallback(GX2CallbackEventType eventType, void* callbackFuncPtr, void* userData)
        {
                if ((size_t)eventType >= GX2CallbackEventTypeCount)
                {
                        cemuLog_log(LogType::Force, "GX2SetEventCallback(): Unknown eventType");
                        return;
                }
                s_eventCallback[(size_t)eventType].callbackFuncPtr = callbackFuncPtr;
                s_eventCallback[(size_t)eventType].userData = userData;
        }

        void GX2GetEventCallback(GX2CallbackEventType eventType, MEMPTR<void>* callbackFuncPtrOut, MEMPTR<void>* userDataOut)
        {
                if ((size_t)eventType >= GX2CallbackEventTypeCount)
                {
                        cemuLog_log(LogType::Force, "GX2GetEventCallback(): Unknown eventType");
                        return;
                }
                if (callbackFuncPtrOut)
                        *callbackFuncPtrOut = s_eventCallback[(size_t)eventType].callbackFuncPtr;
                if (userDataOut)
                        *userDataOut = s_eventCallback[(size_t)eventType].userData;
        }

        // event callback thread
        bool s_callbackThreadLaunched{};
        SysAllocator<OSThread_t> s_eventCallbackThread;
        SysAllocator<uint8, 0x2000> s_eventCallbackThreadStack;
        SysAllocator<char, 64> s_eventCallbackThreadName;
        // event callback queue
        struct GX2EventQueueEntry
        {
                GX2EventQueueEntry() {};
                GX2EventQueueEntry(GX2CallbackEventType eventType) : eventType(eventType) {};
                GX2CallbackEventType eventType{(GX2CallbackEventType)-1};
        };

        SysAllocator<coreinit::OSSemaphore> s_eventCbQueueSemaphore;
        ConcurrentQueue<GX2EventQueueEntry> s_eventCbQueue;

        void ResetQueuedEventCallbacks()
        {
                s_eventCbQueue.clear();
        }

        void __GX2NotifyEvent(GX2CallbackEventType eventType)
        {
                if ((size_t)eventType >= GX2CallbackEventTypeCount)
                {
                        cemu_assert_debug(false);
                        return;
                }
                if (s_eventCallback[(size_t)eventType].callbackFuncPtr)
                {
                        s_eventCbQueue.push(eventType);
                        coreinit::OSSignalSemaphore(s_eventCbQueueSemaphore);
                }
                // wake up threads that are waiting for VSYNC or FLIP event
                if (eventType == GX2CallbackEventType::VSYNC)
                {
                        __OSLockScheduler();
                        g_vsyncThreadQueue->wakeupEntireWaitQueue(false);
                        __OSUnlockScheduler();
                }
                else if (eventType == GX2CallbackEventType::FLIP)
                {
                        __OSLockScheduler();
                        g_flipThreadQueue->wakeupEntireWaitQueue(false);
                        __OSUnlockScheduler();
                }
        }

        void __GX2CallbackThread(PPCInterpreter_t* hCPU)
        {
                while (coreinit::OSWaitSemaphore(s_eventCbQueueSemaphore))
                {
                        GX2EventQueueEntry entry;
                        if (!s_eventCbQueue.peek2(entry))
                                continue;
                        if(!s_eventCallback[(size_t)entry.eventType].callbackFuncPtr)
                                continue;
                        PPCCoreCallback(s_eventCallback[(size_t)entry.eventType].callbackFuncPtr, (sint32)entry.eventType, s_eventCallback[(size_t)entry.eventType].userData);
                }
                osLib_returnFromFunction(hCPU, 0);
        }

        void GX2WaitForVsync()
        {
                __OSLockScheduler();
                g_vsyncThreadQueue.GetPtr()->queueAndWait(coreinit::OSGetCurrentThread());
                __OSUnlockScheduler();
        }

        void GX2WaitForFlip()
        {
                if ((sint32)(_swapEndianU32(LatteGPUState.sharedArea->flipRequestCountBE) == _swapEndianU32(LatteGPUState.sharedArea->flipExecuteCountBE)))
                        return; // dont wait if no flip is requested
                __OSLockScheduler();
                g_flipThreadQueue.GetPtr()->queueAndWait(coreinit::OSGetCurrentThread());
                __OSUnlockScheduler();
        }

        bool GX2DrawDone()
        {
                // optional force full sync (texture readback and occlusion queries)
                bool forceFullSync = false;
                if (g_renderer && g_renderer->GetType() == RendererAPI::Vulkan)
                        forceFullSync = true;
                if (forceFullSync || ActiveSettings::WaitForGX2DrawDoneEnabled())
                {
                        GX2ReserveCmdSpace(2);
                        // write PM4 command
                        gx2WriteGather_submitU32AsBE(pm4HeaderType3(IT_HLE_SYNC_ASYNC_OPERATIONS, 1));
                        gx2WriteGather_submitU32AsBE(0x00000000); // unused
                }
                // flush pipeline
                GX2Command_Flush(0x100, true);

                // ★★★ NesStation R14: 三档模式（同参考项目）
                const int mode = s_drawDoneMode.load(std::memory_order_relaxed);
                if (mode == 1)
                {
                        // "one GX2DrawDone behind"（滞后深度可调 1..4）：
                        // 等待 lag 次 DrawDone 之前已提交的时间戳。滞后环里
                        // 存的是【当次 DrawDone 读到的 lastSubmissionTime】
                        // 快照（含此前全部 flush 的 marker），因此无论两帧
                        // 之间产生多少次提交，队列深度恒定有界于 lag 个
                        // DrawDone 周期的提交量 —— 不随场景复杂度漂移。
                        static uint64 s_lagRing[5] = { 0, 0, 0, 0, 0 };
                        const uint64 submitted = GX2GetLastSubmittedTimeStamp();
                        int lag = s_drawDoneLag.load(std::memory_order_relaxed);
                        if (lag < 1)
                                lag = 1;
                        if (lag > 4)
                                lag = 4;
                        const uint64 target = s_lagRing[5 - lag]; // lag 次调用前的快照（ring[4]=上一次，ring[1]=4 次前）
                        // 环左移一格，尾部写入当次快照
                        for (int i = 0; i < 4; i++)
                                s_lagRing[i] = s_lagRing[i + 1];
                        s_lagRing[4] = submitted;
                        if (target != 0 && submitted > target)
                                GX2WaitTimeStamp(target);
                        else if (target > submitted)
                        {
                                // 时间戳回卷（存档回滚/重开游戏 marker 清零）：
                                // 清环防旧值卡住等待
                                for (auto& v : s_lagRing)
                                        v = 0;
                        }
                        return true;
                }
                if (mode == 2)
                {
                        // "never"：纯跳过。命令池/ring 背压天然限流游戏线程，
                        // 但为防极端场景（读屏/循环无 flip）队列无界增长烧
                        // 满内存，保留大幅安全阀：积压超过 96 个 marker 时
                        // 部分排空到 48（不清零，保留重叠收益）。
                        constexpr uint64 kValveBacklog = 96;
                        constexpr uint64 kValveFloor = 48;
                        const uint64 submitted = GX2GetLastSubmittedTimeStamp();
                        const uint64 retired = GX2GetRetiredTimeStamp();
                        if (submitted > retired && (submitted - retired) > kValveBacklog)
                                GX2WaitTimeStamp(submitted - kValveFloor);
                        return true;
                }
                // mode 0：严格等待（上游语义）
                uint64 ts = GX2GetLastSubmittedTimeStamp();
                return GX2WaitTimeStamp(ts);
        }

        void GX2Init_event()
        {
                // clear queue

                // launch event callback thread
                if (s_callbackThreadLaunched)
                        return;
                s_callbackThreadLaunched = true;
                strcpy(s_eventCallbackThreadName.GetPtr(), "GX2 event callback");
                coreinit::OSCreateThreadType(s_eventCallbackThread, PPCInterpreter_makeCallableExportDepr(__GX2CallbackThread), 0, nullptr, (uint8*)s_eventCallbackThreadStack.GetPtr() + s_eventCallbackThreadStack.GetByteSize(), (sint32)s_eventCallbackThreadStack.GetByteSize(), 16, OSThread_t::ATTR_DETACHED, OSThread_t::THREAD_TYPE::TYPE_IO);
                coreinit::OSSetThreadName(s_eventCallbackThread, s_eventCallbackThreadName);
                coreinit::OSResumeThread(s_eventCallbackThread);
        }

        void GX2EventInit()
        {
                cafeExportRegister("gx2", GX2SetGPUFence, LogType::GX2);
                cafeExportRegister("gx2", GX2SubmitUserTimeStamp, LogType::GX2);

                cafeExportRegister("gx2", GX2SetEventCallback, LogType::GX2);
                cafeExportRegister("gx2", GX2GetEventCallback, LogType::GX2);

                cafeExportRegister("gx2", GX2WaitForVsync, LogType::GX2);
                cafeExportRegister("gx2", GX2WaitForFlip, LogType::GX2);
                cafeExportRegister("gx2", GX2DrawDone, LogType::GX2);

                coreinit::OSInitThreadQueue(g_vsyncThreadQueue.GetPtr());
                coreinit::OSInitThreadQueue(g_flipThreadQueue.GetPtr());

                coreinit::OSInitSemaphore(s_eventCbQueueSemaphore, 0);
        }

    void GX2EventResetToDefaultState()
    {
        s_callbackThreadLaunched = false;
        for(auto& it : s_eventCallback)
        {
            it.callbackFuncPtr = nullptr;
            it.userData = nullptr;
        }
    }

// v3.7: register GX2 event functions for the crash reporter
SAVESTATE_REGISTER_FN(ResetQueuedEventCallbacks)
SAVESTATE_REGISTER_FN(GX2DrawDone)
}
