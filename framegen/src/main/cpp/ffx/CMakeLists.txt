# amdfg: AMD FidelityFX (SDK 1.1.4) optical flow + frame interpolation with the
# Vulkan backend, built from the subset in third_party/fidelityfx. The SDK's
# shader compiler is Windows-only; scripts/ffx-sc makes the same permutation
# headers with glslc at build time (needs python3 and a host C compiler).
cmake_minimum_required(VERSION 3.22)

set(FFX_ROOT ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/fidelityfx)
set(FFX_SDK ${FFX_ROOT}/sdk)
set(FFX_SC ${CMAKE_CURRENT_LIST_DIR}/../../../../scripts/ffx-sc)
set(FFX_GEN ${CMAKE_CURRENT_BINARY_DIR}/ffx_shaders)

if(NOT FFX_GLSLC)
  if(ANDROID_NDK)
    set(FFX_GLSLC ${ANDROID_NDK}/shader-tools/linux-x86_64/glslc)
  else()
    find_program(FFX_GLSLC glslc REQUIRED)
  endif()
endif()

file(GLOB FFX_SHADER_SOURCES
  ${FFX_SDK}/src/backends/vk/shaders/opticalflow/*.glsl
  ${FFX_SDK}/src/backends/vk/shaders/frameinterpolation/*.glsl
  ${FFX_SDK}/include/FidelityFX/gpu/*.h
  ${FFX_SDK}/include/FidelityFX/gpu/opticalflow/*.h
  ${FFX_SDK}/include/FidelityFX/gpu/frameinterpolation/*.h
  ${FFX_SDK}/include/FidelityFX/gpu/spd/*.h)
add_custom_command(
  OUTPUT ${FFX_GEN}/stamp
  COMMAND ${CMAKE_COMMAND} -E env CC=cc GLSLC=${FFX_GLSLC} sh ${FFX_SC}/gen-shaders.sh ${FFX_ROOT} ${FFX_GEN}
  COMMAND ${CMAKE_COMMAND} -E touch ${FFX_GEN}/stamp
  DEPENDS ${FFX_SHADER_SOURCES} ${FFX_SC}/ffx_sc.py ${FFX_SC}/reflect.c ${FFX_SC}/gen-shaders.sh
  COMMENT "FidelityFX shader permutations")
add_custom_target(amdfg_shaders DEPENDS ${FFX_GEN}/stamp)

add_library(amdfg STATIC
  ${CMAKE_CURRENT_LIST_DIR}/amd_fg.cc
  ${FFX_SDK}/src/backends/vk/ffx_vk.cpp
  ${FFX_SDK}/src/backends/shared/ffx_shader_blobs.cpp
  ${FFX_SDK}/src/backends/shared/blob_accessors/ffx_opticalflow_shaderblobs.cpp
  ${FFX_SDK}/src/backends/shared/blob_accessors/ffx_frameinterpolation_shaderblobs.cpp
  ${FFX_SDK}/src/components/opticalflow/ffx_opticalflow.cpp
  ${FFX_SDK}/src/components/frameinterpolation/ffx_frameinterpolation.cpp
  ${FFX_SDK}/src/shared/ffx_assert.cpp
  ${FFX_SDK}/src/shared/ffx_message.cpp
  ${FFX_SDK}/src/shared/ffx_object_management.cpp
  ${FFX_SDK}/src/shared/ffx_breadcrumbs_list.cpp)
add_dependencies(amdfg amdfg_shaders)
# The blob accessors include the generated headers. An order-only dependency let
# ninja plan the build before the headers were rewritten, so a shader change was
# linked one build late; the stamp makes them rebuild in the same run.
set_source_files_properties(
  ${FFX_SDK}/src/backends/shared/ffx_shader_blobs.cpp
  ${FFX_SDK}/src/backends/shared/blob_accessors/ffx_opticalflow_shaderblobs.cpp
  ${FFX_SDK}/src/backends/shared/blob_accessors/ffx_frameinterpolation_shaderblobs.cpp
  PROPERTIES OBJECT_DEPENDS ${FFX_GEN}/stamp)
set_target_properties(amdfg PROPERTIES CXX_STANDARD 17 POSITION_INDEPENDENT_CODE ON)
target_compile_definitions(amdfg PRIVATE FFX_GCC FFX_OF FFX_FI)
target_compile_options(amdfg PRIVATE -include ${CMAKE_CURRENT_LIST_DIR}/posix_shim.h -w)
target_include_directories(amdfg PRIVATE ${FFX_GEN} ${FFX_SDK}/include/FidelityFX/host ${FFX_SDK}/src/shared
  ${FFX_SDK}/src/backends/shared ${FFX_SDK}/src/components ${FFX_SDK}/src)
target_include_directories(amdfg PUBLIC ${CMAKE_CURRENT_LIST_DIR} ${FFX_SDK}/include)
