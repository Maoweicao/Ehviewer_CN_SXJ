# 端侧嵌入推理库（libsemantic.so）
#
# 默认构建"桩模式"：只导出 JNI 符号、不含 llama.cpp，
# Java 层 nativeProbe() 返回 false 后自动降级到远程后端。
# 这样在没 vendor llama.cpp 的机器上也能正常打包与运行。
#
# 启用真正的端侧推理：
#   1. 把 llama.cpp 源码放到 app/src/main/cpp/third_party/llama.cpp
#      （用 scripts/fetch_llama_cpp.ps1 自动拉取）
#   2. 重新构建，本文件会自动检测并编译 llama.cpp

cmake_minimum_required(VERSION 3.18.1)

set(SEMANTIC_THIRD_PARTY "${CMAKE_CURRENT_SOURCE_DIR}/../third_party/llama.cpp")
set(LLAMA_DIR "${SEMANTIC_THIRD_PARTY}/ggml/src")

add_library(semantic SHARED semantic_jni.cpp)

target_link_options(semantic PRIVATE -Wl,-z,max-page-size=16384)
target_compile_options(semantic PRIVATE -O3)

find_library(log-lib log)
target_link_libraries(semantic ${log-lib})

if(EXISTS "${SEMANTIC_THIRD_PARTY}/CMakeLists.txt")
    message(STATUS "semantic: llama.cpp detected at ${SEMANTIC_THIRD_PARTY}, building native backend")

    # llama.cpp 自己会处理 GPU 后端（Vulkan/CUDA），这里只要求构建核心
    set(LLAMA_BUILD_TESTS   OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER  OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)
    # Android 端按需启用 Vulkan（Adreno 等）；Mali 上会失败，由 Java 层探测后关闭
    option(EHVIEWER_SEMANTIC_VULKAN "Enable Vulkan backend for on-device embedding" ON)
    if(EHVIEWER_SEMANTIC_VULKAN)
        set(GGML_VULKAN ON CACHE BOOL "" FORCE)
    endif()
    add_subdirectory("${SEMANTIC_THIRD_PARTY}" llama.cpp EXCLUDE_FROM_ALL)

    target_compile_definitions(semantic PRIVATE EHVIEWER_LLAMA_AVAILABLE)
    target_link_libraries(semantic PRIVATE llama common ggml ggml-base android)
else()
    message(STATUS "semantic: llama.cpp not found, building stub (remote backend will be used)")
endif()