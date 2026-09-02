# Scribe for Android

Local dictation as a system keyboard. Hold the button, speak, let go — your words appear in
whatever app you were typing in. The speech model runs on the phone. No account, no cloud,
no telemetry, and an offline build that has no internet permission at all.

This is the Android port of the desktop Scribe in the parent directory. It shares that
project's identity, its design tokens, its post-processing pipeline and its engine state
machine; what differs is everything the phone forced to be different.

## Why a keyboard

Wispr Flow — the paid, cloud-only product Scribe is measured against — injects text on
Android from a floating bubble. Its own users report that bubble dying every five to ten
minutes to battery management, and that setting it up needs developer options.

That is not a defect in their implementation. An overlay backed by a background service is
not something Android promises to keep alive. An input method is: the system binds it,
keeps it running for exactly as long as a text field has focus, and asks for no overlay
permission, no accessibility service and no battery exemption. Text goes in through
`InputConnection.commitText`, which every app accepts and which never touches the clipboard.

A floating bubble ships too, for people who want that shape, with the caveat attached.

## Raw and Clean

Beside the waveform there is a two-segment switch.

**Raw** is a faithful transcription — what you said, including the ums. **Clean** removes
fillers and stutters, turns spoken punctuation into punctuation, applies corrections you
made out loud ("four p.m., actually three p.m." becomes "3pm"), formats numbers and lists,
capitalises sentences, and applies your dictionary and shortcuts.

All of that is rules: fast, predictable, and incapable of inventing a word. An optional
small language model can additionally smooth awkward phrasing; it is off until you install
one, and everything it produces is checked before use — a candidate that adds a number,
invents content words, truncates, pads or starts explaining itself is discarded and the
rules-only text stands.

**The switch also works after the fact.** Tap it once text has been inserted and Scribe
re-renders that same utterance in the other mode and replaces what it typed. No
re-recording and no model call: the raw string is kept and every pipeline stage is pure.

## Building

The build host is aarch64 Linux, which Google does not ship a working Android toolchain
for. The workarounds are in `tools/` and are not optional — see `docs/jni-contract.md` for
why each exists.

```bash
export JAVA_HOME=$HOME/.jdks/temurin-17 ANDROID_HOME=$HOME/Android/Sdk
export PATH=$JAVA_HOME/bin:$HOME/.local/bin:$PATH

git submodule update --init --recursive          # whisper.cpp v1.8.7, llama.cpp
echo "sdk.dir=$ANDROID_HOME" > local.properties   # gitignored, per machine

python3 tools/fetch-models.py                     # the bundled base.en model (57 MB)
./tools/android-cross/build-native.sh             # libscribewhisper.so, libscribellm.so
./gradlew :app:assembleStandardRelease
```

The APK lands in `app/build/outputs/apk/standard/release/`.

### Flavours

| | `standard` | `airgap` |
|---|---|---|
| `INTERNET` permission | yes | **absent from the manifest** |
| Model downloads | in-app, verified against SHA-256 | copy the file onto the phone |
| Network ledger | every request ever made, listed in Settings | empty by construction |

The airgap build's claim is checkable rather than asserted:

```bash
aapt2 dump permissions app-airgap-release.apk
```

### Release signing

`keystore.properties` at the project root (gitignored) points at a keystore outside the
repository:

```properties
storeFile=/home/you/.keys/scribe-release.jks
storePassword=…
keyAlias=scribe
keyPassword=…
```

Without it the release variant falls back to the debug key so a fresh checkout still
builds — that output is a debug-signed release and must not be handed out as a release.

## Testing

```bash
./gradlew :core:test                  # the engine: pure JVM, no Android
./gradlew :app:testStandardDebugUnitTest
./gradlew :app:screenshotTest         # renders the UI to app/build/screenshots/
```

`core` has no Android imports at all, which is what lets the whole Clean pipeline, the
polish guardrails and the dictation state machine be tested on a machine with no device.

Two limits are worked around rather than hidden:

- **There is no Android emulator for an aarch64 Linux host.** Flows are exercised through
  Robolectric; anything that needs real hardware — microphone behaviour, on-device latency,
  thermals, the physical fold, One UI's own quirks — is marked `OWNER-VERIFY` and is not
  claimed as tested.
- **Robolectric's and Paparazzi's pixel-rendering runtimes are x86-64 only.** The
  `screenshotTest` task therefore forks an emulated x86-64 JVM
  (`tools/setup-screenshot-jvm.sh` builds it) so the UI can be seen rather than assumed.
  Ordinary tests run natively at full speed.

Accuracy is measured, not asserted — `docs/accuracy.md` has the numbers, the corpus, and a
plain statement of which of them transfer to a phone and which do not.

## Layout

```
core/       the engine — pure Kotlin/JVM. Clean pipeline, polish guards, state machine,
            settings, model registry. No Android imports; a test enforces it.
app/        the Android surfaces — keyboard, bubble, capture, JNI, Compose UI
native/     whisper.cpp and llama.cpp JNI wrappers, plus the host accuracy bench
tools/      the aarch64 cross-build, the qemu shims, the fixture and model fetchers
docs/       the JNI contract and the measured accuracy report
```

## Requirements

- Android 13 (API 33) or newer, `arm64-v8a`
- About 100 MB of storage for the app, more for optional models
