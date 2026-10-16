# SPDX-License-Identifier: GPL-3.0-only
#
# Builds libblackbox_vector.so: the LSPlant ART hooking engine used by Vector/LSPosed, wrapped in a
# small JNI bridge for BlackBox. All third-party sources live in external/ (see external/README.md).
cmake_minimum_required(VERSION 3.28)
project(blackbox_vector C CXX ASM)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(EXT ${CMAKE_CURRENT_SOURCE_DIR}/external)

# --- xz-embedded: decompresses .gnu_debugdata so stripped libart.so symbols can be resolved.
set(XZ_SOURCES xz_crc32.c xz_crc64.c xz_dec_lzma2.c xz_dec_stream.c)
list(TRANSFORM XZ_SOURCES PREPEND ${EXT}/xz-embedded/linux/lib/xz/)
add_library(xz_static STATIC ${XZ_SOURCES})
target_compile_options(xz_static PRIVATE -DXZ_USE_CRC64)
target_include_directories(xz_static PRIVATE
    ${EXT}/xz-embedded/linux/include/linux ${EXT}/xz-embedded/userspace)

# --- Dobby (inline hooks) and LSPlant (ART method hooks).
option(LSPLANT_BUILD_SHARED OFF)
option(Plugin.SymbolResolver OFF)
add_subdirectory(${EXT}/dobby dobby)
add_subdirectory(${EXT}/lsplant lsplant)
target_compile_options(lsplant_static PUBLIC
    -Wno-gnu-anonymous-struct -Wno-gnu-string-literal-operator-template)

# --- The JNI library itself.
add_library(blackbox_vector SHARED
    bridge/lsp_bridge.cpp
    bridge/gpu_bridge.cpp
    vector_native/src/elf/elf_image.cpp
    vector_native/src/elf/symbol_cache.cpp)

target_include_directories(blackbox_vector PRIVATE
    vector_native/include
    ${EXT}/xz-embedded/linux/include
    ${EXT}/fmt/include)

target_compile_definitions(blackbox_vector PRIVATE
    FMT_HEADER_ONLY=1 FMT_USE_LOCALE=0 FMT_USE_FLOAT=0 FMT_USE_DOUBLE=0
    FMT_USE_LONG_DOUBLE=0 FMT_USE_BITINT=0
    VERSION_CODE=1 VERSION_NAME="blackbox")

target_compile_options(blackbox_vector PRIVATE -fvisibility=hidden -fno-rtti -fno-exceptions)

target_link_libraries(blackbox_vector PRIVATE
    lsplant_static dobby_static xz_static log z)
target_link_options(blackbox_vector PRIVATE -Wl,--exclude-libs,ALL -Wl,--gc-sections)
