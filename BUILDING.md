# Building Scribe installers

End users should download an installer from Releases. This file is for
maintainers producing those installers. Everything here runs offline except
pip/model downloads.

## Windows (ARM64 / Snapdragon X, or x64)

The bundle contains whatever backend the *building* venv has, so build each
architecture on (or with) its matching Python:

| Target | Python | Backend stack in venv |
|---|---|---|
| win-arm64 | ARM64 CPython 3.11+ | `requirements-npu.txt` + `PySide6` |
| win-x64 | x64 CPython 3.11+ | `requirements.txt` + `PySide6` |

Steps (from the repo root, inside the matching venv):

```powershell
pip install pyinstaller
python -m PyInstaller packaging\scribe.spec --noconfirm
# → dist\scribe\ with scribe.exe (console CLI) and scribe-tray.exe (tray app)
```

> **Building win-x64 on a Snapdragon (ARM64) dev box.** You don't need a
> separate x64 machine. Windows-on-ARM runs x64 Python under emulation, so an
> **x64 venv** (here `.venv`, an AMD64 CPython) with `PySide6 + pyinstaller`
> installed produces a genuine x64 build — PyInstaller emits binaries matching
> the interpreter's architecture. It's slower (emulated) but correct. The spec
> auto-skips `onnxruntime_qnn` when absent, so the x64 bundle ships the
> faster-whisper CPU backend. Smoke-test with `dist\scribe\scribe.exe
> --save-config` (exits without loading a model) before packaging; full
> transcription is best verified on a real x64 machine.

### App icon

The exe/installer icon (`src/scribe/ui/assets/scribe.ico`) is generated from the
logo — regenerate it whenever the logo changes:

```powershell
python tools\make_icon.py    # needs Pillow (build-time only)
```

### Portable build (no installer, no admin)

For a machine where you can't (or don't want to) run an installer, ship the
bundle as a **portable ZIP**. After PyInstaller, and **after** building the
installer (so the installer stays non-portable):

```powershell
python tools\make_portable.py x64      # or arm64
# → dist\Scribe-Portable-x64.zip
```

This drops a `portable.txt` marker into the bundle and zips it under a top-level
`Scribe\` folder. The marker switches the app into **portable mode**
(`src/scribe/portable.py`): config, logs and the downloaded model cache all go
into a `ScribeData\` folder next to `scribe-tray.exe` instead of the user
profile — nothing is written to `%APPDATA%`/`%LOCALAPPDATA%`, no admin needed,
and it runs from a USB stick. The user just unzips and double-clicks
`scribe-tray.exe`.

Smoke-test the bundle before packaging:

```powershell
dist\scribe\scribe.exe --device npu   # (or cpu on x64) — must reach "Hold [...]"
```

Installer (needs [Inno Setup 6](https://jrsoftware.org/isinfo.php); free):

```powershell
iscc /DTargetArch=arm64 packaging\installer.iss   # Snapdragon
iscc packaging\installer.iss                      # x64
# → dist\Scribe-Setup-<arch>.exe
```

The installer is per-user (no admin), adds Start-menu entries, an optional
sign-in autostart, and a clean uninstaller. Models are *not* bundled — the
first-run wizard downloads them once (~500 MB for small.en; the optional
high-accuracy model is ~1 GB more).

**Unsigned binaries:** Windows SmartScreen will warn on first run. Users click
"More info → Run anyway". A code-signing certificate removes this; budget for
one before wide distribution.

## Linux (x64)

> **Status for future Linux workers (read this first).** The **engine already
> runs on Linux from source today** — `./setup.sh` then `./scribe` (CPU) or
> `./scribe-gpu` (NVIDIA CUDA), plus the `scribe.service` systemd unit. That
> path (headless CLI + text injection via `xdotool`/`wtype`) is the original,
> working Linux story and needs nothing new to *run*.
>
> What is **not yet verified on Linux** and is the actual remaining work:
> 1. **The new QML GUI (`--ui`)** has only been exercised on Windows. PySide6 is
>    cross-platform so it should come up on X11; the known rough spots are the
>    **system tray** (needs a StatusNotifierItem host — fine on KDE/most GNOME
>    with an extension) and the **frameless always-on-top overlay pill** and
>    **first-run wizard** on **Wayland** (compositors restrict positioning and
>    global input). Smoke-test `python scribe.py --ui` on both X11 and Wayland;
>    if the overlay can't position on Wayland, degrade gracefully (tray-only).
> 2. **Global hotkey capture** (`pynput`) on Wayland is compositor-dependent —
>    verify Right-Alt push-to-talk actually fires; document any per-desktop
>    caveats.
> 3. **Portable mode** (`src/scribe/portable.py`) already targets Linux paths
>    (`SCRIBE_CONFIG_DIR`/`SCRIBE_LOG_DIR`/`HF_HOME`); untested there.
> 4. **AppImage packaging** below is unwritten beyond the recipe sketch.
>
> In short: to *use* Scribe on Linux, the from-source engine works now. To ship
> the polished GUI, the list above is the checklist.

AppImage is the primary format (single file, no install, works across
distros). Recipe:

```bash
python -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt PySide6 pyinstaller
python -m PyInstaller packaging/scribe.spec --noconfirm
# dist/scribe/ contains scribe and scribe-tray
```

Then wrap `dist/scribe/` with `appimagetool` using an AppDir whose AppRun
launches `scribe-tray`, plus a `.desktop` file (`Categories=Utility;`) and an
icon. Remember the runtime deps callout: users still need `xdotool` (X11) or
`wtype` (Wayland) — document it on the download page; the AppImage cannot
bundle them portably.

Autostart on Linux: the tray app's Settings can write
`~/.config/autostart/scribe.desktop` (standard XDG autostart). The systemd
unit (`scribe.service`) remains for headless users.

## What must be true before tagging a release

1. `python -m unittest discover -s tests` green on the building venv.
2. Frozen `scribe.exe --device <native>` reaches the ready banner with a
   clean stderr.
3. Fresh-profile wizard run completes and dictation types into Notepad.
4. Uninstall leaves no autostart entry and no Start-menu links.
