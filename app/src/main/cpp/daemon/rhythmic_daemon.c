#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <string.h>
#include <unistd.h>
#include <signal.h>
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/select.h>
#include <sys/stat.h>
#include <errno.h>
#include <time.h>

#define LOG_PATH "/data/local/tmp/rhythmic_daemon.log"
#define HOOK_SOCKET "/data/local/tmp/rhythmic_hook.sock"
#define APP_SOCKET  "/data/local/tmp/rhythmic_daemon.sock"
#define HOOK_LIB    "/data/local/tmp/librhythmictouch_hook.so"

static FILE* g_log = NULL;

static void log_msg(const char* fmt, ...) {
    if (!g_log) return;
    time_t t = time(NULL);
    struct tm tm;
    localtime_r(&t, &tm);
    fprintf(g_log, "[%02d:%02d:%02d] ", tm.tm_hour, tm.tm_min, tm.tm_sec);
    va_list args;
    va_start(args, fmt);
    vfprintf(g_log, fmt, args);
    va_end(args);
    fputc('\n', g_log);
    fflush(g_log);
}

static pid_t find_pid_by_name(const char* name) {
    char cmd[128];
    snprintf(cmd, sizeof(cmd), "pidof %s", name);
    FILE* fp = popen(cmd, "r");
    if (!fp) return -1;
    pid_t pid = -1;
    if (fscanf(fp, "%d", &pid) != 1) pid = -1;
    pclose(fp);
    return pid;
}

static int inject_so(pid_t target_pid, const char* so_path) {
    log_msg("Injecting %s into pid %d", so_path, target_pid);
    if (ptrace(PTRACE_ATTACH, target_pid, NULL, NULL) < 0) {
        log_msg("PTRACE_ATTACH failed: %s", strerror(errno));
        return -1;
    }
    int status;
    waitpid(target_pid, &status, 0);
    if (!WIFSTOPPED(status)) {
        log_msg("Target did not stop");
        ptrace(PTRACE_DETACH, target_pid, NULL, NULL);
        return -1;
    }
    log_msg("Attached OK, detaching (real hook via wrap.audioserver)");
    ptrace(PTRACE_DETACH, target_pid, NULL, NULL);
    return -1;
}

static void collect_dumpsys_uids(char* out, size_t out_size) {
    FILE* fp = popen("dumpsys media.audio_flinger 2>/dev/null", "r");
    if (!fp) { out[0] = '\0'; return; }
    char buf[4096];
    int pids[256];
    int uids[256];
    int pid_count = 0;
    int uid_count = 0;
    int active_pids[512];
    int active_count = 0;
    int in_notif = 0;
    int in_thread = 0;
    out[0] = '\0';

    while (fgets(buf, sizeof(buf), fp)) {
        if (strstr(buf, "Notification Clients:")) { in_notif = 1; in_thread = 0; continue; }
        if (strstr(buf, "Status Engine") || strstr(buf, "Global session")) { in_notif = 0; in_thread = 0; }
        if (strstr(buf, "Pid") && strstr(buf, "Priority")) { in_thread = 1; in_notif = 0; continue; }

        if (in_notif && pid_count < 256 && uid_count < 256) {
            int pid = -1, uid = -1;
            char name[256] = {0};
            if (sscanf(buf, " %d %d %255s", &pid, &uid, name) == 3) {
                if (pid > 0 && uid > 1000 && uid < 200000 && name[0] != '\0') {
                    pids[pid_count++] = pid;
                    uids[uid_count++] = uid;
                }
            }
        } else if (in_thread && active_count < 512) {
            int pid = -1, prio = -1;
            char ctrl[16] = {0}, locked[16] = {0}, c1[16] = {0}, c2[16] = {0};
            if (sscanf(buf, " %d %d %15s %15s %15s %15s", &pid, &prio, ctrl, locked, c1, c2) >= 2) {
                if (pid > 0 && prio >= 0) active_pids[active_count++] = pid;
            }
        }
    }
    pclose(fp);

    for (int i = 0; i < active_count; i++) {
        for (int j = 0; j < pid_count; j++) {
            if (pids[j] == active_pids[i]) {
                size_t used = strlen(out);
                snprintf(out + used, out_size - used, "%d,", uids[j]);
                break;
            }
        }
    }
}

static int run_server(const char* path, const char* name) {
    unlink(path);
    int server_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (server_fd < 0) { log_msg("%s socket() errno=%d %s", name, errno, strerror(errno)); return -1; }
    struct sockaddr_un addr = {0};
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, path, sizeof(addr.sun_path) - 1);
    if (bind(server_fd, (struct sockaddr*)&addr, sizeof(addr)) < 0) { log_msg("%s bind() errno=%d %s", name, errno, strerror(errno)); close(server_fd); return -1; }
    if (listen(server_fd, 1) < 0) { log_msg("%s listen() errno=%d", name, errno); close(server_fd); return -1; }
    chmod(path, 0777);
    return server_fd;
}

int main(int argc, char* argv[]) {
    g_log = fopen(LOG_PATH, "a");
    log_msg("RhythmicDaemon started pid=%d", getpid());

    FILE* se = popen("setenforce 0 2>/dev/null", "r");
    if (se) { pclose(se); log_msg("SELinux set to permissive"); }

    pid_t audioserver_pid = find_pid_by_name("audioserver");
    if (audioserver_pid > 0) {
        log_msg("audioserver pid=%d", audioserver_pid);
        inject_so(audioserver_pid, HOOK_LIB);
    } else {
        log_msg("audioserver not found");
    }

    int hook_fd = run_server(HOOK_SOCKET, "hook");
    log_msg("hook_fd=%d", hook_fd);

    fd_set fds;
    char buffer[8192];
    char uidfile_path[] = "/data/local/tmp/rhythmic_uids.txt";
    time_t last_collect = 0;

    while (1) {
        FD_ZERO(&fds);
        if (hook_fd >= 0) FD_SET(hook_fd, &fds);
        struct timeval tv = { .tv_sec = 1, .tv_usec = 0 };
        int n = select(hook_fd + 1, &fds, NULL, NULL, &tv);

        if (n > 0 && hook_fd >= 0 && FD_ISSET(hook_fd, &fds)) {
            int client = accept(hook_fd, NULL, NULL);
            if (client >= 0) {
                ssize_t len;
                while ((len = recv(client, buffer, sizeof(buffer) - 1, 0)) > 0) {
                    buffer[len] = '\0';
                    log_msg("Hook data: %s", buffer);
                    FILE* f = fopen(uidfile_path, "w");
                    if (f) { fprintf(f, "%s\n", buffer); fclose(f); chmod(uidfile_path, 0666); }
                }
                close(client);
            }
        }

        time_t now = time(NULL);
        if (now - last_collect >= 3) {
            last_collect = now;
            collect_dumpsys_uids(buffer, sizeof(buffer));
            if (strlen(buffer) > 0) {
                log_msg("Dumpsys UIDs: %s", buffer);
                FILE* f = fopen(uidfile_path, "w");
                if (f) { fprintf(f, "%s\n", buffer); fclose(f); chmod(uidfile_path, 0666); }
            }
        }
    }
    return 0;
}
