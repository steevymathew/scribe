# PyInstaller spec — builds BOTH exes into one shared onedir bundle:
#   scribe.exe       console CLI (same flags as `python scribe.py`)
#   scribe-tray.exe  windowed tray app (forces --ui)
#
# Build (from the repo root, in the venv that has this platform's backend):
#   python -m PyInstaller packaging/scribe.spec --noconfirm
#
# The bundle contains whatever backend stack is installed in the building
# venv: the ARM64 venv produces the win-arm64/ONNX build, the x64 venv (plus
# PySide6) produces the win-x64/faster-whisper build. Models are NOT bundled —
# the first-run wizard downloads them (ROADMAP §7 Phase 5).

import os
from PyInstaller.utils.hooks import collect_all

repo = os.path.dirname(os.path.dirname(os.path.abspath(SPECPATH + "/x")))
src = os.path.join(SPECPATH, "..", "src")

datas, binaries, hiddenimports = [], [], []

# onnxruntime_qnn ships the QNN provider + Hexagon DLLs as plain package
# data with no PyInstaller hook — collect it manually when present (ARM64).
try:
    import onnxruntime_qnn  # noqa: F401
    d, b, h = collect_all("onnxruntime_qnn")
    datas += d; binaries += b; hiddenimports += h
except ImportError:
    pass

# ---- QML UI (Qt Quick Controls, Material Dark) ----
# 1) our .qml files + logo assets are loaded at runtime by path — ship as data.
datas += [(os.path.join(src, "scribe", "ui", "qml"), "scribe/ui/qml")]
_assets = os.path.join(src, "scribe", "ui", "assets")
if os.path.isdir(_assets):
    datas += [(_assets, "scribe/ui/assets")]
# 2) PyInstaller's Qt hook bundles only Basic/FluentWinUI3 styles — the
#    Material style module must be collected explicitly (verified via spike),
#    along with the Effects module (MultiEffect shadows).
try:
    import PySide6
    _qml = os.path.join(os.path.dirname(PySide6.__file__), "qml")
    for _mod in ("QtQuick/Controls/Material", "QtQuick/Effects"):
        _srcdir = os.path.join(_qml, *_mod.split("/"))
        if os.path.isdir(_srcdir):
            datas += [(_srcdir, "PySide6/qml/" + _mod)]
    hiddenimports += ["PySide6.QtQml", "PySide6.QtQuick",
                      "PySide6.QtQuickControls2", "PySide6.QtQuickWidgets"]
except ImportError:
    pass

a = Analysis(
    [os.path.join(SPECPATH, "launcher.py")],
    pathex=[src],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    runtime_hooks=[],
    excludes=["torch", "tkinter", "IPython", "matplotlib"],
    noarchive=False,
)

a_tray = Analysis(
    [os.path.join(SPECPATH, "launcher_tray.py")],
    pathex=[src],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    runtime_hooks=[],
    excludes=["torch", "tkinter", "IPython", "matplotlib"],
    noarchive=False,
)

MERGE((a, "scribe", "scribe"), (a_tray, "scribe-tray", "scribe-tray"))

pyz = PYZ(a.pure)
pyz_tray = PYZ(a_tray.pure)

exe = EXE(
    pyz,
    a.scripts,
    # Unbuffered stdio, so output redirects/pipes see status lines live.
    [("u", None, "OPTION")],
    exclude_binaries=True,
    name="scribe",
    console=True,
    upx=False,
)

exe_tray = EXE(
    pyz_tray,
    a_tray.scripts,
    [],
    exclude_binaries=True,
    name="scribe-tray",
    console=False,
    upx=False,
)

coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    exe_tray,
    a_tray.binaries,
    a_tray.datas,
    name="scribe",
    upx=False,
)
