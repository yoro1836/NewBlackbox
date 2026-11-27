# framegen: frame generation for virtual Vulkan games (libzfg_vk.so)

NewBlackbox hands this library to a virtual app in place of `libvulkan.so`
(VulkanDriverManager.WrapperProvider -> GpuBridge.installWrapper). It forwards every
call to the real loader (or the custom driver picked in the GPU driver manager) and
inserts generated frames at present time, inside the game process, without root.

Built from source by the `:framegen` Gradle module (arm64-v8a only), which the app
depends on. Needs NDK 29 (glslc from its shader-tools), python3 and a host C compiler
for the FidelityFX shader permutations. Submodule: `git submodule update --init`.

## Engines

- **FSR 3.1** (`engine=fsr`): AMD FidelityFX optical flow + frame interpolation,
  2x. Taken out of the app UI for now (code kept). `ffx/`, `third_party/fidelityfx`.
- **LSFG** (`engine=lsfg`): runs the frame generation shaders of Lossless Scaling,
  2x .. 4x. The shaders are not in this repo: the app extracts them from the
  user's own `Lossless.dll` (Settings -> Frame generation -> LSFG -> Import) into
  `files/framegen/lsfg-shaders.pack` (app `LsfgPack.kt`). `lsfg/` is our own code;
  its pass graph follows the public description in lsfg-vk / Eden, nothing is
  copied from them. `lsfg/shaders/edge.comp` is our own pass after generate that
  blends the screen border toward the real frame closest in time (`edge=`).

## Layout

- `src/main/cpp/vkwrap/`: the wrapper (hooks, swapchain copies, pacing, both engines).
- `src/main/cpp/lsfg/`: LSFG engine.
- `src/main/cpp/ffx/`, `third_party/fidelityfx/`, `scripts/ffx-sc/`: FidelityFX SDK
  subset and the glslc-based shader compiler for it.
- `third_party/ZeroFG`: submodule (yoro1836/ZeroFG); the wrapper links its library.

## Config

Per app, written by the app UI to `files/framegen/<pkg>.txt`:
`fg=0|1;target=<fps>;scale=<percent>;engine=lsfg;mult=2..4;lowlat=0|1;edge=<percent>`; the app
adds `log=<file>` and `lsfg=<pack>`.

## Licences

The wrapper and the LSFG engine: Apache-2.0, like NewBlackbox. FidelityFX SDK: MIT
(`third_party/fidelityfx/LICENSE.txt`). ZeroFG: its own licence (see the submodule).
Lossless Scaling's shaders belong to its author and are never shipped.
