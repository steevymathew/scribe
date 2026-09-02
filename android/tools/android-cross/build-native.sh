#!/usr/bin/env bash
# Builds Scribe's native libraries for Android arm64-v8a (API 33) and installs them into
# app/src/main/jniLibs/arm64-v8a/. That directory is gitignored; this script is the source
# of truth for what ends up in the APK.
#
#   libscribewhisper.so  — whisper.cpp ASR JNI   (native/asr + third_party/whisper.cpp)
#   libscribellm.so      — llama.cpp polish JNI  (native/llm + third_party/llama.cpp)
#
# whisper.cpp and llama.cpp both define a CMake target named `ggml`, so they are configured
# as two separate projects with separate build trees. Each statically links its own copy.
#
# See README.md for the cross-toolchain, which exists because Google ships the NDK's clang
# as an x86-64 binary that cannot run on this aarch64 host.
#
# Usage: tools/android-cross/build-native.sh [--clean] [--asr-only]
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TOOLCHAIN="$REPO_ROOT/tools/android-cross/toolchain.cmake"
OUT_DIR="$REPO_ROOT/app/src/main/jniLibs/arm64-v8a"
JOBS="$(nproc)"

CLEAN=0
ASR_ONLY=0
for arg in "$@"; do
  case "$arg" in
    --clean)    CLEAN=1 ;;
    --asr-only) ASR_ONLY=1 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

if [[ $CLEAN -eq 1 ]]; then
  rm -rf "$REPO_ROOT/native/asr/build-android" "$REPO_ROOT/native/llm/build-android"
fi

require_submodule() {
  if [[ ! -e "$REPO_ROOT/third_party/$1/CMakeLists.txt" ]]; then
    echo "third_party/$1 is missing; run: git submodule update --init --recursive" >&2
    exit 1
  fi
}
require_submodule whisper.cpp
[[ $ASR_ONLY -eq 1 ]] || require_submodule llama.cpp

BUILT=()

echo "== libscribewhisper.so =="
cmake -S "$REPO_ROOT/native/asr" -B "$REPO_ROOT/native/asr/build-android" \
  -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
  -DCMAKE_BUILD_TYPE=Release \
  -DSCRIBE_BUILD_BENCH=OFF \
  -DSCRIBE_BUILD_JNI=ON
cmake --build "$REPO_ROOT/native/asr/build-android" --target scribewhisper -j "$JOBS"
BUILT+=("libscribewhisper.so")

if [[ $ASR_ONLY -eq 0 ]]; then
  echo "== libscribellm.so =="
  cmake -S "$REPO_ROOT/native/llm" -B "$REPO_ROOT/native/llm/build-android" \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DCMAKE_BUILD_TYPE=Release \
    -DSCRIBE_BUILD_JNI=ON
  cmake --build "$REPO_ROOT/native/llm/build-android" --target scribellm -j "$JOBS"
  BUILT+=("libscribellm.so")
fi

mkdir -p "$OUT_DIR"
cp "$REPO_ROOT/native/asr/build-android/libscribewhisper.so" "$OUT_DIR/"
[[ $ASR_ONLY -eq 1 ]] || cp "$REPO_ROOT/native/llm/build-android/libscribellm.so" "$OUT_DIR/"

echo
echo "== verification =="
status=0
for name in "${BUILT[@]}"; do
  so="$OUT_DIR/$name"
  echo "-- $name ($(stat -c%s "$so") bytes)"
  readelf -h "$so" | grep -E 'Class|Machine' | sed 's/^ */   /'
  readelf -d "$so" | grep NEEDED | sed 's/^ */   /'
  readelf --dyn-syms -W "$so" | grep -o 'Java_[A-Za-z0-9_]*' | sort -u | sed 's/^/   /'

  # 16 KB page alignment. Android 15+ requires it and the Fold 7 runs Android 16; a
  # 4 KB-aligned .so does not load at all there, and nothing earlier in the build fails.
  # Checked here so it cannot regress silently.
  bad_align=$(readelf -lW "$so" | awk '$1 == "LOAD" { print $NF }' |
              grep -v -E '^0x(4000|10000|[0-9a-f]{5,})$' || true)
  if [[ -n "$bad_align" ]]; then
    echo "   FAIL: LOAD segments not 16 KB aligned (found: $(echo "$bad_align" | tr '\n' ' '))"
    status=1
  else
    echo "   ok: LOAD segments are 16 KB aligned"
  fi
done

echo
if [[ $status -eq 0 ]]; then
  echo "native build OK -> $OUT_DIR"
else
  echo "native build produced libraries that will not load on Android 15+" >&2
fi
exit $status
