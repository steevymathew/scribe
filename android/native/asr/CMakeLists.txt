cmake_minimum_required(VERSION 3.24)
project(scribe_asr LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

option(SCRIBE_BUILD_JNI   "Build libscribewhisper.so (Android JNI)" OFF)
option(SCRIBE_BUILD_BENCH "Build the host-side accuracy/latency bench" ON)

# whisper.cpp and llama.cpp each define a CMake target called `ggml`, so they are
# configured as two separate projects with two separate build trees rather than as
# subdirectories of one. Each links its own copy statically.

# ------------------------------------------------------ vendored whisper.cpp
# Pinned submodule at third_party/whisper.cpp (v1.8.7 — the same revision VisEar
# proved against this cross-toolchain). CPU only; no examples, tests or server.
# NEON is baseline on aarch64; dotprod+fp16 (armv8.2-a) is a safe floor for every
# SoC that can run API 33 and materially speeds up the quantised models.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
if(CMAKE_CXX_COMPILER_TARGET MATCHES "android")
  set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod" CACHE STRING "" FORCE)
endif()

set(SCRIBE_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../..)

add_subdirectory(${SCRIBE_ROOT}/third_party/whisper.cpp
                 ${CMAKE_CURRENT_BINARY_DIR}/whisper.cpp EXCLUDE_FROM_ALL)

# --------------------------------------------------------------- JNI library
if(SCRIBE_BUILD_JNI)
  add_library(scribewhisper SHARED scribe_whisper_jni.cpp)
  target_include_directories(scribewhisper PRIVATE ${SCRIBE_ROOT}/native/jni)
  target_link_libraries(scribewhisper PRIVATE whisper)
  target_compile_options(scribewhisper PRIVATE -Wall -Wextra)
  if(CMAKE_CXX_COMPILER_TARGET MATCHES "android" OR ANDROID)
    target_link_libraries(scribewhisper PRIVATE log)
  endif()
endif()

# ------------------------------------------------------------- host-side bench
# Runs the real whisper.cpp over a WAV corpus and reports word error rate and
# real-time factor per model. Builds for THIS machine, not for Android: the
# numbers are true for this CPU and are not transferable to a phone.
if(SCRIBE_BUILD_BENCH)
  add_executable(scribe_bench test/scribe_bench.cpp)
  target_include_directories(scribe_bench PRIVATE ${SCRIBE_ROOT}/native/jni)
  target_link_libraries(scribe_bench PRIVATE whisper)
endif()
