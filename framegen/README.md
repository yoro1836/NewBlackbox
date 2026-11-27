# Frame generation (libzfg_vk.so)

`app/src/main/jniLibs/arm64-v8a/libzfg_vk.so` is a prebuilt Vulkan wrapper from
yoro1836/zerofg-android (FidelityFX 3.1 optical flow + frame interpolation, the
`engine=amd` path). NewBlackbox hands it to a virtual app in place of
`libvulkan.so` (VulkanDriverManager.WrapperProvider -> GpuBridge.installWrapper),
so frame generation runs inside the game process without root.

Built from zerofg-android `0b5e967` (branch claude/project-thread-7le218):

    cmake -S app/src/main/cpp -B build/vk -G Ninja \
      -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
      -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-34 -DANDROID_STL=c++_static \
      -DCMAKE_BUILD_TYPE=Release -DZEROFG_PREBUILT_SHADERS=ON
    cmake --build build/vk --target zfg_vk
    llvm-strip -o libzfg_vk.so build/vk/libzfg_vk.so

Config string (per app, written by the app UI to files/framegen/<pkg>.txt):
`fg=0|1;extra=0|1;target=<fps>;scale=<percent>`, plus `log=<file>`.
