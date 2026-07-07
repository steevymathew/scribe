# Scribe 1.0 — Product Roadmap & Implementation Spec

**Audience:** Any engineer or AI agent implementing this. This document is self-contained: it states the vision, the hard constraints, the current state, the benchmark, and a phased, verifiable plan. Do not start coding a phase without reading its acceptance criteria.

---

## 1. Vision

Scribe is **local dictation for people who don't trust the cloud and shouldn't need a terminal**. Hold a key, speak, release — polished text appears at the cursor. It must be installable and usable by a non-technical person on Windows and Linux, and it must remain 100% offline after install.

The benchmark for *polish* is **Wispr Flow** ($15/mo, cloud). The benchmark for *privacy* is us. Wispr Flow's biggest weaknesses are that it is cloud-only, sends on-screen context to its servers, and costs $144/year. Scribe's job is to deliver 80% of Wispr Flow's experience with 100% of the privacy, for free.

### Hard constraints (violating any of these fails the project)

1. **Strictly offline at runtime.** No telemetry, no accounts, no network calls after model download. Audio never touches a socket.
2. **Cross-platform.** Windows (x64 + ARM64/Snapdragon) and Linux (X11 + Wayland). Nothing may break an existing platform to improve another.
3. **Additive evolution.** The existing CLI (`scribe.py` flags) keeps working throughout. The GUI wraps the engine; it does not replace the headless mode (power users and the Linux systemd service depend on it).

---

## 2. Current state (honest assessment)

**What works (keep):**
- Core engine loop is sound: event-driven hotkey → 16 kHz capture → worker-thread transcription → text injection. Architecture doc in README §Architecture is accurate.
- Three backends behind a clean `Transcriber` ABC: `CPUTranscriber` (faster-whisper/CT2), `GPUTranscriber` (openai-whisper/CUDA), `ONNXTranscriber` (native ARM64, KV-cache decode, NPU-ready via QNN with `--npu-encoder` hook).
- Verified speeds on Snapdragon X: small.en ≈ 1.8 s, large-v3-turbo int8 ≈ 3 s for a 5 s clip.
- Cross-platform text injection (xdotool / wtype / SendInput via pynput) with clipboard preserved.

**What is amateur (fix):**
- **Terminal noise.** HF Hub warnings, transformers warnings, symlink warnings, tqdm download bars, ORT chatter, and raw tracebacks all splatter across the user's terminal. A worker-thread exception prints a full traceback and the app keeps running in a broken state (this happened: missing `.onnx_data` sidecar).
- **No UI at all.** Status is `[REC] 3.5s -> "text"` printed to a console. A non-technical user has no idea it's listening, working, or dead.
- **Install is hostile.** `git clone` + `setup.bat` + knowing which of six launcher scripts to run. On Snapdragon it currently requires *two* Pythons and two venvs. Unacceptable for the target user.
- **693-line single file.** Fine at birth, now blocking: UI, config, logging, and backends all need to grow and can't share a file.
- **No config persistence.** Every preference is a CLI flag (README even brags about this). A GUI requires persisted settings; the philosophy must change to "config file, CLI flags override."
- **Crude VAD on the ONNX path** (RMS gate) vs real VAD on the CPU path — quality parity gap, hallucinations on borderline audio.
- **No graceful audio-device handling.** Mic unplugged / default device changed / exclusive-mode conflicts = cryptic errors or silence.

---

## 3. Benchmark teardown: Wispr Flow vs Scribe

| Capability | Wispr Flow | Scribe today | Scribe 1.0 target |
|---|---|---|---|
| Push-to-talk dictation anywhere | ✅ | ✅ | ✅ keep |
| Works offline / private | ❌ cloud-only | ✅ | ✅ core identity |
| Filler-word removal ("um", "uh") | ✅ AI | ❌ | ✅ local post-processing (Phase 4) |
| Auto punctuation & casing | ✅ AI | ⚠️ Whisper's own | ✅ Whisper + cleanup rules |
| Custom dictionary / names | ✅ synced | ❌ | ✅ local dictionary → `initial_prompt` + replacement rules (Phase 4) |
| Context-aware tone per app | ✅ (sends screen to cloud!) | ❌ | ❌ **non-goal** (requires surveillance or local LLM; revisit post-1.0) |
| Command mode ("make this formal") | ✅ cloud LLM | ❌ | ❌ non-goal for 1.0; optional local-LLM track later |
| Visible recording indicator | ✅ overlay pill | ❌ console text | ✅ overlay + tray (Phase 3) |
| Settings UI | ✅ | ❌ flags only | ✅ (Phase 3) |
| First-run onboarding (mic check, hotkey) | ✅ | ❌ | ✅ wizard (Phase 5) |
| One-click installer | ✅ | ❌ git+scripts | ✅ signed-ish installers (Phase 5) |
| Auto-start on login | ✅ | ⚠️ systemd unit, manual | ✅ toggle in settings |
| 100+ languages | ✅ | ⚠️ English-tuned defaults | ⚠️ expose Whisper multilingual as a setting, not a headline |
| Price | $15/mo | free | free |

Everything marked Phase N is specified below.

---

## 4. Target architecture

Refactor `scribe.py` (693 lines) into a package. **The engine must stay importable and runnable headless** — the GUI is a client of the engine, never entangled with it.

```
scribe/
  __main__.py          # `python -m scribe` → CLI (today's behavior, same flags)
  engine.py            # Scribe daemon class: hotkey→record→transcribe→inject state machine
  audio.py             # capture, device enumeration, hotplug recovery, level metering
  vad.py               # Silero VAD (ONNX, ~2 MB) — one VAD for ALL backends
  hotkeys.py           # pynput listener, key matching (incl. AltGr quirk), capture-a-key mode
  inject.py            # xdotool / wtype / SendInput text injection
  postproc.py          # filler removal, dictionary replacements, spacing/casing fixes
  config.py            # TOML config: load/save/defaults/migration; CLI flags override
  logsetup.py          # logging policy (see §5)
  backends/
    __init__.py        # make_transcriber(), device autodetect (cuda→npu→cpu)
    base.py            # Transcriber ABC
    ct2.py             # faster-whisper (CPU x64)
    torch_cuda.py      # openai-whisper (NVIDIA)
    onnx.py            # ONNX Runtime (ARM64 native, QNN/NPU hook, KV cache)
  ui/                  # all Qt code lives here and ONLY here (PySide6)
    app.py             # QApplication bootstrap, single-instance guard
    tray.py            # system tray icon + menu (status, pause, settings, advanced, quit)
    overlay.py         # frameless recording pill: idle/recording/transcribing states
    settings.py        # settings window (tabs: General, Audio, Models, Dictionary, Advanced)
    wizard.py          # first-run onboarding
    logviewer.py       # advanced-mode log panel
```

**Config file** (TOML, `platformdirs` for location — `%APPDATA%\Scribe\config.toml` / `~/.config/scribe/config.toml`): model, heavy model, hotkey, boost key, device, beam size, language, dictionary entries, filler-removal on/off, launch-at-login, advanced mode, input device. CLI flags always win over file values.

**UI toolkit decision: PySide6 (Qt).** Reasons: single toolkit that gives tray, overlay, settings, wizard on both OSes; native-ish look; LGPL; PyInstaller-friendly; pure-pip install on Windows ARM64, x64, and Linux. Rejected: Electron/Tauri (second toolchain, heavyweight vs a Python engine), tkinter (cannot reach "polished"), pystray+ad-hoc windows (that's how apps stay hacky).

---

## 5. Logging & error policy (the "crowded terminal" fix — do this FIRST)

This is the user's most immediate pain and it's cheap to fix. Policy:

1. **Default console output = product UI, nothing else.** Exactly the status lines Scribe itself prints (`[REC] 3.5s -> "text"`). Zero third-party noise.
2. **Everything goes to a rotating log file** (`platformdirs` log dir, e.g. `%LOCALAPPDATA%\Scribe\logs\scribe.log`, 5×2 MB rotation): full tracebacks, ORT/HF/transformers warnings, device errors, timings. The log is the debugging story; the console is not.
3. **Advanced mode** (`--advanced` flag, config key, tray toggle, and `SCRIBE_ADVANCED=1`): streams the full log to console/log-viewer in real time.
4. **Suppress known third-party noise at the source** (belt and suspenders, all set programmatically before imports): `HF_HUB_DISABLE_SYMLINKS_WARNING=1`, `HF_HUB_DISABLE_PROGRESS_BARS=1` (we render our own progress), `TRANSFORMERS_VERBOSITY=error`, `TRANSFORMERS_NO_ADVISORY_WARNINGS=1`, ORT `log_severity_level=3` on every session + `ort.set_default_logger_severity(3)`, `TQDM_DISABLE=1` outside our own download UI. Route Python `warnings` into `logging` (`logging.captureWarnings(True)`).
5. **No naked tracebacks, ever.** The worker thread wraps transcription in a catch-all that logs the traceback to file and emits ONE friendly line: `Transcription failed (see log: <path>)`. Device errors map to actionable messages: `Microphone busy or disconnected — check Settings → Audio`.
6. **Model downloads get real UX:** our own progress reporting (name, %, MB, one line or a Qt progress bar), not raw tqdm spew. Downloads are the *only* permitted network activity and must be clearly labeled one-time setup.

**Acceptance:** run every backend, unplug the mic mid-recording, delete a model file to force an error — the default console shows only product lines + one-line friendly errors; the log file contains the tracebacks; `--advanced` shows everything live.

---

## 6. Hardware/driver resilience (the "puking errors" fix)

- `audio.py` owns device state: enumerate devices (name+id), persist chosen device in config, fall back to system default if missing.
- **Hotplug recovery:** catch `sounddevice`/PortAudio errors on stream start AND in the callback; on failure → re-enumerate → retry once on default device → if still failing, emit friendly status (tray icon turns warning-yellow, overlay shows "mic unavailable") and keep the app alive. Never crash, never spam.
- **Pre-flight check** on startup and in the wizard: open the stream for 200 ms, measure RMS, report "mic OK / silent / failed" — catches the "records silence" class of bugs before the user dictates into the void.
- Windows: default-device changes are detected by stream failure + re-enumeration (no WASAPI event hooks needed for 1.0).
- All raw driver errors go to the log file only (§5).

---

## 7. Phased plan

Each phase is independently shippable and has acceptance criteria. Phases 1–2 are prerequisites for everything; 3–6 can partially parallelize.

### Phase 1 — Silence the noise (logging & errors)  *(small, do first)*
Implement §5 exactly, in the current single file if the refactor hasn't landed yet (env vars + logging config + worker-thread catch-all are ~150 lines). Ship immediately; port into `logsetup.py` during Phase 2.
**Accept:** criteria in §5. Regression: all three backends still work on their platforms.

### Phase 2 — Package refactor + config file
Split per §4 with **zero behavior change** (same flags, same output). Add `config.py` (TOML) with precedence: defaults < file < CLI. Add `--save-config` to write current flags to file. Keep a thin `scribe.py` shim at repo root that calls `scribe.__main__` so existing launchers/service files keep working.
**Accept:** `python -m scribe --device cpu|npu|cuda` byte-identical console behavior vs pre-refactor; old `./scribe`, `scribe.bat`, `scribe.service` still work; config round-trips; unit tests for config precedence and `postproc` (see Phase 4) scaffolding in `tests/`.

### Phase 3 — Tray + overlay (the app becomes visible)
PySide6. Tray icon with states (idle / recording / transcribing / warning) and menu: Pause dictation, Settings, Advanced mode (live log viewer), About, Quit. Overlay: small frameless always-on-top pill near the cursor or screen-bottom-center — appears on key-down (red dot + live mic level), switches to spinner on release, fades on injection. Settings window tabs: **General** (hotkey capture widget — press the actual key, fixes the AltGr class of bugs forever; boost key; launch at login), **Audio** (device picker + live level meter), **Models** (primary/boost model choice, download/delete with progress, disk usage), **Dictionary** (Phase 4 UI), **Advanced** (beam size, device override, advanced-mode toggle, open log folder).
Engine↔UI contract: engine emits events (`recording_started`, `level`, `transcribing`, `injected(text)`, `error(msg)`) via a thread-safe queue; UI subscribes. Headless mode simply has no subscriber.
**Accept:** full dictation session with terminal closed (launched from tray); every engine state visibly reflected within 100 ms; Linux X11 verified, Wayland tray verified (StatusNotifierItem), overlay degraded-but-functional on Wayland (skip overlay if compositor blocks it, tray still shows state).

### Phase 4 — Transcription quality parity+ (the Wispr-gap closers)
1. **Silero VAD** (`vad.py`, ONNX model ~2 MB, runs on every backend incl. ARM64): replaces the RMS gate on the ONNX path and optionally pre-trims on all paths. Kill the hallucination class (`[BLANK_AUDIO]`, invented sentences on noise).
2. **Post-processing pipeline** (`postproc.py`, pure Python, ordered, each step toggleable in config): non-speech annotation stripping → optional filler-word removal (`um`, `uh`, `you know` — conservative word-boundary rules, off by default until tested) → dictionary replacements (user's proper nouns, casing: "jira"→"Jira") → spacing/punctuation normalization.
3. **Custom dictionary** feeds both `initial_prompt` (biases Whisper toward user's vocabulary) and post-hoc replacement. Stored in config; edited in Settings → Dictionary.
4. **Language setting** (auto/en/…) exposed for multilingual models instead of hardcoded English.
**Accept:** golden-file tests for `postproc` (input text → expected output); dictated test phrases with a planted proper noun come out correctly after adding it to the dictionary; 10 s of silence/keyboard noise yields empty output on all backends.

### Phase 5 — Installers & onboarding (the "give it to non-techy people" moment)
- **PyInstaller onedir builds**, one per target: `win-x64` (CT2 backend), `win-arm64` (ONNX backend — the two-venv mess disappears because each build bundles its own interpreter+deps), `linux-x64`. GPU/CUDA stays a power-user pip install in 1.0 (PyTorch bundling is bloat; document it).
- **Windows:** Inno Setup → `Scribe-Setup-{x64|arm64}.exe`: installs to `%LOCALAPPDATA%\Programs\Scribe`, Start-menu entry, optional launch-at-login (HKCU Run key), proper uninstaller. Unsigned initially — document the SmartScreen "More info → Run anyway" flow with a screenshot; budget for a code-signing cert later.
- **Linux:** AppImage (primary) + `.deb` (secondary). Autostart via `~/.config/autostart/scribe.desktop`. Keep `scribe.service` for headless users.
- **Models are NOT bundled** (installers stay ~150 MB): first-run wizard downloads them with progress UI. This is the single explicitly-labeled network step.
- **First-run wizard:** welcome/privacy page ("everything stays on this device") → mic pick + live level check → hotkey capture (default Right Alt, handles AltGr) → model download with progress → "try it here" test box where the user dictates into a text field → done. Wizard must be completable with zero keyboard knowledge.
**Accept:** a fresh Windows VM (no Python, no git): download installer → next-next-finish → wizard → dictating into Notepad in under 10 minutes with zero terminal windows. Same flow on a fresh Ubuntu with the AppImage. Uninstall leaves no autostart residue.

### Phase 6 — Snapdragon NPU artifact (parallel track, pending owner input)
Owner will create a Qualcomm AI Hub account (one-time). Then: compile Whisper encoder for Snapdragon X HTP → validate outputs against the onnx-community decoder → commit artifact to repo via **Git LFS** → `--npu-encoder` auto-detects the bundled artifact so fresh Snapdragon installs get true NPU offload with **no account and no cloud**, preserving constraint #1 for end users. Blocked on: owner's API token. Prepare the compile+validate script in advance (`tools/build_npu_encoder.py`).
**Accept:** startup line reads `encoder on Hexagon NPU (QNN)` on Snapdragon X with no network access at runtime; transcription output within tolerance of CPU encoder on a test set.

### Explicit non-goals for 1.0
macOS; cloud anything; accounts/sync; screen-context awareness (privacy-hostile); LLM command mode (revisit as optional *local* LLM post-1.0); real-time streaming transcription (Whisper is utterance-based; revisit later); mobile.

---

## 8. Execution notes for agents

- **Order:** Phase 1 → 2 are strictly sequential. After 2: Phase 3, 4, and 6-prep can run in parallel (different files). Phase 5 needs 3+4 merged.
- **Platform testing floor:** every phase must be smoke-tested on Windows ARM64 (this dev machine, `--device npu`) AND x64 CPU path (`--device cpu` via the x64 venv until Phase 5 makes venvs obsolete). Linux: at minimum keep `test_linux_imports`-style checks (no Qt/Windows imports leaking into engine modules); real Linux verification before tagging 1.0.
- **The engine stays UI-free:** nothing under `scribe/` outside `ui/` may import PySide6. Enforce with a unit test.
- **Never regress the constraints (§1).** Any new dependency must have wheels for win-x64, win-arm64, and linux-x64 (check before adopting — this is what broke faster-whisper on Snapdragon).
- **Verification habit:** after each phase, run the daemon headless AND (post-Phase 3) via tray on the dev machine; dictate a real sentence; confirm the log file catches an injected fault while the console stays clean.
- Existing quirks to preserve: AltGr≡Right-Alt matching (`RIGHT_ALT_KEYS` in hotkeys), `.onnx_data` sidecar downloads (`_download_onnx`), QNN plugin registration (`_ensure_qnn_registered`), int8 heavy model on ONNX backend.

---

## 9. Success definition

A non-technical Windows-on-Snapdragon user: downloads one installer → clicks through a wizard → holds Right Alt and talks → clean text appears in Word, with a visible pill while recording — never seeing a terminal, a Python, a venv, or a traceback. A Linux power user: `./scribe --device cpu` works exactly as it always has. Both: airplane mode changes nothing.
