# Scribe — Continuation / Handoff

Pick-up notes for the next session (this conversation is near its context
limit). Pairs with [ROADMAP.md](ROADMAP.md) (the vision/plan) and
[CHANGELOG.md](CHANGELOG.md) (dated history). Read those two first.

---

> ✅ **UI polish round shipped (2026-07-09):** overlay pill, mic-lifecycle fix,
> gear icon, sidebar collapse, compact titlebar, configurable heavy model,
> developer section, logo, live input bars — all committed, built, installed,
> and pushed to `v1-polish`. Logo `scribe.png` is in `ui/assets/`. Remaining
> work is the **PENDING** list below (top: QML first-run wizard).

## TL;DR — where things stand (2026-07-09)

Scribe is a **fully-offline push-to-talk dictation app**. It started as a
single script; it is now an installable Windows-on-ARM (Snapdragon) app with a
dark **Material QML** desktop UI, riding on an unchanged transcription engine.

- **Engine works great.** Hold Right Alt, speak, release → text at the cursor.
  Normal model `small.en` (~1s), boost model int8 `large-v3-turbo` (hold Right
  Shift). Long dictations (>30s) now chunk correctly. Verified on real hardware.
- **New QML main window** (dark Material, `--ui`): Dictate + Settings pages,
  tray minimize/restore, floating status pill overlay, live mic input bars.
- **Everything local**; nothing pushed except the git branch `v1-polish`
  (on GitHub) — see below.
- Work lives on branch **`v1-polish`** (pushed to origin). `main` unchanged.

---

## The environment reality (important, non-obvious)

This machine is a **Snapdragon X Elite (Windows on ARM64)**. Two venvs exist by
necessity, because `faster-whisper`/`ctranslate2` has **no ARM64 wheel**:

| venv | Python | Backend | Used for |
|---|---|---|---|
| `.venv` | **x64** (`…\Python311-x64`) | faster-whisper (emulated) | `--device cpu` |
| `.venv-npu` | **native ARM64** (`…\Python311-arm64`) | onnxruntime-qnn + transformers + **PySide6** | `--device npu`, the UI, builds |

**Always use `.venv-npu` for the UI and for building on this machine.**
Absolute python: `C:\Users\Steev\Development\claude\scribe\.venv-npu\Scripts\python.exe`

Config file: `%APPDATA%\Scribe\config.toml`. Logs (the real diagnostic trail,
esp. for the windowed build which has no stdout):
`%LOCALAPPDATA%\Scribe\logs\scribe.log`.

---

## Repo map

```
scribe.py                    compat shim -> src/scribe/__main__
src/scribe/
  __main__.py                CLI: flags, config merge, launches engine or --ui
  engine.py                  Scribe daemon: hotkey→record→transcribe→inject;
                             emits UI events via event_sink(name, payload)
  config.py                  TOML settings, precedence: DEFAULTS < file < CLI
  logsetup.py                quiet console + rotating log + --advanced
  audio.py hotkeys.py inject.py postproc.py vad.py   engine helpers
  backends/{base,ct2,torch_cuda,onnx}.py   transcription backends
  hub.py                     one-time HF model download (announce-once)
  ui/
    app.py                   OLD Qt Widgets UI (tray/overlay/settings/wizard) — still present
    qml_app.py               NEW: boots QML, tray, single-instance, mic-meter, logo icon
    bridge.py                AppBridge: engine<->QML (Properties/Signals/Slots)
    assets/                  <-- DROP scribe.png HERE (logo); see below
    qml/                     Main, Theme(singleton), DictatePage, SettingsPage,
                             Overlay(status pill), Brand, Card, Chip, Glyph(canvas
                             icons), IconButton, SettingsGroup, SettingRow, qmldir
packaging/
  scribe.spec                PyInstaller: scribe.exe (console) + scribe-tray.exe
                             (windowed --ui). Collects onnxruntime_qnn, our qml,
                             assets, and the Material + Effects QML modules.
  installer.iss              Inno Setup per-user installer
  launcher.py launcher_tray.py
tools/build_npu_encoder.py   Phase 6 (Qualcomm AI Hub) encoder build+validate
tests/                       unittest; run with `python -m unittest discover -s tests`
```

---

## Build / test / install (exact commands, run from repo root)

```bash
PY="C:/Users/Steev/Development/claude/scribe/.venv-npu/Scripts/python.exe"

# tests (49+; should stay green)
"$PY" -m unittest discover -s tests

# run the UI from source (dev)
"$PY" scribe.py --ui --device npu
# ...or offscreen smoke (no display): QT_QPA_PLATFORM=offscreen ... then check the log for "ready:"

# QML-only load check (catches syntax/binding errors fast):
# scratchpad/qml_load_test.py loads Main.qml with a fake bridge, prints LOAD_OK

# freeze the app (~2 min). BUILD FROM A SHORT PATH is not required here — the
# repo path is short enough; only the deep scratchpad tripped MAX_PATH.
"$PY" -m PyInstaller packaging/scribe.spec --noconfirm
# -> dist/scribe/{scribe.exe, scribe-tray.exe}

# installer (needs Inno Setup 6 at %LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe)
"/c/Users/Steev/AppData/Local/Programs/Inno Setup 6/ISCC.exe" -DTargetArch=arm64 packaging/installer.iss
# -> dist/Scribe-Setup-arm64.exe

# silent reinstall (KILL ALL scribe first — a running exe can't be overwritten)
powershell -NoProfile -Command "Get-Process | Where-Object {\$_.Name -like '*scribe*'} | Stop-Process -Force"
./dist/Scribe-Setup-arm64.exe //VERYSILENT //SUPPRESSMSGBOXES //MERGETASKS='!autostart,!desktopicon' //NORESTART
```

---

## Tripwires (things that already bit us)

- **Single-instance lock**: `%TEMP%\scribe-ui.lock`. A stale/old running tray
  blocks a new instance AND can't be overwritten by the installer. Always
  kill all `scribe*` processes before reinstalling, and confirm the installed
  exe mtime updated.
- **PyInstaller drops the Material QML style** (ships only Basic/FluentWinUI3).
  `scribe.spec` explicitly collects `QtQuick/Controls/Material` + `QtQuick/Effects`.
- **Windows 260-char path limit** breaks QML asset copy in *deeply nested*
  build dirs (the scratchpad). The repo path is fine.
- **Windowed build has no stdout** — `engine._out()` guards `print`; rely on the
  log file. All pipeline stages are logged (`recording started`, `transcribe
  done: '…'`, `typed N chars`).
- **font.pixelSize is an int in QML** — fractional sizes fail to load.
- **Queue.put as event_sink**: must wrap as `lambda n,p: q.put((n,p))` (put's
  2nd positional arg is `block`).
- **VAD must never gate transcription** — we removed it from the hot path; it
  was dropping real speech. Don't re-add a silence gate.

---

## This UI round (2026-07-09) — done vs. pending

**Done (in `src/scribe/ui/`):**
- Mic only opens during push-to-talk OR while the Settings page is the *visible*
  page (meter start/stop tied to `SettingsPage.active`, set by Main). Fixes
  "Windows shows mic always in use."
- Floating **status pill overlay** restored (`Overlay.qml`) — Listening /
  Transcribing / Inserted, live bars from real mic level; shows even when the
  window is hidden to tray.
- **Live input bars** on the Dictate hero (during recording) and a live input
  meter on Settings (`app.level`, fed by engine `"level"` events).
- Removed the "On device" footer (was mistaken for a button).
- **Gear** settings icon (was a sun); added chevron/check glyphs.
- **Collapsible sidebar** (chevron toggle) + auto-collapse when narrow.
- **Titlebar hidden in compact form** (PIA-style clean flyout).
- **High-accuracy model** is now configurable (`config heavy_model`), with a
  Settings row explaining the hold-Shift boost behavior.
- **Developer section**: "Built by SMantics.dev" → opens https://smantics.dev.
- **Logo wiring**: `Brand.qml` + tray/window icon use `ui/assets/scribe.png`
  when present, else a drawn-mic fallback.

**PENDING — do these next:**
1. **Logo + icon DONE (2026-07-09).** `scribe.png` (81176 bytes) dropped and
   normalized to lowercase. `scribe.ico` (16–256 px) generated by
   `tools/make_icon.py` (needs Pillow, build-time only) and wired into
   `scribe.spec` (EXE `icon=`) + `installer.iss` (`SetupIconFile`). Rebuilt and
   installed: in-app brand, titlebar/taskbar, tray, exe (Explorer/Alt-Tab), and
   installer chrome all show the mark. Also shipped the same day: **boost/
   high-accuracy indicator** (engine `boost` event + `injected heavy=…` →
   bridge `boostActive` + recent `heavy` → Dictate hero badge, overlay "HD"
   tag, warm level bars, recent "Boost" marker); **"cards bounce" fix**
   (fixed-height waveform strip); **compact nav-rail overlap fix** (collapse
   chevron no longer overflows the 66 px rail). See CHANGELOG 2026-07-09.
2. **QML first-run wizard — DONE (2026-07-09).** `ui/qml/Wizard.qml` is a themed
   full-cover overlay shown when `app.needsOnboarding` (no config file):
   welcome → mic pick + live meter → hotkey capture → model download (watches
   the engine's own load, no second download) → done. Bridge gained
   `needsOnboarding`, `inputDevices()`, `startKeyCapture`/`stopKeyCapture` +
   `keyCaptured` signal, `finishOnboarding()`; `run_qml_ui` sets `first_run`.
   The old Qt-Widgets `ui/wizard.py` is now dead code (kept for reference) —
   safe to delete once the QML wizard is confirmed on a fresh profile.
   Possible polish later: true byte-% download progress (today it's an
   indeterminate bar + status text driven by `app.status`), and an optional
   "download the high-accuracy model now" step (currently deferred to first
   boost use).
3. **History + Dictionary — DONE (2026-07-09).** Both are sections in
   `SettingsPage.qml`. Dictionary: view/add/remove `{spoken→replacement}` pairs
   (`bridge.setDictionary`, live-applies via postproc). History: opt-in, OFF by
   default (`config history_enabled`), persisted by new pure-Python
   `scribe/history.py` (`history.json` in config dir, capped 500);
   `bridge.historyEnabled`/`history`/`clearHistory()`. Tests in
   `tests/test_history.py` (suite 61 green).
4. **x64 build DONE (2026-07-09); Linux pending; code-signing pending.**
   - **win-x64** builds on THIS Snapdragon box using the emulated x64 `.venv`
     (AMD64 CPython + `PySide6`+`pyinstaller`) — PyInstaller emits x64 binaries
     under emulation. Verified: PE machine=0x8664, `--save-config` smoke test
     passes. Ships `Scribe-Setup-x64.exe` + `Scribe-Portable-x64.zip`.
   - **Portable mode** (`src/scribe/portable.py`, `tools/make_portable.py`): a
     `portable.txt` marker beside the exe redirects config/logs/models to a
     local `ScribeData` folder — no admin, no profile writes. Built for the x64
     no-admin target.
   - **Linux:** the from-source engine already runs; the NEW QML GUI is
     unverified there. **See BUILDING.md → Linux "Status for future Linux
     workers"** for the exact checklist (tray/overlay/wizard on X11+Wayland,
     hotkey capture on Wayland, AppImage packaging).
   - **Code-signing pipeline DONE (2026-07-09), pending a cert.** `tools/sign.ps1`
     + `tools/make_dev_cert.ps1`, optional `/DSignScribe` in `installer.iss`,
     documented in BUILDING.md. Verified with a self-signed cert. SmartScreen
     only clears once the owner buys an **OV or EV** Authenticode cert (EV clears
     it immediately; OV builds reputation over days/weeks). This is a purchase,
     not code.
5. **Phase 6 NPU — DEPRIORITIZED by owner (2026-07-09).** Likely won't ship: the
   native-ARM64-CPU ONNX path is already fast, and true Hexagon offload needs a
   Qualcomm AI Hub token for marginal gain. Parked, not dropped —
   `tools/build_npu_encoder.py` is ready if ever revisited.
6. **Streaming transcription (candidate, owner interested).** Show/inject words
   while still speaking, for long dictations. Recommended approach: chunked
   Whisper with **local-agreement** (Whisper-Streaming/LocalAgreement-2), opt-in
   (`--stream`), keeping utterance mode as default. Phase A = live-caption in the
   overlay, inject full text on release (safe, all backends, start on
   GPU/x64-CPU-small.en). Phase B = forward-only injection of confirmed tokens.
   Fully offline; no NPU/cloud. See the feasibility notes in chat 2026-07-09.

---

## Engine ↔ UI contract (for reference)

`Scribe(event_sink=cb)` calls `cb(name, payload_dict)` from engine threads:
`model_loading`, `model_loaded{model,backend}`, `recording_started`,
`recording_stopped{duration}`, `transcribing`, `level{rms}`,
`injected{text,elapsed,backend}`, `error{message}`. `AppBridge` enqueues these
and re-emits as Qt properties/signals on the main thread. `scribe.paused` (bool)
pauses the hotkey without stopping the listener. Headless mode passes no sink
and behaves exactly as the CLI always has.

## GitHub

Branch `v1-polish` is pushed to `https://github.com/steevymathew/scribe`.
Open a PR at `https://github.com/steevymathew/scribe/pull/new/v1-polish`.
`gh` CLI is **not** installed here; use the web UI or install gh. Nothing is
merged to `main` yet.
