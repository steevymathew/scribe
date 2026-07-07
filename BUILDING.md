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
