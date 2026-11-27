#!/bin/sh
# Runs the LSFG engine on lavapipe (apt: mesa-vulkan-drivers) with a synthetic
# forward-driving scene and prints the generated frame's error against the true
# middle frame: centre, middle band (5-15% from the edge), outer 5%.
# usage: run.sh <lsfg-shaders.pack> [flow scale] [zoom per frame] [ppm out dir]
# The pack comes from your own Lossless.dll (the app's import, or LsfgPack.kt).
# PARALLAX=1 adds near roadside posts.
set -e
here=$(cd "$(dirname "$0")" && pwd)
cpp=$here/../../src/main/cpp
ndk=${ANDROID_NDK:-/opt/android-sdk/ndk/29.0.13846066}
out=${TMPDIR:-/tmp}/lsfg-host
mkdir -p "$out/gen" "$out/inc"
cp -r "$ndk/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include/vulkan" \
  "$ndk/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include/vk_video" "$out/inc/"
"$ndk/shader-tools/linux-x86_64/glslc" -O -fshader-stage=compute -mfmt=c \
  "$cpp/lsfg/shaders/generate.comp" -o "$out/gen/generate.spv.inc"
clang++ -O2 -std=c++20 -I"$cpp" -I"$out/gen" -I"$out/inc" "$here/harness.cc" "$cpp/lsfg/lsfg.cc" \
  -o "$out/harness" -l:libvulkan.so.1
VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/lvp_icd.json "$out/harness" "$@"
