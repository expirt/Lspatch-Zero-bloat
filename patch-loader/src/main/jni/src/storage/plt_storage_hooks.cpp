#include "plt_storage_hooks.h"
#include "path_translation.h"
#include "lsplt.hpp"
#include "elf_util.hpp"

#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <cstdarg>
#include <sys/stat.h>
#include <sys/vfs.h>
#include <sys/statvfs.h>
#include <dirent.h>
#include <unistd.h>
#include <cerrno>
#include <cstring>
#include <linux/memfd.h>
#include <sys/syscall.h>
#include <sys/system_properties.h>
#include <unordered_map>
#include <unordered_set>
#include <mutex>
#include <jni.h>

#define TAG "LSPatch-PltStorage"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace lspd::storage {

namespace {

thread_local bool t_in_hook = false;

struct HookGuard {
    HookGuard() { t_in_hook = true; }
    ~HookGuard() { t_in_hook = false; }
};

// Open descriptor tracking to prevent stale/recycled fd collisions
static std::mutex g_fd_mutex;
static std::unordered_map<int, std::string> g_fd_paths;

void TrackFd(int fd, const std::string& path) {
    if (fd >= 0) {
        std::lock_guard<std::mutex> lock(g_fd_mutex);
        g_fd_paths[fd] = path;
    }
}

void UntrackFd(int fd) {
    if (fd >= 0) {
        std::lock_guard<std::mutex> lock(g_fd_mutex);
        g_fd_paths.erase(fd);
    }
}

void DuplicateFd(int oldfd, int newfd) {
    if (oldfd >= 0 && newfd >= 0) {
        std::lock_guard<std::mutex> lock(g_fd_mutex);
        auto it = g_fd_paths.find(oldfd);
        if (it != g_fd_paths.end()) {
            g_fd_paths[newfd] = it->second;
        } else {
            g_fd_paths.erase(newfd);
        }
    }
}

std::string ResolveAt(int dirfd, const char* pathname) {
    if (pathname == nullptr || pathname[0] == '\0') return "";
    if (pathname[0] == '/') {
        return pathname; // Absolute path ignores dirfd
    }
    if (dirfd == AT_FDCWD) {
        return pathname; // Relative to CWD
    }
    // dirfd >= 0
    std::lock_guard<std::mutex> lock(g_fd_mutex);
    auto it = g_fd_paths.find(dirfd);
    if (it != g_fd_paths.end()) {
        return it->second + "/" + pathname;
    }
    return ""; // Untracked dirfd: pass untouched directly to underlying libc call
}

// --- Anti-Detection & Identity Native Hooks ---
static bool g_hide_root_files = false;
static bool g_mask_proc_maps = false;
static std::string g_spoof_boot_id_path;
static std::mutex g_prop_mutex;
static std::unordered_map<std::string, std::string> g_spoof_props;
static std::unordered_map<const prop_info*, std::string> g_prop_info_names;

static bool IsSuspiciousPath(const char* pathname) {
    if (!g_hide_root_files || pathname == nullptr) return false;
    std::string p(pathname);
    const char* frags[] = {
        "/su", "/magisk", "/supersu", "superuser.apk", "/sbin/.magisk",
        "/data/adb/", "/xposed", "xposedbridge", "lsposed"
    };
    for (const char* frag : frags) {
        size_t flen = strlen(frag);
        if (p.size() >= flen && p.compare(p.size() - flen, flen, frag) == 0) return true;
        if (p.find(std::string(frag) + "/") != std::string::npos) return true;
    }
    return false;
}

static bool IsProcMapsPath(const char* pathname) {
    if (!g_mask_proc_maps || pathname == nullptr) return false;
    std::string p(pathname);
    if (p == "/proc/self/maps" || p == "/proc/self/smaps") return true;
    if (p.rfind("/proc/", 0) == 0) {
        size_t len = p.size();
        if ((len > 5 && p.compare(len - 5, 5, "/maps") == 0) ||
            (len > 6 && p.compare(len - 6, 6, "/smaps") == 0)) {
            return true;
        }
    }
    return false;
}

static bool TranslatePathWithAntiDetect(const char* pathname, std::string& out, bool& hide_out) {
    hide_out = false;
    if (pathname == nullptr) return false;
    if (IsSuspiciousPath(pathname)) {
        hide_out = true;
        return false;
    }
    if (!g_spoof_boot_id_path.empty() && strcmp(pathname, "/proc/sys/kernel/random/boot_id") == 0) {
        out = g_spoof_boot_id_path;
        return true;
    }
    return PathTranslator::Instance().ToRealPath(pathname, out);
}

static bool ShouldFilterMapLine(const std::string& line) {
    const char* frags[] = {"lspatch", "lsposed", "xposed", "lsplant", "vector", "liblspatch"};
    for (const char* f : frags) {
        if (line.find(f) != std::string::npos) return true;
    }
    return false;
}

static int (*backup_open)(const char*, int, ...) = nullptr;
static int (*backup_open_2)(const char*, int) = nullptr;
static int (*backup_openat)(int, const char*, int, ...) = nullptr;
static FILE* (*backup_fopen)(const char*, const char*) = nullptr;

static int OpenMaskedProcMapsFd(const char* pathname) {
    int raw_fd = backup_open ? backup_open(pathname, O_RDONLY, 0) : open(pathname, O_RDONLY);
    if (raw_fd < 0) return -1;

    std::string raw;
    char buf[4096];
    ssize_t n;
    while ((n = read(raw_fd, buf, sizeof(buf))) > 0) {
        raw.append(buf, n);
    }
    close(raw_fd);

    int mfd = static_cast<int>(syscall(__NR_memfd_create, "maps", MFD_CLOEXEC));
    if (mfd < 0) {
        return backup_open ? backup_open(pathname, O_RDONLY, 0) : open(pathname, O_RDONLY);
    }

    std::string filtered;
    filtered.reserve(raw.size());
    size_t start = 0;
    while (start < raw.size()) {
        size_t end = raw.find('\n', start);
        if (end == std::string::npos) end = raw.size();
        std::string line = raw.substr(start, end - start);
        if (!ShouldFilterMapLine(line)) {
            filtered.append(line);
            if (end < raw.size()) filtered.push_back('\n');
        }
        start = end + 1;
    }

    size_t written = 0;
    while (written < filtered.size()) {
        ssize_t w = write(mfd, filtered.data() + written, filtered.size() - written);
        if (w <= 0) break;
        written += static_cast<size_t>(w);
    }
    lseek(mfd, 0, SEEK_SET);
    return mfd;
}

static int (*backup_system_property_get)(const char*, char*) = nullptr;
static const prop_info* (*backup_system_property_find)(const char*) = nullptr;
static void (*backup_system_property_read_callback)(
        const prop_info*,
        void (*)(void*, const char*, const char*, uint32_t),
        void*) = nullptr;

static int Hook_system_property_get(const char* name, char* value) {
    if (name != nullptr && value != nullptr) {
        std::lock_guard<std::mutex> lock(g_prop_mutex);
        auto it = g_spoof_props.find(name);
        if (it != g_spoof_props.end()) {
            size_t len = std::min(it->second.size(), static_cast<size_t>(PROP_VALUE_MAX - 1));
            memcpy(value, it->second.data(), len);
            value[len] = '\0';
            return static_cast<int>(len);
        }
    }
    return backup_system_property_get ? backup_system_property_get(name, value) : __system_property_get(name, value);
}

static const prop_info* Hook_system_property_find(const char* name) {
    const prop_info* pi = backup_system_property_find ? backup_system_property_find(name) : __system_property_find(name);
    if (pi != nullptr && name != nullptr) {
        std::lock_guard<std::mutex> lock(g_prop_mutex);
        if (g_spoof_props.count(name)) {
            g_prop_info_names[pi] = name;
        }
    }
    return pi;
}

struct PropCallbackWrap {
    void (*orig_cb)(void*, const char*, const char*, uint32_t);
    void* orig_cookie;
    std::string spoof_val;
};

static void WrappedPropCallback(void* cookie, const char* name, const char* value, uint32_t serial) {
    auto* wrap = reinterpret_cast<PropCallbackWrap*>(cookie);
    wrap->orig_cb(wrap->orig_cookie, name, wrap->spoof_val.c_str(), serial);
}

static void Hook_system_property_read_callback(
        const prop_info* pi,
        void (*callback)(void* cookie, const char* name, const char* value, uint32_t serial),
        void* cookie) {
    if (pi != nullptr && callback != nullptr) {
        std::string spoof_val;
        bool has_spoof = false;
        {
            std::lock_guard<std::mutex> lock(g_prop_mutex);
            auto it_name = g_prop_info_names.find(pi);
            if (it_name != g_prop_info_names.end()) {
                auto it_val = g_spoof_props.find(it_name->second);
                if (it_val != g_spoof_props.end()) {
                    spoof_val = it_val->second;
                    has_spoof = true;
                }
            }
        }
        auto* orig_fn = backup_system_property_read_callback
                ? backup_system_property_read_callback
                : reinterpret_cast<void (*)(const prop_info*, void (*)(void*, const char*, const char*, uint32_t), void*)>(
                        dlsym(RTLD_DEFAULT, "__system_property_read_callback"));
        if (has_spoof && orig_fn != nullptr) {
            PropCallbackWrap wrap{callback, cookie, spoof_val};
            orig_fn(pi, WrappedPropCallback, &wrap);
            return;
        }
        if (orig_fn != nullptr) {
            orig_fn(pi, callback, cookie);
            return;
        }
    }
    if (backup_system_property_read_callback) {
        backup_system_property_read_callback(pi, callback, cookie);
    }
}

// Function pointer signatures & backups

static int (*backup_stat)(const char*, struct stat*) = nullptr;
static int (*backup_stat64)(const char*, struct stat64*) = nullptr;
static int (*backup_fstat)(int, struct stat*) = nullptr;
static int (*backup_fstat64)(int, struct stat64*) = nullptr;
static int (*backup_lstat)(const char*, struct stat*) = nullptr;
static int (*backup_lstat64)(const char*, struct stat64*) = nullptr;
static int (*backup_fstatat)(int, const char*, struct stat*, int) = nullptr;
static int (*backup_fstatat64)(int, const char*, struct stat64*, int) = nullptr;

static int (*backup_access)(const char*, int) = nullptr;
static int (*backup_faccessat)(int, const char*, int, int) = nullptr;

static int (*backup_mkdir)(const char*, mode_t) = nullptr;
static int (*backup_mkdirat)(int, const char*, mode_t) = nullptr;

static int (*backup_rename)(const char*, const char*) = nullptr;
static int (*backup_renameat)(int, const char*, int, const char*) = nullptr;

static int (*backup_unlink)(const char*) = nullptr;
static int (*backup_unlinkat)(int, const char*, int) = nullptr;
static int (*backup_rmdir)(const char*) = nullptr;
static int (*backup_remove)(const char*) = nullptr;

static DIR* (*backup_opendir)(const char*) = nullptr;
static struct dirent* (*backup_readdir)(DIR*) = nullptr;
static struct dirent64* (*backup_readdir64)(DIR*) = nullptr;
static int (*backup_closedir)(DIR*) = nullptr;

static int (*backup_close)(int) = nullptr;
static int (*backup_dup)(int) = nullptr;
static int (*backup_dup2)(int, int) = nullptr;
static int (*backup_dup3)(int, int, int) = nullptr;

static int (*backup_chmod)(const char*, mode_t) = nullptr;
static int (*backup_fchmod)(int, mode_t) = nullptr;
static int (*backup_fchmodat)(int, const char*, mode_t, int) = nullptr;

static ssize_t (*backup_readlink)(const char*, char*, size_t) = nullptr;
static ssize_t (*backup_readlinkat)(int, const char*, char*, size_t) = nullptr;
static char* (*backup_realpath)(const char*, char*) = nullptr;

static int (*backup_statfs)(const char*, struct statfs*) = nullptr;
static int (*backup_statfs64)(const char*, struct statfs64*) = nullptr;
static int (*backup_statvfs)(const char*, struct statvfs*) = nullptr;
static int (*backup_statvfs64)(const char*, struct statvfs64*) = nullptr;
static int (*backup_fstatfs)(int, struct statfs*) = nullptr;
static int (*backup_fstatfs64)(int, struct statfs64*) = nullptr;
static int (*backup_fstatvfs)(int, struct statvfs*) = nullptr;
static int (*backup_fstatvfs64)(int, struct statvfs64*) = nullptr;

// Ensure parent directories exist on disk
static void EnsureParentDirs(const char* path) {
    if (path == nullptr || path[0] != '/') return;
    char buffer[PATH_MAX];
    strncpy(buffer, path, sizeof(buffer) - 1);
    buffer[sizeof(buffer) - 1] = '\0';
    char* slash = strrchr(buffer, '/');
    if (slash != nullptr && slash != buffer) {
        *slash = '\0';
        // Walk down hierarchy
        for (char* p = buffer + 1; *p; ++p) {
            if (*p == '/') {
                *p = '\0';
                mkdir(buffer, 0771);
                *p = '/';
            }
        }
        mkdir(buffer, 0771);
    }
}

static std::string GetParentDir(const char* path) {
    if (path == nullptr || path[0] != '/') return "";
    const char* last_slash = strrchr(path, '/');
    if (last_slash == nullptr || last_slash == path) return "";
    return std::string(path, last_slash - path);
}

static bool StrEndsWith(const std::string& str, const std::string& suffix) {
    return str.length() >= suffix.length() &&
           str.compare(str.length() - suffix.length(), suffix.length(), suffix) == 0;
}

// Hook Implementations

static int Hook_open(const char* pathname, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list args;
        va_start(args, flags);
        mode = static_cast<mode_t>(va_arg(args, int));
        va_end(args);
    }

    if (t_in_hook || pathname == nullptr) {
        return backup_open ? backup_open(pathname, flags, mode) : open(pathname, flags, mode);
    }
    HookGuard guard;

    std::string translated;
    const char* target = pathname;
    bool hide = false;
    bool is_redirected = TranslatePathWithAntiDetect(pathname, translated, hide);
    if (hide) {
        errno = ENOENT;
        return -1;
    }
    if (IsProcMapsPath(pathname) && (flags & O_ACCMODE) == O_RDONLY) {
        return OpenMaskedProcMapsFd(pathname);
    }
    if (is_redirected) {
        target = translated.c_str();
    } else {
        if (StrEndsWith(pathname, ".so") || strstr(pathname, ".so.") != nullptr) {
            std::string parent = GetParentDir(pathname);
            if (!parent.empty()) {
                PathTranslator::Instance().AddNativeLibDir(parent);
            }
        }
    }
    if (flags & O_CREAT) {
        EnsureParentDirs(target);
    }

    int ret = backup_open ? backup_open(target, flags, mode) : open(target, flags, mode);
    if (ret >= 0 && (flags & O_DIRECTORY)) {
        TrackFd(ret, target);
    }
    return ret;
}

static int Hook_open_2(const char* pathname, int flags) {
    if (t_in_hook || pathname == nullptr) {
        return backup_open_2 ? backup_open_2(pathname, flags) : open(pathname, flags);
    }
    HookGuard guard;

    std::string translated;
    const char* target = pathname;
    bool hide = false;
    bool is_redirected = TranslatePathWithAntiDetect(pathname, translated, hide);
    if (hide) {
        errno = ENOENT;
        return -1;
    }
    if (IsProcMapsPath(pathname) && (flags & O_ACCMODE) == O_RDONLY) {
        return OpenMaskedProcMapsFd(pathname);
    }
    if (is_redirected) {
        target = translated.c_str();
    } else {
        if (StrEndsWith(pathname, ".so") || strstr(pathname, ".so.") != nullptr) {
            std::string parent = GetParentDir(pathname);
            if (!parent.empty()) {
                PathTranslator::Instance().AddNativeLibDir(parent);
            }
        }
    }
    if (flags & O_CREAT) {
        EnsureParentDirs(target);
    }
    int ret = backup_open_2 ? backup_open_2(target, flags) : open(target, flags);
    if (ret >= 0 && (flags & O_DIRECTORY)) {
        TrackFd(ret, target);
    }
    return ret;
}

static FILE* Hook_fopen(const char* pathname, const char* mode) {
    if (t_in_hook || pathname == nullptr) {
        return backup_fopen ? backup_fopen(pathname, mode) : fopen(pathname, mode);
    }
    HookGuard guard;

    std::string translated;
    const char* target = pathname;
    bool hide = false;
    bool is_redirected = TranslatePathWithAntiDetect(pathname, translated, hide);
    if (hide) {
        errno = ENOENT;
        return nullptr;
    }
    if (IsProcMapsPath(pathname) && (mode == nullptr || mode[0] == 'r')) {
        int mfd = OpenMaskedProcMapsFd(pathname);
        if (mfd >= 0) {
            FILE* f = fdopen(mfd, mode ? mode : "r");
            if (f != nullptr) return f;
            close(mfd);
        }
    }
    if (is_redirected) {
        target = translated.c_str();
    } else {
        if (StrEndsWith(pathname, ".so") || strstr(pathname, ".so.") != nullptr) {
            std::string parent = GetParentDir(pathname);
            if (!parent.empty()) {
                PathTranslator::Instance().AddNativeLibDir(parent);
            }
        }
    }
    if (mode != nullptr && (strchr(mode, 'w') != nullptr || strchr(mode, 'a') != nullptr || strchr(mode, '+') != nullptr)) {
        EnsureParentDirs(target);
    }
    return backup_fopen ? backup_fopen(target, mode) : fopen(target, mode);
}

static int Hook_openat(int dirfd, const char* pathname, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list args;
        va_start(args, flags);
        mode = static_cast<mode_t>(va_arg(args, int));
        va_end(args);
    }

    if (t_in_hook || pathname == nullptr) {
        return backup_openat ? backup_openat(dirfd, pathname, flags, mode) : openat(dirfd, pathname, flags, mode);
    }
    HookGuard guard;

    std::string resolved = ResolveAt(dirfd, pathname);
    const char* target = pathname;
    std::string translated;

    if (!resolved.empty()) {
        bool hide = false;
        if (TranslatePathWithAntiDetect(resolved.c_str(), translated, hide)) {
            target = translated.c_str();
            dirfd = AT_FDCWD;
        } else if (hide) {
            errno = ENOENT;
            return -1;
        } else if (IsProcMapsPath(resolved.c_str()) && (flags & O_ACCMODE) == O_RDONLY) {
            return OpenMaskedProcMapsFd(resolved.c_str());
        } else {
            target = resolved.c_str();
            dirfd = AT_FDCWD;
            if (StrEndsWith(resolved, ".so") || resolved.find(".so.") != std::string::npos) {
                std::string parent = GetParentDir(resolved.c_str());
                if (!parent.empty()) {
                    PathTranslator::Instance().AddNativeLibDir(parent);
                }
            }
        }
        if (flags & O_CREAT) {
            EnsureParentDirs(target);
        }
    }

    int ret = backup_openat ? backup_openat(dirfd, target, flags, mode) : openat(dirfd, target, flags, mode);
    if (ret >= 0 && (flags & O_DIRECTORY)) {
        TrackFd(ret, target);
    }
    return ret;
}

static int Hook_stat(const char* pathname, struct stat* statbuf) {
    if (t_in_hook || pathname == nullptr) {
        return backup_stat ? backup_stat(pathname, statbuf) : stat(pathname, statbuf);
    }
    HookGuard guard;

    std::string translated;
    const char* target = pathname;
    if (PathTranslator::Instance().ToRealPath(pathname, translated)) {
        target = translated.c_str();
    }
    return backup_stat ? backup_stat(target, statbuf) : stat(target, statbuf);
}

static int Hook_stat64(const char* pathname, struct stat64* statbuf) {
    if (t_in_hook || pathname == nullptr) {
        return backup_stat64 ? backup_stat64(pathname, statbuf) : stat64(pathname, statbuf);
    }
    HookGuard guard;

    std::string translated;
    const char* target = pathname;
    if (PathTranslator::Instance().ToRealPath(pathname, translated)) {
        target = translated.c_str();
    }
    return backup_stat64 ? backup_stat64(target, statbuf) : stat64(target, statbuf);
}

static int Hook_lstat(const char* pathname, struct stat* statbuf) {
    if (t_in_hook || pathname == nullptr) {
        return backup_lstat ? backup_lstat(pathname, statbuf) : lstat(pathname, statbuf);
    }
    HookGuard guard;

    std::string translated;
    const char* target = pathname;
    if (PathTranslator::Instance().ToRealPath(pathname, translated)) {
        target = translated.c_str();
    }
    return backup_lstat ? backup_lstat(target, statbuf) : lstat(target, statbuf);
}

static int Hook_lstat64(const char* pathname, struct stat64* statbuf) {
    if (t_in_hook || pathname == nullptr) {
        return backup_lstat64 ? backup_lstat64(pathname, statbuf) : lstat64(pathname, statbuf);
    }
    HookGuard guard;

    std::string translated;
    const char* target = pathname;
    if (PathTranslator::Instance().ToRealPath(pathname, translated)) {
        target = translated.c_str();
    }
    return backup_lstat64 ? backup_lstat64(target, statbuf) : lstat64(target, statbuf);
}

static int Hook_fstatat(int dirfd, const char* pathname, struct stat* statbuf, int flags) {
    if (t_in_hook || pathname == nullptr || (flags & AT_EMPTY_PATH) != 0 || pathname[0] == '\0') {
        return backup_fstatat ? backup_fstatat(dirfd, pathname, statbuf, flags) : fstatat(dirfd, pathname, statbuf, flags);
    }
    HookGuard guard;

    std::string resolved = ResolveAt(dirfd, pathname);
    const char* target = pathname;
    std::string translated;

    if (!resolved.empty()) {
        if (PathTranslator::Instance().ToRealPath(resolved, translated)) {
            target = translated.c_str();
            dirfd = AT_FDCWD;
        } else {
            target = resolved.c_str();
            dirfd = AT_FDCWD;
        }
    }
    return backup_fstatat ? backup_fstatat(dirfd, target, statbuf, flags) : fstatat(dirfd, target, statbuf, flags);
}

static int Hook_fstatat64(int dirfd, const char* pathname, struct stat64* statbuf, int flags) {
    if (t_in_hook || pathname == nullptr || (flags & AT_EMPTY_PATH) != 0 || pathname[0] == '\0') {
        return backup_fstatat64 ? backup_fstatat64(dirfd, pathname, statbuf, flags) : fstatat64(dirfd, pathname, statbuf, flags);
    }
    HookGuard guard;

    std::string resolved = ResolveAt(dirfd, pathname);
    const char* target = pathname;
    std::string translated;

    if (!resolved.empty()) {
        if (PathTranslator::Instance().ToRealPath(resolved, translated)) {
            target = translated.c_str();
            dirfd = AT_FDCWD;
        } else {
            target = resolved.c_str();
            dirfd = AT_FDCWD;
        }
    }
    return backup_fstatat64 ? backup_fstatat64(dirfd, target, statbuf, flags) : fstatat64(dirfd, target, statbuf, flags);
}

static int Hook_fstat(int fd, struct stat* statbuf) {
    if (t_in_hook || statbuf == nullptr) {
        return backup_fstat ? backup_fstat(fd, statbuf) : fstat(fd, statbuf);
    }
    HookGuard guard;
    return backup_fstat ? backup_fstat(fd, statbuf) : fstat(fd, statbuf);
}

static int Hook_fstat64(int fd, struct stat64* statbuf) {
    if (t_in_hook || statbuf == nullptr) {
        return backup_fstat64 ? backup_fstat64(fd, statbuf) : fstat64(fd, statbuf);
    }
    HookGuard guard;
    return backup_fstat64 ? backup_fstat64(fd, statbuf) : fstat64(fd, statbuf);
}

static int Hook_access(const char* pathname, int mode) {
    if (t_in_hook || pathname == nullptr) {
        return backup_access ? backup_access(pathname, mode) : access(pathname, mode);
    }
    HookGuard guard;

    std::string translated;
    const char* target = pathname;
    if (PathTranslator::Instance().ToRealPath(pathname, translated)) {
        target = translated.c_str();
    }
    return backup_access ? backup_access(target, mode) : access(target, mode);
}

static int Hook_faccessat(int dirfd, const char* pathname, int mode, int flags) {
    if (t_in_hook || pathname == nullptr || pathname[0] == '\0') {
        return backup_faccessat ? backup_faccessat(dirfd, pathname, mode, flags) : faccessat(dirfd, pathname, mode, flags);
    }
    HookGuard guard;

    std::string resolved = ResolveAt(dirfd, pathname);
    const char* target = pathname;
    std::string translated;

    if (!resolved.empty()) {
        if (PathTranslator::Instance().ToRealPath(resolved, translated)) {
            target = translated.c_str();
            dirfd = AT_FDCWD;
        } else {
            target = resolved.c_str();
            dirfd = AT_FDCWD;
        }
    }
    return backup_faccessat ? backup_faccessat(dirfd, target, mode, flags) : faccessat(dirfd, target, mode, flags);
}

static int Hook_mkdir(const char* pathname, mode_t mode) {
    if (t_in_hook || pathname == nullptr) {
        return backup_mkdir ? backup_mkdir(pathname, mode) : mkdir(pathname, mode);
    }
    HookGuard guard;

    std::string translated;
    const char* target = pathname;
    if (PathTranslator::Instance().ToRealPath(pathname, translated)) {
        target = translated.c_str();
        EnsureParentDirs(target);
    }
    return backup_mkdir ? backup_mkdir(target, mode) : mkdir(target, mode);
}

static int Hook_mkdirat(int dirfd, const char* pathname, mode_t mode) {
    if (t_in_hook || pathname == nullptr) {
        return backup_mkdirat ? backup_mkdirat(dirfd, pathname, mode) : mkdirat(dirfd, pathname, mode);
    }
    HookGuard guard;

    std::string resolved = ResolveAt(dirfd, pathname);
    const char* target = pathname;
    std::string translated;

    if (!resolved.empty()) {
        if (PathTranslator::Instance().ToRealPath(resolved, translated)) {
            target = translated.c_str();
            dirfd = AT_FDCWD;
            EnsureParentDirs(target);
        } else {
            target = resolved.c_str();
            dirfd = AT_FDCWD;
        }
    }
    return backup_mkdirat ? backup_mkdirat(dirfd, target, mode) : mkdirat(dirfd, target, mode);
}

static int Hook_rename(const char* oldpath, const char* newpath) {
    if (t_in_hook || oldpath == nullptr || newpath == nullptr) {
        return backup_rename ? backup_rename(oldpath, newpath) : rename(oldpath, newpath);
    }
    HookGuard guard;

    std::string trans_old, trans_new;
    const char* target_old = oldpath;
    const char* target_new = newpath;

    if (PathTranslator::Instance().ToRealPath(oldpath, trans_old)) target_old = trans_old.c_str();
    if (PathTranslator::Instance().ToRealPath(newpath, trans_new)) {
        target_new = trans_new.c_str();
        EnsureParentDirs(target_new);
    }
    return backup_rename ? backup_rename(target_old, target_new) : rename(target_old, target_new);
}

static int Hook_renameat(int olddirfd, const char* oldpath, int newdirfd, const char* newpath) {
    if (t_in_hook || oldpath == nullptr || newpath == nullptr) {
        return backup_renameat ? backup_renameat(olddirfd, oldpath, newdirfd, newpath) : renameat(olddirfd, oldpath, newdirfd, newpath);
    }
    HookGuard guard;

    std::string resolved_old = ResolveAt(olddirfd, oldpath);
    std::string resolved_new = ResolveAt(newdirfd, newpath);
    const char* target_old = oldpath;
    const char* target_new = newpath;
    std::string trans_old, trans_new;

    if (!resolved_old.empty()) {
        if (PathTranslator::Instance().ToRealPath(resolved_old, trans_old)) {
            target_old = trans_old.c_str();
            olddirfd = AT_FDCWD;
        } else {
            target_old = resolved_old.c_str();
            olddirfd = AT_FDCWD;
        }
    }

    if (!resolved_new.empty()) {
        if (PathTranslator::Instance().ToRealPath(resolved_new, trans_new)) {
            target_new = trans_new.c_str();
            newdirfd = AT_FDCWD;
            EnsureParentDirs(target_new);
        } else {
            target_new = resolved_new.c_str();
            newdirfd = AT_FDCWD;
        }
    }
    return backup_renameat ? backup_renameat(olddirfd, target_old, newdirfd, target_new) : renameat(olddirfd, target_old, newdirfd, target_new);
}

static int Hook_unlink(const char* pathname) {
    if (t_in_hook || pathname == nullptr) {
        return backup_unlink ? backup_unlink(pathname) : unlink(pathname);
    }
    HookGuard guard;

    std::string translated;
    const char* target = pathname;
    if (PathTranslator::Instance().ToRealPath(pathname, translated)) {
        target = translated.c_str();
    }
    return backup_unlink ? backup_unlink(target) : unlink(target);
}

static int Hook_unlinkat(int dirfd, const char* pathname, int flags) {
    if (t_in_hook || pathname == nullptr) {
        return backup_unlinkat ? backup_unlinkat(dirfd, pathname, flags) : unlinkat(dirfd, pathname, flags);
    }
    HookGuard guard;

    std::string resolved = ResolveAt(dirfd, pathname);
    const char* target = pathname;
    std::string translated;

    if (!resolved.empty()) {
        if (PathTranslator::Instance().ToRealPath(resolved, translated)) {
            target = translated.c_str();
            dirfd = AT_FDCWD;
        } else {
            target = resolved.c_str();
            dirfd = AT_FDCWD;
        }
    }
    return backup_unlinkat ? backup_unlinkat(dirfd, target, flags) : unlinkat(dirfd, target, flags);
}

static int Hook_rmdir(const char* pathname) {
    if (t_in_hook || pathname == nullptr) {
        return backup_rmdir ? backup_rmdir(pathname) : rmdir(pathname);
    }
    HookGuard guard;

    std::string translated;
    const char* target = pathname;
    if (PathTranslator::Instance().ToRealPath(pathname, translated)) {
        target = translated.c_str();
    }
    return backup_rmdir ? backup_rmdir(target) : rmdir(target);
}

static int Hook_remove(const char* pathname) {
    if (t_in_hook || pathname == nullptr) {
        return backup_remove ? backup_remove(pathname) : remove(pathname);
    }
    HookGuard guard;

    std::string translated;
    const char* target = pathname;
    if (PathTranslator::Instance().ToRealPath(pathname, translated)) {
        target = translated.c_str();
    }
    return backup_remove ? backup_remove(target) : remove(target);
}

static DIR* Hook_opendir(const char* name) {
    if (t_in_hook || name == nullptr) {
        return backup_opendir ? backup_opendir(name) : opendir(name);
    }
    HookGuard guard;

    std::string translated;
    const char* target = name;
    if (PathTranslator::Instance().ToRealPath(name, translated)) {
        target = translated.c_str();
    }
    DIR* dirp = backup_opendir ? backup_opendir(target) : opendir(target);
    if (dirp != nullptr) {
        int fd = dirfd(dirp);
        if (fd >= 0) {
            TrackFd(fd, target);
        }
    }
    return dirp;
}

static struct dirent* Hook_readdir(DIR* dirp) {
    return backup_readdir ? backup_readdir(dirp) : readdir(dirp);
}

static struct dirent64* Hook_readdir64(DIR* dirp) {
    return backup_readdir64 ? backup_readdir64(dirp) : readdir64(dirp);
}

static int Hook_closedir(DIR* dirp) {
    if (dirp != nullptr) {
        int fd = dirfd(dirp);
        if (fd >= 0) {
            UntrackFd(fd);
        }
    }
    return backup_closedir ? backup_closedir(dirp) : closedir(dirp);
}

static int Hook_close(int fd) {
    UntrackFd(fd);
    return backup_close ? backup_close(fd) : close(fd);
}

static int Hook_dup(int oldfd) {
    int newfd = backup_dup ? backup_dup(oldfd) : dup(oldfd);
    DuplicateFd(oldfd, newfd);
    return newfd;
}

static int Hook_dup2(int oldfd, int newfd) {
    int ret = backup_dup2 ? backup_dup2(oldfd, newfd) : dup2(oldfd, newfd);
    if (ret >= 0) {
        DuplicateFd(oldfd, ret);
    }
    return ret;
}

static int Hook_dup3(int oldfd, int newfd, int flags) {
    int ret = backup_dup3 ? backup_dup3(oldfd, newfd, flags) : dup3(oldfd, newfd, flags);
    if (ret >= 0) {
        DuplicateFd(oldfd, ret);
    }
    return ret;
}

static int Hook_chmod(const char* pathname, mode_t mode) {
    if (t_in_hook || pathname == nullptr) {
        return backup_chmod ? backup_chmod(pathname, mode) : chmod(pathname, mode);
    }
    HookGuard guard;

    std::string translated;
    const char* target = pathname;
    bool is_redirected = PathTranslator::Instance().ToRealPath(pathname, translated);
    if (is_redirected) target = translated.c_str();

    int ret = backup_chmod ? backup_chmod(target, mode) : chmod(target, mode);
    // Shared storage limitation: swallow EPERM/EACCES on redirected container paths
    if (ret != 0 && (is_redirected || PathTranslator::Instance().IsAlreadyRealPath(target))) {
        if (errno == EPERM || errno == EACCES) {
            return 0;
        }
    }
    return ret;
}

static int Hook_fchmod(int fd, mode_t mode) {
    int ret = backup_fchmod ? backup_fchmod(fd, mode) : fchmod(fd, mode);
    if (ret != 0 && (errno == EPERM || errno == EACCES)) {
        return 0; // Swallow on container descriptors
    }
    return ret;
}

static int Hook_fchmodat(int dirfd, const char* pathname, mode_t mode, int flags) {
    if (t_in_hook || pathname == nullptr || (flags & AT_EMPTY_PATH) != 0 || pathname[0] == '\0') {
        return backup_fchmodat ? backup_fchmodat(dirfd, pathname, mode, flags) : fchmodat(dirfd, pathname, mode, flags);
    }
    HookGuard guard;

    std::string resolved = ResolveAt(dirfd, pathname);
    const char* target = pathname;
    std::string translated;
    bool is_redirected = false;

    if (!resolved.empty()) {
        is_redirected = PathTranslator::Instance().ToRealPath(resolved, translated);
        if (is_redirected) {
            target = translated.c_str();
            dirfd = AT_FDCWD;
        } else {
            target = resolved.c_str();
            dirfd = AT_FDCWD;
        }
    }

    int ret = backup_fchmodat ? backup_fchmodat(dirfd, target, mode, flags) : fchmodat(dirfd, target, mode, flags);
    if (ret != 0 && (is_redirected || PathTranslator::Instance().IsAlreadyRealPath(target))) {
        if (errno == EPERM || errno == EACCES) {
            return 0;
        }
    }
    return ret;
}

static ssize_t Hook_readlink(const char* pathname, char* buf, size_t bufsiz) {
    if (t_in_hook || pathname == nullptr || buf == nullptr) {
        return backup_readlink ? backup_readlink(pathname, buf, bufsiz) : readlink(pathname, buf, bufsiz);
    }
    HookGuard guard;

    // Check /proc/self/fd/ interception
    ssize_t ret = backup_readlink ? backup_readlink(pathname, buf, bufsiz) : readlink(pathname, buf, bufsiz);
    if (ret > 0 && bufsiz > 0) {
        std::string target(buf, static_cast<size_t>(ret));
        std::string virt;
        if (PathTranslator::Instance().ToVirtualPath(target, virt)) {
            size_t copy_len = std::min(bufsiz, virt.length());
            memcpy(buf, virt.c_str(), copy_len);
            return static_cast<ssize_t>(copy_len);
        }
    }
    return ret;
}

static ssize_t Hook_readlinkat(int dirfd, const char* pathname, char* buf, size_t bufsiz) {
    if (t_in_hook || pathname == nullptr || buf == nullptr) {
        return backup_readlinkat ? backup_readlinkat(dirfd, pathname, buf, bufsiz) : readlinkat(dirfd, pathname, buf, bufsiz);
    }
    HookGuard guard;

    ssize_t ret = backup_readlinkat ? backup_readlinkat(dirfd, pathname, buf, bufsiz) : readlinkat(dirfd, pathname, buf, bufsiz);
    if (ret > 0 && bufsiz > 0) {
        std::string target(buf, static_cast<size_t>(ret));
        std::string virt;
        if (PathTranslator::Instance().ToVirtualPath(target, virt)) {
            size_t copy_len = std::min(bufsiz, virt.length());
            memcpy(buf, virt.c_str(), copy_len);
            return static_cast<ssize_t>(copy_len);
        }
    }
    return ret;
}

static char* Hook_realpath(const char* path, char* resolved_path) {
    if (t_in_hook || path == nullptr) {
        return backup_realpath ? backup_realpath(path, resolved_path) : realpath(path, resolved_path);
    }
    HookGuard guard;

    std::string translated;
    const char* target = path;
    if (PathTranslator::Instance().ToRealPath(path, translated)) {
        target = translated.c_str();
    }

    char local_buf[PATH_MAX];
    char* r = backup_realpath ? backup_realpath(target, local_buf) : realpath(target, local_buf);
    if (r == nullptr) return nullptr;

    std::string virt;
    const char* final_path = local_buf;
    if (PathTranslator::Instance().ToVirtualPath(local_buf, virt)) {
        final_path = virt.c_str();
    }

    if (resolved_path != nullptr) {
        strncpy(resolved_path, final_path, PATH_MAX - 1);
        resolved_path[PATH_MAX - 1] = '\0';
        return resolved_path;
    } else {
        return strdup(final_path);
    }
}

static int StatFsFallback(const char* target, struct statfs* buf) {
    int ret = backup_statfs ? backup_statfs(target, buf) : statfs(target, buf);
    if (ret == 0) return 0;

    std::string cur = target;
    while (ret != 0) {
        std::string parent = GetParentDir(cur.c_str());
        if (parent.empty() || parent == cur) break;
        cur = parent;
        ret = backup_statfs ? backup_statfs(cur.c_str(), buf) : statfs(cur.c_str(), buf);
    }
    if (ret != 0) {
        const auto& root = PathTranslator::Instance().ContainerRoot();
        if (!root.empty()) {
            ret = backup_statfs ? backup_statfs(root.c_str(), buf) : statfs(root.c_str(), buf);
        }
    }
    if (ret != 0) {
        ret = backup_statfs ? backup_statfs("/storage/emulated/0", buf) : statfs("/storage/emulated/0", buf);
    }
    if (ret != 0) {
        ret = backup_statfs ? backup_statfs("/data", buf) : statfs("/data", buf);
    }
    return ret;
}

static int StatFs64Fallback(const char* target, struct statfs64* buf) {
    int ret = backup_statfs64 ? backup_statfs64(target, buf) : statfs64(target, buf);
    if (ret == 0) return 0;

    std::string cur = target;
    while (ret != 0) {
        std::string parent = GetParentDir(cur.c_str());
        if (parent.empty() || parent == cur) break;
        cur = parent;
        ret = backup_statfs64 ? backup_statfs64(cur.c_str(), buf) : statfs64(cur.c_str(), buf);
    }
    if (ret != 0) {
        const auto& root = PathTranslator::Instance().ContainerRoot();
        if (!root.empty()) {
            ret = backup_statfs64 ? backup_statfs64(root.c_str(), buf) : statfs64(root.c_str(), buf);
        }
    }
    if (ret != 0) {
        ret = backup_statfs64 ? backup_statfs64("/storage/emulated/0", buf) : statfs64("/storage/emulated/0", buf);
    }
    if (ret != 0) {
        ret = backup_statfs64 ? backup_statfs64("/data", buf) : statfs64("/data", buf);
    }
    return ret;
}

static int StatVfsFallback(const char* target, struct statvfs* buf) {
    int ret = backup_statvfs ? backup_statvfs(target, buf) : statvfs(target, buf);
    if (ret == 0) return 0;

    std::string cur = target;
    while (ret != 0) {
        std::string parent = GetParentDir(cur.c_str());
        if (parent.empty() || parent == cur) break;
        cur = parent;
        ret = backup_statvfs ? backup_statvfs(cur.c_str(), buf) : statvfs(cur.c_str(), buf);
    }
    if (ret != 0) {
        const auto& root = PathTranslator::Instance().ContainerRoot();
        if (!root.empty()) {
            ret = backup_statvfs ? backup_statvfs(root.c_str(), buf) : statvfs(root.c_str(), buf);
        }
    }
    if (ret != 0) {
        ret = backup_statvfs ? backup_statvfs("/storage/emulated/0", buf) : statvfs("/storage/emulated/0", buf);
    }
    if (ret != 0) {
        ret = backup_statvfs ? backup_statvfs("/data", buf) : statvfs("/data", buf);
    }
    return ret;
}

static int StatVfs64Fallback(const char* target, struct statvfs64* buf) {
    int ret = backup_statvfs64 ? backup_statvfs64(target, buf) : statvfs64(target, buf);
    if (ret == 0) return 0;

    std::string cur = target;
    while (ret != 0) {
        std::string parent = GetParentDir(cur.c_str());
        if (parent.empty() || parent == cur) break;
        cur = parent;
        ret = backup_statvfs64 ? backup_statvfs64(cur.c_str(), buf) : statvfs64(cur.c_str(), buf);
    }
    if (ret != 0) {
        const auto& root = PathTranslator::Instance().ContainerRoot();
        if (!root.empty()) {
            ret = backup_statvfs64 ? backup_statvfs64(root.c_str(), buf) : statvfs64(root.c_str(), buf);
        }
    }
    if (ret != 0) {
        ret = backup_statvfs64 ? backup_statvfs64("/storage/emulated/0", buf) : statvfs64("/storage/emulated/0", buf);
    }
    if (ret != 0) {
        ret = backup_statvfs64 ? backup_statvfs64("/data", buf) : statvfs64("/data", buf);
    }
    return ret;
}

static int Hook_statfs(const char* path, struct statfs* buf) {
    if (t_in_hook || path == nullptr || buf == nullptr) {
        return backup_statfs ? backup_statfs(path, buf) : statfs(path, buf);
    }
    HookGuard guard;

    std::string translated;
    const char* target = path;
    bool is_redirected = PathTranslator::Instance().ToRealPath(path, translated);
    if (is_redirected) target = translated.c_str();

    int ret = StatFsFallback(target, buf);
    if (ret == 0 && (is_redirected || PathTranslator::Instance().IsAlreadyRealPath(target))) {
        // Spoof filesystem type to EXT4_SUPER_MAGIC (0xef53)
        buf->f_type = 0xef53;
    }
    return ret;
}

static int Hook_statfs64(const char* path, struct statfs64* buf) {
    if (t_in_hook || path == nullptr || buf == nullptr) {
        return backup_statfs64 ? backup_statfs64(path, buf) : statfs64(path, buf);
    }
    HookGuard guard;

    std::string translated;
    const char* target = path;
    bool is_redirected = PathTranslator::Instance().ToRealPath(path, translated);
    if (is_redirected) target = translated.c_str();

    int ret = StatFs64Fallback(target, buf);
    if (ret == 0 && (is_redirected || PathTranslator::Instance().IsAlreadyRealPath(target))) {
        buf->f_type = 0xef53;
    }
    return ret;
}

static int Hook_statvfs(const char* path, struct statvfs* buf) {
    if (t_in_hook || path == nullptr || buf == nullptr) {
        return backup_statvfs ? backup_statvfs(path, buf) : statvfs(path, buf);
    }
    HookGuard guard;

    std::string translated;
    const char* target = path;
    bool is_redirected = PathTranslator::Instance().ToRealPath(path, translated);
    if (is_redirected) target = translated.c_str();

    int ret = StatVfsFallback(target, buf);
    return ret;
}

static int Hook_statvfs64(const char* path, struct statvfs64* buf) {
    if (t_in_hook || path == nullptr || buf == nullptr) {
        return backup_statvfs64 ? backup_statvfs64(path, buf) : statvfs64(path, buf);
    }
    HookGuard guard;

    std::string translated;
    const char* target = path;
    bool is_redirected = PathTranslator::Instance().ToRealPath(path, translated);
    if (is_redirected) target = translated.c_str();

    int ret = StatVfs64Fallback(target, buf);
    return ret;
}

static int Hook_fstatfs(int fd, struct statfs* buf) {
    if (t_in_hook || buf == nullptr) {
        return backup_fstatfs ? backup_fstatfs(fd, buf) : fstatfs(fd, buf);
    }
    HookGuard guard;
    int ret = backup_fstatfs ? backup_fstatfs(fd, buf) : fstatfs(fd, buf);
    if (ret == 0) {
        buf->f_type = 0xef53;
    }
    return ret;
}

static int Hook_fstatfs64(int fd, struct statfs64* buf) {
    if (t_in_hook || buf == nullptr) {
        return backup_fstatfs64 ? backup_fstatfs64(fd, buf) : fstatfs64(fd, buf);
    }
    HookGuard guard;
    int ret = backup_fstatfs64 ? backup_fstatfs64(fd, buf) : fstatfs64(fd, buf);
    if (ret == 0) {
        buf->f_type = 0xef53;
    }
    return ret;
}

static int Hook_fstatvfs(int fd, struct statvfs* buf) {
    if (t_in_hook || buf == nullptr) {
        return backup_fstatvfs ? backup_fstatvfs(fd, buf) : fstatvfs(fd, buf);
    }
    HookGuard guard;
    return backup_fstatvfs ? backup_fstatvfs(fd, buf) : fstatvfs(fd, buf);
}

static int Hook_fstatvfs64(int fd, struct statvfs64* buf) {
    if (t_in_hook || buf == nullptr) {
        return backup_fstatvfs64 ? backup_fstatvfs64(fd, buf) : fstatvfs64(fd, buf);
    }
    HookGuard guard;
    return backup_fstatvfs64 ? backup_fstatvfs64(fd, buf) : fstatvfs64(fd, buf);
}

// SQLite VFS Interception
typedef struct sqlite3_vfs sqlite3_vfs;
typedef void (*sqlite3_syscall_ptr)(void);
struct sqlite3_vfs {
    int iVersion;
    int szOsFile;
    int mxPathname;
    sqlite3_vfs *pNext;
    const char *zName;
    void *pAppData;
    int (*xOpen)(sqlite3_vfs*, const char*, void*, int, int*);
    int (*xDelete)(sqlite3_vfs*, const char*, int);
    int (*xAccess)(sqlite3_vfs*, const char*, int, int*);
    int (*xFullPathname)(sqlite3_vfs*, const char*, int, char*);
    void *(*xDlOpen)(sqlite3_vfs*, const char*);
    void (*xDlError)(sqlite3_vfs*, int, char*);
    void (*(*xDlSym)(sqlite3_vfs*, void*, const char*))(void);
    void (*xDlClose)(sqlite3_vfs*, void*);
    int (*xRandomness)(sqlite3_vfs*, int, char*);
    int (*xSleep)(sqlite3_vfs*, int);
    int (*xCurrentTime)(sqlite3_vfs*, double*);
    int (*xGetLastError)(sqlite3_vfs*, int, char*);
    int (*xCurrentTimeInt64)(sqlite3_vfs*, int64_t*);
    int (*xSetSystemCall)(sqlite3_vfs*, const char*, sqlite3_syscall_ptr);
    sqlite3_syscall_ptr (*xGetSystemCall)(sqlite3_vfs*, const char*);
    const char *(*xNextSystemCall)(sqlite3_vfs*, const char*);
};

static void ApplySqliteVfsHooks(sqlite3_vfs* vfs) {
    if (vfs == nullptr || vfs->iVersion < 3 || vfs->xSetSystemCall == nullptr) return;
    vfs->xSetSystemCall(vfs, "open", reinterpret_cast<sqlite3_syscall_ptr>(Hook_open));
    vfs->xSetSystemCall(vfs, "stat", reinterpret_cast<sqlite3_syscall_ptr>(Hook_stat));
    vfs->xSetSystemCall(vfs, "fstat", reinterpret_cast<sqlite3_syscall_ptr>(Hook_fstat));
    vfs->xSetSystemCall(vfs, "lstat", reinterpret_cast<sqlite3_syscall_ptr>(Hook_lstat));
    vfs->xSetSystemCall(vfs, "access", reinterpret_cast<sqlite3_syscall_ptr>(Hook_access));
    vfs->xSetSystemCall(vfs, "unlink", reinterpret_cast<sqlite3_syscall_ptr>(Hook_unlink));
    vfs->xSetSystemCall(vfs, "mkdir", reinterpret_cast<sqlite3_syscall_ptr>(Hook_mkdir));
    vfs->xSetSystemCall(vfs, "rmdir", reinterpret_cast<sqlite3_syscall_ptr>(Hook_rmdir));
    vfs->xSetSystemCall(vfs, "fchmod", reinterpret_cast<sqlite3_syscall_ptr>(Hook_fchmod));
    vfs->xSetSystemCall(vfs, "readlink", reinterpret_cast<sqlite3_syscall_ptr>(Hook_readlink));
}

static void HookSqliteVfs() {
    auto maps = lsplt::MapInfo::Scan();
    uintptr_t sqlite_base = 0;
    for (const auto& map : maps) {
        if (map.offset == 0 && map.path.find("libsqlite.so") != std::string::npos) {
            sqlite_base = map.start;
            break;
        }
    }
    if (sqlite_base == 0) return;

    Elf elf(sqlite_base);
    if (!elf.Valid()) return;

    uintptr_t find_addr = elf.FindExportedAddr("sqlite3_vfs_find");
    if (find_addr == 0) {
        LOGW("Failed to locate sqlite3_vfs_find in libsqlite.so");
        return;
    }

    typedef sqlite3_vfs* (*fn_vfs_find)(const char*);
    auto p_vfs_find = reinterpret_cast<fn_vfs_find>(find_addr);

    sqlite3_vfs* cur = p_vfs_find(nullptr);
    while (cur != nullptr) {
        LOGI("Configuring SQLite VFS '%s' with transparent storage syscall hooks", cur->zName ? cur->zName : "default");
        ApplySqliteVfsHooks(cur);
        cur = cur->pNext;
    }
}

static int (*backup_sqlite3_vfs_register)(sqlite3_vfs*, int) = nullptr;
static int Hook_sqlite3_vfs_register(sqlite3_vfs* pVfs, int makeDflt) {
    if (pVfs != nullptr) {
        ApplySqliteVfsHooks(pVfs);
    }
    if (backup_sqlite3_vfs_register) {
        return backup_sqlite3_vfs_register(pVfs, makeDflt);
    }
    void* sym = dlsym(RTLD_DEFAULT, "sqlite3_vfs_register");
    if (sym) {
        auto fn = reinterpret_cast<int (*)(sqlite3_vfs*, int)>(sym);
        return fn(pVfs, makeDflt);
    }
    return 0;
}

// Target library identification
static bool IsTargetModule(const std::string& path) {
    if (path.empty()) return false;

    // Never hook our own libraries, libc, dobby, or dynamic linker
    if (path.find("liblspatch.so") != std::string::npos ||
        path.find("liblspatch_storage.so") != std::string::npos ||
        path.find("libdobby.so") != std::string::npos ||
        path.find("libc.so") != std::string::npos ||
        path.find("libdl.so") != std::string::npos) {
        return false;
    }

    // Framework modules verified on Android 14 ARM64
    if (path.find("libopenjdk.so") != std::string::npos ||
        path.find("libjavacore.so") != std::string::npos ||
        path.find("libsqlite.so") != std::string::npos ||
        path.find("libandroid_runtime.so") != std::string::npos) {
        return true;
    }

    // Target app's own native libraries
    if (path.find("/data/app/") != std::string::npos) {
        if (path.rfind(".so") != std::string::npos || path.find(".so.") != std::string::npos) {
            return true;
        }
        return false;
    }

    // Extracted libraries in app-private storage
    const auto& internal_dir = PathTranslator::Instance().InternalDataDir();
    if (!internal_dir.empty() && path.find(internal_dir) != std::string::npos) {
        if (path.rfind(".so") != std::string::npos || path.find(".so.") != std::string::npos) {
            return true;
        }
        return false;
    }

    const auto& pkg = PathTranslator::Instance().PackageName();
    if (!pkg.empty()) {
        std::string p1 = "/data/user/0/" + pkg;
        std::string p2 = "/data/data/" + pkg;
        if ((path.find(p1) != std::string::npos || path.find(p2) != std::string::npos) &&
            (path.rfind(".so") != std::string::npos || path.find(".so.") != std::string::npos)) {
            return true;
        }
    }

    return false;
}

static void HookLibraryByMap(const lsplt::MapInfo& map) {
    dev_t dev = map.dev;
    ino_t inode = map.inode;

    // File I/O Core Calls
    lsplt::RegisterHook(dev, inode, "open", reinterpret_cast<void*>(Hook_open), reinterpret_cast<void**>(&backup_open));
    lsplt::RegisterHook(dev, inode, "__open_2", reinterpret_cast<void*>(Hook_open_2), reinterpret_cast<void**>(&backup_open_2));
    lsplt::RegisterHook(dev, inode, "openat", reinterpret_cast<void*>(Hook_openat), reinterpret_cast<void**>(&backup_openat));
    lsplt::RegisterHook(dev, inode, "fopen", reinterpret_cast<void*>(Hook_fopen), reinterpret_cast<void**>(&backup_fopen));

    lsplt::RegisterHook(dev, inode, "stat", reinterpret_cast<void*>(Hook_stat), reinterpret_cast<void**>(&backup_stat));
    lsplt::RegisterHook(dev, inode, "stat64", reinterpret_cast<void*>(Hook_stat64), reinterpret_cast<void**>(&backup_stat64));
    lsplt::RegisterHook(dev, inode, "fstat", reinterpret_cast<void*>(Hook_fstat), reinterpret_cast<void**>(&backup_fstat));
    lsplt::RegisterHook(dev, inode, "fstat64", reinterpret_cast<void*>(Hook_fstat64), reinterpret_cast<void**>(&backup_fstat64));
    lsplt::RegisterHook(dev, inode, "lstat", reinterpret_cast<void*>(Hook_lstat), reinterpret_cast<void**>(&backup_lstat));
    lsplt::RegisterHook(dev, inode, "lstat64", reinterpret_cast<void*>(Hook_lstat64), reinterpret_cast<void**>(&backup_lstat64));
    lsplt::RegisterHook(dev, inode, "fstatat", reinterpret_cast<void*>(Hook_fstatat), reinterpret_cast<void**>(&backup_fstatat));
    lsplt::RegisterHook(dev, inode, "fstatat64", reinterpret_cast<void*>(Hook_fstatat64), reinterpret_cast<void**>(&backup_fstatat64));

    lsplt::RegisterHook(dev, inode, "access", reinterpret_cast<void*>(Hook_access), reinterpret_cast<void**>(&backup_access));
    lsplt::RegisterHook(dev, inode, "faccessat", reinterpret_cast<void*>(Hook_faccessat), reinterpret_cast<void**>(&backup_faccessat));

    lsplt::RegisterHook(dev, inode, "mkdir", reinterpret_cast<void*>(Hook_mkdir), reinterpret_cast<void**>(&backup_mkdir));
    lsplt::RegisterHook(dev, inode, "mkdirat", reinterpret_cast<void*>(Hook_mkdirat), reinterpret_cast<void**>(&backup_mkdirat));

    lsplt::RegisterHook(dev, inode, "rename", reinterpret_cast<void*>(Hook_rename), reinterpret_cast<void**>(&backup_rename));
    lsplt::RegisterHook(dev, inode, "renameat", reinterpret_cast<void*>(Hook_renameat), reinterpret_cast<void**>(&backup_renameat));

    lsplt::RegisterHook(dev, inode, "unlink", reinterpret_cast<void*>(Hook_unlink), reinterpret_cast<void**>(&backup_unlink));
    lsplt::RegisterHook(dev, inode, "unlinkat", reinterpret_cast<void*>(Hook_unlinkat), reinterpret_cast<void**>(&backup_unlinkat));
    lsplt::RegisterHook(dev, inode, "rmdir", reinterpret_cast<void*>(Hook_rmdir), reinterpret_cast<void**>(&backup_rmdir));
    lsplt::RegisterHook(dev, inode, "remove", reinterpret_cast<void*>(Hook_remove), reinterpret_cast<void**>(&backup_remove));

    lsplt::RegisterHook(dev, inode, "opendir", reinterpret_cast<void*>(Hook_opendir), reinterpret_cast<void**>(&backup_opendir));
    lsplt::RegisterHook(dev, inode, "readdir", reinterpret_cast<void*>(Hook_readdir), reinterpret_cast<void**>(&backup_readdir));
    lsplt::RegisterHook(dev, inode, "readdir64", reinterpret_cast<void*>(Hook_readdir64), reinterpret_cast<void**>(&backup_readdir64));
    lsplt::RegisterHook(dev, inode, "closedir", reinterpret_cast<void*>(Hook_closedir), reinterpret_cast<void**>(&backup_closedir));

    lsplt::RegisterHook(dev, inode, "close", reinterpret_cast<void*>(Hook_close), reinterpret_cast<void**>(&backup_close));
    lsplt::RegisterHook(dev, inode, "dup", reinterpret_cast<void*>(Hook_dup), reinterpret_cast<void**>(&backup_dup));
    lsplt::RegisterHook(dev, inode, "dup2", reinterpret_cast<void*>(Hook_dup2), reinterpret_cast<void**>(&backup_dup2));
    lsplt::RegisterHook(dev, inode, "dup3", reinterpret_cast<void*>(Hook_dup3), reinterpret_cast<void**>(&backup_dup3));

    lsplt::RegisterHook(dev, inode, "chmod", reinterpret_cast<void*>(Hook_chmod), reinterpret_cast<void**>(&backup_chmod));
    lsplt::RegisterHook(dev, inode, "fchmod", reinterpret_cast<void*>(Hook_fchmod), reinterpret_cast<void**>(&backup_fchmod));
    lsplt::RegisterHook(dev, inode, "fchmodat", reinterpret_cast<void*>(Hook_fchmodat), reinterpret_cast<void**>(&backup_fchmodat));

    lsplt::RegisterHook(dev, inode, "readlink", reinterpret_cast<void*>(Hook_readlink), reinterpret_cast<void**>(&backup_readlink));
    lsplt::RegisterHook(dev, inode, "readlinkat", reinterpret_cast<void*>(Hook_readlinkat), reinterpret_cast<void**>(&backup_readlinkat));
    lsplt::RegisterHook(dev, inode, "realpath", reinterpret_cast<void*>(Hook_realpath), reinterpret_cast<void**>(&backup_realpath));

    lsplt::RegisterHook(dev, inode, "statfs", reinterpret_cast<void*>(Hook_statfs), reinterpret_cast<void**>(&backup_statfs));
    lsplt::RegisterHook(dev, inode, "statfs64", reinterpret_cast<void*>(Hook_statfs64), reinterpret_cast<void**>(&backup_statfs64));
    lsplt::RegisterHook(dev, inode, "statvfs", reinterpret_cast<void*>(Hook_statvfs), reinterpret_cast<void**>(&backup_statvfs));
    lsplt::RegisterHook(dev, inode, "statvfs64", reinterpret_cast<void*>(Hook_statvfs64), reinterpret_cast<void**>(&backup_statvfs64));
    lsplt::RegisterHook(dev, inode, "fstatfs", reinterpret_cast<void*>(Hook_fstatfs), reinterpret_cast<void**>(&backup_fstatfs));
    lsplt::RegisterHook(dev, inode, "fstatfs64", reinterpret_cast<void*>(Hook_fstatfs64), reinterpret_cast<void**>(&backup_fstatfs64));
    lsplt::RegisterHook(dev, inode, "fstatvfs", reinterpret_cast<void*>(Hook_fstatvfs), reinterpret_cast<void**>(&backup_fstatvfs));
    lsplt::RegisterHook(dev, inode, "fstatvfs64", reinterpret_cast<void*>(Hook_fstatvfs64), reinterpret_cast<void**>(&backup_fstatvfs64));
    lsplt::RegisterHook(dev, inode, "sqlite3_vfs_register", reinterpret_cast<void*>(Hook_sqlite3_vfs_register), reinterpret_cast<void**>(&backup_sqlite3_vfs_register));
    lsplt::RegisterHook(dev, inode, "__system_property_get", reinterpret_cast<void*>(Hook_system_property_get), reinterpret_cast<void**>(&backup_system_property_get));
    lsplt::RegisterHook(dev, inode, "__system_property_find", reinterpret_cast<void*>(Hook_system_property_find), reinterpret_cast<void**>(&backup_system_property_find));
    lsplt::RegisterHook(dev, inode, "__system_property_read_callback", reinterpret_cast<void*>(Hook_system_property_read_callback), reinterpret_cast<void**>(&backup_system_property_read_callback));
}

} // namespace

static std::mutex g_hook_mutex;
static std::unordered_set<ino_t> g_hooked_inodes;

bool InstallStoragePltHooks() {
    auto maps = lsplt::MapInfo::Scan();
    int hooked_modules = 0;

    std::lock_guard<std::mutex> lock(g_hook_mutex);
    for (const auto& map : maps) {
        if (g_hooked_inodes.count(map.inode)) continue;
        if (IsTargetModule(map.path)) {
            g_hooked_inodes.insert(map.inode);
            LOGI("Hooking target module via PLT: %s (dev=%ld, inode=%ld)", map.path.c_str(), (long)map.dev, (long)map.inode);
            HookLibraryByMap(map);
            hooked_modules++;
        }
    }

    bool success = lsplt::CommitHook();
    LOGI("Committed PLT storage hooks across %d modules: %s", hooked_modules, success ? "SUCCESS" : "PARTIAL/FAIL");
    HookSqliteVfs();
    return success;
}

void HookAppNativeLibraries() {
    auto maps = lsplt::MapInfo::Scan();
    int new_hooks = 0;

    std::lock_guard<std::mutex> lock(g_hook_mutex);
    for (const auto& map : maps) {
        if (g_hooked_inodes.count(map.inode)) continue;
        if (IsTargetModule(map.path)) {
            g_hooked_inodes.insert(map.inode);
            LOGI("Hooking dynamically loaded module via PLT: %s", map.path.c_str());
            HookLibraryByMap(map);
            new_hooks++;
        }
    }
    if (new_hooks > 0) {
        bool success = lsplt::CommitHook();
        LOGI("Committed PLT storage hooks for %d newly loaded modules: %s", new_hooks, success ? "SUCCESS" : "FAIL");
    }
    HookSqliteVfs();
}

} // namespace lspd::storage

// JNI Bridge for StorageRedirector.java
extern "C" {

JNIEXPORT void JNICALL
Java_org_lsposed_lspatch_loader_StorageRedirector_nativeInitStorageSpoof(
        JNIEnv* env, jclass /*clazz*/, jstring containerRoot, jstring packageName, jstring internalDataDir,
        jboolean redirectExternal, jboolean redirectInternal) {
    const char* c_container = env->GetStringUTFChars(containerRoot, nullptr);
    const char* c_package = env->GetStringUTFChars(packageName, nullptr);
    const char* c_internal = env->GetStringUTFChars(internalDataDir, nullptr);

    lspd::storage::PathTranslator::Instance().Init(c_container, c_package, c_internal, redirectExternal, redirectInternal);

    env->ReleaseStringUTFChars(containerRoot, c_container);
    env->ReleaseStringUTFChars(packageName, c_package);
    env->ReleaseStringUTFChars(internalDataDir, c_internal);

    lspd::storage::InstallStoragePltHooks();
}

JNIEXPORT void JNICALL
Java_org_lsposed_lspatch_loader_StorageRedirector_nativeAddNativeLibDir(
        JNIEnv* env, jclass /*clazz*/, jstring libDir) {
    if (libDir == nullptr) return;
    const char* c_dir = env->GetStringUTFChars(libDir, nullptr);
    lspd::storage::PathTranslator::Instance().AddNativeLibDir(c_dir);
    env->ReleaseStringUTFChars(libDir, c_dir);
}

JNIEXPORT void JNICALL
Java_org_lsposed_lspatch_loader_StorageRedirector_nativeAddCustomRule(
        JNIEnv* env, jclass /*clazz*/, jstring virt, jstring real, jint flags) {
    if (virt == nullptr || real == nullptr) return;
    lspd::storage::HookGuard guard;
    const char* c_virt = env->GetStringUTFChars(virt, nullptr);
    const char* c_real = env->GetStringUTFChars(real, nullptr);

    lspd::storage::CustomRule rule{
        .virtual_prefix = c_virt,
        .real_prefix = c_real,
        .flags = static_cast<uint32_t>(flags)
    };
    lspd::storage::PathTranslator::Instance().AddCustomRule(rule);

    std::string real_str = c_real;
    if (!real_str.empty() && real_str[0] == '/') {
        lspd::storage::EnsureParentDirs((real_str + "/probe").c_str());
    }

    env->ReleaseStringUTFChars(virt, c_virt);
    env->ReleaseStringUTFChars(real, c_real);
}

JNIEXPORT jstring JNICALL
Java_org_lsposed_lspatch_loader_StorageRedirector_nativeToRealPath(
        JNIEnv* env, jclass /*clazz*/, jstring path) {
    if (path == nullptr) return nullptr;
    const char* c_path = env->GetStringUTFChars(path, nullptr);
    std::string out;
    lspd::storage::PathTranslator::Instance().ToRealPath(c_path, out);
    env->ReleaseStringUTFChars(path, c_path);
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jstring JNICALL
Java_org_lsposed_lspatch_loader_StorageRedirector_nativeToVirtualPath(
        JNIEnv* env, jclass /*clazz*/, jstring path) {
    if (path == nullptr) return nullptr;
    const char* c_path = env->GetStringUTFChars(path, nullptr);
    std::string out;
    lspd::storage::PathTranslator::Instance().ToVirtualPath(c_path, out);
    env->ReleaseStringUTFChars(path, c_path);
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL
Java_org_lsposed_lspatch_loader_StorageRedirector_nativeRescanNativeLibs(
        JNIEnv* /*env*/, jclass /*clazz*/) {
    lspd::storage::HookAppNativeLibraries();
}

JNIEXPORT void JNICALL
Java_org_lsposed_lspatch_loader_StorageRedirector_nativeInitAntiDetect(
        JNIEnv* env, jclass /*clazz*/,
        jboolean hideRootFiles, jboolean maskProcMaps, jstring bootIdFilePath,
        jobjectArray propKeys, jobjectArray propVals) {
    lspd::storage::g_hide_root_files = hideRootFiles;
    lspd::storage::g_mask_proc_maps = maskProcMaps;
    if (bootIdFilePath != nullptr) {
        const char* c_boot = env->GetStringUTFChars(bootIdFilePath, nullptr);
        lspd::storage::g_spoof_boot_id_path = c_boot ? c_boot : "";
        env->ReleaseStringUTFChars(bootIdFilePath, c_boot);
    } else {
        lspd::storage::g_spoof_boot_id_path.clear();
    }

    if (propKeys != nullptr && propVals != nullptr) {
        jsize len = env->GetArrayLength(propKeys);
        jsize vlen = env->GetArrayLength(propVals);
        if (len == vlen) {
            std::lock_guard<std::mutex> lock(lspd::storage::g_prop_mutex);
            lspd::storage::g_spoof_props.clear();
            for (jsize i = 0; i < len; i++) {
                auto jkey = static_cast<jstring>(env->GetObjectArrayElement(propKeys, i));
                auto jval = static_cast<jstring>(env->GetObjectArrayElement(propVals, i));
                if (jkey != nullptr && jval != nullptr) {
                    const char* ck = env->GetStringUTFChars(jkey, nullptr);
                    const char* cv = env->GetStringUTFChars(jval, nullptr);
                    if (ck != nullptr && cv != nullptr) {
                        lspd::storage::g_spoof_props[ck] = cv;
                    }
                    if (ck) env->ReleaseStringUTFChars(jkey, ck);
                    if (cv) env->ReleaseStringUTFChars(jval, cv);
                }
                if (jkey) env->DeleteLocalRef(jkey);
                if (jval) env->DeleteLocalRef(jval);
            }
        }
    }

    lspd::storage::InstallStoragePltHooks();
}

} // extern "C"
