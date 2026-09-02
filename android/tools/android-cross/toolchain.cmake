# CMake toolchain: host-native (aarch64 Linux) clang-18 -> aarch64-linux-android33.
#
# Why not the NDK's own clang: Google ships x86-64-only compiler binaries which
# cannot run on this ARM64 host. We instead use Ubuntu's clang-18 (extracted
# root-free from debs into ~/.local/share/visear/clang18 -- see README.md) and
# point it at the NDK's *sysroot*, which is architecture-independent data
# (bionic headers + aarch64-linux-android libs + libc++).
#
# Overrides (environment):
#   VISEAR_CLANG18   root of the extracted clang-18 tree (contains usr/lib/llvm-18)
#   ANDROID_NDK_ROOT NDK installation (only its sysroot + builtins archive are used)

cmake_minimum_required(VERSION 3.24)

# The extracted clang-18 tree is a shared machine asset (installed root-free from
# Ubuntu debs by the VisEar setup; see README.md to recreate it on a fresh box).
set(_visear_clang18 "$ENV{SCRIBE_CLANG18}")
if(NOT _visear_clang18)
  set(_visear_clang18 "$ENV{VISEAR_CLANG18}")
endif()
if(NOT _visear_clang18)
  set(_visear_clang18 "$ENV{HOME}/.local/share/visear/clang18/root")
endif()
set(_ndk "$ENV{ANDROID_NDK_ROOT}")
if(NOT _ndk)
  set(_ndk "$ENV{HOME}/Android/Sdk/ndk/27.2.12479018")
endif()

set(_llvm_bin "${_visear_clang18}/usr/lib/llvm-18/bin")
set(_ndk_sysroot "${_ndk}/toolchains/llvm/prebuilt/linux-x86_64/sysroot")
if(NOT EXISTS "${_llvm_bin}/clang" OR NOT EXISTS "${_ndk_sysroot}/usr/include/jni.h")
  message(FATAL_ERROR "android-cross: clang18 tree or NDK sysroot missing; "
                      "see tools/android-cross/README.md")
endif()

# Plain Linux-style cross config (not CMAKE_SYSTEM_NAME=Android): CMake's
# Android mode insists on an NDK/standalone-toolchain layout our extracted
# clang does not have. The android triple below still defines __ANDROID__.
set(CMAKE_SYSTEM_NAME Linux)
set(CMAKE_SYSTEM_PROCESSOR aarch64)

set(CMAKE_C_COMPILER   "${_llvm_bin}/clang")
set(CMAKE_CXX_COMPILER "${_llvm_bin}/clang++")
set(CMAKE_C_COMPILER_TARGET   aarch64-linux-android33)
set(CMAKE_CXX_COMPILER_TARGET aarch64-linux-android33)
set(CMAKE_SYSROOT "${_ndk_sysroot}")

# Ubuntu clang defaults to libgcc, which does not exist for Android targets.
# libclang_rt.builtins-aarch64-android.a was copied from the NDK into the
# extracted clang's resource dir (README.md step 3), so compiler-rt resolves.
string(APPEND CMAKE_C_FLAGS_INIT   " --rtlib=compiler-rt -fPIC")
string(APPEND CMAKE_CXX_FLAGS_INIT " --rtlib=compiler-rt -fPIC")

# lld links Android ELF correctly; static libc++ keeps NEEDED down to bionic.
# max-page-size=16384: Android 15+/Play require 16 KB page-size compatible
# ELF alignment (the NDK's own driver adds this since r28).
set(_visear_link "-fuse-ld=lld --rtlib=compiler-rt -static-libstdc++ -Wl,--build-id=sha1 -Wl,--no-undefined -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")
set(CMAKE_EXE_LINKER_FLAGS_INIT    "${_visear_link}")
set(CMAKE_SHARED_LINKER_FLAGS_INIT "${_visear_link}")
set(CMAKE_MODULE_LINKER_FLAGS_INIT "${_visear_link}")

# Search only the target sysroot for libs/headers; keep host paths for tools.
set(CMAKE_FIND_ROOT_PATH "${_ndk_sysroot}")
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE ONLY)

# Android convention.
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_ANDROID_TARGETED ON)  # informational for our own CMakeLists
