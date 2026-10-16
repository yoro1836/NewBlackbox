// Loads a user-supplied Vulkan driver (Mesa Turnip, PanVK, ...) for the current
// virtual app, the same way Winlator's Vulkan wrapper does: libadrenotools opens a
// private copy of the system libvulkan.so whose HAL load is redirected to the
// custom driver.
//
// Unlike an emulator, the app here was not written against that private copy, so
// we also hand it to the app: ByteHook patches libdl.so's calls into the linker
// (__loader_dlopen / __loader_android_dlopen_ext, which carry the original caller
// address, so no other load changes namespace) and answers requests for
// "libvulkan.so" with the adrenotools handle. Apps that link libvulkan.so at
// install time instead of dlopen-ing it are covered by the GraphicsEnv path on
// the Java side.
#include <jni.h>
#include <android/api-level.h>
#include <android/log.h>
#include <dlfcn.h>

#include <cstring>
#include <string>

#if BB_HAS_ADRENOTOOLS
#include <adrenotools/driver.h>
#include <bytehook.h>
#endif

#define TAG "BBGpu"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

#if BB_HAS_ADRENOTOOLS

// libvulkan.so instance whose driver is the custom one; never closed.
void *gVulkan = nullptr;

bool isLibvulkan(const char *filename) {
    if (filename == nullptr) {
        return false;
    }
    const char *base = strrchr(filename, '/');
    base = base != nullptr ? base + 1 : filename;
    return strcmp(base, "libvulkan.so") == 0;
}

void *proxyLoaderDlopen(const char *filename, int flags, const void *caller) {
    BYTEHOOK_STACK_SCOPE();
    if (gVulkan != nullptr && isLibvulkan(filename)) {
        LOGI("dlopen(%s) answered with the custom-driver libvulkan", filename);
        return gVulkan;
    }
    return BYTEHOOK_CALL_PREV(proxyLoaderDlopen, filename, flags, caller);
}

void *proxyLoaderDlopenExt(const char *filename, int flags, const void *extinfo,
                           const void *caller) {
    BYTEHOOK_STACK_SCOPE();
    if (gVulkan != nullptr && isLibvulkan(filename)) {
        LOGI("android_dlopen_ext(%s) answered with the custom-driver libvulkan", filename);
        return gVulkan;
    }
    return BYTEHOOK_CALL_PREV(proxyLoaderDlopenExt, filename, flags, extinfo, caller);
}

// Engines often probe Vulkan with dlopen + dlclose before using it for real; the
// handle we hand out is shared, so closing it would unload the driver under them.
int proxyLoaderDlclose(void *handle) {
    BYTEHOOK_STACK_SCOPE();
    if (handle != nullptr && handle == gVulkan) {
        return 0;
    }
    return BYTEHOOK_CALL_PREV(proxyLoaderDlclose, handle);
}

void onHooked(bytehook_stub_t, int status, const char *caller, const char *sym, void *, void *,
              void *) {
    if (status == BYTEHOOK_STATUS_CODE_OK) {
        LOGI("hooked %s in %s", sym, caller);
    } else {
        LOGE("hooking %s in %s failed: %d", sym, caller, status);
    }
}

bool installDlopenHooks() {
    int status = bytehook_init(BYTEHOOK_MODE_AUTOMATIC, false);
    if (status != BYTEHOOK_STATUS_CODE_OK) {
        LOGE("bytehook_init failed: %d", status);
        return false;
    }
    bool ok = bytehook_hook_single("libdl.so", nullptr, "__loader_dlopen",
                                   reinterpret_cast<void *>(proxyLoaderDlopen), onHooked,
                                   nullptr) != nullptr;
    ok = bytehook_hook_single("libdl.so", nullptr, "__loader_android_dlopen_ext",
                              reinterpret_cast<void *>(proxyLoaderDlopenExt), onHooked,
                              nullptr) != nullptr && ok;
    ok = bytehook_hook_single("libdl.so", nullptr, "__loader_dlclose",
                              reinterpret_cast<void *>(proxyLoaderDlclose), onHooked,
                              nullptr) != nullptr && ok;
    return ok;
}

std::string withSlash(const char *dir) {
    std::string out(dir);
    if (out.empty() || out.back() != '/') {
        out.push_back('/');
    }
    return out;
}

#endif  // BB_HAS_ADRENOTOOLS

std::string toString(JNIEnv *env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string out = chars != nullptr ? chars : "";
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(value, chars);
    }
    return out;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_org_blackbox_gpudriver_GpuBridge_nativeInstall(JNIEnv *env, jclass, jstring jHookDir,
                                                    jstring jDriverDir, jstring jDriverName) {
#if BB_HAS_ADRENOTOOLS
    if (gVulkan != nullptr) {
        return JNI_TRUE;
    }
    if (android_get_device_api_level() < 28) {
        LOGE("adrenotools needs Android 9+");
        return JNI_FALSE;
    }
    std::string hookDir = withSlash(toString(env, jHookDir).c_str());
    std::string driverDir = withSlash(toString(env, jDriverDir).c_str());
    std::string driverName = toString(env, jDriverName);
    LOGI("adrenotools: hooks=%s driver=%s%s", hookDir.c_str(), driverDir.c_str(),
         driverName.c_str());

    void *vulkan = adrenotools_open_libvulkan(RTLD_NOW, ADRENOTOOLS_DRIVER_CUSTOM, nullptr,
                                              hookDir.c_str(), driverDir.c_str(),
                                              driverName.c_str(), nullptr, nullptr);
    if (vulkan == nullptr) {
        LOGE("adrenotools_open_libvulkan failed: %s", dlerror());
        return JNI_FALSE;
    }
    gVulkan = vulkan;
    if (!installDlopenHooks()) {
        LOGE("dlopen hooks incomplete; apps that dlopen libvulkan.so may keep the system driver");
        return JNI_FALSE;
    }
    LOGI("custom driver ready; libvulkan.so requests now resolve to it");
    return JNI_TRUE;
#else
    (void) env;
    (void) jHookDir;
    (void) jDriverDir;
    (void) jDriverName;
    LOGE("adrenotools is not available on this ABI");
    return JNI_FALSE;
#endif
}
