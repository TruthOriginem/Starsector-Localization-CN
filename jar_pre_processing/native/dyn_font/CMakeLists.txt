# ss_dyn_font — 远行星号汉化版动态字体生成 native 库
#
# 产物：
#   ss_dyn_font.dll — JNI 库（游戏运行时经 Java 薄层调用）
#   dynfont_cli.exe — 命令行驱动（离线调试 + 与 fnt_composer 金标准逐字形 diff）
#
# 构建：jar_pre_processing/build.py dynfont（首次自动 clone FreeType 源码到 third_party/freetype）。
# 工具链：MinGW-w64 g++（与 ssime.dll 同链），FreeType 静态链接（禁用全部外部依赖），
# PNG 编码用 vendored fpng（public domain）。

cmake_minimum_required(VERSION 3.20)
project(ss_dyn_font CXX)

include(CTest)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

# A/B 基准确认自有渲染核心在 GCC 15 下 -O3 会明显退化，故默认显式使用 -O2，并配合
# IPO/LTO 获取跨翻译单元优化。两个 cache 参数也可用于复现实验；保持通用 x86-64
# 目标，不使用 -march=native。
option(DYNFONT_ENABLE_IPO "为动态字体自有目标启用 IPO/LTO" ON)
set(DYNFONT_PROJECT_OPTIMIZATION "-O2" CACHE STRING
    "自有目标的优化等级（默认 -O2；空=继承 Release 默认 -O3）")

# ── FreeType（静态，纯字形渲染核心，不需要 zlib/png/harfbuzz 等）──────────────
set(FT_DISABLE_ZLIB ON CACHE BOOL "" FORCE)
set(FT_DISABLE_BZIP2 ON CACHE BOOL "" FORCE)
set(FT_DISABLE_PNG ON CACHE BOOL "" FORCE)
set(FT_DISABLE_HARFBUZZ ON CACHE BOOL "" FORCE)
set(FT_DISABLE_BROTLI ON CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(SKIP_INSTALL_ALL ON CACHE BOOL "" FORCE)
add_subdirectory(third_party/freetype EXCLUDE_FROM_ALL)

# FreeType 2.13.2 子项目使用旧 CMake policy，维持其自身 Release -O3；IPO 从这里开始
# 只作用于随后创建的 fpng、dynfont_core、CLI 与 DLL。
if(DYNFONT_ENABLE_IPO)
    include(CheckIPOSupported)
    check_ipo_supported(RESULT DYNFONT_IPO_SUPPORTED OUTPUT DYNFONT_IPO_ERROR LANGUAGES CXX)
    if(NOT DYNFONT_IPO_SUPPORTED)
        message(FATAL_ERROR "动态字体 Release 构建要求 IPO/LTO 支持: ${DYNFONT_IPO_ERROR}")
    endif()
    set(CMAKE_INTERPROCEDURAL_OPTIMIZATION TRUE)
endif()

function(dynfont_apply_project_optimization target)
    if(NOT DYNFONT_PROJECT_OPTIMIZATION STREQUAL "")
        target_compile_options(${target} PRIVATE ${DYNFONT_PROJECT_OPTIMIZATION})
    endif()
endfunction()

# ── fpng（快速 PNG 编码；SSE4.1+PCLMUL 路径带运行时 CPU 检测回退）────────────
add_library(fpng STATIC third_party/fpng/fpng.cpp)
target_include_directories(fpng PUBLIC third_party/fpng)
# fpng 上游明确要求 GCC/Clang 禁用 strict aliasing；否则其指针 type-punning
# 在 Release + IPO/LTO 下存在误编译风险。
target_compile_options(fpng PRIVATE -msse4.1 -mpclmul -fno-strict-aliasing)

# ── 核心静态库（渲染/装箱/写出全流程，CLI 与 JNI 共用）──────────────────────
add_library(dynfont_core STATIC
    src/image.cpp
    src/lanczos.cpp
    src/freetype_renderer.cpp
    src/packer.cpp
    src/fnt_writer.cpp
    src/dfnt_writer.cpp
    src/chars_file.cpp
    src/kerning.cpp
    src/pack_reader.cpp
    src/composer.cpp
    src/generate.cpp
)
target_include_directories(dynfont_core PUBLIC src)
target_link_libraries(dynfont_core PUBLIC freetype fpng)
# -ffp-contract=off：禁用 FMA 收缩，浮点逐运算舍入与 MSVC 编译的 Pillow 一致
# （Lanczos 系数定点化对末位敏感，金标准逐像素 diff 的前提）
target_compile_options(dynfont_core PRIVATE -Wall -Wextra -ffp-contract=off)
dynfont_apply_project_optimization(dynfont_core)

if(BUILD_TESTING)
    add_executable(dfnt_writer_test tests/dfnt_writer_test.cpp)
    target_link_libraries(dfnt_writer_test PRIVATE dynfont_core)
    dynfont_apply_project_optimization(dfnt_writer_test)
    add_test(NAME dfnt_writer_test COMMAND dfnt_writer_test)

    add_executable(proxy_fnt_writer_test tests/proxy_fnt_writer_test.cpp)
    target_link_libraries(proxy_fnt_writer_test PRIVATE dynfont_core)
    dynfont_apply_project_optimization(proxy_fnt_writer_test)
    add_test(NAME proxy_fnt_writer_test COMMAND proxy_fnt_writer_test)

    add_executable(baseline_alignment_test tests/baseline_alignment_test.cpp)
    target_link_libraries(baseline_alignment_test PRIVATE dynfont_core)
    dynfont_apply_project_optimization(baseline_alignment_test)
    add_test(NAME baseline_alignment_test COMMAND baseline_alignment_test)

    add_executable(png_encoding_test tests/png_encoding_test.cpp)
    target_link_libraries(png_encoding_test PRIVATE dynfont_core)
    dynfont_apply_project_optimization(png_encoding_test)
    add_test(NAME png_encoding_test COMMAND png_encoding_test)
endif()

# ── 命令行驱动（-municode：wmain 接收宽字符参数，正确处理中文路径）───────────
add_executable(dynfont_cli src/cli_main.cpp)
target_link_libraries(dynfont_cli PRIVATE dynfont_core)
target_compile_options(dynfont_cli PRIVATE -municode)
dynfont_apply_project_optimization(dynfont_cli)
target_link_options(dynfont_cli PRIVATE -municode -static -static-libgcc -static-libstdc++)

# ── JNI 动态库（游戏内加载；jni.h 取自 JAVA_HOME）────────────────────────────
if(DEFINED ENV{JAVA_HOME})
    add_library(ss_dyn_font SHARED src/jni_bridge.cpp)
    target_include_directories(ss_dyn_font PRIVATE
        "$ENV{JAVA_HOME}/include"
        "$ENV{JAVA_HOME}/include/win32"
    )
    target_link_libraries(ss_dyn_font PRIVATE dynfont_core)
    dynfont_apply_project_optimization(ss_dyn_font)
    # 与 ssime.dll 同参：全静态、strip、去 PE 时间戳（源码不变则产物字节一致）
    target_link_options(ss_dyn_font PRIVATE
        -static -static-libgcc -static-libstdc++ -s -Wl,--no-insert-timestamp)
    set_target_properties(ss_dyn_font PROPERTIES PREFIX "" OUTPUT_NAME "ss_dyn_font")
else()
    message(WARNING "JAVA_HOME 未设置，跳过 ss_dyn_font.dll（仅构建 CLI）")
endif()
