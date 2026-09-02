cmake_minimum_required(VERSION 3.24)
project(scribe_llm LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

option(SCRIBE_BUILD_JNI "Build libscribellm.so (Android JNI)" OFF)

# Configured as its own project rather than a subdirectory alongside native/asr, because
# whisper.cpp and llama.cpp both define a CMake target called `ggml`.

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
if(CMAKE_CXX_COMPILER_TARGET MATCHES "android")
  set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod" CACHE STRING "" FORCE)
endif()

set(SCRIBE_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../..)

add_subdirectory(${SCRIBE_ROOT}/third_party/llama.cpp
                 ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp EXCLUDE_FROM_ALL)

if(SCRIBE_BUILD_JNI)
  add_library(scribellm SHARED scribe_llm_jni.cpp)
  target_include_directories(scribellm PRIVATE ${SCRIBE_ROOT}/native/jni)
  target_link_libraries(scribellm PRIVATE llama)
  target_compile_options(scribellm PRIVATE -Wall -Wextra)
  if(CMAKE_CXX_COMPILER_TARGET MATCHES "android" OR ANDROID)
    target_link_libraries(scribellm PRIVATE log)
  endif()
endif()
