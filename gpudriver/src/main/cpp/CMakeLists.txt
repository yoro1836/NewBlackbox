# Builds libblackbox_gpu.so, the JNI bridge that loads a user-supplied Vulkan
# driver through libadrenotools, plus the adrenotools hook libraries
# (libmain_hook.so, libhook_impl.so, ...) it needs in nativeLibraryDir.
cmake_minimum_required(VERSION 3.18)
project(blackbox_gpu C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(blackbox_gpu SHARED gpu_bridge.cpp)
target_link_libraries(blackbox_gpu PRIVATE log)
target_compile_options(blackbox_gpu PRIVATE -fvisibility=hidden -fno-rtti -fno-exceptions)

# libadrenotools only supports arm64 (its linker namespace bypass walks arm64
# instructions). Other ABIs build the bridge alone and report "unsupported".
if(ANDROID_ABI STREQUAL "arm64-v8a")
    add_subdirectory(external/libadrenotools)
    target_sources(blackbox_gpu PRIVATE plt_hook.cpp)
    target_compile_definitions(blackbox_gpu PRIVATE BB_HAS_ADRENOTOOLS=1)
    target_link_libraries(blackbox_gpu PRIVATE adrenotools)
endif()
