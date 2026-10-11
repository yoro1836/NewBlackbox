#include <sys/system_properties.h>
#include <cstring>
#include <string>
#include <map>
#include <pthread.h>
#include <jni.h>
#include "./xdl.h"
#include <android/log.h>
#include <dlfcn.h>
#include "Dobby/dobby.h"


#define LOG_TAG "VirtualSpoof"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

#if defined(__x86_64__)
#define BLACKBOX_CPU_ABI "x86_64"
#elif defined(__i386__)
#define BLACKBOX_CPU_ABI "x86"
#elif defined(__aarch64__) || defined(__arm64__)
#define BLACKBOX_CPU_ABI "arm64-v8a"
#else
#define BLACKBOX_CPU_ABI "armeabi-v7a"
#endif

struct SpoofedProp {
    const char* key;
    const char* value;
};

SpoofedProp spoofed_props[] = {
        {"ro.product.model", "Pixel 6"},
        {"ro.product.brand", "google"},
        {"ro.product.manufacturer", "Google"},
        {"ro.product.device", "oriole"},
        {"ro.build.fingerprint", "google/oriole/oriole:12/SP1A.210812.015/7679548:user/release-keys"},
        {"ro.build.version.release", "12"},
        {"ro.build.version.security_patch", "2022-01-05"},
        {"ro.serialno", "1A2B3C4D5E6F"},
        {"ro.hardware", "qcom"},
        {"ro.boot.hardware", "qcom"},
        {"ro.product.board", "lahaina"},
        {"ro.product.cpu.abi", BLACKBOX_CPU_ABI},
        {"ro.build.type", "user"},
        {"ro.build.tags", "release-keys"},
        {"ro.kernel.qemu", "0"},
        {"ro.kernel.android.qemud", ""},
        {"ro.hardware.egl", "adreno"},
        {"ro.boot.qemu", "0"},
    {nullptr, nullptr} 
};


static int (*orig_system_property_get)(const char *name, char *value) = nullptr;

// User-configured overrides (empty = use compiled defaults below).
static std::map<std::string, std::string> gSpoofOverride;
static pthread_mutex_t gSpoofLock = PTHREAD_MUTEX_INITIALIZER;

void setSpoofProp(JNIEnv *env, jclass, jstring key, jstring value) {
    if (key == nullptr || value == nullptr) {
        return;
    }
    const char *k = env->GetStringUTFChars(key, nullptr);
    const char *v = env->GetStringUTFChars(value, nullptr);
    if (k != nullptr && v != nullptr) {
        pthread_mutex_lock(&gSpoofLock);
        gSpoofOverride[k] = v;
        pthread_mutex_unlock(&gSpoofLock);
    }
    if (k != nullptr) {
        env->ReleaseStringUTFChars(key, k);
    }
    if (v != nullptr) {
        env->ReleaseStringUTFChars(value, v);
    }
}

extern "C" void Java_top_niunaijun_blackbox_core_NativeCore_clearSpoofProps(
        JNIEnv *, jclass) {
    pthread_mutex_lock(&gSpoofLock);
    gSpoofOverride.clear();
    pthread_mutex_unlock(&gSpoofLock);
}

int my_system_property_get(const char *name, char *value) {
    if (name != nullptr) {
        pthread_mutex_lock(&gSpoofLock);
        std::map<std::string, std::string>::iterator it = gSpoofOverride.find(name);
        if (it != gSpoofOverride.end()) {
            const std::string &custom = it->second;
            size_t len = custom.size();
            if (len > PROP_VALUE_MAX - 1) {
                len = PROP_VALUE_MAX - 1;
            }
            memcpy(value, custom.c_str(), len);
            value[len] = '\0';
            pthread_mutex_unlock(&gSpoofLock);
            return (int) len;
        }
        pthread_mutex_unlock(&gSpoofLock);
    }
    if (orig_system_property_get) {
        return orig_system_property_get(name, value);
    }
    value[0] = '\0';
    return 0;
}

static void (*orig_property_read)(const void *pi, char *name, char *value) = nullptr;
static void (*orig_read_callback)(const void *pi,
        void (*callback)(void *cookie, const char *name, const char *value, unsigned int serial),
        void *cookie) = nullptr;

static void my_read_callback(const void *pi,
        void (*callback)(void *cookie, const char *name, const char *value, unsigned int serial),
        void *cookie) {
    if (pi != nullptr && orig_property_read != nullptr && callback != nullptr) {
        char name[PROP_NAME_MAX];
        char value[PROP_VALUE_MAX];
        orig_property_read(pi, name, value);
        pthread_mutex_lock(&gSpoofLock);
        std::map<std::string, std::string>::iterator it = gSpoofOverride.find(name);
        if (it != gSpoofOverride.end()) {
            const std::string &custom = it->second;
            pthread_mutex_unlock(&gSpoofLock);
            callback(cookie, name, custom.c_str(), 0);
            return;
        }
        pthread_mutex_unlock(&gSpoofLock);
    }
    if (orig_read_callback != nullptr) {
        orig_read_callback(pi, callback, cookie);
    }
}

void install_property_get_hook() {
    void* handle = xdl_open("libc.so", XDL_DEFAULT);
    void* target = xdl_dsym(handle, "__system_property_get", nullptr);
    if (target) {
        if (DobbyHook(target, (void*)my_system_property_get, (void**)&orig_system_property_get) == 0) {
            LOGD("Spoof installed successfully");
        } else {
            LOGD("Spoof hook failed");
        }
        xdl_close(handle);
    } else{
        xdl_close(handle);
    }
    // SystemProperties.get() (the Java API most apps use) goes through
    // __system_property_read_callback, not __system_property_get.
    void* handle2 = xdl_open("libc.so", XDL_DEFAULT);
    if (handle2) {
        orig_property_read = (void (*)(const void *, char *, char *))
                xdl_sym(handle2, "__system_property_read", nullptr);
        void* cbTarget = xdl_dsym(handle2, "__system_property_read_callback", nullptr);
        if (cbTarget && orig_property_read) {
            if (DobbyHook(cbTarget, (void*)my_read_callback, (void**)&orig_read_callback) == 0) {
                LOGD("Spoof read_callback installed successfully");
            } else {
                LOGD("Spoof read_callback hook failed");
            }
        }
        xdl_close(handle2);
    }

}


__attribute__((constructor)) void init_virtual_spoof()
{
    install_property_get_hook();
    LOGD("VirtualSpoof: __system_property_get hook loaded");
}
