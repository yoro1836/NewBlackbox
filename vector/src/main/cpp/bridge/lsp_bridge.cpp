// SPDX-License-Identifier: GPL-3.0-only
//
// JNI bridge between BlackBox and the LSPlant ART hooking engine (as used by Vector/LSPosed).
// Derived from Vector's native layer (https://github.com/JingMatrix/Vector, GPL-3.0).
#include <jni.h>
#include <android/log.h>

#include <string_view>

#include "dobby.h"
#include "elf/elf_image.h"
#include "elf/symbol_cache.h"
#include "lsplant.hpp"

#define TAG "BBVector"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

using vector::native::ElfSymbolCache;

namespace {

void *InlineHook(void *target, void *hooker) {
    void *backup = nullptr;
    return DobbyHook(target, reinterpret_cast<dobby_dummy_func_t>(hooker),
                     reinterpret_cast<dobby_dummy_func_t *>(&backup)) == 0
               ? backup
               : nullptr;
}

bool InlineUnhook(void *func) { return DobbyDestroy(func) == 0; }

void *ResolveArtSymbol(std::string_view symbol) {
    const auto *art = ElfSymbolCache::GetArt();
    return art != nullptr ? art->getSymbAddress(symbol) : nullptr;
}

void *ResolveArtSymbolPrefix(std::string_view prefix) {
    const auto *art = ElfSymbolCache::GetArt();
    return art != nullptr ? art->getSymbPrefixFirstAddress(prefix) : nullptr;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_org_blackbox_vector_nativebridge_LspBridge_nativeInit(JNIEnv *env, jclass) {
    const auto *art = ElfSymbolCache::GetArt();
    if (art == nullptr || !art->IsValid()) {
        LOGE("libart.so could not be located; ART hooking unavailable");
        return JNI_FALSE;
    }
    lsplant::InitInfo info{
        .inline_hooker = InlineHook,
        .inline_unhooker = InlineUnhook,
        .art_symbol_resolver = ResolveArtSymbol,
        .art_symbol_prefix_resolver = ResolveArtSymbolPrefix,
    };
    const bool ok = lsplant::Init(env, info);
    if (!ok) {
        LOGE("lsplant::Init failed");
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobject JNICALL Java_org_blackbox_vector_nativebridge_LspBridge_nativeHook(
    JNIEnv *env, jclass, jobject target, jobject hooker, jobject callback) {
    return lsplant::Hook(env, target, hooker, callback);
}

JNIEXPORT jboolean JNICALL
Java_org_blackbox_vector_nativebridge_LspBridge_nativeUnhook(JNIEnv *env, jclass, jobject target) {
    return lsplant::UnHook(env, target) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_org_blackbox_vector_nativebridge_LspBridge_nativeIsHooked(
    JNIEnv *env, jclass, jobject target) {
    return lsplant::IsHooked(env, target) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_org_blackbox_vector_nativebridge_LspBridge_nativeDeoptimize(
    JNIEnv *env, jclass, jobject target) {
    return lsplant::Deoptimize(env, target) ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
