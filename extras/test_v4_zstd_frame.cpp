// 从 native_savestate.cpp 原样提取 ZstdFrameWriter 与 ValidateFrame ——
// 保证测试代码 == 交付代码（提取自 1078-1208 行，仅去掉注释中中文标点差异）。
// ApplyFrame 的 zstd 流式解码循环 + run 合并逻辑以同构副本验证。
#include <zstd.h>
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <random>
#include <string>
#include <vector>
#include <cassert>
#include <cstdio>

using u8 = uint8_t;
using u64 = uint64_t;
constexpr size_t kPageSize = 4 * 1024;

// ===================== 提取区开始（原样拷贝） =====================
constexpr int kZstdLevel = 3;                // Cemu 同款级别
constexpr size_t kZstdOutChunk = 1 << 20;    // 1MB 出块
constexpr size_t kZstdInChunk = 256 * 1024;  // 256KB 入块

class ZstdFrameWriter {
public:
    explicit ZstdFrameWriter(std::ofstream& out) : m_out{out}, m_buf(kZstdOutChunk) {}

    ~ZstdFrameWriter() {
        if (m_cctx != nullptr) {
            ZSTD_freeCCtx(m_cctx);
        }
    }

    bool Begin(u64 pledged_src_size) {
        if (m_cctx == nullptr) {
            m_cctx = ZSTD_createCCtx();
            if (m_cctx == nullptr) {
                return false;
            }
        }
        ZSTD_CCtx_reset(m_cctx, ZSTD_reset_session_only);
        ZSTD_CCtx_setParameter(m_cctx, ZSTD_c_compressionLevel, kZstdLevel);
        ZSTD_CCtx_setParameter(m_cctx, ZSTD_c_checksumFlag, 1);
        if (ZSTD_isError(ZSTD_CCtx_setPledgedSrcSize(m_cctx, pledged_src_size))) {
            return false;
        }
        m_written = 0;
        return true;
    }

    bool Write(const void* data, size_t len) {
        ZSTD_inBuffer in{data, len, 0};
        while (in.pos < in.size) {
            ZSTD_outBuffer out{m_buf.data(), m_buf.size(), 0};
            const size_t r = ZSTD_compressStream2(m_cctx, &out, &in, ZSTD_e_continue);
            if (ZSTD_isError(r)) {
                return false;
            }
            if (!WriteOut(out.pos)) {
                return false;
            }
        }
        return true;
    }

    bool Finish() {
        ZSTD_inBuffer in{nullptr, 0, 0};
        for (;;) {
            ZSTD_outBuffer out{m_buf.data(), m_buf.size(), 0};
            const size_t r = ZSTD_compressStream2(m_cctx, &out, &in, ZSTD_e_end);
            if (ZSTD_isError(r)) {
                return false;
            }
            if (!WriteOut(out.pos)) {
                return false;
            }
            if (r == 0) {
                return true; // 帧完成、输入全部消费（pledged 校验通过）
            }
        }
    }

    u64 written() const {
        return m_written;
    }

private:
    bool WriteOut(size_t bytes) {
        if (bytes == 0) {
            return true;
        }
        m_out.write(reinterpret_cast<const char*>(m_buf.data()),
                    static_cast<std::streamsize>(bytes));
        if (!m_out) {
            return false;
        }
        m_written += bytes;
        return true;
    }

    std::ofstream& m_out;
    ZSTD_CCtx* m_cctx{};
    std::vector<u8> m_buf;
    u64 m_written{};
};

bool ValidateFrame(std::ifstream& in, u64 uncompressed, u64 compressed) {
    ZSTD_DCtx* dctx = ZSTD_createDCtx();
    if (dctx == nullptr) {
        return false;
    }
    std::vector<u8> inBuf(kZstdInChunk);
    std::vector<u8> discard(kZstdOutChunk);
    u64 inRemaining = compressed;
    u64 outTotal = 0;
    bool frameDone = false;
    bool ok = false;
    while (!frameDone && inRemaining > 0) {
        const size_t toRead = static_cast<size_t>(std::min<u64>(kZstdInChunk, inRemaining));
        in.read(reinterpret_cast<char*>(inBuf.data()),
                static_cast<std::streamsize>(toRead));
        if (static_cast<size_t>(in.gcount()) != toRead) {
            goto finish;
        }
        inRemaining -= toRead;
        ZSTD_inBuffer inDesc{inBuf.data(), toRead, 0};
        while (inDesc.pos < inDesc.size) {
            ZSTD_outBuffer outDesc{discard.data(), discard.size(), 0};
            const size_t r = ZSTD_decompressStream(dctx, &outDesc, &inDesc);
            if (ZSTD_isError(r)) {
                goto finish;
            }
            outTotal += outDesc.pos;
            if (outTotal > uncompressed) {
                goto finish; // 解码量超过记录范围
            }
            if (r == 0) {
                frameDone = true;
                break;
            }
        }
    }
    ok = frameDone && inRemaining == 0 && outTotal == uncompressed;
finish:
    ZSTD_freeDCtx(dctx);
    return ok;
}
// ===================== 提取区结束 =====================

// ---- ApplyFrame 同构测试副本：假想 guest 内存 + 连续页表 ----
struct FakeMemory {
    std::vector<u8> mem;
    size_t page_count;
    u8* GetPtr(u64 va) {
        const size_t page = va / kPageSize;
        if (page >= page_count) return nullptr;
        return mem.data() + page * kPageSize;
    }
};

bool ApplyFrameFake(std::ifstream& in, FakeMemory& mem, u64 va, u64 uncompressed,
                    u64 compressed, std::string* err) {
    ZSTD_DCtx* dctx = ZSTD_createDCtx();
    if (dctx == nullptr) { *err = "dctx"; return false; }
    std::vector<u8> inBuf(kZstdInChunk);
    std::vector<u8> outBuf(kZstdOutChunk);
    u64 inRemaining = compressed, outTotal = 0;
    bool frameDone = false, ok = false;
    while (!frameDone && inRemaining > 0) {
        const size_t toRead = static_cast<size_t>(std::min<u64>(kZstdInChunk, inRemaining));
        in.read(reinterpret_cast<char*>(inBuf.data()), static_cast<std::streamsize>(toRead));
        if (static_cast<size_t>(in.gcount()) != toRead) { *err = "read"; goto finish; }
        inRemaining -= toRead;
        ZSTD_inBuffer inDesc{inBuf.data(), toRead, 0};
        while (inDesc.pos < inDesc.size) {
            ZSTD_outBuffer outDesc{outBuf.data(), outBuf.size(), 0};
            const size_t r = ZSTD_decompressStream(dctx, &outDesc, &inDesc);
            if (ZSTD_isError(r)) { *err = std::string(ZSTD_getErrorName(r)); goto finish; }
            if (outDesc.pos > 0) {
                if (outTotal + outDesc.pos > uncompressed) { *err = "overrun"; goto finish; }
                const u64 chunk_va = va + outTotal;
                const u8* src = outBuf.data();
                size_t off = 0;
                while (off < outDesc.pos) {
                    const u64 page_va = chunk_va + off;
                    const size_t in_page = kPageSize - (page_va % kPageSize);
                    const size_t take = std::min(in_page, outDesc.pos - off);
                    u8* dst = mem.GetPtr(page_va);
                    if (dst == nullptr) { off += take; continue; }
                    if (take == kPageSize) {
                        size_t run = kPageSize;
                        while (off + run + kPageSize <= outDesc.pos) {
                            u8* const next = mem.GetPtr(page_va + run);
                            if (next != dst + run) break;
                            run += kPageSize;
                        }
                        std::memcpy(dst, src + off, run);
                        off += run;
                    } else {
                        std::memcpy(dst, src + off, take);
                        off += take;
                    }
                }
                outTotal += outDesc.pos;
            }
            if (r == 0) { frameDone = true; break; }
        }
    }
    ok = frameDone && inRemaining == 0 && outTotal == uncompressed;
finish:
    ZSTD_freeDCtx(dctx);
    return ok;
}

int main() {
    std::string tmp = "/tmp/v4_frame_test.bin";
    std::mt19937_64 rng(12345);

    // ---- 用例 1：12MB+ 随机/零页混合，随机长度分片喂入（模拟 run） ----
    const u64 block_size = 12ull * 1024 * 1024 + 7777;
    std::vector<u8> src(block_size);
    for (u64 i = 0; i < block_size; ++i) {
        src[i] = ((i / kPageSize) % 3 == 1) ? 0 : static_cast<u8>(rng());
    }
    u64 compressed1 = 0;
    {
        std::ofstream out(tmp, std::ios::binary | std::ios::trunc);
        ZstdFrameWriter w(out);
        assert(w.Begin(block_size));
        u64 off = 0;
        while (off < block_size) {
            const size_t chunk = static_cast<size_t>(std::min<u64>(rng() % 300000 + 1, block_size - off));
            assert(w.Write(src.data() + off, chunk));
            off += chunk;
        }
        assert(w.Finish());
        compressed1 = w.written();
        printf("case1: uncompressed=%llu compressed=%llu ratio=%.3f\n",
               (unsigned long long)block_size, (unsigned long long)compressed1,
               (double)compressed1 / block_size);
        out.close();

        std::ifstream in(tmp, std::ios::binary);
        assert(ValidateFrame(in, block_size, compressed1));
        const std::streamoff after = in.tellg();
        in.seekg(0, std::ios::end);
        assert(after == in.tellg()); // 帧后流位置 == 帧尾
        printf("case1: validate OK (stream ends at frame boundary)\n");

        FakeMemory mem;
        mem.page_count = block_size / kPageSize + 1;
        mem.mem.assign(mem.page_count * kPageSize, 0xAB);
        in.clear(); in.seekg(0, std::ios::beg);
        std::string err;
        if (!ApplyFrameFake(in, mem, 0, block_size, compressed1, &err)) {
            printf("apply failed: %s\n", err.c_str());
            assert(false);
        }
        assert(std::memcmp(mem.mem.data(), src.data(), block_size) == 0);
        printf("case1: apply + memcmp OK\n");
    }

    // ---- 用例 2：单字节损坏 → 校验和拒绝 ----
    {
        {
            std::fstream f(tmp, std::ios::binary | std::ios::in | std::ios::out);
            f.seekp(100);
            char c = 0; f.read(&c, 1); c ^= 0x55; f.seekp(100); f.write(&c, 1);
        }
        std::ifstream in2(tmp, std::ios::binary);
        const bool v = ValidateFrame(in2, block_size, compressed1);
        printf("case2: corrupt frame rejected = %s\n", v ? "NO(BAD!)" : "YES");
        assert(!v);
    }

    // ---- 用例 3：pledged 不匹配 → Finish 必须失败 ----
    {
        const std::string tmp2 = "/tmp/v4_frame_test2.bin";
        std::ofstream out(tmp2, std::ios::binary | std::ios::trunc);
        ZstdFrameWriter w(out);
        assert(w.Begin(100));
        assert(w.Write(src.data(), 99));
        const bool fin = w.Finish();
        printf("case3: pledged mismatch rejected = %s\n", fin ? "NO(BAD!)" : "YES");
        assert(!fin);
        out.close();
    }

    // ---- 用例 4：64MB 全零（零页为主的游戏内存） ----
    {
        const std::string tmp3 = "/tmp/v4_frame_test3.bin";
        const u64 zsize = 64ull << 20;
        std::vector<u8> zeros(zsize, 0);
        std::ofstream out(tmp3, std::ios::binary | std::ios::trunc);
        ZstdFrameWriter w(out);
        assert(w.Begin(zsize));
        for (u64 o = 0; o < zsize; o += (1 << 20)) {
            assert(w.Write(zeros.data() + o, static_cast<size_t>(std::min<u64>(1 << 20, zsize - o))));
        }
        assert(w.Finish());
        const u64 comp = w.written();
        printf("case4: 64MB zeros -> %llu bytes (%.4f%%)\n",
               (unsigned long long)comp, 100.0 * comp / zsize);
        out.close();
        std::ifstream in(tmp3, std::ios::binary);
        assert(ValidateFrame(in, zsize, comp));
        printf("case4: validate OK\n");
    }

    printf("ALL ZSTD FRAME TESTS PASSED\n");
    return 0;
}
