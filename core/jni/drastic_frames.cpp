// SPDX-License-Identifier: MIT
// DraStic（激烈）帧一致性读取 shim —— libndscore 对预编译 libdrastic*.so 的
// 补充读取通道。
//
// ## 为什么要这个文件（反汇编根因，详见 DRASTIC_FIX_NOTES / 本轮 NOTES）
//
// libdrastic 的帧池是双缓冲（video_state 结构，每屏区 0xC0000 字节）：
//   - 模拟线程渲染帧 N 到 bufs[slot]，帧边界处"翻转 slot + signal 屏幕条件
//     变量"（0x1cb14：锁池互斥锁 → slot = ~slot & 1 → pthread_cond_signal →
//     解锁）；
//   - 原生 GL 路径 renderFrame（0x1ceac）在【池互斥锁】下读取【另一档】
//     （~slot = 刚完成的帧 N）—— 始终一致；
//   - 而原生 getScreenBuffers（0x19068 → 缓冲获取器 0x1cd18）【不加锁】读取
//     【当前写入档】（slot 本身 = 帧边界翻转后马上要被帧 N+1 覆盖的缓冲！）
//     —— 画布路径用它搬运帧，天然与模拟线程的下一帧写入竞争：
//       · 普通游戏：瞬时撕裂（3D 满屏刷新时可见）；
//       · 开启"多线程 3D 渲染"（bit28）：异步分段光栅化在帧边界后仍继续
//         写入 16 行/段 → 读到的帧上下段新旧混杂 → "上下屏部分贴图错乱"。
//
// ## 本 shim 做什么
//
// 在同一进程内定位 libdrastic 的 video_state（dladdr 取加载基址 + 反汇编
// 固定偏移），读取【刚完成的帧】（bufs[(slot+1)&1] —— 与原生 renderFrame
// 同一个缓冲），拷贝并以与原生 getScreenBuffers 完全相同的 RGBA8888 →
// ARGB_8888 字节序写入 Java IntArray。拷贝后复读 slot 校验一致性，翻档了
// 就重试（模拟线程要隔一整个帧周期才会再碰这个缓冲，正常一次成功）。
//
// 同时支持读取"高清渲染"（bit41，_Hires3D）下的全分辨率帧：
//   - 池在两种分辨率档下都是【稠密】布局（1x：256×192×4 = 0x30000/屏；
//     2x：512×384×4 = 0xC0000/屏，行距 2048 字节）—— 由原生降采样路径
//     （0x19398：4096 字节/超级行 = 2 条稠密 512 宽行）反证；
//   - 原生 getScreenBuffers 在 HD 档只返回 2:1 抽取降采样后的 256×192
//     （分辨率增益全部丢失）；本 shim 恒返回池的真实分辨率，画布路径因此
//     也能完整呈现 HD 帧（此前 HD 被强制要求 GL 路径）。
//
// 任何校验失败（库未加载 / 池未初始化 / 16 位渲染 bit23 / 档位异常）都
// 返回 false，由 Kotlin 侧回落到原生 getScreenBuffers —— 行为不劣于修复前。

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <cstdint>
#include <cstring>
#include <pthread.h>

#define TAG "ndscore"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace drasticframes {

// ---- 反汇编提取的每 ABI 固定偏移（三个预编译库验证见脚本输出） ----
struct LibLayout {
    const char* soname;
    uintptr_t   videoState; // video_state 结构的 ELF vaddr（dli_fbase + vaddr）
    ptrdiff_t   slotOff;    // 当前写入档 int32（相对 videoState）
    ptrdiff_t   scaleOff;   // 每屏分辨率档 int32[2]（0=256x192, 1=512x384）
    uintptr_t   configAddr; // 打包 config 位域（bit23=16位, bit41=高清）
    bool        is64;
};

static constexpr uintptr_t kScreenBytes  = 0xC0000;   // 每屏池区大小
static constexpr uintptr_t kPoolBufGap   = 0x180000;   // bufs[1] - bufs[0]（两屏总量）
static constexpr uint32_t  kHdStride    = 2048;    // HD 稠密行距（512px×4B）
static constexpr uint32_t  kSdStride    = 1024;    // 1x 稠密行距（256px×4B）

static const LibLayout kLayouts[] = {
    // libdrastic_arm64.so：VS=0x3f2d1f8（bufs@+0, slot@+0x958, scale@+0x968）；
    // 打包 config 全局 = 0x14c468（getScreenBuffers 直接测试 bit23/bit41）
    { "libdrastic_arm64.so",  0x3f2d1f8, 0x958, 0x968, 0x14c468, true  },
    // libdrastic.so：VS=0x2ee7930（bufs@+0, slot@+0x8ec, scale@+0x8f0）；
    // 打包 config = [cfg 全局 0x135000 + 0x458]（getScreenBuffers 读 [r4+0x458]）
    { "libdrastic.so",        0x2ee7930, 0x8ec, 0x8f0, 0x135458, false },
    // libdrastic_compat.so：VS=0x2ed0930，config = 0x11e000 + 0x458
    { "libdrastic_compat.so", 0x2ed0930, 0x8ec, 0x8f0, 0x11e458, false },
};

struct Resolved {
    const LibLayout* layout = nullptr;
    uint8_t* base = nullptr;      // dli_fbase（ELF vaddr 0 的映射地址）
};

static Resolved g_resolved;
static pthread_mutex_t g_resolveMu = PTHREAD_MUTEX_INITIALIZER;

static bool resolveLocked(Resolved& r) {
    // 进程内已加载的 DraStic 主库只有一个（System.loadLibrary 按探测结果加载）。
    // 用 RTLD_NOLOAD 查找，绝不触发加载。
    for (const auto& l : kLayouts) {
        void* h = dlopen(l.soname, RTLD_NOW | RTLD_NOLOAD);
        if (h == nullptr) continue;
        void* sym = dlsym(h, "Java_com_dsemu_drastic_DraSticJNI_waitScreen");
        if (sym == nullptr) continue;
        Dl_info info{};
        if (dladdr(sym, &info) == 0 || info.dli_fbase == nullptr) continue;
        r.layout = &l;
        r.base = reinterpret_cast<uint8_t*>(info.dli_fbase);
        return true;
    }
    return false;
}

static inline uint32_t rd32(const uint8_t* p) {
    uint32_t v;
    memcpy(&v, p, 4);
    return v;
}
static inline uint64_t rd64(const uint8_t* p) {
    uint64_t v;
    memcpy(&v, p, 8);
    return v;
}

// RGBA8888（池内字节序 R,G,B,A）→ ARGB_8888（Android IntArray 0xAARRGGBB）
static inline uint32_t rgbaToArgb(uint32_t px) {
    return 0xFF000000u | ((px >> 16) & 0xFFu) | (px & 0xFF00u) | ((px << 16) & 0x00FF0000u);
}

static bool copyOneScreen(const uint8_t* src, jint* dst, int w, int h) {
    const uint32_t stride = (w == 512) ? kHdStride : kSdStride;
    for (int y = 0; y < h; ++y) {
        const uint32_t* row = reinterpret_cast<const uint32_t*>(src + (size_t)y * stride);
        jint* out = dst + (size_t)y * w;
        for (int x = 0; x < w; ++x) {
            out[x] = (jint)rgbaToArgb(row[x]);
        }
    }
    return true;
}

} // namespace drasticframes

using namespace drasticframes;

// 拉取【刚完成】的一帧（上/下屏，真实分辨率）。outDims = {topW, topH, botW, botH}。
// 返回 false = 校验失败（Kotlin 回落原生 getScreenBuffers，行为同修复前）。
extern "C" JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_NdsNative_drasticGetCompletedFrames(
        JNIEnv* env, jclass, jintArray top, jintArray bottom, jintArray outDims) {
    if (top == nullptr || bottom == nullptr || outDims == nullptr) return JNI_FALSE;

    // ---- 解析库基址（每会话一次；失败后下次调用重试） ----
    Resolved r;
    {
        pthread_mutex_lock(&g_resolveMu);
        r = g_resolved;
        pthread_mutex_unlock(&g_resolveMu);
    }
    if (r.layout == nullptr) {
        pthread_mutex_lock(&g_resolveMu);
        if (g_resolved.layout == nullptr) {
            Resolved nr;
            if (resolveLocked(nr)) g_resolved = nr;
        }
        r = g_resolved;
        pthread_mutex_unlock(&g_resolveMu);
        if (r.layout == nullptr) return JNI_FALSE;
    }

    const uint8_t* vs = r.base + r.layout->videoState;
    const uint8_t* cfg = r.base + r.layout->configAddr;

    // ---- 16 位渲染（bit23）：池为 RGB565/4444 布局，shim 不支持 → 回落 ----
    const uint32_t cfgLo = rd32(cfg);
    if (cfgLo & 0x00800000u) return JNI_FALSE;

    // ---- 档位 / 池指针校验 ----
    const int32_t slot = (int32_t)rd32(vs + r.layout->slotOff);
    if (slot != 0 && slot != 1) return JNI_FALSE;

    uintptr_t bufs[2];
    if (r.layout->is64) {
        bufs[0] = (uintptr_t)rd64(vs);
        bufs[1] = (uintptr_t)rd64(vs + 8);
    } else {
        bufs[0] = rd32(vs);
        bufs[1] = rd32(vs + 4);
    }
    if (bufs[0] == 0 || bufs[1] != bufs[0] + kPoolBufGap) return JNI_FALSE;

    int32_t scale[2];
    scale[0] = (int32_t)rd32(vs + r.layout->scaleOff);
    scale[1] = (int32_t)rd32(vs + r.layout->scaleOff + 4);
    if ((scale[0] != 0 && scale[0] != 1) || (scale[1] != 0 && scale[1] != 1))
        return JNI_FALSE;

    const int w0 = scale[0] ? 512 : 256, h0 = scale[0] ? 384 : 192;
    const int w1 = scale[1] ? 512 : 256, h1 = scale[1] ? 384 : 192;
    if (w0 != w1 || h0 != h1) return JNI_FALSE; // 双屏档位应一致（setResolution 同源）

    const size_t px = (size_t)w0 * (size_t)h0;
    if (env->GetArrayLength(top) < (jsize)px || env->GetArrayLength(bottom) < (jsize)px)
        return JNI_FALSE;

    jint* dims = env->GetIntArrayElements(outDims, nullptr);
    if (dims == nullptr) return JNI_FALSE;

    jint* topP = env->GetIntArrayElements(top, nullptr);
    jint* botP = topP ? env->GetIntArrayElements(bottom, nullptr) : nullptr;
    if (botP == nullptr) {
        if (topP) env->ReleaseIntArrayElements(top, topP, JNI_ABORT);
        env->ReleaseIntArrayElements(outDims, dims, 0);
        return JNI_FALSE;
    }

    // ---- 一致性拷贝：读"已完成档"，拷完复读 slot，翻档则重试 ----
    bool ok = false;
    for (int attempt = 0; attempt < 3 && !ok; ++attempt) {
        const int32_t s = (int32_t)rd32(vs + r.layout->slotOff);
        if (s != 0 && s != 1) break;
        const int done = (s + 1) & 1;
        // bufs[done] 指向该档两屏连续区域：上屏在前 +0x0，下屏在 +0xC0000
        const uint8_t* bufDone = (const uint8_t*)(r.layout->is64
            ? (uintptr_t)rd64(vs + (size_t)done * 8)
            : (uintptr_t)rd32(vs + (size_t)done * 4));
        if (bufDone == nullptr) break;
        const uint8_t* srcTop = bufDone;
        const uint8_t* srcBot = bufDone + kScreenBytes;

        copyOneScreen(srcTop, topP, w0, h0);
        copyOneScreen(srcBot, botP, w1, h1);

        // 拷贝期间发生翻档（模拟线程已开始写下一帧的下一档）→ 重试一次；
        // 正常情况下模拟线程要隔整个帧周期才会回来写这个缓冲，一次即成功。
        const int32_t s2 = (int32_t)rd32(vs + r.layout->slotOff);
        if (s2 == s) ok = true;
    }

    dims[0] = w0; dims[1] = h0; dims[2] = w1; dims[3] = h1;

    env->ReleaseIntArrayElements(bottom, botP, 0);
    env->ReleaseIntArrayElements(top, topP, 0);
    env->ReleaseIntArrayElements(outDims, dims, 0);

    if (!ok) {
        LOGW("drasticGetCompletedFrames: slot unstable, fallback to getScreenBuffers");
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}
