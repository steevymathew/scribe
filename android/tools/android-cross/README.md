# android-cross — host-native clang → aarch64-linux-android toolchain

Builds the two VisEar native libraries (`libvisearcore.so`, `libvisearwhisper.so`)
for arm64-v8a / API 33 **on an ARM64 Linux host without root and without a
runnable NDK compiler**.

## Why this exists

- Host: aarch64 Ubuntu 24.04, no sudo.
- Google's NDK (`~/Android/Sdk/ndk/27.2.12479018`) ships **x86-64-only**
  compiler binaries — they cannot execute on this host. Its *sysroot*
  (`toolchains/llvm/prebuilt/linux-x86_64/sysroot`) is architecture-independent
  data, though: bionic headers (incl. `jni.h`), per-API `aarch64-linux-android`
  stub libs, and `libc++_static.a`. Fully usable.
- Solution: Ubuntu's own clang-18 (an aarch64 host binary), extracted root-free
  from debs, driving `--target=aarch64-linux-android33` against the NDK sysroot
  with `ld.lld`.

## One-time setup (reproducible, no root)

```sh
# 1. Download the debs (host arch = arm64). libclang-cpp18 and libllvm18 were
#    already installed system-wide on this host; if `ldd .../bin/clang` reports
#    them missing, download + extract those two the same way and add an
#    LD_LIBRARY_PATH wrapper.
mkdir -p ~/.local/share/visear/clang18/debs
cd ~/.local/share/visear/clang18/debs
apt-get download clang-18 lld-18 libclang-common-18-dev llvm-18-linker-tools

# 2. Extract without root
cd ~/.local/share/visear/clang18
for d in debs/*.deb; do dpkg-deb -x "$d" root; done

# 3. Give clang the Android compiler-rt builtins (Ubuntu clang defaults to
#    libgcc, which has no Android flavour; the NDK archive is plain aarch64
#    static code, architecture-independent to *host*):
NDK=~/Android/Sdk/ndk/27.2.12479018
RES=~/.local/share/visear/clang18/root/usr/lib/llvm-18/lib/clang/18
mkdir -p "$RES/lib/linux"
cp "$NDK/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/18/lib/linux/libclang_rt.builtins-aarch64-android.a" \
   "$RES/lib/linux/"
```

Exact debs used (noble-updates/universe, all `1:18.1.3-1ubuntu1`, arm64):
`clang-18`, `lld-18`, `libclang-common-18-dev` (builtin headers),
`llvm-18-linker-tools`. Runtime deps `libclang-cpp18` / `libllvm18`
(same version) were already present system-wide, so no wrapper scripts are
needed — the extracted `clang` runs directly.

## Smoke test

```sh
CL=~/.local/share/visear/clang18/root/usr/lib/llvm-18/bin
SYS=~/Android/Sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/sysroot
$CL/clang++ --target=aarch64-linux-android33 --sysroot=$SYS \
  -fuse-ld=lld --rtlib=compiler-rt -static-libstdc++ \
  -shared -fPIC -O2 -o /tmp/libhello.so hello.cpp
readelf -h /tmp/libhello.so   # ELF64, AArch64
readelf -d /tmp/libhello.so   # NEEDED: libm.so libdl.so libc.so (bionic style)
```

## Files

- `toolchain.cmake` — CMake toolchain file. Env overrides: `VISEAR_CLANG18`
  (extracted tree root), `ANDROID_NDK_ROOT`. Uses `CMAKE_SYSTEM_NAME Linux`
  deliberately: CMake's Android mode requires an NDK/standalone layout our
  clang doesn't have; the android triple still defines `__ANDROID__`.
- `build-native.sh` — builds **both** `.so` files into
  `app/src/main/jniLibs/arm64-v8a/` (gitignored; this script is the source of
  truth). Run from anywhere; requires network on first run (FetchContent).

## Conventions

- ABI: arm64-v8a only, min API 33 (`aarch64-linux-android33` triple).
- C++ runtime linked statically (`libc++_static.a` from the NDK sysroot) so
  NEEDED stays within bionic: `libc.so libm.so libdl.so liblog.so` at most.
- JNI symbol contract: `docs/jni-contract.md` (do not drift).
