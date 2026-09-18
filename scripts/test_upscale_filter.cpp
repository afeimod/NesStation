// Host-side runtime test: validates the exact filter pipeline used by
// Java_com_nesstation_app_core_jni_NdsNative_applyUpscaleFilter in
// nds_bridge.cpp (same buffer sizes, same dispatch, same RGBA conversion).
#include "core_shared.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

static constexpr int kDfMaxSrcW = 256;
static constexpr int kDfMaxSrcH = 192;
static uint32_t s_dfBuf2x[kDfMaxSrcW * 2 * kDfMaxSrcH * 2];
static uint32_t s_dfBuf4x[kDfMaxSrcW * 4 * kDfMaxSrcH * 4];

static inline void argbToRgbaBytes(const uint32_t* src, uint8_t* dst, size_t n) {
    for (size_t i = 0; i < n; ++i) {
        const uint32_t px = src[i];
        dst[i * 4 + 0] = (uint8_t)((px >> 16) & 0xFF);
        dst[i * 4 + 1] = (uint8_t)((px >> 8)  & 0xFF);
        dst[i * 4 + 2] = (uint8_t)( px        & 0xFF);
        dst[i * 4 + 3] = 0xFF;
    }
}

// Returns bytes written (mirrors the JNI function's contract).
static int applyUpscaleFilter(int filter, const uint32_t* srcPx, int w, int h, uint8_t* dst) {
    const bool is2x = (filter == 4 || filter == 5 || filter == 7);
    const bool is4x = (filter == 6 || filter == 8 || filter == 9 || filter == 10);
    if (!is2x && !is4x) return 0;
    if (w <= 0 || h <= 0 || w > kDfMaxSrcW || h > kDfMaxSrcH) return 0;
    const size_t outW = (size_t)w * (is4x ? 4 : 2);
    const size_t outH = (size_t)h * (is4x ? 4 : 2);
    if (filter == 4 || filter == 7) {
        coreshared::xbr2xUpscale(srcPx, (unsigned)w, (unsigned)h, (size_t)w, s_dfBuf2x);
    } else if (filter == 5) {
        hq2x_32_rb(srcPx, (uint32_t)(w * sizeof(uint32_t)),
                   s_dfBuf2x, (uint32_t)(outW * sizeof(uint32_t)), (int)w, (int)h);
    } else if (filter == 8 || filter == 9) {
        coreshared::xbr4xUpscale(srcPx, (unsigned)w, (unsigned)h, (size_t)w, s_dfBuf4x, s_dfBuf2x);
    } else {
        hq4x_32_rb(srcPx, (uint32_t)(w * sizeof(uint32_t)),
                   s_dfBuf4x, (uint32_t)(outW * sizeof(uint32_t)), (int)w, (int)h);
    }
    const uint32_t* outBuf = is4x ? s_dfBuf4x : s_dfBuf2x;
    argbToRgbaBytes(outBuf, dst, outW * outH);
    return (int)(outW * outH * 4);
}

int g_failures = 0;
#define CHECK(cond, msg) do { \
    if (cond) printf("  PASS: %s\n", msg); \
    else { printf("  FAIL: %s\n", msg); ++g_failures; } \
} while (0)

int main() {
    // 64x32 test pattern: sharp 2-color quadrant edges (xBR/HQx edge cases)
    const int W = 64, H = 32;
    std::vector<uint32_t> src(W * H);
    for (int y = 0; y < H; ++y)
        for (int x = 0; x < W; ++x) {
            uint32_t c;
            if (x < W / 2 && y < H / 2)      c = 0xFFFF0000u; // red   (0xAARRGGBB)
            else if (x >= W / 2 && y < H/2)  c = 0xFF00FF00u; // green
            else if (x < W / 2)              c = 0xFF0000FFu; // blue
            else                             c = 0xFFFFFFFFu; // white
            src[(size_t)y * W + x] = c;
        }

    std::vector<uint8_t> dst(kDfMaxSrcW * 4 * kDfMaxSrcH * 4 * 4);

    printf("[1] 2x filters (4=xbr, 5=hq2x, 7=xbr+dot)\n");
    for (int f : {4, 5, 7}) {
        int n = applyUpscaleFilter(f, src.data(), W, H, dst.data());
        char msg[64]; snprintf(msg, sizeof msg, "filter %d -> bytes=%d (expect %d)", f, n, W*H*2*2*4);
        CHECK(n == W * H * 2 * 2 * 4, msg);
        (void)n;
    }
    // hq2x (5) content sanity: corner of quadrant0 must stay red-ish
    {
        applyUpscaleFilter(5, src.data(), W, H, dst.data());
        int outW = W * 2;
        const uint8_t* px = dst.data() + (4 * outW + 4) * 4; // (4,2) deep inside quadrant 0
        bool redOk = px[0] > 200 && px[1] < 100 && px[2] < 100 && px[3] == 255;
        CHECK(redOk, "hq2x keeps red quadrant red (RGBA byte order)");
    }
    // xbr (4) content sanity: same spot
    {
        applyUpscaleFilter(4, src.data(), W, H, dst.data());
        int outW = W * 2;
        const uint8_t* px = dst.data() + (4 * outW + 4) * 4;
        bool redOk = px[0] > 200 && px[1] < 100 && px[2] < 100 && px[3] == 255;
        CHECK(redOk, "xbr keeps red quadrant red (RGBA byte order)");
    }
    // edge sharpness: row just left of the vertical quadrant boundary stays red
    {
        applyUpscaleFilter(4, src.data(), W, H, dst.data());
        int outW = W * 2;
        // src boundary at x=32 -> out x=62..63 (before boundary), y=4
        const uint8_t* px = dst.data() + (4 * outW + 62) * 4;
        bool edgeOk = px[0] > 200 && px[1] < 100 && px[2] < 100;
        CHECK(edgeOk, "xbr preserves hard vertical edge (no 1px bleed left of boundary)");
    }

    printf("[2] 4x filters (6=hq4x, 8=4xbr, 9=4xbr+dot, 10=hq4x+dot)\n");
    for (int f : {6, 8, 9, 10}) {
        int n = applyUpscaleFilter(f, src.data(), W, H, dst.data());
        char msg[64]; snprintf(msg, sizeof msg, "filter %d -> bytes=%d (expect %d)", f, n, W*H*4*4*4);
        CHECK(n == W * H * 4 * 4 * 4, msg);
    }
    // hq4x content sanity
    {
        applyUpscaleFilter(6, src.data(), W, H, dst.data());
        int outW = W * 4;
        const uint8_t* px = dst.data() + (8 * outW + 8) * 4;
        bool redOk = px[0] > 200 && px[1] < 100 && px[2] < 100 && px[3] == 255;
        CHECK(redOk, "hq4x keeps red quadrant red");
    }
    // 4xbr cascade sanity (mid-buffer path)
    {
        applyUpscaleFilter(8, src.data(), W, H, dst.data());
        int outW = W * 4;
        const uint8_t* px = dst.data() + (8 * outW + 8) * 4;
        bool redOk = px[0] > 200 && px[1] < 100 && px[2] < 100 && px[3] == 255;
        CHECK(redOk, "4xbr cascade keeps red quadrant red");
    }

    printf("[3] full-size NDS screen (256x192) — no overrun\n");
    {
        std::vector<uint32_t> big(256 * 192);
        for (size_t i = 0; i < big.size(); ++i) big[i] = (uint32_t)(0xFF000000u | (i & 0xFFFFFF));
        int n = applyUpscaleFilter(8, big.data(), 256, 192, dst.data());
        CHECK(n == 256 * 4 * 192 * 4 * 4, "4xbr at 256x192 -> 1024x768 output");
        n = applyUpscaleFilter(5, big.data(), 256, 192, dst.data());
        CHECK(n == 256 * 2 * 192 * 2 * 4, "hq2x at 256x192 -> 512x384 output");
    }

    printf("[4] unsupported filter / bad args rejected\n");
    CHECK(applyUpscaleFilter(1, src.data(), W, H, dst.data()) == 0, "overlay filter(1) returns 0");
    CHECK(applyUpscaleFilter(0, src.data(), W, H, dst.data()) == 0, "filter(0) returns 0");
    CHECK(applyUpscaleFilter(5, src.data(), 300, 192, dst.data()) == 0, "oversize width rejected");

    if (g_failures == 0) printf("\nALL TESTS PASSED\n");
    else printf("\n%d TEST(S) FAILED\n", g_failures);
    return g_failures == 0 ? 0 : 1;
}
