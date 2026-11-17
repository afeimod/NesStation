"""Execute production restore lifecycle methods against small kernel doubles.

This exercises ordering/counting only; it is not an Eden integration test.
Usage: python scripts/test_eden_restore_threads.py <clang++> <scratch-dir>
"""
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
source = (root / 'core/eden/src/core/hle/kernel/k_thread.cpp').read_text(encoding='utf-8')

def method(name):
    start = source.index('void KThread::' + name + '(')
    body = source.index('{', start)
    depth = 1
    end = body + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]

harness = r'''
#include <atomic>
#include <cassert>
#include <cstdio>
#include <memory>
#include <vector>
namespace Kernel {
enum class ThreadState { Initialized, Waiting, Runnable, Terminated };
enum class SuspendType { System };
enum class LimitableResource { ThreadCountMax };
constexpr int ResultTerminationRequested = 1, ResultInvalidState = 2;
struct KThread;
struct Scheduler {
    int removed = 0;
    void RemoveThread(KThread*) { ++removed; }
};
struct KernelCore {
    Scheduler scheduler;
    Scheduler& GlobalSchedulerContext() { return scheduler; }
};
struct KScopedSchedulerLock { KScopedSchedulerLock(KernelCore&) {} };
struct Resource {
    int hints = 1;
    void Release(KernelCore&, LimitableResource, int value, int hint) {
        assert(value == 0 && hints >= hint); hints -= hint;
    }
};
struct KProcess {
    int running = 1;
    Resource resource;
    Resource* GetResourceLimit() { return &resource; }
    void SavestateDecrementRunningThreadCount() { assert(running > 0); --running; }
    void IncrementRunningThreadCount(KernelCore&) { ++running; }
    void ReleaseUserException(KernelCore&, KThread*) {}
    void UnpinThread(KernelCore&, KThread*) {}
};
struct LockInfo {
    int GetWaiterCount() { return 0; }
    KThread* GetHighestPriorityWaiter() { return nullptr; }
    bool RemoveWaiter(KThread*) { return false; }
};
struct LockWithPriorityInheritanceInfo {
    static void Free(KernelCore&, LockInfo*) {}
};
struct KSynchronizationObject { static void NotifyAvailable(KernelCore&) {} };
struct KThread {
    ThreadState state = ThreadState::Runnable;
    KProcess* m_parent;
    std::vector<LockInfo> m_held_lock_info_list;
    void* m_wait_queue = nullptr;
    void* m_waiting_lock_info = nullptr;
    int m_num_kernel_waiters = 0, m_suspend_allowed_flags = 0, refs = 2;
    std::atomic<bool> m_termination_requested{false};
    std::atomic<ThreadState> m_thread_state{ThreadState::Runnable};
    bool m_signaled = false, m_resource_limit_release_hint = false, suspended = false;
    struct Stack { bool is_pinned = false; } stack;
    explicit KThread(KProcess* owner) : m_parent(owner) {}
    ThreadState GetState() { return state; }
    KProcess* GetOwnerProcess() { return m_parent; }
    Stack& GetStackParameters() { return stack; }
    void CancelWait(KernelCore&, int, bool) { state = ThreadState::Runnable; }
    void StartTermination(KernelCore&) { state = ThreadState::Terminated; }
    void RequestSuspend(KernelCore&, SuspendType) { suspended = true; }
    void Open(KernelCore&) { ++refs; }
    void Close(KernelCore&) { assert(refs > 0); --refs; }
    void SetState(KernelCore&, ThreadState s) {
        assert(s != ThreadState::Runnable || suspended); state = s;
    }
    void SavestateRemoveThread(KernelCore&);
    void SavestateRecreateRun(KernelCore&);
};
@METHODS@
}
int main() {
    using namespace Kernel;
    KernelCore kernel;
    KProcess process;
    for (int i = 0; i < 100; ++i) {
        KThread old(&process);
        old.SavestateRemoveThread(kernel);
        assert(process.running == 0 && process.resource.hints == 0);
        assert(old.refs == 1 && old.m_resource_limit_release_hint);
        const int removed = kernel.scheduler.removed;
        old.SavestateRemoveThread(kernel);
        assert(kernel.scheduler.removed == removed && old.refs == 1);
        ++process.resource.hints; // production KScopedResourceReservation
        KThread restored(&process);
        restored.state = ThreadState::Initialized;
        restored.refs = 1; // Create reference, detached guest thread
        restored.SavestateRecreateRun(kernel);
        restored.Close(kernel); // production SCOPE_EXIT
        assert(restored.refs == 1 && restored.suspended);
        assert(process.running == 1 && process.resource.hints == 1);
    }
    assert(kernel.scheduler.removed == 100);
    KThread unstarted(&process);
    unstarted.state = ThreadState::Initialized;
    unstarted.SavestateRemoveThread(kernel);
    assert(unstarted.refs == 2 && process.running == 1);
    std::puts("PASS: production thread removal/start methods, 100 cycles, suspension, references, running count, resource hints");
}
'''
scratch = Path(sys.argv[2]).resolve()
scratch.mkdir(parents=True, exist_ok=True)
cpp = scratch / 'eden_threads.cpp'
cpp.write_text(harness.replace('@METHODS@', method('SavestateRemoveThread') + '\n' + method('SavestateRecreateRun')), encoding='utf-8')
exe = scratch / 'eden_threads.exe'
subprocess.run([sys.argv[1], '-std=c++20', str(cpp), '-o', str(exe)], check=True)
subprocess.run([str(exe)], check=True)
