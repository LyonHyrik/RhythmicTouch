#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <stdatomic.h>
#include "dobby.h"

#define TAG "RhythmicHook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define SOCKET_PATH "/data/local/tmp/rhythmic_hook.sock"
#define MAX_UIDS 512

typedef struct {
    int uid;
    int track_count;
} uid_entry_t;

static uid_entry_t g_uids[MAX_UIDS];
static int g_uid_count = 0;
static int g_socket_fd = -1;
static atomic_int g_running = 0;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

static void uid_add(int uid) {
    if (uid <= 0) return;
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < g_uid_count; i++) {
        if (g_uids[i].uid == uid) { g_uids[i].track_count++; pthread_mutex_unlock(&g_lock); return; }
    }
    if (g_uid_count < MAX_UIDS) {
        g_uids[g_uid_count].uid = uid;
        g_uids[g_uid_count].track_count = 1;
        g_uid_count++;
    }
    pthread_mutex_unlock(&g_lock);
}

static void uid_remove(int uid) {
    if (uid <= 0) return;
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < g_uid_count; i++) {
        if (g_uids[i].uid == uid) {
            g_uids[i].track_count--;
            if (g_uids[i].track_count <= 0 && i < g_uid_count - 1) {
                g_uids[i] = g_uids[--g_uid_count];
            } else if (g_uids[i].track_count <= 0) {
                g_uid_count--;
            }
            break;
        }
    }
    pthread_mutex_unlock(&g_lock);
}

static int connect_to_daemon() {
    for (int retry = 0; retry < 30; retry++) {
        int fd = socket(AF_UNIX, SOCK_STREAM, 0);
        if (fd < 0) { usleep(500000); continue; }
        struct sockaddr_un addr = {0};
        addr.sun_family = AF_UNIX;
        strncpy(addr.sun_path, SOCKET_PATH, sizeof(addr.sun_path) - 1);
        if (connect(fd, (struct sockaddr*)&addr, sizeof(addr)) == 0) return fd;
        close(fd);
        usleep(500000);
    }
    return -1;
}

static void* sender_thread(void* arg) {
    while (atomic_load(&g_running)) {
        usleep(200000);
        if (g_socket_fd < 0) continue;
        char buf[4096] = {0};
        int off = 0;
        pthread_mutex_lock(&g_lock);
        for (int i = 0; i < g_uid_count && off < (int)sizeof(buf) - 32; i++) {
            if (g_uids[i].track_count > 0) {
                off += snprintf(buf + off, sizeof(buf) - off, "%d:%d,", g_uids[i].uid, g_uids[i].track_count);
            }
        }
        pthread_mutex_unlock(&g_lock);
        if (off > 0) {
            buf[off - 1] = '\n';
            if (send(g_socket_fd, buf, off, MSG_NOSIGNAL) < 0) {
                close(g_socket_fd);
                g_socket_fd = -1;
            }
        }
    }
    return NULL;
}

typedef void* (*orig_createTrack_t)(void*, ...);
typedef int (*orig_releaseTrack_t)(void*, ...);

static orig_createTrack_t real_createTrack = NULL;
static orig_releaseTrack_t real_releaseTrack = NULL;

static void* hook_createTrack(void* thiz, ...) {
    void* result = real_createTrack(thiz);
    void** args = __builtin_frame_address(0);
    int pid = 0;
    for (int i = 1; i < 16; i++) {
        int val = (int)(long)args[i];
        if (val > 10000 && val < 200000) { pid = val; break; }
    }
    if (pid > 0) uid_add(pid);
    return result;
}

static int hook_releaseTrack(void* thiz, ...) {
    int result = real_releaseTrack(thiz);
    void** args = __builtin_frame_address(0);
    int pid = 0;
    for (int i = 1; i < 16; i++) {
        int val = (int)(long)args[i];
        if (val > 10000 && val < 200000) { pid = val; break; }
    }
    if (pid > 0) uid_remove(pid);
    return result;
}

static void* find_afl_symbol(const char* name) {
    void* h = dlopen("libaudioflinger.so", RTLD_NOLOAD | RTLD_NOW);
    if (!h) return NULL;
    void* sym = dlsym(h, name);
    dlclose(h);
    return sym;
}

static void* hook_init_thread(void* arg) {
    sleep(3);

    g_socket_fd = connect_to_daemon();
    if (g_socket_fd < 0) {
        LOGE("Cannot connect to daemon");
        return NULL;
    }
    LOGI("Connected to daemon");

    atomic_store(&g_running, 1);
    pthread_t tid;
    pthread_create(&tid, NULL, sender_thread, NULL);
    pthread_detach(tid);

    const char* createTrack_syms[] = {
        "_ZN7android11AudioFlinger11createTrackERKNS_16audio_attributes_tERKNS_16audio_config_tEmNS_13audio_output_tEjPNS_2spINS_7IAudioFlinger11IAudioTrackEEEPNS_11audio_session_tEPNS_7status_tE",
        "_ZN7android11AudioFlinger11createTrackERKNS_27audio_attributes_internal_tERKNS_16audio_config_tEmNS_13audio_output_tEjPNS_2spINS_7IAudioFlinger11IAudioTrackEEEPNS_11audio_session_tEPNS_7status_tE",
        NULL
    };

    const char* releaseTrack_syms[] = {
        "_ZN7android11AudioFlinger12releaseTrackEj",
        "_ZN7android11AudioFlinger12releaseTrackEi",
        NULL
    };

    for (int i = 0; createTrack_syms[i]; i++) {
        void* sym = find_afl_symbol(createTrack_syms[i]);
        if (sym) {
            LOGI("Hooking createTrack: %s -> %p", createTrack_syms[i], sym);
            if (DobbyHook(sym, hook_createTrack, (void**)&real_createTrack) == 0) {
                LOGI("createTrack hooked OK");
                break;
            }
        }
    }

    for (int i = 0; releaseTrack_syms[i]; i++) {
        void* sym = find_afl_symbol(releaseTrack_syms[i]);
        if (sym) {
            LOGI("Hooking releaseTrack: %s -> %p", releaseTrack_syms[i], sym);
            if (DobbyHook(sym, hook_releaseTrack, (void**)&real_releaseTrack) == 0) {
                LOGI("releaseTrack hooked OK");
                break;
            }
        }
    }

    return NULL;
}

__attribute__((constructor))
static void on_load() {
    LOGI("RhythmicHook loaded into process pid=%d", getpid());
    pthread_t tid;
    pthread_create(&tid, NULL, hook_init_thread, NULL);
    pthread_detach(tid);
}
