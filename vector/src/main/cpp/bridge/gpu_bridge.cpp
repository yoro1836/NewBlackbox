// SPDX-License-Identifier: GPL-3.0-only
//
// adrenotools-style custom Vulkan driver loader for BlackBox virtual apps.
//
// Android's libvulkan.so (the Google loader) only ever loads the GPU Vulkan HAL
// through android_load_sphal_library("vulkan.<ro.hardware.vulkan>.so"), and that
// load happens inside an isolated linker namespace that refuses .so files living
// in app storage. To make a user-supplied driver (Mesa Turnip, PanVK, ...) load
// instead, we:
//   1. Build a dedicated linker namespace whose search/permitted path contains
//      the imported driver directory, linked to the platform namespace so the
//      driver's public-library dependencies (libc/liblog/libz/libsync/...)
//      resolve.
//   2. android_dlopen_ext() the driver into that namespace.
//   3. Inline-hook android_load_sphal_library so libvulkan's HAL load returns the
//      custom driver handle.
//
// This mirrors the mechanism libadrenotools uses for emulators, adapted to drive
// unmodified apps running inside the virtual space. Every failure path is
// non-fatal: the app simply keeps using the system driver.
#include <jni.h>
#include <android/dlext.h>
#include <android/log.h>
#include <dlfcn.h>

#include <cstdint>
#include <cstring>
#include <string>

#include "dobby.h"

#define TAG "BBGpu"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// android/dlext.h forward-declares struct android_namespace_t at global scope and
// types android_dlextinfo::library_namespace as a pointer to it; we reuse that
// opaque type rather than redeclaring it.
using create_ns_fn = android_namespace_t *(*)(const char *name,
                                              const char *ld_library_path,
                                              const char *default_library_path,
                                              uint64_t type,
                                              const char *permitted_when_isolated_path,
                                              android_namespace_t *parent,
                                              const void *caller_addr);
using link_ns_fn = bool (*)(android_namespace_t *from, android_namespace_t *to,
                            const char *shared_libs_sonames);
using get_exported_ns_fn = android_namespace_t *(*)(const char *name);
using sphal_load_fn = void *(*)(const char *name, int flag);

// Namespace type flags the private linker defines but the NDK header omits.
constexpr uint64_t kNsTypeIsolated = 1;  // ANDROID_NAMESPACE_TYPE_ISOLATED
constexpr uint64_t kNsTypeShared = 2;    // ANDROID_NAMESPACE_TYPE_SHARED

create_ns_fn gCreateNamespace = nullptr;
link_ns_fn gLinkNamespaces = nullptr;
get_exported_ns_fn gGetExportedNamespace = nullptr;

sphal_load_fn gOrigSphalLoad = nullptr;
void *gCustomDriver = nullptr;
bool gHookInstalled = false;

// Public platform libraries a Mesa Vulkan driver links against. Listing them
// keeps the isolated driver namespace able to pull them from the platform
// namespace instead of failing to resolve.
constexpr const char *kSharedLibs =
        "libc.so:libm.so:libdl.so:liblog.so:libz.so:libsync.so:"
        "libnativewindow.so:libandroid.so:libvndksupport.so:libhardware.so:"
        "libcutils.so:libbase.so:libdmabufheap.so:libgpudataproducer.so";

void *new_sphal_load(const char *name, int flag) {
    if (gCustomDriver != nullptr && name != nullptr && strstr(name, "vulkan") != nullptr) {
        LOGI("Redirecting sphal load of '%s' to the custom Vulkan driver", name);
        return gCustomDriver;
    }
    if (gOrigSphalLoad != nullptr) {
        return gOrigSphalLoad(name, flag);
    }
    return android_dlopen_ext(name, flag, nullptr);
}

bool resolveLinkerApis() {
    if (gCreateNamespace != nullptr) {
        return true;
    }
    // The loader entry points live in ld-android.so (API 28+). libdl_android.so
    // re-exports them on some builds, so try it as a fallback.
    void *ld = dlopen("ld-android.so", RTLD_NOW | RTLD_GLOBAL);
    if (ld == nullptr) {
        ld = dlopen("libdl_android.so", RTLD_NOW | RTLD_GLOBAL);
    }
    if (ld == nullptr) {
        LOGE("Cannot open the linker exports: %s", dlerror());
        return false;
    }
    gCreateNamespace =
            reinterpret_cast<create_ns_fn>(dlsym(ld, "__loader_android_create_namespace"));
    gLinkNamespaces =
            reinterpret_cast<link_ns_fn>(dlsym(ld, "__loader_android_link_namespaces"));
    gGetExportedNamespace =
            reinterpret_cast<get_exported_ns_fn>(dlsym(ld, "__loader_android_get_exported_namespace"));
    return gCreateNamespace != nullptr;
}

sphal_load_fn resolveSphalLoad() {
    void *vndk = dlopen("libvndksupport.so", RTLD_NOW);
    if (vndk == nullptr) {
        return nullptr;
    }
    return reinterpret_cast<sphal_load_fn>(dlsym(vndk, "android_load_sphal_library"));
}

android_namespace_t *createDriverNamespace(const char *driverDir) {
    if (!resolveLinkerApis()) {
        return nullptr;
    }
    // Permit the driver directory plus the system/vendor GPU library locations so
    // vendor-side dependencies (e.g. libdrm, kernel UAPI helpers) can be reached.
    std::string permitted = std::string(driverDir) +
            ":/vendor/lib64:/vendor/lib64/hw:/vendor/lib:/vendor/lib/hw"
            ":/system/lib64:/system/lib:/data";
    android_namespace_t *ns = gCreateNamespace(
            "bb_gpu_driver",
            driverDir,  // ld_library_path
            driverDir,  // default_library_path
            kNsTypeShared | kNsTypeIsolated,
            permitted.c_str(),
            nullptr,  // parent namespace inferred from caller_addr below
            reinterpret_cast<const void *>(&createDriverNamespace));
    if (ns == nullptr) {
        LOGW("create_namespace failed; driver dependencies may not resolve");
        return nullptr;
    }
    // Link to the platform namespace so the driver shares the public libs above.
    if (gLinkNamespaces != nullptr && gGetExportedNamespace != nullptr) {
        android_namespace_t *platform = gGetExportedNamespace("default");
        if (platform == nullptr) {
            platform = gGetExportedNamespace("system");
        }
        if (platform != nullptr) {
            gLinkNamespaces(ns, platform, kSharedLibs);
        }
    }
    return ns;
}

bool installSphalHook() {
    if (gHookInstalled) {
        return true;
    }
    sphal_load_fn target = resolveSphalLoad();
    if (target == nullptr) {
        LOGE("android_load_sphal_library not found; cannot intercept HAL load");
        return false;
    }
    void *backup = nullptr;
    if (DobbyHook(reinterpret_cast<void *>(target),
                  reinterpret_cast<dobby_dummy_func_t>(new_sphal_load),
                  reinterpret_cast<dobby_dummy_func_t *>(&backup)) != 0) {
        LOGE("Failed to hook android_load_sphal_library");
        return false;
    }
    gOrigSphalLoad = reinterpret_cast<sphal_load_fn>(backup);
    gHookInstalled = true;
    return true;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_org_blackbox_vector_nativebridge_GpuBridge_nativeLoadDriver(
        JNIEnv *env, jclass, jstring jDriverDir, jstring jSoName, jint apiLevel) {
    // Custom driver loading relies on the stable linker-namespace APIs from
    // Android 9 (API 28); on older releases the feature stays dormant.
    if (apiLevel < 28) {
        LOGW("Custom Vulkan driver needs Android 9+ (api>=28); api=%d", apiLevel);
        return JNI_FALSE;
    }
    if (gCustomDriver != nullptr) {
        // A driver is already active in this process; one ICD per process.
        return JNI_TRUE;
    }
    const char *driverDir = env->GetStringUTFChars(jDriverDir, nullptr);
    const char *soName = env->GetStringUTFChars(jSoName, nullptr);
    jboolean ok = JNI_FALSE;
    if (driverDir != nullptr && soName != nullptr) {
        std::string full = std::string(driverDir) + "/" + soName;
        void *handle = nullptr;
        android_namespace_t *ns = createDriverNamespace(driverDir);
        if (ns != nullptr) {
            android_dlextinfo info{};
            info.flags = ANDROID_DLEXT_USE_NAMESPACE;
            info.library_namespace = ns;
            handle = android_dlopen_ext(full.c_str(), RTLD_NOW | RTLD_LOCAL, &info);
        }
        if (handle == nullptr) {
            // Fall back to a plain load for permissive devices whose app
            // namespace already allows the driver directory.
            handle = android_dlopen_ext(full.c_str(), RTLD_NOW | RTLD_LOCAL, nullptr);
        }
        if (handle == nullptr) {
            LOGE("Failed to load custom driver %s: %s", full.c_str(), dlerror());
        } else {
            gCustomDriver = handle;
            ok = installSphalHook() ? JNI_TRUE : JNI_FALSE;
            if (ok) {
                LOGI("Custom Vulkan driver '%s' loaded and HAL load hooked", soName);
            } else {
                // Could not intercept the loader; drop the handle so we do not
                // falsely report an active driver.
                gCustomDriver = nullptr;
            }
        }
    }
    if (driverDir != nullptr) {
        env->ReleaseStringUTFChars(jDriverDir, driverDir);
    }
    if (soName != nullptr) {
        env->ReleaseStringUTFChars(jSoName, soName);
    }
    return ok;
}
