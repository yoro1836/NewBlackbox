#include "FileSystemHook.h"
#include "Log.h"
#include "xdl.h"
#include "Dobby/dobby.h"
#include "../IO.h"
#include <cerrno>
#include <cstring>
#include <sys/stat.h>
#include <unistd.h>


static int (*orig_open)(const char *pathname, int flags, ...) = nullptr;
static int (*orig_open64)(const char *pathname, int flags, ...) = nullptr;
static int (*orig_openat)(int dirfd, const char *pathname, int flags, ...) = nullptr;
static int (*orig___open_2)(const char *pathname, int flags, ...) = nullptr;
static int (*orig___openat_2)(int dirfd, const char *pathname, int flags, ...) = nullptr;
static int (*orig_stat)(const char *pathname, struct stat *buf) = nullptr;
static int (*orig_lstat)(const char *pathname, struct stat *buf) = nullptr;
static int (*orig_fstatat)(int dirfd, const char *pathname, struct stat *buf, int flags) = nullptr;
static int (*orig_access)(const char *pathname, int mode) = nullptr;
static int (*orig_faccessat)(int dirfd, const char *pathname, int mode, int flags) = nullptr;
static int (*orig_mkdir)(const char *pathname, mode_t mode) = nullptr;

static bool isBlockedPath(const char *pathname) {
    if (pathname == nullptr) {
        return false;
    }
    return strstr(pathname, "resource-cache") ||
            strstr(pathname, "@idmap") ||
            strstr(pathname, ".frro") ||
            strstr(pathname, "systemui") ||
            strstr(pathname, "data@resource-cache@");
}
static const char *convergeWebViewProfile(const char *pathname);
static void ensureWebViewLockDir(const char *path);

static const char *redirect(const char *pathname) {
    if (pathname == nullptr || pathname[0] != '/') {
        return pathname;
    }
    const char *converged = convergeWebViewProfile(pathname);
    if (converged != pathname) {
        return converged;
    }
    const char *out = IO::redirectPath(pathname);
    if (out != pathname) {
        ALOGD("FileSystemHook: %s -> %s", pathname, out);
    }
    ensureWebViewLockDir(out);
    return out;
}

// Per-launch WebView profiles (app_webview_<pkg>.<pid>) abandon cookies and
// sessions every restart. Fold them into one stable dir per package, except
// Chromium lock files (sharing those would reintroduce profile-lock crashes).
static __thread char sConvergeBuf[4096];

static bool isWebViewLockFile(const char *path) {
    const char *base = strrchr(path, '/');
    base = (base != nullptr) ? base + 1 : path;
    if (strcmp(base, "SingletonSocket") == 0
            || strcmp(base, "SingletonCookie") == 0
            || strcmp(base, "SingletonLock") == 0
            || strcmp(base, "LOCK") == 0) {
        return true;
    }
    size_t len = strlen(base);
    if (len > 5 && strcmp(base + len - 5, ".lock") == 0) {
        return true;
    }
    return false;
}

// Lock files keep their per-pid directory (convergeWebViewProfile leaves them alone), but
// every other WebView path is folded into "_stable", so nothing ever created the per-pid
// directory. Chromium's AwDataDirLock then failed with ENOENT and the WebView constructor
// threw on the main thread, which left the game without a UI thread.
static void ensureWebViewLockDir(const char *path) {
    if (path == nullptr || orig_mkdir == nullptr
            || strstr(path, "/app_webview_") == nullptr || !isWebViewLockFile(path)) {
        return;
    }
    char dir[4096];
    size_t len = strlen(path);
    if (len >= sizeof(dir)) {
        return;
    }
    memcpy(dir, path, len + 1);
    char *slash = strrchr(dir, '/');
    if (slash == nullptr || slash == dir) {
        return;
    }
    *slash = '\0';
    orig_mkdir(dir, 0700);  // EEXIST is fine
}

static const char *convergeWebViewProfile(const char *pathname) {
    const char *anchor = strstr(pathname, "/app_webview_");
    if (anchor == nullptr || isWebViewLockFile(pathname)) {
        return pathname;
    }
    // Expect "/app_webview_<name>.<digits>[/...]"; fold digits to "_stable".
    const char *dot = nullptr;
    for (const char *p = anchor + 14; *p != '\0' && *p != '/'; ++p) {
        if (*p == '.') {
            dot = p;
        }
    }
    if (dot == nullptr) {
        return pathname;
    }
    const char *q = dot + 1;
    if (*q < '0' || *q > '9') {
        return pathname;
    }
    while (*q >= '0' && *q <= '9') {
        ++q;
    }
    if (*q != '/' && *q != '\0') {
        return pathname;
    }
    size_t headLen = (size_t) (dot - pathname);
    size_t tailLen = strlen(q);
    static const char kStable[] = "_stable";
    if (headLen + sizeof(kStable) - 1 + tailLen >= sizeof(sConvergeBuf)) {
        return pathname;
    }
    memcpy(sConvergeBuf, pathname, headLen);
    memcpy(sConvergeBuf + headLen, kStable, sizeof(kStable) - 1);
    memcpy(sConvergeBuf + headLen + sizeof(kStable) - 1, q, tailLen + 1);
    return sConvergeBuf;
}

int new_open(const char *pathname, int flags, ...) {
    if (isBlockedPath(pathname)) {
        ALOGD("FileSystemHook: Blocking problematic file access: %s", pathname);
        errno = ENOENT;
        return -1;
    }

    va_list args;
    va_start(args, flags);
    mode_t mode = va_arg(args, mode_t);
    va_end(args);

    return orig_open(redirect(pathname), flags, mode);
}

int new_open64(const char *pathname, int flags, ...) {
    if (isBlockedPath(pathname)) {
        ALOGD("FileSystemHook: Blocking problematic file access (64): %s", pathname);
        errno = ENOENT;
        return -1;
    }

    va_list args;
    va_start(args, flags);
    mode_t mode = va_arg(args, mode_t);
    va_end(args);

    return orig_open64(redirect(pathname), flags, mode);
}

int new_openat(int dirfd, const char *pathname, int flags, ...) {
    if (isBlockedPath(pathname)) {
        errno = ENOENT;
        return -1;
    }

    va_list args;
    va_start(args, flags);
    mode_t mode = va_arg(args, mode_t);
    va_end(args);

    return orig_openat(dirfd, redirect(pathname), flags, mode);
}

int new_stat(const char *pathname, struct stat *buf) {
    if (isBlockedPath(pathname)) {
        errno = ENOENT;
        return -1;
    }
    return orig_stat(redirect(pathname), buf);
}

int new___open_2(const char *pathname, int flags, ...) {
    if (isBlockedPath(pathname)) {
        errno = ENOENT;
        return -1;
    }

    va_list args;
    va_start(args, flags);
    mode_t mode = va_arg(args, mode_t);
    va_end(args);

    return orig___open_2(redirect(pathname), flags, mode);
}

int new___openat_2(int dirfd, const char *pathname, int flags, ...) {
    if (isBlockedPath(pathname)) {
        errno = ENOENT;
        return -1;
    }

    va_list args;
    va_start(args, flags);
    mode_t mode = va_arg(args, mode_t);
    va_end(args);

    return orig___openat_2(dirfd, redirect(pathname), flags, mode);
}

int new_lstat(const char *pathname, struct stat *buf) {
    if (isBlockedPath(pathname)) {
        errno = ENOENT;
        return -1;
    }
    return orig_lstat(redirect(pathname), buf);
}

int new_fstatat(int dirfd, const char *pathname, struct stat *buf, int flags) {
    if (isBlockedPath(pathname)) {
        errno = ENOENT;
        return -1;
    }
    return orig_fstatat(dirfd, redirect(pathname), buf, flags);
}

int new_access(const char *pathname, int mode) {
    if (isBlockedPath(pathname)) {
        errno = ENOENT;
        return -1;
    }
    return orig_access(redirect(pathname), mode);
}

int new_faccessat(int dirfd, const char *pathname, int mode, int flags) {
    if (isBlockedPath(pathname)) {
        errno = ENOENT;
        return -1;
    }
    return orig_faccessat(dirfd, redirect(pathname), mode, flags);
}

int new_mkdir(const char *pathname, mode_t mode) {
    if (isBlockedPath(pathname)) {
        errno = ENOENT;
        return -1;
    }
    return orig_mkdir(redirect(pathname), mode);
}

#define HOOK_SYM(handle, name, orig, replacement) \
    do { \
        orig = (decltype(orig))xdl_sym(handle, name, nullptr); \
        if (orig) { \
            void *target = xdl_dsym(handle, name, nullptr); \
            if (target && DobbyHook(target, (void *)replacement, (void **)&orig) == 0) { \
                ALOGD("FileSystemHook: hooked %s", name); \
            } else { \
                ALOGE("FileSystemHook: failed to hook %s", name); \
            } \
        } else { \
            ALOGE("FileSystemHook: symbol %s not found", name); \
        } \
    } while (0)

void FileSystemHook::init() {
    ALOGD("FileSystemHook: Initializing file system hooks");

    void* handle = xdl_open("libc.so", XDL_DEFAULT);
    if (!handle) {
        ALOGE("FileSystemHook: Failed to open libc.so");
        return;
    }

    HOOK_SYM(handle, "open", orig_open, new_open);
    // LP64: open64 is an alias of open (same address). Hooking it again
    // chains the hook into itself = infinite recursion. Share instead.
    void *openAddr = xdl_dsym(handle, "open", nullptr);
    void *open64Addr = xdl_dsym(handle, "open64", nullptr);
    if (open64Addr != nullptr && open64Addr != openAddr) {
        HOOK_SYM(handle, "open64", orig_open64, new_open64);
    } else {
        orig_open64 = orig_open;
        ALOGD("FileSystemHook: open64 aliases open, sharing hook");
    }
    HOOK_SYM(handle, "openat", orig_openat, new_openat);
    HOOK_SYM(handle, "__open_2", orig___open_2, new___open_2);
    HOOK_SYM(handle, "__openat_2", orig___openat_2, new___openat_2);
    HOOK_SYM(handle, "stat", orig_stat, new_stat);
    HOOK_SYM(handle, "lstat", orig_lstat, new_lstat);
    HOOK_SYM(handle, "fstatat", orig_fstatat, new_fstatat);
    HOOK_SYM(handle, "access", orig_access, new_access);
    HOOK_SYM(handle, "faccessat", orig_faccessat, new_faccessat);
    HOOK_SYM(handle, "mkdir", orig_mkdir, new_mkdir);

    xdl_close(handle);
}
