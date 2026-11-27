#!/bin/sh
# Generates the FidelityFX optical flow + frame interpolation shader headers
# (what the SDK's Windows-only FidelityFX_SC.exe makes) with glslc (from the NDK).
# Usage: gen-shaders.sh <FidelityFX-SDK dir> <out dir>
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
SDK=$(cd "${1:?sdk dir}" && pwd)
OUT=${2:?out dir}
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)
GPU=$SDK/sdk/include/FidelityFX/gpu
VK=$SDK/sdk/src/backends/vk/shaders
SR=$SDK/sdk/tools/ffx_shader_compiler/libs/SPIRV-Reflect
REFLECT=$OUT/.reflect
[ -x "$REFLECT" ] || ${CC:-cc} -O1 -I"$SR" -o "$REFLECT" "$HERE/reflect.c" "$SR/spirv_reflect.c"

# One pass in its four variants (the GLSL path only differs by FFX_HALF).
pass() {  # <effect> <file stem> <args...>
  effect=$1 stem=$2
  shift 2
  for v in "" _wave64; do
    python3 "$HERE/ffx_sc.py" --glslc "${GLSLC:-glslc}" --reflect "$REFLECT" --out "$OUT" --name "$stem$v" -DFFX_GPU=1 -DFFX_HALF=0 "$@" \
      -I"$GPU" -I"$GPU/$effect" "$VK/$effect/$stem.glsl"
    python3 "$HERE/ffx_sc.py" --glslc "${GLSLC:-glslc}" --reflect "$REFLECT" --out "$OUT" --name "${stem}${v}_16bit" -DFFX_GPU=1 -DFFX_HALF=1 "$@" \
      -I"$GPU" -I"$GPU/$effect" "$VK/$effect/$stem.glsl"
  done
}

for f in "$VK"/opticalflow/*.glsl; do
  pass opticalflow "$(basename "$f" .glsl)" "-DFFX_OPTICALFLOW_OPTION_HDR_COLOR_INPUT={0,1}" &
done
wait
for f in "$VK"/frameinterpolation/*.glsl; do
  pass frameinterpolation "$(basename "$f" .glsl)" \
    -DFFX_FRAMEINTERPOLATION_OPTION_UPSAMPLE_SAMPLERS_USE_DATA_HALF=0 \
    -DFFX_FRAMEINTERPOLATION_OPTION_ACCUMULATE_SAMPLERS_USE_DATA_HALF=0 \
    -DFFX_FRAMEINTERPOLATION_OPTION_REPROJECT_SAMPLERS_USE_DATA_HALF=1 \
    -DFFX_FRAMEINTERPOLATION_OPTION_POSTPROCESSLOCKSTATUS_SAMPLERS_USE_DATA_HALF=0 \
    -DFFX_FRAMEINTERPOLATION_OPTION_UPSAMPLE_USE_LANCZOS_TYPE=2 \
    "-DFFX_FRAMEINTERPOLATION_OPTION_LOW_RES_MOTION_VECTORS={0,1}" \
    "-DFFX_FRAMEINTERPOLATION_OPTION_JITTER_MOTION_VECTORS={0,1}" \
    "-DFFX_FRAMEINTERPOLATION_OPTION_INVERTED_DEPTH={0,1}" &
done
wait
ls "$OUT"/*_permutations.h | wc -l
