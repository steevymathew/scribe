# App icon / logo

Save the Scribe logo here as **`scribe.png`** (square, ideally 512×512, with the
teal-on-dark mark you provided). The app uses it automatically for:

- the brand mark in the window's nav rail (`Brand.qml`),
- the taskbar / window icon,
- the system-tray icon.

If `scribe.png` is missing, the app falls back to a drawn microphone glyph, so
nothing breaks — but drop the file here to get the real logo everywhere.

`scribe.ico` is the Windows executable / installer icon (Explorer, taskbar,
Alt-Tab, the setup wizard). **It is generated from `scribe.png`** — after
changing the logo, regenerate it:

```
python tools/make_icon.py     # needs Pillow (build-time only)
```

`packaging/scribe.spec` embeds it into `scribe.exe` / `scribe-tray.exe` and
`packaging/installer.iss` uses it for the installer chrome.
