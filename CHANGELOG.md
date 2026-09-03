# Changelog

A dated record of what changed and when. Newest first. Times are local
(America/Chicago). This log is maintained by hand alongside the git history.

## Android — `android` branch

The Android port has its own docs (`android/README.md`, `android/docs/`); this section
records what shipped and when, so the history is legible from the root of the project.

### 2026-09-03 — v0.7.0: the bubble types, and the keyboard settles

- **The bubble now puts words in the field.** It never did. Insertion had exactly one way
  to find the target — `findFocus(FOCUS_INPUT)` — one way to write to it, and no way to
  report that either had failed: the exception was swallowed, the panel collapsed on its
  way back to idle, and a dictation that went nowhere looked identical to one that worked.
  Finding the field is now a cascade of five strategies (the service's focus, the active
  window's own, every other window, a walk for a focused field, and the only field on
  screen); the write is attempted twice, asking for focus first, because several toolkits
  refuse `ACTION_SET_TEXT` on a node they do not consider focused; and the whole thing runs
  on the service's thread instead of the reveal's timer thread. **A failure is now
  visible** — the panel stays open with the transcript still in it, names the reason, and
  offers to try again. Every outcome logs under `ScribeA11y`.
- **Where the text goes is now testable.** The splice — read the field, insert at the
  cursor, write the whole thing back — moved to `core` as `TextSplice`, with twelve tests.
  It picked up two fixes on the way: a selection reported back-to-front no longer leaves
  the selected phrase in place, and a transcript spliced mid-sentence no longer welds
  itself onto the word after the cursor.
- **The bubble knew which app it was typing into and then stopped.** Persistent mode
  returned before the line that recorded it, so every bubble dictation since had been
  cleaned with the neutral tone profile.
- **The keyboard's show and hide are smooth.** Four causes, all in the same place: the
  waveform ran three infinite animations *continuously* whatever the state, so the panel
  never stopped producing frames and both window animations dropped frames underneath it;
  the neumorphic surfaces rebuilt two gradient shaders per element per frame, eighty a
  frame with the letters up; the Compose lifecycle was paused in `onFinishInputView`, part
  way through the hide, and replayed everything it had missed into the first frame of the
  next show; and the panel's height came from DataStore a frame or two late, so a keyboard
  with the letters up opened short and then grew.
- Accessibility nodes are no longer recycled: `recycle()` has been a documented no-op since
  API 33, which is Scribe's minimum.
- The privacy test that pins network access to one file now strips comments before it
  scans, so prose can name a `WebView` without failing the build.
- 203 tests, all green, none needing a device. Hardware behaviour remains OWNER-VERIFY —
  `android/docs/OWNER-VERIFY.md` has the v0.7.0 script.

### 2026-09-01/02 — v0.1.0 to v0.6.0

The port itself: engine core and native ASR/LLM, the keyboard, measured accuracy, settings
screens and a signed release, the voice-input service, the floating bubble, a keyboard that
types, the Raw/Clean reveal, and the landscape and setup fixes. See the git log on the
`android` branch and `android/docs/`.

## Desktop

### 2026-09-03 — the Windows key-matching fix moves into the package

Merging the Android work into `main` brought the package refactor with it, which
retires the monolithic `scribe.py` that the June key-matching fix was written
against. That fix is now in `src/scribe/hotkeys.py` rather than lost in the
merge: the `_WIN_VK` table for the side-specific modifiers, AltGr treated as
Right Alt, and virtual-key-code matching for the Windows drivers that hand
pynput a bare `KeyCode` instead of a named key. `--debug` prints the code again.

One change on the way in: the Windows table is consulted **on Windows only**.
Those same small numbers are Latin-1 keysyms under X11 — 163 is `£`, not Right
Ctrl — so matching on them everywhere would have fired the hotkey on a currency
symbol. Ten tests in `tests/test_hotkeys.py` pin all of it; the suite is 71.

## Unreleased — `v1-polish` branch

### 2026-07-09 (later still)

- **Dictionary editor (Settings).** View/add/remove custom `{spoken → replacement}`
  pairs (e.g. "jira" → "Jira"). Persists via config and live-updates the running
  engine — fixes apply on the next dictation, no restart.
- **On-device history (Settings), opt-in and OFF by default.** New pure-Python
  `scribe/history.py` stores transcripts as `history.json` in the config dir
  (newest-first, capped 500, every disk op best-effort). Settings gains a toggle,
  a saved-transcript list with per-row copy, and a "Clear history" button.
  Nothing is read or written unless you turn it on. +12 tests (suite now 61).
- **Code-signing pipeline (optional, additive).** `tools/sign.ps1` Authenticode-
  signs the exes/installers with a trusted timestamp (cert from
  `SCRIBE_CERT_PFX`/`SCRIBE_CERT_PASS`; no-ops cleanly when unset).
  `tools/make_dev_cert.ps1` makes a self-signed cert for testing the plumbing;
  `installer.iss` has an optional `/DSignScribe` block; BUILDING.md documents it.
  Note: SmartScreen only clears with a purchased OV/EV cert.

### 2026-07-09 (later)

- **x64 build + portable packaging.** Produced the win-x64 build from the
  emulated x64 `.venv` on the Snapdragon dev box (documented in BUILDING.md).
  Added **portable mode** (`src/scribe/portable.py`): a `portable.txt` marker
  next to the exe redirects config, logs and the model cache into a
  `ScribeData` folder beside the app (via `SCRIBE_CONFIG_DIR`/`SCRIBE_LOG_DIR`/
  `HF_HOME`), so the shipped `Scribe-Portable-x64.zip` runs with no admin and
  writes nothing to the user profile. New `tools/make_portable.py` builds the
  ZIP. `logsetup.log_dir()` now honours `SCRIBE_LOG_DIR`.
- **Tray hint on close.** Closing the window (which hides to the tray) now pops
  a one-time tray notification — "Scribe is still running" — so it doesn't feel
  like quitting. New bridge `closedToTray` signal + `notifyClosedToTray` slot;
  `qml_app` shows the balloon once per session.
- **Settings pinned to the bottom of the sidebar** (Dictate stays at the top),
  via a reusable `NavItem.qml`.
- **Collapse control moved to the rail edge.** Replaced the always-visible
  chevron button(s) with a single handle straddling the nav-rail's right edge
  that fades in only on hover — an unobtrusive hint instead of a permanent
  control.

### 2026-07-09

- **First-run wizard (QML).** The QML UI previously skipped onboarding — a fresh
  install got the window but no mic/hotkey/model setup. Added a themed
  full-cover onboarding overlay (`ui/qml/Wizard.qml`) shown when no config file
  exists: welcome/privacy → microphone pick + live level meter → push-to-talk
  key capture (reuses the engine's key matching via a new bridge
  `startKeyCapture`/`keyCaptured`) → speech-model download (observes the
  engine's own `model_loading`/`model_loaded` so there's no second download) →
  done. Finishing writes the config (so it never shows again); quitting
  mid-wizard writes nothing and it reappears next launch. New bridge surface:
  `needsOnboarding`, `inputDevices()`, `startKeyCapture`/`stopKeyCapture`,
  `keyCaptured`, `finishOnboarding()`; `run_qml_ui` detects first run.
- **Boost / high-accuracy indicator.** Holding the boost key now shows a live
  "High accuracy" badge on the Dictate hero, an "HD" tag on the floating overlay
  pill (visible even when hidden to the tray), and warm-tinted level bars; the
  engine emits a `boost` event on key change and tags each transcript with
  whether the heavy model ran, so recent items carry a "Boost" marker. Before
  this there was no indication the heavier model was engaged.
- **Fixed the "cards bounce" while speaking.** The live input bars animated
  their own height inside a layout, so every amplitude frame resized the hero
  card. The waveform now lives in a fixed-height strip (bars animate *inside*
  it) and is always present (resting low when idle), so the card height is
  constant whether idle, recording, or boosting.
- **Fixed the nav-rail overlap in compact mode.** The collapse chevron shared a
  row with the brand mark in the 66 px collapsed rail and overflowed onto the
  content area (a stray `>` over the orb). The brand now centers alone and the
  expand toggle sits in its own row below it.
- **App/installer icon.** Generated a multi-size `scribe.ico` (16–256 px) from
  the logo (`tools/make_icon.py`) and wired it into `scribe.spec` (exe icon) and
  `installer.iss` (`SetupIconFile`), so Explorer/taskbar/Alt-Tab and the setup
  wizard show the Scribe mark instead of the default PyInstaller icon.
- **Logo asset normalized** to lowercase `scribe.png` (was `scribe.PNG`, which
  would have broken the case-sensitive Linux build).
- **GETTING-STARTED.md** — a non-technical install guide (supported systems →
  which single executable, wizard walkthrough, "ignore the dev scripts" note).

### 2026-07-08

- **23:40 — Long-audio fix: transcribe clips longer than 30 seconds.**
  Whisper encodes a fixed 30 s window and the feature extractor silently
  truncates anything longer, so the tail of a long dictation was dropped
  (both models — it's the ONNX pipeline, not the model). The ONNX backend now
  splits long audio into ≤30 s windows, cutting each at the quietest point in
  its final ~2 s so word boundaries survive, and joins the results. Verified a
  36.7 s clip now captures content past 0:30 that was previously lost.
- **23:10 — Removed VAD from the ONNX hot path.** Whisper already pads clips to
  30 s internally, so voice-activity trimming saved almost nothing while
  risking clipped quiet words and, worse, dropping real speech. The path now
  transcribes the full clip; genuine silence returns `[BLANK_AUDIO]`, which the
  post-processor strips. `vad.py` stays for possible future opt-in use.
- **15:50 — Fixed "nothing typed" on quieter mics.** Two independent silence
  gates (an RMS energy check and a second `is_speech()` gate inside the ONNX
  backend) were discarding real dictation on mics quieter than the test clip.
- **14:17 — Fixed dictation getting stuck on "Transcribing".** When the VAD
  judged a clip silent it returned without emitting the terminal UI event, so
  the tray/overlay hung. Every path now emits a terminal event; console status
  is stdout-safe for the windowed build; the full record→transcribe→type
  pipeline is now logged for diagnosis.

### 2026-07-07 — v1 polish (Wispr-Flow-grade UX pass, per `ROADMAP.md`)

- **18:00 — Packaging & distribution (Phase 5b/5c) + NPU tooling (Phase 6
  prep).** PyInstaller spec building `scribe.exe` (console) and
  `scribe-tray.exe` (windowed); per-user Inno Setup installer (Start menu,
  optional sign-in autostart, clean uninstall); `BUILDING.md`;
  `tools/build_npu_encoder.py` for the future Qualcomm AI Hub encoder artifact
  (offline validate path verified).
- **17:52 — First-run onboarding wizard (Phase 5a).** Welcome/privacy → mic
  check with live level meter → press-a-key hotkey capture → one-time model
  download → done. Shown when no config exists.
- **17:06–17:10 — Desktop UI (Phase 3) and quality (Phase 4), built in
  parallel and merged.** System-tray app with recording overlay and settings
  window (`--ui`); Silero VAD, post-processing (filler removal, custom
  dictionary), language setting.
- **11:49 — Package refactor + config (Phase 2).** Split the single file into
  `src/scribe/` with a compatibility shim so all launchers keep working; TOML
  config with `defaults < file < CLI` precedence; unit tests.
- **11:41 — Quiet console + logging (Phase 1).** Third-party noise suppressed;
  everything routed to a rotating log file; `--advanced` streams the full log;
  no more raw tracebacks — friendly one-liners, details in the log.
- **11:35 — Baseline tag.** Snapshot of the working NPU/ONNX backend before the
  polish work began.

## Earlier — original project and Snapdragon backend

- **2026-06-25 — Snapdragon (Windows-on-ARM) support added.** Native-ARM64
  ONNX Runtime backend (`--device npu`) because `faster-whisper`/CTranslate2
  has no ARM64 build and would otherwise run under slow x64 emulation.
  KV-cache decoding; int8 `large-v3-turbo` for high-accuracy (boost) mode;
  Right-Alt/AltGr hotkey handling. Encoder is NPU-ready via `--npu-encoder`;
  until a QNN-prepared model is supplied it runs on the native-ARM64 CPU
  (still several times faster than emulation).
- **Original — Scribe.** Fully-offline push-to-talk dictation for Linux
  (X11/Wayland) and Windows: hold a key, speak, release, text appears at the
  cursor. faster-whisper (CPU) and openai-whisper (CUDA) backends.
