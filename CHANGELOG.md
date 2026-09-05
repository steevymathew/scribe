# Changelog

A dated record of what changed and when. Newest first. Times are local
(America/Chicago). This log is maintained by hand alongside the git history.

## Android — `android` branch

The Android port has its own docs (`android/README.md`, `android/docs/`); this section
records what shipped and when, so the history is legible from the root of the project.

### 2026-09-05 — v0.11.0: the mock-up, uniform keys, and typing that lands

- **Typing felt imprecise because it was.** Characters committed on *release*, so a thumb
  that landed on a key and drifted a millimetre before lifting typed nothing at all — and
  the user found out several words later. Characters now commit on **touch-down**, as every
  keyboard on the platform does. The cost is that a swipe beginning on a key types one
  character on its way past, so the card raises a counter the moment a drag becomes a swipe
  and the key that fired takes its character back.
- **The keys are a uniform grid.** Rows were laid out purely by weight, so the nine-key
  `asdfghjkl` was stretched to the same width as the ten-key row above it and came out
  visibly fatter — a thumb aiming between rows landed on the wrong letter. Every row is now
  padded to a fixed ten key-widths, which is the stagger a physical keyboard has.
- **The height no longer changes between pages.** The symbols page was one row shorter than
  the letters, so swiping sideways re-laid out the app behind the keyboard. Both pages now
  share the number row and are built to the same four-rows-plus-bottom-row grid; the emoji
  grid is given exactly the height it stands in for.
- **Pages slide** in from the side they were pulled from rather than cutting, and the bottom
  row is on every page including emoji, so no page is one you can get stuck on.
- **Hiding the keys lays the card face down.** It tips forward from its bottom edge to leave
  the same slab seen side-on, which is also the control that props it back up. The container
  height is interpolated in step, so the app behind moves with the card.
- **Shift is a shift arrow**, not the lightning bolt borrowed from the desktop's boost
  control, and holding a key now *replaces* the character it typed rather than appending —
  `a` held gives `@`, not `a@`.
- **"Inserted" is a flash again.** It was written into the status and left there, so a panel
  opened an hour later still reported the last dictation as if it had just happened, and
  "Ready" never came back.
- **Deleting whole words has its own heavier haptic.** Holding backspace switches from
  characters to words and the two felt identical, so the change of unit passed unnoticed
  until several words were gone.
- The keyboard is listed as **"Scribe"** rather than "English".
- 246 tests, all green. `android/docs/OWNER-VERIFY.md` v0.11.0.
- Autocorrect, suggestions, clipboard, themes and glide typing are planned rather than
  built: `Scribe — Keyboard Plan` in the vault, with FUTO as the benchmark and three
  questions that need the owner before the largest of them can start.

### 2026-09-04 — v0.10.0: the keyboard rebuilt, and a notification that stays

- **The bubble's notification is permanent and resists dismissal.** It used to be posted
  only *after* the bubble was closed — the moment the user is least likely to be reading
  notifications — and swiping it away stranded them with no route back short of the
  accessibility settings. It is now posted when the service connects, re-words itself
  depending on whether the bubble is on screen, and puts itself back via a delete intent if
  it is swiped away. `setOngoing` alone stopped being enough at Android 14. It belongs to
  the bubble alone and goes for good when the service is switched off.
- **New keyboard layout**, from the owner's two screenshots: a number row across the top, a
  symbol on the shoulder of every letter reached by holding it, and `?123 | , | ☺ | space |
  . | ⏎` along the bottom. Holding the comma opens Scribe, which is Android's own
  convention for a keyboard's settings key.
- **The split repeats the middle key on both halves** — `asdfg`/`ghjkl`, `zxcv`/`vbnm` —
  so neither thumb reaches across an eight-inch display for two of the commonest letters in
  English. Shift and backspace hang off the outside edges.
- **Keys are pressed in when touched.** The whole argument for the neumorphic style on a
  keyboard, and it was missing: every key was drawn raised and stayed raised, so the only
  confirmation of a tap was the haptic and a character appearing elsewhere. The light flips
  on touch-down, on the same frame as the vibration.
- **The utility bar is gone, replaced by gestures.** Swipe down to put the keys away and up
  on the grabber to get them back; swipe left and right for the emoji and symbol pages.
  Faint chevrons on the card's edges and an outline around it are what make that findable.
  Width and split moved to a KEYBOARD card in Settings — decisions taken once for a device,
  which do not need permanent keys on a surface that covers what the user is writing.
- **No switch-to-another-keyboard key.** Android's own picker does that; a second one on
  the primary keyboard is an invitation to leave. `Key.SwitchIme` was deleted rather than
  left unused, so the type system enforces it.
- The keys' up/down state is remembered across appearances; **the page never is** — the
  keyboard always opens on the letters.
- A stale-lambda bug the new tests caught: `pointerInput` keyed on a `data object` key
  captured the first composition's handler, so `?123` switched to symbols and then the same
  key on the symbols page switched to symbols again. Handlers now read through
  `rememberUpdatedState`.
- 242 tests, all green. `android/docs/OWNER-VERIFY.md` v0.10.0 — §4 asks whether the swipe
  threshold is right, which is the number most likely to need tuning on a real thumb.

### 2026-09-04 — v0.9.0: the audio that went missing, and the wait that was never explained

- **Long dictations lost their ending, and it was three faults at once.** The clip was cut
  to 120 s with `copyOf` *after* the user had finished — so the panel showed a live
  transcript of everything and typed in the first two minutes, with nothing anywhere to say
  why. Past 130 s `ensureCapacity` clamped and `append` then wrote off the end of the
  array, killing the capture thread inside a `catch (Throwable)` that only logged. And the
  buffer grew by doubling — copying several megabytes **on the capture thread**, which has
  20 ms to get back to `AudioRecord.read` before the driver overruns, so past about a
  minute that copy was long enough to drop audio out of the *middle* of an utterance.
- **The recorder holds one-second blocks now.** Nothing is copied or reallocated on the
  capture thread, storage is 16-bit rather than float (half the memory for samples that
  arrive as 16-bit anyway), there is no ceiling, and the conversion to float happens on
  whichever thread asked for the audio. Nothing captured is ever discarded: the limit is
  now enforced by **stopping** the recording at 180 s and saying so, before the words
  exist, rather than by trimming them afterwards.
- **The limit is visible.** `LISTENING · 1:37` counts up while you talk and turns amber
  inside the last twenty seconds. A limit nobody can see is indistinguishable from a bug.
- **The wait after pressing insert is explained.** `TRANSCRIBING 1:02` names the length of
  audio being decoded, which is the one number that predicts the wait — Whisper is not a
  streaming recogniser, the live transcript is a separate decode that is thrown away, and
  insert starts a fresh one over the whole clip.
- **The reveal is skipped after a slow decode.** Past 2.5 s of decoding the cleanup
  animation is a second added to a wait the user has already endured, so the text goes
  straight in.
- 220 tests, all green. `android/docs/OWNER-VERIFY.md` v0.9.0 — §3 asks for two timings
  that nothing on the build host can establish.

### 2026-09-03 — v0.8.0: the bubble as one object, and a model setting that means something

From the first real device pass. Most of these are things that were wrong, not missing.

- **Choosing a different speech model did nothing.** `WhisperProvider` returned its cached
  transcriber the moment one existed, without asking which model was wanted — so the first
  model loaded after install did every transcription for the life of the process, while
  the panel's label named it and Settings showed the choice as taken. It now compares the
  loaded model against the configured one and swaps it, closing the old handle before
  opening the new; the engine re-warms when the setting changes so the label follows
  immediately rather than at the next dictation. The requirement is written into
  `TranscriberProvider`'s contract, where it was missing, and pinned by a test. The panel
  also names the model the way the settings screen does — "Base (English)", not "base.en".
- **The circle and the panel are one object.** They used to be a swap: the circle you
  pressed to open the panel vanished as the panel appeared, and getting back was a
  different control somewhere else. The circle is now permanent, sits below the panel and
  aligned to the same edge, and — because the window is anchored to the bottom — does not
  move when the panel opens above it. It is the expand/collapse control, the drag handle,
  and where recording state is shown. Dictation starts from the panel's microphone.
- **The whole assembly drags**, not just the circle, so an open panel can be moved off
  whatever it is covering; and it is now bounded by the display on all four sides, which
  mattered much less when the dragged object was a 58 dp circle.
- **Cancel is a red button** in the control row, present only while there is something to
  cancel. It was a line in the hamburger menu, which is not where a destructive action
  belongs. The panel's own collapse control is gone — the circle does that.
- **"High accuracy" is gone from the models screen.** It chose a second model for a boost
  mode that nothing could turn on: the keyboard's boost control had been removed on
  purpose, and this was left behind pointing at it. It set a value, showed a tag, and
  changed nothing anyone could observe. The engine's per-utterance model switch is kept
  and documented as unreachable rather than deleted.
- **The bubble's setup instructions were wrong.** Android blocks accessibility access for
  sideloaded apps, and the app sent you to a screen where the toggle cannot be enabled.
  The five taps that actually work — including Settings → Apps → Scribe → ⋮ → Allow
  restricted settings — are now in the app behind a Steps button, written from what the
  phone did rather than from what the intent was expected to do.
- 216 tests, all green, none needing a device. `android/docs/OWNER-VERIFY.md` v0.8.0.

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
