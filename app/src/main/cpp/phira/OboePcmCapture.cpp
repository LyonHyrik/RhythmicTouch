#include "OboePcmCapture.hpp"

#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <link.h>
#include <pthread.h>
#include <stdio.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include <atomic>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <vector>

#define OBOE_LOG_TAG "OboeCapture"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, OBOE_LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, OBOE_LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, OBOE_LOG_TAG, __VA_ARGS__)

// ══════════════════════════════════════════════════════════════════
// AAudio type definitions (self-contained — no header/link dependency).
// ══════════════════════════════════════════════════════════════════
typedef struct AAudioStreamStruct AAudioStream;
typedef struct AAudioStreamBuilderStruct AAudioStreamBuilder;

typedef int32_t aaudio_result_t;
typedef int32_t aaudio_format_t;
#define AAUDIO_FORMAT_INVALID          (-1)
#define AAUDIO_FORMAT_UNSPECIFIED      0
#define AAUDIO_FORMAT_PCM_I16          1
#define AAUDIO_FORMAT_PCM_FLOAT        2
#define AAUDIO_FORMAT_PCM_I24_PACKED   3
#define AAUDIO_FORMAT_PCM_I32          4

typedef int32_t aaudio_data_callback_result_t;
#define AAUDIO_CALLBACK_RESULT_CONTINUE 0
#define AAUDIO_CALLBACK_RESULT_STOP     1

typedef aaudio_data_callback_result_t (*AAudioStream_dataCallback)(
    AAudioStream* stream, void* userData, void* audioData, int32_t numFrames);

// Function-pointer types for the AAudio entry points we use.
typedef void          (*fn_AAudioStreamBuilder_setDataCallback)(AAudioStreamBuilder*, AAudioStream_dataCallback, void*);
typedef aaudio_format_t (*fn_AAudioStream_getFormat)(AAudioStream*);
typedef int32_t       (*fn_AAudioStream_getChannelCount)(AAudioStream*);
typedef int32_t       (*fn_AAudioStream_getSampleRate)(AAudioStream*);
typedef aaudio_result_t (*fn_AAudioStream_write)(AAudioStream*, const void*, int32_t, int64_t);

// 🔥 NEW: Types for forcing SHARED mode (to match AudioTrack behavior!)
typedef int32_t aaudio_sharing_mode_t;
#define AAUDIO_SHARING_MODE_EXCLUSIVE 0
#define AAUDIO_SHARING_MODE_SHARED     1

typedef int32_t aaudio_performance_mode_t;
#define AAUDIO_PERFORMANCE_MODE_NONE         10
#define AAUDIO_PERFORMANCE_MODE_POWER_SAVING 11
#define AAUDIO_PERFORMANCE_MODE_LOW_LATENCY  12

typedef void (*fn_AAudioStreamBuilder_setSharingMode)(AAudioStreamBuilder*, aaudio_sharing_mode_t);
typedef void (*fn_AAudioStreamBuilder_setPerformanceMode)(AAudioStreamBuilder*, aaudio_performance_mode_t);

namespace phira {

static fn_AAudioStreamBuilder_setDataCallback g_orig_setDataCallback = nullptr;
static fn_AAudioStream_getFormat             g_AAudioStream_getFormat = nullptr;
static fn_AAudioStream_getChannelCount       g_AAudioStream_getChannelCount = nullptr;
static fn_AAudioStream_getSampleRate         g_AAudioStream_getSampleRate = nullptr;
static fn_AAudioStream_write                 g_orig_AAudioStream_write = nullptr;

// 🔥 NEW: Original function pointers for SHARED mode enforcement
static fn_AAudioStreamBuilder_setSharingMode      g_orig_setSharingMode = nullptr;
static fn_AAudioStreamBuilder_setPerformanceMode  g_orig_setPerformanceMode = nullptr;

static fn_AAudioStreamBuilder_setDataCallback g_orig_setDataCallback_forGOT = nullptr;
static fn_AAudioStream_write                 g_orig_AAudioStream_write_forGOT = nullptr;

struct GotPatch {
    void** slot;
    void* original;
};
static std::vector<GotPatch> g_gotPatches;

static AAudioStream_dataCallback g_origDataCallback = nullptr;
static void* g_origUserData = nullptr;
static std::atomic<bool> g_callbackModeActive{false};

static constexpr int kChannelCount = 2;
static constexpr int kRingFrames = 32768;
static constexpr int kRingFloats = kRingFrames * kChannelCount;

static float g_ring[kRingFloats];
static std::atomic<int> g_writeIndex{0};
static std::atomic<int> g_readIndex{0};
static std::atomic<long> g_overflowCount{0};

static JavaVM* g_jvm = nullptr;
static jobject g_bridgeRef = nullptr;
static jmethodID g_onOboeAudioFrame = nullptr;

static std::atomic<bool> g_captureActive{false};
static std::atomic<bool> g_drainRunning{false};
static std::atomic<bool> g_inlineHooked{false};
static std::atomic<int> g_sampleRate{0};
static uint8_t g_origAAudioWritePrologue[32] = {};
static pthread_t g_drainThread{};

static inline void pushStereoFloat(const float* stereo, int numFrames) {
    int write = g_writeIndex.load(std::memory_order_relaxed);
    int read = g_readIndex.load(std::memory_order_acquire);
    int occupied = (write - read + kRingFloats) % kRingFloats;
    int space = kRingFloats - 1 - occupied;
    int spaceFrames = space / kChannelCount;
    int n = numFrames < spaceFrames ? numFrames : spaceFrames;
    if (n < numFrames) {
        long ov = g_overflowCount.fetch_add(1);
        if (ov < 10 || ov % 500 == 0) {
            LOGW("⚠️ Ring buffer overflow #%ld: dropping %d/%d frames (occupied=%d)", ov, numFrames - n, numFrames, occupied);
        }
    }
    for (int f = 0; f < n; ++f) {
        g_ring[write] = stereo[f * 2];
        g_ring[write + 1] = stereo[f * 2 + 1];
        write += kChannelCount;
        if (write >= kRingFloats) write = 0;
    }
    g_writeIndex.store(write, std::memory_order_release);
}

template <typename SampleT>
static inline void convertAndPush(const void* audioData, int numFrames, int channels, float scale) {
    const auto* in = static_cast<const SampleT*>(audioData);
    float buf[256];
    int frame = 0;
    while (frame < numFrames) {
        int batch = numFrames - frame;
        if (batch > 128) batch = 128;
        for (int i = 0; i < batch; ++i) {
            int idx = (frame + i) * channels;
            float l = static_cast<float>(in[idx]) * scale;
            float r = (channels >= 2) ? static_cast<float>(in[idx + 1]) * scale : l;
            buf[i * 2] = l;
            buf[i * 2 + 1] = r;
        }
        pushStereoFloat(buf, batch);
        frame += batch;
    }
}

static std::atomic<int> g_captureCount{0};

static void captureBuffer(const void* audioData, int numFrames, AAudioStream* stream) {
    if (!audioData || numFrames <= 0) return;
    
    if (g_AAudioStream_getSampleRate) {
        int32_t sr = g_AAudioStream_getSampleRate(stream);
        if (sr > 0) g_sampleRate.store(sr, std::memory_order_relaxed);
    }
    
    int capNum = g_captureCount.fetch_add(1);
    
    if (capNum < 20 || (capNum > 0 && capNum % 1000 == 0)) {
        int channels = kChannelCount;
        if (g_AAudioStream_getChannelCount) {
            int32_t c = g_AAudioStream_getChannelCount(stream);
            if (c > 0) channels = c;
        }
        
        aaudio_format_t fmt = AAUDIO_FORMAT_UNSPECIFIED;
        if (g_AAudioStream_getFormat) {
            fmt = g_AAudioStream_getFormat(stream);
        }
        
        const char* fmtName = "UNKNOWN";
        switch (fmt) {
            case AAUDIO_FORMAT_PCM_FLOAT:      fmtName = "FLOAT"; break;
            case AAUDIO_FORMAT_PCM_I16:        fmtName = "I16"; break;
            case AAUDIO_FORMAT_PCM_I24_PACKED: fmtName = "I24"; break;
            case AAUDIO_FORMAT_PCM_I32:        fmtName = "I32"; break;
            default: break;
        }
        
        LOGI("📥 captureBuffer #%d: %d frames, %d ch, format=%s", 
             capNum, numFrames, channels, fmtName);
    }

    int channels = kChannelCount;
    if (g_AAudioStream_getChannelCount) {
        int32_t c = g_AAudioStream_getChannelCount(stream);
        if (c > 0) channels = c;
    }

    aaudio_format_t fmt = AAUDIO_FORMAT_UNSPECIFIED;
    if (g_AAudioStream_getFormat) {
        fmt = g_AAudioStream_getFormat(stream);
    }

    switch (fmt) {
        case AAUDIO_FORMAT_PCM_FLOAT:
            convertAndPush<float>(audioData, numFrames, channels, 1.0f);
            break;
        case AAUDIO_FORMAT_PCM_I16:
            convertAndPush<int16_t>(audioData, numFrames, channels, 1.0f / 32768.0f);
            break;
        case AAUDIO_FORMAT_PCM_I24_PACKED: {
            const uint8_t* p = static_cast<const uint8_t*>(audioData);
            float buf[256];
            int frame = 0;
            while (frame < numFrames) {
                int batch = numFrames - frame;
                if (batch > 128) batch = 128;
                for (int i = 0; i < batch; ++i) {
                    int idx = (frame + i) * channels;
                    auto read24 = [&](int sampleIdx) -> float {
                        const uint8_t* b = p + sampleIdx * 3;
                        int32_t v = static_cast<int8_t>(b[2]);
                        v = (v << 16) | (b[1] << 8) | b[0];
                        return static_cast<float>(v) / 8388608.0f;
                    };
                    float l = read24(idx);
                    float r = (channels >= 2) ? read24(idx + 1) : l;
                    buf[i * 2] = l;
                    buf[i * 2 + 1] = r;
                }
                pushStereoFloat(buf, batch);
                frame += batch;
            }
            break;
        }
        case AAUDIO_FORMAT_PCM_I32:
            convertAndPush<int32_t>(audioData, numFrames, channels, 1.0f / 2147483648.0f);
            break;
        default:
            convertAndPush<float>(audioData, numFrames, channels, 1.0f);
            break;
    }
}

static std::atomic<int> g_callbackCount{0};

static aaudio_data_callback_result_t DataCallbackWrapper(
        AAudioStream* stream, void* userData, void* audioData, int32_t numFrames) {
    int cbNum = g_callbackCount.fetch_add(1);
    
    static std::once_flag g_firstCallback;
    std::call_once(g_firstCallback, [] {
        LOGI("🎼 DataCallbackWrapper: FIRST INVOCATION — callback mode active!");
    });
    
    if (cbNum < 10 || (cbNum > 0 && cbNum % 1000 == 0)) {
        LOGI("🎼 DataCallbackWrapper #%d: stream=%p data=%p frames=%d active=%s",
             cbNum, stream, audioData, numFrames,
             g_captureActive.load() ? "YES" : "NO");
    }
    
    aaudio_data_callback_result_t result = AAUDIO_CALLBACK_RESULT_CONTINUE;
    if (g_origDataCallback) {
        result = g_origDataCallback(stream, g_origUserData, audioData, numFrames);
    }
    
    if (g_captureActive.load(std::memory_order_relaxed) && result == AAUDIO_CALLBACK_RESULT_CONTINUE) {
        if (cbNum < 20 || (cbNum > 0 && cbNum % 500 == 0)) {
            LOGI("✅ CAPTURING AUDIO in callback #%d: %d frames", cbNum, numFrames);
        }
        captureBuffer(audioData, numFrames, stream);
    }
    return result;
}

static std::atomic<int> g_hookRecursionGuard{0};

static void Hooked_setDataCallback(AAudioStreamBuilder* builder,
                                   AAudioStream_dataCallback callback,
                                   void* userData) {
    int expected = 0;
    if (!g_hookRecursionGuard.compare_exchange_strong(expected, 1)) {
        if (g_orig_setDataCallback_forGOT) {
            g_orig_setDataCallback_forGOT(builder, callback, userData);
        }
        return;
    }
    
    if (callback) {
        static std::atomic<int> logCounter{0};
        int cnt = logCounter.fetch_add(1);
        if (cnt < 10) {
            LOGI("🎛️ setDataCallback INTERCEPTED #%d: builder=%p callback=%p userData=%p", 
                 cnt + 1, builder, callback, userData);
        } else if (cnt == 10) {
            LOGI("(suppressing further setDataCallback logs — %d+ streams detected)", cnt + 1);
        }
        
        g_origDataCallback = callback;
        g_origUserData = userData;
        g_callbackModeActive.store(true, std::memory_order_relaxed);
        
        if (cnt < 5) {
            LOGI("🔄 WRAPPING callback with DataCallbackWrapper");
        }
        g_orig_setDataCallback_forGOT(builder, DataCallbackWrapper, userData);
    } else {
        static std::atomic<int> nullCount{0};
        int nc = nullCount.fetch_add(1);
        if (nc < 3) {
            LOGI("⚪ setDataCallback called with NULL callback #%d", nc + 1);
        }
    g_callbackModeActive.store(false, std::memory_order_relaxed);
    g_sampleRate.store(0, std::memory_order_relaxed);
        g_orig_setDataCallback_forGOT(builder, nullptr, userData);
    }
    
    g_hookRecursionGuard.store(0);
}

// 🔥 NEW: Force SHARED mode to match AudioTrack behavior!
static void Hooked_setSharingMode(AAudioStreamBuilder* builder, aaudio_sharing_mode_t mode) {
    static std::atomic<int> sharingLogCount{0};
    int slc = sharingLogCount.fetch_add(1);
    
    // ALWAYS force to SHARED mode - this is the KEY to matching AudioTrack!
    aaudio_sharing_mode_t forcedMode = AAUDIO_SHARING_MODE_SHARED;
    
    if (slc < 5) {
        const char* origName = (mode == AAUDIO_SHARING_MODE_EXCLUSIVE) ? "EXCLUSIVE" : "SHARED";
        LOGI("🔀 setSharingMode INTERCEPTED #%d: %s → FORCED SHARED! (builder=%p)", 
             slc + 1, origName, builder);
    } else if (slc == 5) {
        LOGI("(suppressing further setSharingMode logs)");
    }
    
    if (g_orig_setSharingMode) {
        g_orig_setSharingMode(builder, forcedMode);  // Always use SHARED!
    }
}

// 🔥 NEW: Force NONE performance mode to avoid LOW_LATENCY (which tends to EXCLUSIVE)
static void Hooked_setPerformanceMode(AAudioStreamBuilder* builder, aaudio_performance_mode_t mode) {
    static std::atomic<int> perfLogCount{0};
    int plc = perfLogCount.fetch_add(1);
    
    // Force NONE mode to avoid LOW_LATENCY which may trigger EXCLUSIVE MMAP
    aaudio_performance_mode_t forcedMode = AAUDIO_PERFORMANCE_MODE_NONE;
    
    if (plc < 5) {
        const char* origName = "UNKNOWN";
        switch (mode) {
            case AAUDIO_PERFORMANCE_MODE_NONE:         origName = "NONE"; break;
            case AAUDIO_PERFORMANCE_MODE_POWER_SAVING: origName = "POWER_SAVING"; break;
            case AAUDIO_PERFORMANCE_MODE_LOW_LATENCY:  origName = "LOW_LATENCY"; break;
        }
        LOGI("⚡ setPerformanceMode INTERCEPTED #%d: %s → FORCED NONE! (builder=%p)", 
             plc + 1, origName, builder);
    } else if (plc == 5) {
        LOGI("(suppressing further setPerformanceMode logs)");
    }
    
    if (g_orig_setPerformanceMode) {
        g_orig_setPerformanceMode(builder, forcedMode);  // Always use NONE!
    }
}

static std::atomic<int> g_writeCallCount{0};

static aaudio_result_t Hooked_AAudioStream_write(
        AAudioStream* stream, const void* buffer, int32_t numFrames, int64_t timeoutNanos) {
    int callNum = g_writeCallCount.fetch_add(1);
    
    if (callNum < 10 || (callNum > 0 && callNum % 1000 == 0)) {
        LOGI("🎵 Hooked_AAudioStream_write #%d: stream=%p buf=%p frames=%d timeout=%lld active=%s callbackMode=%s",
             callNum, stream, buffer, numFrames, (long long)timeoutNanos,
             g_captureActive.load() ? "YES" : "NO",
             g_callbackModeActive.load() ? "YES" : "NO");
    }
    
    if (g_captureActive.load(std::memory_order_relaxed) &&
        !g_callbackModeActive.load(std::memory_order_relaxed) &&
        buffer && numFrames > 0) {
        
        if (callNum < 20 || (callNum > 0 && callNum % 500 == 0)) {
            LOGI("✅ CAPTURING AUDIO in write #%d: %d frames", callNum, numFrames);
        }
        captureBuffer(buffer, numFrames, stream);
    }
    return g_orig_AAudioStream_write_forGOT(stream, buffer, numFrames, timeoutNanos);
}

static void* (*g_real_dlsym)(void* handle, const char* symbol) = nullptr;

static void* Hooked_dlsym(void* handle, const char* symbol) {
    if (!symbol) return g_real_dlsym ? g_real_dlsym(handle, symbol) : nullptr;

    if (strcmp(symbol, "AAudioStreamBuilder_setDataCallback") == 0) {
        if (!g_orig_setDataCallback_forGOT && g_real_dlsym) {
            g_orig_setDataCallback_forGOT = reinterpret_cast<fn_AAudioStreamBuilder_setDataCallback>(
                g_real_dlsym(handle, symbol));
            g_orig_setDataCallback = g_orig_setDataCallback_forGOT;
            LOGI("dlsym-hook: captured real %s = %p", symbol, g_orig_setDataCallback_forGOT);
        }
        return reinterpret_cast<void*>(Hooked_setDataCallback);
    }
    
    // 🔥 NEW: Intercept setSharingMode to force SHARED mode!
    if (strcmp(symbol, "AAudioStreamBuilder_setSharingMode") == 0) {
        if (!g_orig_setSharingMode && g_real_dlsym) {
            g_orig_setSharingMode = reinterpret_cast<fn_AAudioStreamBuilder_setSharingMode>(
                g_real_dlsym(handle, symbol));
            LOGI("dlsym-hook: captured real %s = %p (will force SHARED!)", symbol, g_orig_setSharingMode);
        }
        return reinterpret_cast<void*>(Hooked_setSharingMode);
    }
    
    // 🔥 NEW: Intercept setPerformanceMode to force NONE mode!
    if (strcmp(symbol, "AAudioStreamBuilder_setPerformanceMode") == 0) {
        if (!g_orig_setPerformanceMode && g_real_dlsym) {
            g_orig_setPerformanceMode = reinterpret_cast<fn_AAudioStreamBuilder_setPerformanceMode>(
                g_real_dlsym(handle, symbol));
            LOGI("dlsym-hook: captured real %s = %p (will force NONE!)", symbol, g_orig_setPerformanceMode);
        }
        return reinterpret_cast<void*>(Hooked_setPerformanceMode);
    }
    
    if (strcmp(symbol, "AAudioStream_write") == 0) {
        if (!g_orig_AAudioStream_write_forGOT && g_real_dlsym) {
            g_orig_AAudioStream_write_forGOT = reinterpret_cast<fn_AAudioStream_write>(
                g_real_dlsym(handle, symbol));
            g_orig_AAudioStream_write = g_orig_AAudioStream_write_forGOT;
            LOGI("dlsym-hook: captured real %s = %p", symbol, g_orig_AAudioStream_write_forGOT);
        }
        return reinterpret_cast<void*>(Hooked_AAudioStream_write);
    }
    if (strcmp(symbol, "AAudioStream_getFormat") == 0 && !g_AAudioStream_getFormat && g_real_dlsym) {
        g_AAudioStream_getFormat = reinterpret_cast<fn_AAudioStream_getFormat>(g_real_dlsym(handle, symbol));
    } else if (strcmp(symbol, "AAudioStream_getChannelCount") == 0 && !g_AAudioStream_getChannelCount && g_real_dlsym) {
        g_AAudioStream_getChannelCount = reinterpret_cast<fn_AAudioStream_getChannelCount>(g_real_dlsym(handle, symbol));
    } else if (strcmp(symbol, "AAudioStream_getSampleRate") == 0 && !g_AAudioStream_getSampleRate && g_real_dlsym) {
        g_AAudioStream_getSampleRate = reinterpret_cast<fn_AAudioStream_getSampleRate>(g_real_dlsym(handle, symbol));
    }

    return g_real_dlsym ? g_real_dlsym(handle, symbol) : nullptr;
}

static inline int16_t f32_to_s16(float v) {
    if (v > 1.0f) v = 1.0f;
    else if (v < -1.0f) v = -1.0f;
    return static_cast<int16_t>(v * 32767.0f);
}

static std::atomic<long> g_totalJniFramesSent{0};
static std::atomic<int> g_drainIterations{0};

static void* drain_thread_func(void*) {
    LOGI("🔄 Drain thread STARTED");
    
    JNIEnv* env = nullptr;
    if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        LOGE("❌ Drain thread FAILED to attach to JVM");
        return nullptr;
    }
    LOGI("✅ Drain thread attached to JVM successfully");

    jshortArray out = env->NewShortArray(kRingFloats);
    jshort* outBuf = out ? env->GetShortArrayElements(out, nullptr) : nullptr;

    while (g_drainRunning.load(std::memory_order_relaxed)) {
        int iterNum = g_drainIterations.fetch_add(1);
        
        if (iterNum > 0 && iterNum % 5000 == 0) {
            LOGI("🔄 Drain #%d: total sent=%ld, overflow=%ld", 
                 iterNum, g_totalJniFramesSent.load(), g_overflowCount.load());
        }
        
        if (outBuf && g_bridgeRef && g_onOboeAudioFrame) {
            int read = g_readIndex.load(std::memory_order_acquire);
            int write = g_writeIndex.load(std::memory_order_acquire);
            int avail = (write - read + kRingFloats) % kRingFloats;
            
            if (avail >= kChannelCount) {
                int count = 0;
                while (count + kChannelCount <= avail) {
                    outBuf[count]     = f32_to_s16(g_ring[read]);
                    outBuf[count + 1] = f32_to_s16(g_ring[read + 1]);
                    count += 2;
                    read += kChannelCount;
                    if (read >= kRingFloats) read = 0;
                }
                g_readIndex.store(read, std::memory_order_release);

                env->ReleaseShortArrayElements(out, outBuf, JNI_ABORT);

                jshortArray sized = env->NewShortArray(count);
                env->SetShortArrayRegion(sized, 0, count, outBuf);
                env->CallVoidMethod(g_bridgeRef, g_onOboeAudioFrame, sized,
                                    static_cast<jint>(g_sampleRate.load(std::memory_order_relaxed)));
                env->DeleteLocalRef(sized);
                g_totalJniFramesSent.fetch_add(count / 2);
                outBuf = env->GetShortArrayElements(out, nullptr);

                if (env->ExceptionCheck()) env->ExceptionClear();
            }
        }
        struct timespec ts{0, 4000000L};
        nanosleep(&ts, nullptr);
    }

    if (out) {
        if (outBuf) env->ReleaseShortArrayElements(out, outBuf, JNI_ABORT);
        env->DeleteLocalRef(out);
    }
    g_jvm->DetachCurrentThread();
    return nullptr;
}

static void makeWritable(void* addr, size_t len) {
    long pagesize = sysconf(_SC_PAGESIZE);
    uintptr_t start = reinterpret_cast<uintptr_t>(addr) & ~(pagesize - 1);
    uintptr_t end = (reinterpret_cast<uintptr_t>(addr) + len + pagesize - 1) & ~(pagesize - 1);
    mprotect(reinterpret_cast<void*>(start), end - start, PROT_READ | PROT_WRITE);
}

static int iterateLoadedLibs(int (*cb)(const char*, void*, void*), void* data) {
    std::pair<int (*)(const char*, void*, void*), void*> pair(cb, data);
    dl_iterate_phdr(
        [](struct dl_phdr_info* info, size_t, void* d) -> int {
            auto* p = static_cast<std::pair<int (*)(const char*, void*, void*), void*>*>(d);
            const char* name = info->dlpi_name;
            if (!name || !*name) return 0;
            p->first(name, reinterpret_cast<void*>(info->dlpi_addr), p->second);
            return 0;
        },
        &pair);
    return 0;
}

struct GotScanData {
    const char* symbolName;
    void* replacement;
    int patched;
    const char* libFilter;
};

static int scanElfGot(const char* libName, void* baseAddr, void* userData) {
    auto* data = static_cast<GotScanData*>(userData);
    if (data->libFilter && !strstr(libName, data->libFilter)) return 0;
    uintptr_t base = reinterpret_cast<uintptr_t>(baseAddr);

    int fd = open(libName, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;

    struct stat st;
    if (fstat(fd, &st) < 0) { close(fd); return 0; }
    void* map = mmap(nullptr, st.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (map == MAP_FAILED) return 0;

    auto* ehdr = static_cast<Elf64_Ehdr*>(map);
    auto* phdrs = reinterpret_cast<Elf64_Phdr*>(reinterpret_cast<uintptr_t>(map) + ehdr->e_phoff);

    struct SegMap { uint64_t vaddr; uint64_t foff; uint64_t fsz; };
    std::vector<SegMap> segs;
    for (int i = 0; i < ehdr->e_phnum; ++i) {
        if (phdrs[i].p_type == PT_LOAD) {
            segs.push_back({static_cast<uint64_t>(phdrs[i].p_vaddr), 
                           static_cast<uint64_t>(phdrs[i].p_offset), 
                           static_cast<uint64_t>(phdrs[i].p_filesz)});
        }
    }
    auto vaddrToFile = [&](uintptr_t vaddr) -> uintptr_t {
        for (const auto& s : segs) {
            if (vaddr >= s.vaddr && vaddr < s.vaddr + s.fsz) {
                return reinterpret_cast<uintptr_t>(map) + s.foff + (vaddr - s.vaddr);
            }
        }
        return 0;
    };

    Elf64_Sym* dynsym = nullptr;
    const char* dynstr = nullptr;
    Elf64_Rela* rela = nullptr;
    size_t relaSz = 0;
    Elf64_Rela* pltRela = nullptr;
    size_t pltRelaSz = 0;

    for (int i = 0; i < ehdr->e_phnum; ++i) {
        if (phdrs[i].p_type != PT_DYNAMIC) continue;
        auto* dyn = reinterpret_cast<Elf64_Dyn*>(reinterpret_cast<uintptr_t>(map) + phdrs[i].p_offset);
        for (auto* d = dyn; d->d_tag != DT_NULL; ++d) {
            switch (d->d_tag) {
                case DT_SYMTAB:   dynsym = reinterpret_cast<Elf64_Sym*>(vaddrToFile(d->d_un.d_ptr)); break;
                case DT_STRTAB:   dynstr = reinterpret_cast<const char*>(vaddrToFile(d->d_un.d_ptr)); break;
                case DT_RELA:     rela = reinterpret_cast<Elf64_Rela*>(vaddrToFile(d->d_un.d_ptr)); break;
                case DT_RELASZ:   relaSz = d->d_un.d_val; break;
                case DT_JMPREL:   pltRela = reinterpret_cast<Elf64_Rela*>(vaddrToFile(d->d_un.d_ptr)); break;
                case DT_PLTRELSZ: pltRelaSz = d->d_un.d_val; break;
            }
        }
        break;
    }

    if (!dynsym || !dynstr) { munmap(map, st.st_size); return 0; }

    auto processRela = [&](Elf64_Rela* tab, size_t sz) {
        if (!tab || sz == 0) return;
        size_t count = sz / sizeof(Elf64_Rela);
        for (size_t i = 0; i < count; ++i) {
            uint32_t symIdx = ELF64_R_SYM(tab[i].r_info);
            if (symIdx == 0) continue;
            const char* name = dynstr + dynsym[symIdx].st_name;
            if (strcmp(name, data->symbolName) != 0) continue;
            void** gotSlot = reinterpret_cast<void**>(base + tab[i].r_offset);
            GotPatch patch{gotSlot, *gotSlot};
            makeWritable(gotSlot, sizeof(void*));
            *gotSlot = data->replacement;
            g_gotPatches.push_back(patch);
            data->patched++;
            LOGI("GOT patched %s: slot %p in %s (was %p → %p)",
                 data->symbolName, gotSlot, libName, patch.original, data->replacement);
        }
    };

    processRela(rela, relaSz);
    processRela(pltRela, pltRelaSz);

    munmap(map, st.st_size);
    return 0;
}

static int rewriteGotEntries(const char* symbolName, void* replacement,
                             const char* libFilter = nullptr) {
    GotScanData data{symbolName, replacement, 0, libFilter};
    iterateLoadedLibs(&scanElfGot, &data);
    return data.patched;
}

bool installPcmCapture(JNIEnv* env, jobject bridgeGlobalRef) {
    LOGI("🚀 installPcmCapture CALLED — MusicHaptics FULL VERSION");
    
    if (g_captureActive.load(std::memory_order_relaxed)) {
        LOGI("⚠️ already active, skipping");
        return true;
    }
    if (!env || !bridgeGlobalRef) {
        LOGE("❌ invalid parameters");
        return false;
    }

    g_real_dlsym = reinterpret_cast<void* (*)(void*, const char*)>(dlsym(RTLD_DEFAULT, "dlsym"));
    if (!g_real_dlsym) {
        LOGE("❌ real dlsym not found");
        return false;
    }

    jclass bridgeClass = env->GetObjectClass(bridgeGlobalRef);
    jmethodID mid = env->GetMethodID(bridgeClass, "onOboeAudioFrame", "([SI)V");
    env->DeleteLocalRef(bridgeClass);
    if (!mid) {
        LOGE("❌ onOboeAudioFrame method not found");
        return false;
    }

    jobject global = env->NewGlobalRef(bridgeGlobalRef);
    if (!global) return false;
    g_bridgeRef = global;
    g_onOboeAudioFrame = mid;
    env->GetJavaVM(&g_jvm);

    // Strategy 1: dlsym GOT hook
    LOGI("🔧 Installing dlsym GOT hook...");
    int gotDlsym = rewriteGotEntries("dlsym", reinterpret_cast<void*>(Hooked_dlsym), "libphira.so");
    if (gotDlsym > 0) {
        LOGI("✅ dlsym GOT hooked: %d slot(s)", gotDlsym);
    }

    // Strategy 2: Resolve real AAudio addresses and do memory scan
    LOGI("🔍 Resolving real AAudio functions...");
    void* aaudioHandle = dlopen("libaaudio.so", RTLD_NOW | RTLD_NOLOAD);
    if (!aaudioHandle) aaudioHandle = dlopen("libaaudio.so", RTLD_NOW);
    void* aaudioInternalHandle = dlopen("libaaudio_internal.so", RTLD_NOW | RTLD_NOLOAD);
    if (!aaudioInternalHandle) aaudioInternalHandle = dlopen("libaaudio_internal.so", RTLD_NOW);
    
    void* realSetCb = nullptr;
    void* realWrite = nullptr;
    
    if (aaudioInternalHandle) {
        realSetCb = g_real_dlsym(aaudioInternalHandle, "AAudioStreamBuilder_setDataCallback");
        realWrite = g_real_dlsym(aaudioInternalHandle, "AAudioStream_write");
    }
    if (!realSetCb && aaudioHandle) {
        realSetCb = g_real_dlsym(aaudioHandle, "AAudioStreamBuilder_setDataCallback");
        realWrite = g_real_dlsym(aaudioHandle, "AAudioStream_write");
    }
    if (!realSetCb) {
        realSetCb = g_real_dlsym(RTLD_DEFAULT, "AAudioStreamBuilder_setDataCallback");
        realWrite = g_real_dlsym(RTLD_DEFAULT, "AAudioStream_write");
    }

    // Resolve format/channel/sample-rate getters AFTER libaaudio.so is loaded.
    // These used to be resolved via RTLD_DEFAULT before dlopen(), which returned
    // null because the library wasn't loaded yet at install time.
    auto resolveGetter = [&](const char* sym) -> void* {
        void* p = aaudioInternalHandle ? g_real_dlsym(aaudioInternalHandle, sym) : nullptr;
        if (!p && aaudioHandle) p = g_real_dlsym(aaudioHandle, sym);
        if (!p) p = g_real_dlsym(RTLD_DEFAULT, sym);
        return p;
    };
    g_AAudioStream_getFormat = reinterpret_cast<fn_AAudioStream_getFormat>(resolveGetter("AAudioStream_getFormat"));
    g_AAudioStream_getChannelCount = reinterpret_cast<fn_AAudioStream_getChannelCount>(resolveGetter("AAudioStream_getChannelCount"));
    g_AAudioStream_getSampleRate = reinterpret_cast<fn_AAudioStream_getSampleRate>(resolveGetter("AAudioStream_getSampleRate"));
    LOGI("AAudio getters: format=%p channels=%p sampleRate=%p",
         reinterpret_cast<void*>(g_AAudioStream_getFormat),
         reinterpret_cast<void*>(g_AAudioStream_getChannelCount),
         reinterpret_cast<void*>(g_AAudioStream_getSampleRate));

    LOGI("Real AAudio addrs: setDataCallback=%p write=%p", realSetCb, realWrite);

    if (realSetCb) {
        g_orig_setDataCallback_forGOT = reinterpret_cast<fn_AAudioStreamBuilder_setDataCallback>(realSetCb);
        g_orig_setDataCallback = g_orig_setDataCallback_forGOT;
    }
    if (realWrite) {
        g_orig_AAudioStream_write_forGOT = reinterpret_cast<fn_AAudioStream_write>(realWrite);
        g_orig_AAudioStream_write = g_orig_AAudioStream_write_forGOT;
        
        // Strategy 2a: Light scan of libphira.so's writable sections
        LOGI("🔍 Scanning libphira.so writable memory...");
        int patched = 0;
        FILE* fp = fopen("/proc/self/maps", "r");
        if (fp) {
            char line[512];
            while (fgets(line, sizeof(line), fp)) {
                unsigned long start, end;
                char perm[8], path[256];
                if (sscanf(line, "%lx-%lx %7s %*s %*s %*s %255s", &start, &end, perm, path) == 4 &&
                    strstr(path, "libphira.so") && strchr(perm, 'w')) {
                    void** slot = reinterpret_cast<void**>(start);
                    void** limit = reinterpret_cast<void**>(end);
                    while (slot < limit) {
                        if (*slot == realWrite) {
                            makeWritable(slot, sizeof(void*));
                            g_gotPatches.push_back({slot, *slot});
                            *slot = reinterpret_cast<void*>(Hooked_AAudioStream_write);
                            patched++;
                        }
                        if (realSetCb && *slot == realSetCb) {
                            makeWritable(slot, sizeof(void*));
                            g_gotPatches.push_back({slot, *slot});
                            *slot = reinterpret_cast<void*>(Hooked_setDataCallback);
                            patched++;
                        }
                        slot++;
                    }
                }
            }
            fclose(fp);
        }
        
        if (patched > 0) {
            LOGI("✅ Light GOT scan: patched %d entries in libphira.so", patched);
        } else {
            // Strategy 2b: SAFE targeted scan of libphira.so's data sections only
            LOGW("🔍 Starting SAFE targeted memory scan (libphira.so .data/.bss only)...");
            int safePatched = 0;
            
            FILE* maps = fopen("/proc/self/maps", "r");
            if (maps) {
                char line[512];
                while (fgets(line, sizeof(line), maps)) {
                    unsigned long start, end;
                    char perm[8], pathname[256];
                    
                    // Only scan libphira.so writable sections (.data and .bss)
                    if (sscanf(line, "%lx-%lx %7s %*s %*s %*s %255s", 
                              &start, &end, perm, pathname) == 4 &&
                        strstr(pathname, "libphira.so") && 
                        strchr(perm, 'w')) {
                        
                        LOGI("🔍 Scanning libphira.so section: %lx-%lx (%s)", start, end, perm);
                        
                        void** slot = reinterpret_cast<void**>(start);
                        void** limit = reinterpret_cast<void**>(end);
                        
                        while (slot < limit) {
                            void* val = *slot;
                            
                            // Check if this pointer matches AAudio functions
                            if (val == realWrite) {
                                // Verify it's really AAudioStream_write using dladdr
                                Dl_info info;
                                if (dladdr(val, &info) && info.dli_sname &&
                                    (strstr(info.dli_sname, "AAudioStream_write") ||
                                     strstr(info.dli_sname, "AudioTrack_write"))) {
                                    
                                    LOGI("✅🎯 VERIFIED AAudioStream_write at %p (in %s)", 
                                         slot, info.dli_fname ? info.dli_fname : "?");
                                    makeWritable(slot, sizeof(void*));
                                    g_gotPatches.push_back({slot, *slot});
                                    *slot = reinterpret_cast<void*>(Hooked_AAudioStream_write);
                                    safePatched++;
                                }
                            }
                            
                            if (realSetCb && val == realSetCb) {
                                Dl_info info;
                                if (dladdr(val, &info) && info.dli_sname &&
                                    strstr(info.dli_sname, "setDataCallback")) {
                                    
                                    LOGI("✅🎯 VERIFIED setDataCallback at %p", slot);
                                    makeWritable(slot, sizeof(void*));
                                    g_gotPatches.push_back({slot, *slot});
                                    *slot = reinterpret_cast<void*>(Hooked_setDataCallback);
                                    safePatched++;
                                }
                            }
                            
                            slot++;
                        }
                    }
                }
                fclose(maps);
            }
            
            if (safePatched > 0) {
                LOGI("✅ SAFE SCAN SUCCESS: verified and patched %d AAudio pointer(s)!", safePatched);
                patched += safePatched;
            } else {
                // Strategy 2c: Safe aggressive scan of heap/anonymous memory with strict verification
                LOGW("🔍 Starting SAFE AGGRESSIVE scan (heap + anon, with dladdr verification)...");
                int aggrPatched = 0;
                
                FILE* mapsAggressive = fopen("/proc/self/maps", "r");
                if (mapsAggressive) {
                    char line[512];
                    while (fgets(line, sizeof(line), mapsAggressive)) {
                        unsigned long start, end;
                        char perm[8];
                        
                        if (sscanf(line, "%lx-%lx %7s", &start, &end, perm) != 3) continue;
                        
                        // Must be writable
                        if (!strchr(perm, 'w')) continue;
                        
                        size_t regionSize = end - start;
                        
                        // Skip very large regions (>50MB)
                        if (regionSize > 50 * 1024 * 1024) continue;
                        
                        // Skip ART/Dalvik runtime regions
                        if (strstr(line, ".art") || strstr(line, ".oat") || 
                            strstr(line, "dalvik") || strstr(line, "/system/framework")) continue;
                        
                        // Only scan heap, anonymous mappings, and libphira.so
                        bool isHeap = strstr(line, "[heap]") != nullptr;
                        bool isAnon = (strstr(line, "[anon") != nullptr ||
                                       strstr(line, "[linker") != nullptr);
                        bool isPhira = strstr(line, "libphira.so") != nullptr;
                        
                        // Check for truly anonymous (no pathname after 6th field)
                        if (!isHeap && !isAnon && !isPhira) {
                            const char* p = line;
                            int spaces = 0;
                            while (*p && spaces < 5) {
                                if (*p == ' ') { spaces++; while (*p == ' ') p++; }
                                else p++;
                            }
                            if (spaces == 5) {
                                while (*p == ' ') p++;
                                if (*p == '\0' || *p == '\n') isAnon = true;
                            }
                        }
                        
                        if (!isHeap && !isAnon && !isPhira) continue;
                        
                        const char* regionType = isHeap ? "HEAP" : (isPhira ? "PHIRA" : "ANON");
                        
                        // Scan this region with STRICT dladdr verification
                        void** slot = reinterpret_cast<void**>(start);
                        void** limit = reinterpret_cast<void**>(end);
                        
                        while (slot < limit) {
                            void* val = *slot;
                            
                            // Check for AAudioStream_write
                            if (val == realWrite) {
                                Dl_info info;
                                if (dladdr(val, &info) && info.dli_sname &&
                                    (strstr(info.dli_sname, "AAudioStream_write") ||
                                     strstr(info.dli_sname, "AudioTrack_write"))) {
                                    
                                    LOGI("✅🎯 VERIFIED write at %p in %s (%s)", 
                                         slot, regionType, info.dli_sname);
                                    makeWritable(slot, sizeof(void*));
                                    g_gotPatches.push_back({slot, *slot});
                                    *slot = reinterpret_cast<void*>(Hooked_AAudioStream_write);
                                    aggrPatched++;
                                } else {
                                    LOGW("⚠️ SKIP unverified write ptr at %p in %s", slot, regionType);
                                }
                            }
                            
                            // Check for setDataCallback
                            if (realSetCb && val == realSetCb) {
                                Dl_info info;
                                if (dladdr(val, &info) && info.dli_sname &&
                                    strstr(info.dli_sname, "setDataCallback")) {
                                    
                                    LOGI("✅🎯 VERIFIED callback at %p in %s", slot, regionType);
                                    makeWritable(slot, sizeof(void*));
                                    g_gotPatches.push_back({slot, *slot});
                                    *slot = reinterpret_cast<void*>(Hooked_setDataCallback);
                                    aggrPatched++;
                                } else {
                                    LOGW("⚠️ SKIP unverified callback ptr at %p in %s", slot, regionType);
                                }
                            }
                            
                            slot++;
                        }
                    }
                    fclose(mapsAggressive);
                }
                
                if (aggrPatched > 0) {
                    LOGI("✅✅ SAFE AGGRESSIVE SCAN SUCCESS: verified & patched %d pointers!", aggrPatched);
                    patched += aggrPatched;
                } else {
                    LOGW("⚠️ No AAudio pointers found anywhere — relying solely on dlsym hook");
                }
            }
        }
    }

    LOGI("🎯 HOOK INSTALLATION SUMMARY:");
    LOGI("   dlsym GOT hook: %s", gotDlsym > 0 ? "✅ INSTALLED" : "❌ FAILED");
    LOGI("   Memory scan: %s", (gotDlsym > 0) ? "✅ DONE" : "❌ FAILED");
    LOGI("   Inline hook: %s", g_inlineHooked.load() ? "✅ INSTALLED" : "❌ NOT USED");

    g_captureActive.store(true, std::memory_order_release);
    LOGI("✅ CAPTURE ACTIVATED");

    g_drainRunning.store(true, std::memory_order_relaxed);
    if (pthread_create(&g_drainThread, nullptr, drain_thread_func, nullptr) != 0) {
        LOGW("drain thread creation failed");
        g_drainRunning.store(false, std::memory_order_relaxed);
    } else {
        pthread_setname_np(g_drainThread, "OboeDrain");
    }

    return true;
}

void uninstallPcmCapture() {
    if (!g_captureActive.exchange(false, std::memory_order_acq_rel)) return;

    g_drainRunning.store(false, std::memory_order_relaxed);
    if (g_drainThread) {
        pthread_join(g_drainThread, nullptr);
        g_drainThread = {};
    }

    for (const auto& patch : g_gotPatches) {
        makeWritable(patch.slot, sizeof(void*));
        *patch.slot = patch.original;
    }
    g_gotPatches.clear();

    g_orig_setDataCallback = nullptr;
    g_orig_AAudioStream_write = nullptr;
    g_orig_setDataCallback_forGOT = nullptr;
    g_orig_AAudioStream_write_forGOT = nullptr;
    g_origDataCallback = nullptr;
    g_origUserData = nullptr;
    g_real_dlsym = nullptr;
    g_callbackModeActive.store(false, std::memory_order_relaxed);

    if (g_bridgeRef && g_jvm) {
        JNIEnv* env = nullptr;
        if (g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            env->DeleteGlobalRef(g_bridgeRef);
            g_jvm->DetachCurrentThread();
        }
        g_bridgeRef = nullptr;
    }
    g_jvm = nullptr;
    g_onOboeAudioFrame = nullptr;
}

bool isCaptureInstalled() {
    return g_captureActive.load(std::memory_order_relaxed);
}

}  // namespace phira

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_lyon_rhythmictouch_systemui_OboeBridge_installCapture(JNIEnv* env, jclass, jobject bridgeGlobalRef) {
    return phira::installPcmCapture(env, bridgeGlobalRef) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_lyon_rhythmictouch_systemui_OboeBridge_uninstallCapture(JNIEnv*, jclass) {
    phira::uninstallPcmCapture();
}

JNIEXPORT jboolean JNICALL
Java_com_lyon_rhythmictouch_systemui_OboeBridge_isInstalledNative(JNIEnv*, jclass) {
    return phira::isCaptureInstalled() ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"