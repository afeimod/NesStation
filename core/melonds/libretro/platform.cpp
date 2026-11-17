/*
    melonDS libretro platform abstraction layer
    Rewritten for melonDS 1.1 API (melonDS::Platform namespace)
*/

#include <cstdio>
#include <cstring>
#include <string>
#include <cstdarg>
#include <ctime>

#if defined(__ANDROID__)
#include <android/log.h>
#endif

#if defined(_WIN32) && !defined(_XBOX)
#include <winsock2.h>
#include <windows.h>
#include <ws2tcpip.h>
#define socket_t    SOCKET
#define sockaddr_t  SOCKADDR
#define pcap_dev_name description
#else
#include <unistd.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <time.h>
#include <dlfcn.h>
#define socket_t    int
#define sockaddr_t  struct sockaddr
#define closesocket close
#define pcap_dev_name name
#endif

#if defined(__HAIKU__)
#include <sys/select.h>
#endif

#ifdef HAVE_PCAP
#include "libui_sdl/LAN_PCap.h"
#include "libui_sdl/LAN_Socket.h"
#endif

#ifdef HAVE_LIBNX
#include <switch/services/bsd.h>
#endif

#ifndef HAVE_WIFI
#define SO_REUSEADDR 0
#define SO_BROADCAST 0
#define socket(domain, type, protocol) NULL
#define bind_socket(sockfd, addr, addrlen) -1
#define setsockopt(sockfd, level, optname, optval, optlen) -1
#define sendto(sockfd, buf, len, flags, dest_addr, addrlen) 0
#define recvfrom(sockfd, buf, len, flags, src_addr, addrlen) 0
#else
#define bind_socket(sockfd, addr, addrlen) bind(sockfd, addr, addrlen)
#endif

#ifdef HAVE_THREADS
#include <stdlib.h>
#include <rthreads/rthreads.h>
#include <rthreads/rsemaphore.h>
#endif

#include <streams/file_stream.h>
#include <retro_timers.h>

#include "types.h"
#include "Platform.h"
#include "Config.h"

#include "frontend/mic_blow.h"

using namespace melonDS;

extern char retro_base_directory[4096];

#ifndef INVALID_SOCKET
#define INVALID_SOCKET  (socket_t)-1
#endif

#define NIFI_VER 1

socket_t MPSocket;
sockaddr_t MPSendAddr;
u8 PacketBuffer[2048];

// Shared mic state (set by libretro.cpp)
extern int mic_noise_type;
extern bool mic_noise_held;

// Save file paths (set by libretro.cpp)
extern std::string retro_save_path;
extern std::string retro_gba_save_path;

// ========== FileHandle implementation ==========
// FileHandle wraps a FILE* (from file_stream_transforms, which is VFS-aware)
struct melonDS::Platform::FileHandle
{
    FILE* fp;
};

// Convert FileMode to fopen() mode string
static const char* filemode_to_fopen(melonDS::Platform::FileMode mode)
{
    bool read  = mode & melonDS::Platform::FileMode::Read;
    bool write = mode & melonDS::Platform::FileMode::Write;
    bool preserve = mode & melonDS::Platform::FileMode::Preserve;
    //bool no_create = mode & melonDS::Platform::FileMode::NoCreate;
    bool append = mode & melonDS::Platform::FileMode::Append;

    if (read && !write)
        return "rb";
    if (!read && write && append)
        return "ab";
    if (!read && write && preserve)
        return "ab";
    if (!read && write)
        return "wb";
    if (read && write && preserve)
        return "r+b";
    if (read && write)
        return "w+b";

    return "rb";
}

namespace melonDS { namespace Platform {

// ========== File Operations ==========

std::string GetLocalFilePath(const std::string& filename)
{
    return std::string(retro_base_directory) + "/" + filename;
}

FileHandle* OpenFile(const std::string& path, FileMode mode)
{
    const char* fmode = filemode_to_fopen(mode);
    if (!fmode) return nullptr;

    FILE* fp = fopen(path.c_str(), fmode);
    if (!fp) return nullptr;

    FileHandle* handle = new FileHandle;
    handle->fp = fp;
    return handle;
}

FileHandle* OpenLocalFile(const std::string& path, FileMode mode)
{
    return OpenFile(GetLocalFilePath(path), mode);
}

bool FileExists(const std::string& name)
{
    FILE* fp = fopen(name.c_str(), "rb");
    if (fp)
    {
        fclose(fp);
        return true;
    }
    return false;
}

bool LocalFileExists(const std::string& name)
{
    return FileExists(GetLocalFilePath(name));
}

bool CheckFileWritable(const std::string& filepath)
{
    FILE* fp = fopen(filepath.c_str(), "ab");
    if (fp)
    {
        fclose(fp);
        return true;
    }
    return false;
}

bool CheckLocalFileWritable(const std::string& filepath)
{
    return CheckFileWritable(GetLocalFilePath(filepath));
}

bool CloseFile(FileHandle* file)
{
    if (!file) return false;
    int ret = fclose(file->fp);
    delete file;
    return ret == 0;
}

bool IsEndOfFile(FileHandle* file)
{
    if (!file) return true;
    return feof(file->fp) != 0;
}

bool FileReadLine(char* str, int count, FileHandle* file)
{
    if (!file) return false;
    return fgets(str, count, file->fp) != nullptr;
}

u64 FilePosition(FileHandle* file)
{
    if (!file) return 0;
    return ftell(file->fp);
}

bool FileSeek(FileHandle* file, s64 offset, FileSeekOrigin origin)
{
    if (!file) return false;
    int whence;
    switch (origin)
    {
        case FileSeekOrigin::Start:   whence = SEEK_SET; break;
        case FileSeekOrigin::Current: whence = SEEK_CUR; break;
        case FileSeekOrigin::End:     whence = SEEK_END; break;
        default: return false;
    }
    return fseeko(file->fp, offset, whence) == 0;
}

void FileRewind(FileHandle* file)
{
    if (file) rewind(file->fp);
}

u64 FileRead(void* data, u64 size, u64 count, FileHandle* file)
{
    if (!file || !data) return 0;
    return fread(data, size, count, file->fp);
}

bool FileFlush(FileHandle* file)
{
    if (!file) return false;
    return fflush(file->fp) == 0;
}

u64 FileWrite(const void* data, u64 size, u64 count, FileHandle* file)
{
    if (!file || !data) return 0;
    return fwrite(data, size, count, file->fp);
}

u64 FileWriteFormatted(FileHandle* file, const char* fmt, ...)
{
    if (!file) return 0;
    va_list args;
    va_start(args, fmt);
    int ret = vfprintf(file->fp, fmt, args);
    va_end(args);
    return ret < 0 ? 0 : (u64)ret;
}

u64 FileLength(FileHandle* file)
{
    if (!file) return 0;
    long pos = ftell(file->fp);
    fseek(file->fp, 0, SEEK_END);
    long len = ftell(file->fp);
    fseek(file->fp, pos, SEEK_SET);
    return len < 0 ? 0 : (u64)len;
}

// ========== Logging ==========

void Log(LogLevel level, const char* fmt, ...)
{
    (void)level;
    va_list args;
    va_start(args, fmt);
    vfprintf(stderr, fmt, args);
    va_end(args);
}

// ========== Signal/Stop ==========

void SignalStop(StopReason reason, void* userdata)
{
    // Not used in libretro; the frontend handles stopping
    (void)reason;
    (void)userdata;
}

// ========== Threading ==========

struct ThreadData
{
    std::function<void()> fn;
};

static void function_trampoline(void* param)
{
    ThreadData* data = (ThreadData*)param;
    data->fn();
    delete data;
}

Thread* Thread_Create(std::function<void()> func)
{
#ifdef HAVE_THREADS
    return (Thread*)sthread_create(function_trampoline, new ThreadData{func});
#else
    return nullptr;
#endif
}

void Thread_Free(Thread* thread)
{
#ifdef HAVE_THREADS
    if (thread)
        sthread_detach((sthread_t*)thread);
#endif
}

void Thread_Wait(Thread* thread)
{
#ifdef HAVE_THREADS
    if (thread)
        sthread_join((sthread_t*)thread);
#endif
}

// ========== Semaphore ==========

Semaphore* Semaphore_Create()
{
#ifdef HAVE_THREADS
    ssem_t* sem = ssem_new(0);
    if (sem)
        return (Semaphore*)sem;
#endif
    return nullptr;
}

void Semaphore_Free(Semaphore* sema)
{
#ifdef HAVE_THREADS
    if (sema)
        ssem_free((ssem_t*)sema);
#endif
}

void Semaphore_Reset(Semaphore* sema)
{
#ifdef HAVE_THREADS
    if (sema)
    {
        while (ssem_get((ssem_t*)sema) > 0)
            ssem_trywait((ssem_t*)sema);
    }
#endif
}

void Semaphore_Wait(Semaphore* sema)
{
#ifdef HAVE_THREADS
    if (sema)
        ssem_wait((ssem_t*)sema);
#endif
}

bool Semaphore_TryWait(Semaphore* sema, int timeout_ms)
{
#ifdef HAVE_THREADS
    if (!sema) return false;

    if (timeout_ms <= 0)
    {
        return ssem_trywait((ssem_t*)sema) == 0;
    }

    // Poll-based wait with timeout
    // TODO: use a proper timed wait if available
    u64 start = GetUSCount();
    do
    {
        if (ssem_trywait((ssem_t*)sema) == 0)
            return true;
        Sleep(1000); // 1ms
    } while ((GetUSCount() - start) < (u64)timeout_ms * 1000);

    return false;
#else
    return false;
#endif
}

void Semaphore_Post(Semaphore* sema, int count)
{
#ifdef HAVE_THREADS
    if (sema)
    {
        for (int i = 0; i < count; i++)
            ssem_signal((ssem_t*)sema);
    }
#endif
}

// ========== Mutex ==========

Mutex* Mutex_Create()
{
#ifdef HAVE_THREADS
    return (Mutex*)slock_new();
#else
    return nullptr;
#endif
}

void Mutex_Free(Mutex* mutex)
{
#ifdef HAVE_THREADS
    if (mutex)
        slock_free((slock_t*)mutex);
#endif
}

void Mutex_Lock(Mutex* mutex)
{
#ifdef HAVE_THREADS
    if (mutex)
        slock_lock((slock_t*)mutex);
#endif
}

void Mutex_Unlock(Mutex* mutex)
{
#ifdef HAVE_THREADS
    if (mutex)
        slock_unlock((slock_t*)mutex);
#endif
}

bool Mutex_TryLock(Mutex* mutex)
{
#ifdef HAVE_THREADS
    if (mutex)
        return slock_try_lock((slock_t*)mutex);
#endif
    return true;
}

// ========== Timing ==========

void Sleep(u64 usecs)
{
    retro_sleep(usecs / 1000);
}

u64 GetMSCount()
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (u64)ts.tv_sec * 1000 + (u64)ts.tv_nsec / 1000000;
}

u64 GetUSCount()
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (u64)ts.tv_sec * 1000000 + (u64)ts.tv_nsec / 1000;
}

// ========== Save Callbacks ==========

void WriteNDSSave(const u8* savedata, u32 savelen, u32 writeoffset, u32 writelen, void* userdata)
{
    if (retro_save_path.empty() || !savedata) return;

    FILE* fp = fopen(retro_save_path.c_str(), "r+b");
    if (!fp)
    {
        fp = fopen(retro_save_path.c_str(), "wb");
        if (!fp) return;
    }

    // Write the entire save buffer
    fwrite(savedata, 1, savelen, fp);
    fclose(fp);
}

void WriteGBASave(const u8* savedata, u32 savelen, u32 writeoffset, u32 writelen, void* userdata)
{
    if (retro_gba_save_path.empty() || !savedata) return;

    FILE* fp = fopen(retro_gba_save_path.c_str(), "r+b");
    if (!fp)
    {
        fp = fopen(retro_gba_save_path.c_str(), "wb");
        if (!fp) return;
    }

    fwrite(savedata, 1, savelen, fp);
    fclose(fp);
}

void WriteFirmware(const Firmware& firmware, u32 writeoffset, u32 writelen, void* userdata)
{
    // Firmware writes are not persisted in libretro mode
    (void)firmware;
    (void)writeoffset;
    (void)writelen;
    (void)userdata;
}

void WriteDateTime(int year, int month, int day, int hour, int minute, int second, void* userdata)
{
    (void)year;
    (void)month;
    (void)day;
    (void)hour;
    (void)minute;
    (void)second;
    (void)userdata;
}

// ========== Microphone ==========

void Mic_Start(void* userdata)
{
    (void)userdata;
}

void Mic_Stop(void* userdata)
{
    (void)userdata;
}

int Mic_ReadInput(s16* data, int maxlength, void* userdata)
{
    (void)userdata;
    if (!data || maxlength <= 0) return 0;

    if (mic_noise_held)
    {
        int samples = (maxlength < 735) ? maxlength : 735;

        if (mic_noise_type == 0)
        {
            // Random noise
            for (int i = 0; i < samples; i++)
                data[i] = (s16)(rand() & 0xFFFF);
        }
        else
        {
            // Blow noise from mic_blow.h
            static int sample_pos = 0;
            int sample_len = sizeof(mic_blow) / sizeof(u16);

            for (int i = 0; i < samples; i++)
            {
                data[i] = (s16)mic_blow[sample_pos];
                sample_pos++;
                if (sample_pos >= sample_len)
                    sample_pos = 0;
            }
        }

        return samples;
    }

    return 0; // Silence
}

// ========== Multiplayer ==========

void MP_Begin(void* userdata) { (void)userdata; }
void MP_End(void* userdata)   { (void)userdata; }

int MP_SendPacket(u8* data, int len, u64 timestamp, void* userdata)
{
    (void)timestamp;
    (void)userdata;
    if (MPSocket < 0) return 0;
    if (len > 2048-8) return 0;

    *(u32*)&PacketBuffer[0] = htonl(0x4946494E); // NIFI
    PacketBuffer[4] = NIFI_VER;
    PacketBuffer[5] = 0;
    *(u16*)&PacketBuffer[6] = htons(len);
    memcpy(&PacketBuffer[8], data, len);

    int slen = sendto(MPSocket, (const char*)PacketBuffer, len+8, 0, &MPSendAddr, sizeof(sockaddr_t));
    if (slen < 8) return 0;
    return slen - 8;
}

int MP_RecvPacket(u8* data, u64* timestamp, void* userdata)
{
    (void)timestamp;
    (void)userdata;
    if (MPSocket < 0) return 0;

    fd_set fd;
    struct timeval tv;
    FD_ZERO(&fd);
    FD_SET(MPSocket, &fd);
    tv.tv_sec = 0;
    tv.tv_usec = 0;

    if (!select(MPSocket+1, &fd, 0, 0, &tv))
        return 0;

    sockaddr_t fromAddr;
    socklen_t fromLen = sizeof(sockaddr_t);
    int rlen = recvfrom(MPSocket, (char*)PacketBuffer, 2048, 0, &fromAddr, &fromLen);
    if (rlen < 8+24) return 0;
    rlen -= 8;

    if (ntohl(*(u32*)&PacketBuffer[0]) != 0x4946494E) return 0;
    if (PacketBuffer[4] != NIFI_VER) return 0;
    if (ntohs(*(u16*)&PacketBuffer[6]) != rlen) return 0;

    memcpy(data, &PacketBuffer[8], rlen);
    return rlen;
}

int MP_SendCmd(u8* data, int len, u64 timestamp, void* userdata)
{
    return MP_SendPacket(data, len, timestamp, userdata);
}

int MP_SendReply(u8* data, int len, u64 timestamp, u16 aid, void* userdata)
{
    (void)aid;
    return MP_SendPacket(data, len, timestamp, userdata);
}

int MP_SendAck(u8* data, int len, u64 timestamp, void* userdata)
{
    return MP_SendPacket(data, len, timestamp, userdata);
}

int MP_RecvHostPacket(u8* data, u64* timestamp, void* userdata)
{
    return MP_RecvPacket(data, timestamp, userdata);
}

u16 MP_RecvReplies(u8* data, u64 timestamp, u16 aidmask, void* userdata)
{
    (void)data;
    (void)timestamp;
    (void)aidmask;
    (void)userdata;
    return 0;
}

// ========== Network ==========

int Net_SendPacket(u8* data, int len, void* userdata)
{
    (void)userdata;
    return MP_SendPacket(data, len, 0, nullptr);
}

int Net_RecvPacket(u8* data, void* userdata)
{
    (void)userdata;
    return MP_RecvPacket(data, nullptr, nullptr);
}

// ========== Camera ==========

void Camera_Start(int num, void* userdata)    { (void)num; (void)userdata; }
void Camera_Stop(int num, void* userdata)     { (void)num; (void)userdata; }
void Camera_CaptureFrame(int num, u32* frame, int width, int height, bool yuv, void* userdata)
{
    (void)num; (void)frame; (void)width; (void)height; (void)yuv; (void)userdata;
}

// ========== AAC ==========

AACDecoder* AAC_Init()                                    { return nullptr; }
void AAC_DeInit(AACDecoder* dec)                          { (void)dec; }
bool AAC_Configure(AACDecoder* dec, int frequency, int channels)
{
    (void)dec; (void)frequency; (void)channels;
    return false;
}
bool AAC_DecodeFrame(AACDecoder* dec, const void* input, int inputlen, void* output, int outputlen)
{
    (void)dec; (void)input; (void)inputlen; (void)output; (void)outputlen;
    return false;
}

// ========== Addon Inputs ==========

bool Addon_KeyDown(KeyType type, void* userdata)
{
    (void)type; (void)userdata;
    return false;
}

void Addon_RumbleStart(u32 len, void* userdata)  { (void)len; (void)userdata; }
void Addon_RumbleStop(void* userdata)             { (void)userdata; }

float Addon_MotionQuery(MotionQueryType type, void* userdata)
{
    (void)type; (void)userdata;
    return 0.0f;
}

// ========== Dynamic Library ==========

struct DynamicLibrary
{
#if defined(_WIN32) && !defined(_XBOX)
    HMODULE handle;
#else
    void* handle;
#endif
};

DynamicLibrary* DynamicLibrary_Load(const char* lib)
{
    DynamicLibrary* libHandle = new DynamicLibrary();
    if (!libHandle)
        return nullptr;

#if defined(_WIN32) && !defined(_XBOX)
    libHandle->handle = LoadLibraryA(lib);
#else
    libHandle->handle = dlopen(lib, RTLD_LAZY);
#endif

    if (!libHandle->handle)
    {
        delete libHandle;
        return nullptr;
    }

    return libHandle;
}

void DynamicLibrary_Unload(DynamicLibrary* lib)
{
    if (!lib)
        return;

#if defined(_WIN32) && !defined(_XBOX)
    if (lib->handle)
        FreeLibrary(lib->handle);
#else
    if (lib->handle)
        dlclose(lib->handle);
#endif

    delete lib;
}

void* DynamicLibrary_LoadFunction(DynamicLibrary* lib, const char* name)
{
#if defined(_WIN32) && !defined(_XBOX)
    if (lib && lib->handle)
    {
        void* fun = (void*)GetProcAddress(lib->handle, name);
        if (fun)
            return fun;
    }
#endif

#if !defined(_WIN32) || defined(_XBOX)
    if (lib && lib->handle)
    {
        void* fun = dlsym(lib->handle, name);
        if (fun)
            return fun;
    }
    // Fallback: some Android devices restrict dlopen() of system libs via
    // linker namespaces, but libandroid.so is already linked into the APK
    // (-landroid), so its exported symbols are resolvable in the global
    // scope. This keeps melonDS JIT FastMem working everywhere.
    return dlsym(RTLD_DEFAULT, name);
#else
    return nullptr;
#endif
}

// ========== Legacy LAN (pcap/socket) ==========

bool LAN_Init()
{
#ifdef HAVE_PCAP
    if (Config::DirectLAN)
    {
        if (!LAN_PCap::Init(true))
            return false;
    }
    else
    {
        if (!LAN_Socket::Init())
            return false;
    }
    return true;
#else
    return false;
#endif
}

void LAN_DeInit()
{
#ifdef HAVE_PCAP
    LAN_PCap::DeInit();
    LAN_Socket::DeInit();
#endif
}

int LAN_SendPacket(u8* data, int len)
{
#ifdef HAVE_PCAP
    if (Config::DirectLAN)
        return LAN_PCap::SendPacket(data, len);
    else
        return LAN_Socket::SendPacket(data, len);
#else
    return 0;
#endif
}

int LAN_RecvPacket(u8* data)
{
#ifdef HAVE_PCAP
    if (Config::DirectLAN)
        return LAN_PCap::RecvPacket(data);
    else
        return LAN_Socket::RecvPacket(data);
#else
    return 0;
#endif
}

#ifdef HAVE_OPENGL
void* GL_GetProcAddress(const char* proc)
{
    (void)proc;
    return nullptr;
}
#endif

}} // namespace melonDS::Platform


// ========== Legacy MP/LAN functions (called from elsewhere) ==========

bool MP_Init()
{
    int opt_true = 1;
    int res;

#ifdef _WIN32
    WSADATA wsadata;
    if (WSAStartup(MAKEWORD(2, 2), &wsadata) != 0)
        return false;
#endif

    MPSocket = socket(AF_INET, SOCK_DGRAM, 0);
    if (MPSocket < 0) return false;

    res = setsockopt(MPSocket, SOL_SOCKET, SO_REUSEADDR, (const char*)&opt_true, sizeof(int));
    if (res < 0)
    {
        closesocket(MPSocket);
        MPSocket = INVALID_SOCKET;
        return false;
    }

    sockaddr_t saddr;
    saddr.sa_family = AF_INET;
    *(u32*)&saddr.sa_data[2] = htonl(INADDR_ANY);
    *(u16*)&saddr.sa_data[0] = htons(7064);
    res = bind_socket(MPSocket, &saddr, sizeof(sockaddr_t));
    if (res < 0)
    {
        closesocket(MPSocket);
        MPSocket = INVALID_SOCKET;
        return false;
    }

    res = setsockopt(MPSocket, SOL_SOCKET, SO_BROADCAST, (const char*)&opt_true, sizeof(int));
    if (res < 0)
    {
        closesocket(MPSocket);
        MPSocket = INVALID_SOCKET;
        return false;
    }

    MPSendAddr.sa_family = AF_INET;
    *(u32*)&MPSendAddr.sa_data[2] = htonl(INADDR_BROADCAST);
    *(u16*)&MPSendAddr.sa_data[0] = htons(7064);

    return true;
}

void MP_DeInit()
{
    if (MPSocket >= 0)
        closesocket(MPSocket);
#ifdef _WIN32
    WSACleanup();
#endif
}
