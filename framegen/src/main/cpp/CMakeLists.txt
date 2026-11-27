# libzfg_vk.so: handed to virtual games in place of libvulkan.so (see
# app FrameGen.kt / GpuBridge). Frame generation engines: AMD FSR 3.1 (FidelityFX
# optical flow + frame interpolation) and LSFG (Lossless Scaling shaders from the
# user's own Lossless.dll, run by lsfg/lsfg.cc).
cmake_minimum_required(VERSION 3.22)
project(framegen LANGUAGES C CXX ASM)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(ZEROFG_PREBUILT_SHADERS "ON" CACHE STRING "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/ZeroFG zerofg)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/ffx amdfg)

# Links no Vulkan loader: it forwards to the real one it is given at init.
add_library(zfg_vk SHARED vkwrap/wrap.cc vkwrap/trampolines.S lsfg/lsfg.cc)
target_include_directories(zfg_vk PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(zfg_vk PRIVATE zerofg::zerofg amdfg nativewindow log dl)
target_compile_options(zfg_vk PRIVATE -fvisibility=hidden)
