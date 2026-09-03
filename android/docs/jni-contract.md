# JNI contract

Both sides MUST match this document exactly. **Change this file first**, then the Kotlin
`external fun` declarations and the C++ `extern "C"` implementations together.

Two native libraries, both `arm64-v8a` only, both built out-of-band by
`tools/android-cross/build-native.sh` into `app/src/main/jniLibs/arm64-v8a/`. That
directory is gitignored: the build script is the source of truth for what ships.

Neither library may be assumed present. `NativeLibs` wraps both `System.loadLibrary` calls
and every caller checks the flag first, so an APK assembled without one degrades to a clear
message instead of an `UnsatisfiedLinkError` in the middle of a dictation.

---

## `libscribewhisper.so` — speech recognition

Kotlin object `dev.smantics.scribe.asr.NativeWhisper`:

```kotlin
external fun create(modelPath: String, nThreads: Int): Long
external fun transcribe(
    handle: Long,
    pcm16k: FloatArray,      // mono float32 @ 16 kHz, one utterance
    language: String,        // ISO code, "en" for the .en models
    beamSize: Int,           // 1 = greedy; >1 selects beam search
    initialPrompt: String?,  // the user's dictionary, biasing recognition
): String                    // UTF-8 transcript, "" if nothing was decoded
external fun cancel(handle: Long)
external fun destroy(handle: Long)
external fun systemInfo(): String
```

C symbols: `Java_dev_smantics_scribe_asr_NativeWhisper_{create,transcribe,cancel,destroy,systemInfo}`.

- `create` returns `0` on failure. Never throws.
- `transcribe` **blocks** for the length of the decode. Call only from the dictation
  worker, never from the main thread or the IME callback thread.
- `cancel` is safe from any thread and safe after `destroy`. It sets an abort flag that
  whisper's `abort_callback` observes; a cancelled decode returns `""`.
- `destroy` frees the context. The handle is an opaque pointer with exactly one owner;
  `WhisperTranscriber.close` nulls its copy before calling.
- Decode parameters fixed on the native side: `no_context = true` (each press of the key is
  an independent utterance — carrying decoder state lets one bad transcript poison the
  next), `no_timestamps = true`, all printing off, `use_gpu = false`.

## `libscribellm.so` — optional polish

Kotlin object `dev.smantics.scribe.llm.NativeLlm`:

```kotlin
external fun create(modelPath: String, nThreads: Int, contextTokens: Int): Long
external fun generate(
    handle: Long,
    systemPrompt: String,
    userText: String,
    maxTokens: Int,
    temperature: Float,      // 0 selects greedy sampling, which is the default
): String
external fun cancel(handle: Long)
external fun destroy(handle: Long)
```

C symbols: `Java_dev_smantics_scribe_llm_NativeLlm_{create,generate,cancel,destroy}`.

- `generate` clears the KV cache on entry. Every call is independent; a leftover cache
  would let one utterance bleed into the next one's polish.
- The model's own chat template is applied when the GGUF carries one, and a plain
  concatenation is used when it does not.
- **Nothing here judges the output.** Whether a candidate is acceptable is decided by
  `PolishGuard` in the `core` module, so it is testable without a model or a device.

---

## Strings crossing the boundary

`NewStringUTF` is **not** used for any model output. It expects Java's *modified* UTF-8 and
aborts the process under CheckJNI when handed a real four-byte sequence, which whisper's
and llama's byte-fallback tokens can both produce. `scribe::make_jstring` in
`native/jni/jni_util.h` decodes UTF-8 to UTF-16 itself, skipping malformed bytes, and calls
`NewString`. This is not a stylistic choice; it is a crash that has already been paid for
once, in the VisEar project.

## Packaging and the 16 KB requirement

Android 15 and later require native libraries whose `LOAD` segments are aligned to 16 KB,
and the Galaxy Z Fold 7 runs Android 16. A 4 KB-aligned `.so` does not fail at build time —
it simply refuses to load on the device.

The cross-toolchain passes `-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384`, and
`build-native.sh` asserts the alignment with `readelf -lW` after every build and fails if it
is wrong. Do not remove that check.

## Why the toolchain looks unusual

The build host is aarch64 Linux. Google ships the NDK's clang, `aapt2` and `adb` as
x86-64 binaries, none of which run here. So:

- native code is compiled with Ubuntu's own aarch64 clang-18, extracted root-free into
  `~/.local/share/visear/clang18/root`, targeting `aarch64-linux-android33` against the
  NDK's **sysroot only** (headers and libs are architecture-independent data), with
  `CMAKE_SYSTEM_NAME Linux` rather than `Android`;
- `aapt2` runs under user-mode qemu through `tools/qemu-x86/aapt2`, wired up by
  `android.aapt2FromMavenOverride` in `gradle.properties`;
- `adb` is a native aarch64 build at `~/.local/bin/adb`.

whisper.cpp and llama.cpp each define a CMake target called `ggml`, so they are configured
as two separate projects with separate build trees rather than as subdirectories of one.
