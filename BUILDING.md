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
"More info → Run anyway". A code-signing certificate removes this (see below);
budget for one before wide distribution.

### Code signing

Signing is **optional and additive** — every build above works unsigned. When a
certificate is configured the same pipeline Authenticode-signs the two app exes
and the installers; when it isn't, signing is skipped and the build proceeds.

**Getting a real certificate.** Buy an Authenticode code-signing cert from a
public CA (DigiCert, Sectigo, SSL.com, etc.):

- **OV (Organization Validation)** — cheaper, issued as a `.pfx` you hold. It
  signs fine, but SmartScreen still distrusts a *new* signer until the signed
  binaries accumulate download/run **reputation** (days to weeks of real-world
  installs; there is no shortcut). Some CAs now issue OV on a hardware token /
  cloud HSM ("attested"), which builds reputation faster.
- **EV (Extended Validation)** — pricier, key lives on a FIPS hardware token or
  cloud HSM. SmartScreen grants EV-signed binaries reputation **immediately**,
  so first-run warnings clear from day one. Prefer EV if you distribute widely.

Either way, an unknown/self-signed cert does **not** help — only a CA-issued
OV/EV cert changes what SmartScreen shows.

**Point the pipeline at your `.pfx`.** The signing script reads the cert path
and password from environment variables (never hardcoded):

```powershell
$env:SCRIBE_CERT_PFX  = 'C:\secure\scribe-codesign.pfx'
$env:SCRIBE_CERT_PASS = '<pfx password>'
```

(For an EV token, install the vendor's signing tool and point `SCRIBE_CERT_PFX`
at the token per their docs; the token PIN replaces the password.)

**Sign a release** — after PyInstaller and Inno Setup have produced the
artifacts:

```powershell
# 1. the two app exes in the bundle
tools\sign.ps1 dist\scribe\scribe.exe dist\scribe\scribe-tray.exe
#    (or sign the whole bundle dir: tools\sign.ps1 dist\scribe)

# 2. the installers, after building them
tools\sign.ps1 dist\Scribe-Setup-x64.exe
tools\sign.ps1 dist\Scribe-Setup-arm64.exe
```

Sign the exes **before** building the installer so the signed exes are the ones
packaged, then sign the installer itself. `sign.ps1` verifies each file with
`Get-AuthenticodeSignature` and fails loudly if a signature didn't land. With
`SCRIBE_CERT_PFX` unset it prints a notice and exits 0, so unsigned CI/dev
builds are unaffected.

`packaging\installer.iss` also has an optional compile-time signing block
(`/DSignScribe`) that additionally signs the embedded **uninstaller**; the
post-build `sign.ps1` route above is the lower-risk default. See the comment in
that file.

**Why timestamping matters.** `sign.ps1` counter-signs every signature with a
trusted timestamp (DigiCert's server by default). The timestamp records *when*
the file was signed, so the signature stays **valid after the signing
certificate expires** — without it, every signed binary would "expire" the day
the cert does and start warning again. Never sign without a timestamp.

**Testing the pipeline without a real cert.** Generate a throwaway self-signed
cert to exercise signing + verification end to end:

```powershell
tools\make_dev_cert.ps1 -Password 'test1234'    # writes dist\scribe-dev-cert.pfx
$env:SCRIBE_CERT_PFX  = "$PWD\dist\scribe-dev-cert.pfx"
$env:SCRIBE_CERT_PASS = 'test1234'
tools\sign.ps1 dist\scribe\scribe.exe
```

`Get-AuthenticodeSignature` will show the file **signed** but with an
**untrusted** chain (status `UnknownError`/`NotTrusted`) — that is expected and
correct for a self-signed cert. A self-signed cert proves the plumbing works;
it does **not** remove SmartScreen warnings. Only a purchased OV/EV cert does.

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
