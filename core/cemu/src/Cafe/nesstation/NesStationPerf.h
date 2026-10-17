#pragma once
// ★★★ NesStation R11: CPU 核心调度（参考「风之杖HD 重编译版」的性能实现）
//   游戏模拟线程（3 个调度主机线程）与 GPU 渲染线程按策略绑定 CPU 簇：
//   0 = 默认（不干预 / 解除绑定恢复全核）
//   1 = 大核优先（渲染最快，发热更高）
//   2 = 小核优先（发热更低，运行稳定·省电）
// 大小核分类：读 /sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq，
// 最高频率 ≥ 全局最大值 85% 的 CPU 记为大核簇，其余为小核簇；无法分类
// （单簇设备 / sysfs 不可读）时策略静默不生效。线程在入口处自注册 tid，
// 运行中切换策略经 JNI 对全部已注册线程立即重绑（热生效）。
// 头文件内联实现：无需改动任何构建脚本。
//
// ★★★ NesStation R12: ADPF 动态性能提示（参考同款 APK 的 WWHD_NO_ADPF /
//   WWHD_PERF_HINT 语义反向移植 —— 参考 APK 默认启用 ADPF，NO_ADPF 用于
//   调试关闭）。游戏运行期间通过 Android Dynamic Performance Framework
//   (APerformanceHint, API 31+) 向调度器持续上报帧工作时长，让系统感知
//   持续高负载、抑制温控/调度降频抖动（帧率稳定性的系统级手段）。
//   dlopen("libandroid.so") 运行时绑定符号：API < 31 或符号缺失时静默
//   禁用，零硬链接依赖。target 固定 60fps 预算（16.667ms）：即便游戏
//   30fps 锁，actual(33ms) > target 也会促使调度器维持加速状态。

#include <atomic>
#include <mutex>
#include <vector>
#include <sched.h>
#include <unistd.h>
#include <cstdio>
#include <cstdint>
#include <chrono>
#include <dlfcn.h>

namespace NesStationPerf
{
        inline std::atomic<int> s_coreSchedPolicy{ 0 };

        inline std::mutex s_tidLock;
        inline std::vector<pid_t> s_registeredTids;

        inline void RegisterThread(pid_t tid)
        {
                std::lock_guard<std::mutex> _l(s_tidLock);
                for (pid_t existing : s_registeredTids)
                        if (existing == tid)
                                return; // 重复注册防御（线程退出后 tid 复用的极端场景）
                s_registeredTids.push_back(tid);
        }

        inline void ClearThreads()
        {
                std::lock_guard<std::mutex> _l(s_tidLock);
                s_registeredTids.clear();
        }

        // 分类结果缓存（sysfs 内容进程内不变）
        struct CoreClusters
        {
                bool valid{ false };
                cpu_set_t bigSet{};
                cpu_set_t littleSet{};
        };

        inline CoreClusters& GetClusters()
        {
                static CoreClusters clusters;
                static bool initialized = false;
                static std::mutex initLock;
                std::lock_guard<std::mutex> _l(initLock);
                if (!initialized)
                {
                        initialized = true;
                        int freq[64] = {};
                        int n = 0;
                        int maxFreq = 0;
                        for (int c = 0; c < 64; c++)
                        {
                                char path[128];
                                snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", c);
                                FILE* f = fopen(path, "r");
                                if (!f)
                                        break;
                                int v = 0;
                                if (fscanf(f, "%d", &v) == 1 && v > 0)
                                {
                                        freq[n++] = v;
                                        if (v > maxFreq)
                                                maxFreq = v;
                                }
                                fclose(f);
                        }
                        if (n > 0 && maxFreq > 0)
                        {
                                CPU_ZERO(&clusters.bigSet);
                                CPU_ZERO(&clusters.littleSet);
                                int bigCount = 0, littleCount = 0;
                                for (int c = 0; c < n; c++)
                                {
                                        // ≥ 全局最高频率 85% 归大核簇（覆盖 1+3+4 与 4+4 常见拓扑）
                                        if ((long long)freq[c] * 100 >= (long long)maxFreq * 85)
                                        {
                                                CPU_SET(c, &clusters.bigSet);
                                                bigCount++;
                                        }
                                        else
                                        {
                                                CPU_SET(c, &clusters.littleSet);
                                                littleCount++;
                                        }
                                }
                                // 仅当两簇都非空才有意义（单簇设备无法区分）
                                clusters.valid = (bigCount > 0 && littleCount > 0);
                        }
                }
                return clusters;
        }

        inline void ApplyPolicyToThread(pid_t tid, int policy)
        {
                if (tid <= 0)
                        return;
                const CoreClusters& clusters = GetClusters();
                if (!clusters.valid)
                        return;
                if (policy == 1)
                        sched_setaffinity(tid, sizeof(cpu_set_t), &clusters.bigSet);
                else if (policy == 2)
                        sched_setaffinity(tid, sizeof(cpu_set_t), &clusters.littleSet);
                else
                {
                        // 0 = 默认：解除绑定（允许全部在线 CPU）
                        cpu_set_t allSet;
                        CPU_ZERO(&allSet);
                        int n = (int)sysconf(_SC_NPROCESSORS_ONLN);
                        if (n > CPU_SETSIZE)
                                n = CPU_SETSIZE;
                        if (n < 1)
                                n = 1;
                        for (int i = 0; i < n; i++)
                                CPU_SET(i, &allSet);
                        sched_setaffinity(tid, sizeof(cpu_set_t), &allSet);
                }
        }

        inline void ApplyPolicyToAll(int policy)
        {
                std::vector<pid_t> tids;
                {
                        std::lock_guard<std::mutex> _l(s_tidLock);
                        tids = s_registeredTids;
                }
                for (pid_t tid : tids)
                        ApplyPolicyToThread(tid, policy);
        }

        // 线程入口调用：自注册 + 按当前策略绑核
        inline void RegisterAndApplySelf()
        {
                pid_t tid = gettid();
                RegisterThread(tid);
                ApplyPolicyToThread(tid, s_coreSchedPolicy.load(std::memory_order_relaxed));
        }

        // ================= R12 ADPF 动态性能提示 =================
        // dlopen libandroid.so 运行时绑定（API 31+ 才导出这些符号）
        inline std::atomic<bool> s_adpfEnabled{ true };
        inline std::atomic<bool> s_adpfInitTried{ false };
        inline std::atomic<bool> s_adpfAvailable{ false };
        inline void* s_adpfManager = nullptr;   // AHintManager*
        inline void* s_adpfSession = nullptr;   // AHintSession*（仅 GPU 线程访问，无需原子）

        // APerformanceHint C API 函数指针（<android/performance_hint.h> 语义）
        using FnGetManager = void* (*)();
        using FnCreateSession = void* (*)(void* manager, const int32_t* tids, size_t size, int64_t initialTargetNanos);
        using FnUpdateTarget = int (*)(void* session, int64_t targetNanos);
        using FnReportActual = int (*)(void* session, int64_t actualDurationNanos);
        using FnCloseSession = void (*)(void* session);
        inline FnGetManager s_fnGetManager = nullptr;
        inline FnCreateSession s_fnCreateSession = nullptr;
        inline FnUpdateTarget s_fnUpdateTarget = nullptr;
        inline FnReportActual s_fnReportActual = nullptr;
        inline FnCloseSession s_fnCloseSession = nullptr;

        inline void AdpfInitOnce()
        {
                bool expected = false;
                if (!s_adpfInitTried.compare_exchange_strong(expected, true))
                        return;
                void* h = dlopen("libandroid.so", RTLD_NOW | RTLD_GLOBAL);
                if (!h)
                        return;
                s_fnGetManager = (FnGetManager)dlsym(h, "APerformanceHint_getManager");
                s_fnCreateSession = (FnCreateSession)dlsym(h, "APerformanceHint_createSession");
                s_fnUpdateTarget = (FnUpdateTarget)dlsym(h, "APerformanceHint_updateTargetWorkDuration");
                s_fnReportActual = (FnReportActual)dlsym(h, "APerformanceHint_reportActualWorkDuration");
                s_fnCloseSession = (FnCloseSession)dlsym(h, "APerformanceHint_closeSession");
                if (!s_fnGetManager || !s_fnCreateSession || !s_fnUpdateTarget || !s_fnReportActual)
                        return;
                s_adpfManager = s_fnGetManager();
                if (!s_adpfManager)
                        return;
                s_adpfAvailable.store(true, std::memory_order_relaxed);
        }

        // 线程注册表快照（会话创建用；GPU 线程调用）
        inline void AdpfEnsureSession()
        {
                if (!s_adpfEnabled.load(std::memory_order_relaxed))
                        return;
                if (!s_adpfAvailable.load(std::memory_order_relaxed))
                        return;
                if (s_adpfSession)
                        return;
                std::vector<pid_t> tids;
                {
                        std::lock_guard<std::mutex> _l(s_tidLock);
                        tids = s_registeredTids;
                }
                if (tids.size() < 2)
                        return; // 调度线程组未就绪，等下一帧
                std::vector<int32_t> tids32(tids.begin(), tids.end());
                void* session = s_fnCreateSession(s_adpfManager, tids32.data(), tids32.size(), 16'666'666LL);
                if (session)
                        s_adpfSession = session;
        }

        // GPU 线程每帧调用（主画布 present 后）；内部节流（ADPF 建议 ≥1ms 间隔）
        inline void ReportFrameTick()
        {
                if (!s_adpfEnabled.load(std::memory_order_relaxed))
                        return;
                if (!s_adpfSession)
                        AdpfEnsureSession();
                void* session = s_adpfSession;
                if (!session)
                        return;
                static std::chrono::steady_clock::time_point s_lastTick{};
                static bool s_lastValid = false;
                auto now = std::chrono::steady_clock::now();
                if (!s_lastValid)
                {
                        s_lastValid = true;
                        s_lastTick = now;
                        return;
                }
                long long ns = std::chrono::duration_cast<std::chrono::nanoseconds>(now - s_lastTick).count();
                s_lastTick = now;
                if (ns <= 500'000LL)
                        return; // 间隔过短（重复 present 等）跳过
                s_fnReportActual(session, ns);
        }

        inline void SetAdpfEnabled(bool enable)
        {
                s_adpfEnabled.store(enable, std::memory_order_relaxed);
                if (enable)
                {
                        AdpfInitOnce();
                        s_adpfSession = nullptr; // 下一帧重建会话
                }
        }
}
