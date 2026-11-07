cmake_minimum_required(VERSION 3.18)
project(waifu2x-jni)

set(CMAKE_CXX_STANDARD 17)

# Allow Gradle or the environment to provide the ncnn Android SDK path.
set(NCNN_SDK_DIR "${NCNN_SDK_DIR}" CACHE PATH "Path to the ncnn Android Vulkan SDK")

if(NOT NCNN_SDK_DIR AND DEFINED ENV{NCNN_SDK_DIR})
    set(NCNN_SDK_DIR "$ENV{NCNN_SDK_DIR}")
endif()

if(NOT NCNN_SDK_DIR)
    message(FATAL_ERROR
        "NCNN_SDK_DIR is not set. Define it via local.properties (ncnn.sdk.dir=...), "
        "a Gradle property (-PncnnSdkDir=...), the NCNN_SDK_DIR environment variable, "
        "or place the SDK under third_party/ncnn-20260113-android-vulkan."
    )
endif()

set(ncnn_DIR ${NCNN_SDK_DIR}/${ANDROID_ABI}/lib/cmake/ncnn)

if(NOT EXISTS "${ncnn_DIR}/ncnnConfig.cmake" AND NOT EXISTS "${ncnn_DIR}/ncnn-config.cmake")
    message(FATAL_ERROR
        "Could not find ncnn CMake package for ABI ${ANDROID_ABI} under ${ncnn_DIR}. "
        "Check that NCNN_SDK_DIR points to the root of the ncnn Android Vulkan SDK."
    )
endif()

find_package(ncnn REQUIRED)

file(GLOB GLSLC_CANDIDATES
    "${ANDROID_NDK}/shader-tools/*/glslc"
    "${ANDROID_NDK}/shader-tools/*/glslc.exe"
)
if(GLSLC_CANDIDATES)
    list(GET GLSLC_CANDIDATES 0 GLSLC_EXECUTABLE)
else()
    find_program(GLSLC_EXECUTABLE glslc REQUIRED)
endif()

set(GENERATED_SHADER_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated-shaders")
file(MAKE_DIRECTORY "${GENERATED_SHADER_DIR}")

set(FUSED_PREPROC_SHADER "${CMAKE_CURRENT_SOURCE_DIR}/waifu2x_fused_preproc.comp")
set(FUSED_POSTPROC_SHADER "${CMAKE_CURRENT_SOURCE_DIR}/waifu2x_fused_postproc.comp")
# SY --> 单通道（luma）模型（Anime4K ACNet）的融合着色器。
# 3 通道版把通道数写死，单通道模型用不了；这两支是对应的 1 通道变体，
# 让 luma 模型也走完整的 GPU 融合管线，而不是退回 CPU staged 路径。
set(FUSED_PREPROC_LUMA_SHADER "${CMAKE_CURRENT_SOURCE_DIR}/waifu2x_fused_preproc_luma.comp")
set(FUSED_POSTPROC_LUMA_SHADER "${CMAKE_CURRENT_SOURCE_DIR}/waifu2x_fused_postproc_luma.comp")
# SY <--
set(FUSED_SHADER_OUTPUTS
    "${GENERATED_SHADER_DIR}/waifu2x_fused_preproc_fp16.inc"
    "${GENERATED_SHADER_DIR}/waifu2x_fused_postproc_fp16.inc"
    "${GENERATED_SHADER_DIR}/waifu2x_fused_preproc_fp32.inc"
    "${GENERATED_SHADER_DIR}/waifu2x_fused_postproc_fp32.inc"
    # SY --> luma 变体（单通道）
    "${GENERATED_SHADER_DIR}/waifu2x_fused_preproc_luma_fp16.inc"
    "${GENERATED_SHADER_DIR}/waifu2x_fused_postproc_luma_fp16.inc"
    "${GENERATED_SHADER_DIR}/waifu2x_fused_preproc_luma_fp32.inc"
    "${GENERATED_SHADER_DIR}/waifu2x_fused_postproc_luma_fp32.inc"
    # SY <--
)

add_custom_command(
    OUTPUT ${FUSED_SHADER_OUTPUTS}
    COMMAND "${GLSLC_EXECUTABLE}" -g -fshader-stage=compute --target-env=vulkan1.0 -DFUSED_FP16=1 -mfmt=c "${FUSED_PREPROC_SHADER}" -o "${GENERATED_SHADER_DIR}/waifu2x_fused_preproc_fp16.inc"
    COMMAND "${GLSLC_EXECUTABLE}" -g -fshader-stage=compute --target-env=vulkan1.0 -DFUSED_FP16=1 -mfmt=c "${FUSED_POSTPROC_SHADER}" -o "${GENERATED_SHADER_DIR}/waifu2x_fused_postproc_fp16.inc"
    COMMAND "${GLSLC_EXECUTABLE}" -g -fshader-stage=compute --target-env=vulkan1.0 -mfmt=c "${FUSED_PREPROC_SHADER}" -o "${GENERATED_SHADER_DIR}/waifu2x_fused_preproc_fp32.inc"
    COMMAND "${GLSLC_EXECUTABLE}" -g -fshader-stage=compute --target-env=vulkan1.0 -mfmt=c "${FUSED_POSTPROC_SHADER}" -o "${GENERATED_SHADER_DIR}/waifu2x_fused_postproc_fp32.inc"
    # SY --> luma 变体
    COMMAND "${GLSLC_EXECUTABLE}" -g -fshader-stage=compute --target-env=vulkan1.0 -DFUSED_FP16=1 -mfmt=c "${FUSED_PREPROC_LUMA_SHADER}" -o "${GENERATED_SHADER_DIR}/waifu2x_fused_preproc_luma_fp16.inc"
    COMMAND "${GLSLC_EXECUTABLE}" -g -fshader-stage=compute --target-env=vulkan1.0 -DFUSED_FP16=1 -mfmt=c "${FUSED_POSTPROC_LUMA_SHADER}" -o "${GENERATED_SHADER_DIR}/waifu2x_fused_postproc_luma_fp16.inc"
    COMMAND "${GLSLC_EXECUTABLE}" -g -fshader-stage=compute --target-env=vulkan1.0 -mfmt=c "${FUSED_PREPROC_LUMA_SHADER}" -o "${GENERATED_SHADER_DIR}/waifu2x_fused_preproc_luma_fp32.inc"
    COMMAND "${GLSLC_EXECUTABLE}" -g -fshader-stage=compute --target-env=vulkan1.0 -mfmt=c "${FUSED_POSTPROC_LUMA_SHADER}" -o "${GENERATED_SHADER_DIR}/waifu2x_fused_postproc_luma_fp32.inc"
    # SY <--
    DEPENDS "${FUSED_PREPROC_SHADER}" "${FUSED_POSTPROC_SHADER}"
            "${FUSED_PREPROC_LUMA_SHADER}" "${FUSED_POSTPROC_LUMA_SHADER}"
    VERBATIM
)
add_custom_target(waifu2x-fused-shaders DEPENDS ${FUSED_SHADER_OUTPUTS})

# Source files
set(WAIFU2X_SOURCES
    waifu2x.cpp
    anime4k.cpp
    waifu2x_jni.cpp
)

# Create shared library
add_library(waifu2x-jni SHARED ${WAIFU2X_SOURCES})
add_dependencies(waifu2x-jni waifu2x-fused-shaders)

# SY --> AGP 对 debug 变体会传 -DCMAKE_BUILD_TYPE=Debug，CMake 随之只给 -g（即 -O0），
# 超分的图像预处理/合成循环会慢一个数量级。这里显式保证任何构建类型都开优化，
# 只有真机 lldb 调试原生代码时才需要手动关掉。
target_compile_options(waifu2x-jni PRIVATE
    $<$<CONFIG:Debug>:-O2>
    -fno-math-errno
)
# SY <--

# Include directories
target_include_directories(waifu2x-jni PRIVATE
    ${CMAKE_SOURCE_DIR}
    ${GENERATED_SHADER_DIR}
)

# Link libraries
target_link_libraries(waifu2x-jni
    ncnn
    android
    log
    jnigraphics
    GLESv3
    EGL
    dl
)

# SY --> VR：OpenXR 引擎（Pico Neo 3 等 OpenXR 头显）
#
# loader 与头文件随仓库 vendor，来源是 Khronos 官方发布的
# openxr_loader_for_android-1.1.65.aar 里的 Prefab 包（Apache-2.0 OR MIT，可再分发）：
#   - 头文件 → third_party/openxr/include/openxr/
#   - 各 ABI 的 libopenxr_loader.so → app/src/main/jniLibs/<abi>/（AGP 自动打包进 APK）
#
# ⚠️ 刻意**不走 AGP 的 prefab**：prefab 靠隐式注入的 CMake 变量和 find_package 包装
# 生效，猜错一处（包名大小写、变量名、生成的 config 路径）整层就白做，而且本地没有
# NDK 无法预验证。这里所有路径都显式写出，能逐条核对。
#
# ⚠️ 为什么 loader 必须打进 APK 而不是用系统的：Android 7.0 起应用不能 dlopen
# 非公开 SDK 的系统库。标准做法是自带 loader，由它通过
# org.khronos.openxr.OpenXRRuntimeService 这个 Binder 服务找到设备上的 runtime
# （Pico/Quest 都是这个机制，Thru3D 的包里同样有这个 .so 和这个服务声明）。
set(OPENXR_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/openxr/include")
set(OPENXR_LOADER_LIB "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libopenxr_loader.so")

if(NOT EXISTS "${OPENXR_INCLUDE_DIR}/openxr/openxr.h")
    message(FATAL_ERROR
        "找不到 vendor 的 OpenXR 头文件：${OPENXR_INCLUDE_DIR}/openxr/openxr.h"
    )
endif()
if(NOT EXISTS "${OPENXR_LOADER_LIB}")
    message(FATAL_ERROR
        "找不到 ${ANDROID_ABI} 的 libopenxr_loader.so（应在 ${OPENXR_LOADER_LIB}）。"
        "若新增了 ABI，需从 openxr_loader_for_android AAR 的 "
        "prefab/modules/openxr_loader/libs/android.${ANDROID_ABI}/ 下补进来。"
    )
endif()

add_library(openxr_loader SHARED IMPORTED)
set_target_properties(openxr_loader PROPERTIES IMPORTED_LOCATION "${OPENXR_LOADER_LIB}")

add_library(mihonvr-jni SHARED
    vr/xr_engine.cpp
    vr/vr_jni.cpp
)

# GLES 3.2 而非 Vulkan：SD835 上 GLES swapchain 风险低得多，而深度 warp 本来就是
# 片元着色器的活，不需要 compute。着色器源码在 vr/shaders.h 里（GLSL ES 无需预编译）。
target_compile_options(mihonvr-jni PRIVATE
    $<$<CONFIG:Debug>:-O2>
    -Wall
    -Wno-missing-field-initializers
    -fno-exceptions
)

# xr_engine.h 里也定义了这两个宏，这里再显式给一遍：OpenXR 的头文件是按宏分块的，
# 少一个就会得到「符号存在但类型没定义」这种很难读的报错。
target_compile_definitions(mihonvr-jni PRIVATE
    XR_USE_PLATFORM_ANDROID
    XR_USE_GRAPHICS_API_OPENGL_ES
)

target_include_directories(mihonvr-jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/vr
    ${OPENXR_INCLUDE_DIR}
)

target_link_libraries(mihonvr-jni
    openxr_loader
    android
    log
    jnigraphics
    EGL
    GLESv3
    dl
)
# SY <--
