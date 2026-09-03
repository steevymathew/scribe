"""Package the frozen bundle in dist/scribe/ as a portable ZIP.

Run AFTER PyInstaller (and after building the installer, so the installer stays
non-portable):

    python tools/make_portable.py [x64|arm64]

Adds a ``portable.txt`` marker to the bundle — which switches the app into
portable mode (config/logs/models in a ScribeData folder beside the exe; see
src/scribe/portable.py) — zips the bundle under a top-level ``Scribe/`` folder,
then removes the loose marker so a later installer build isn't accidentally
portable. The ZIP retains the marker, so unzip-and-run is portable.
"""

import os
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
BUNDLE = os.path.join(ROOT, "dist", "scribe")

MARKER_TEXT = (
    "Scribe portable mode.\n\n"
    "Settings, logs and downloaded speech models live in the ScribeData folder\n"
    "next to scribe-tray.exe — nothing is written to your Windows user profile,\n"
    "and no admin rights are needed. Delete this file to use the normal\n"
    "per-user locations instead.\n"
)


def main():
    arch = sys.argv[1] if len(sys.argv) > 1 else "x64"
    out = os.path.join(ROOT, "dist", f"Scribe-Portable-{arch}.zip")

    if not os.path.isdir(BUNDLE):
        sys.exit(f"no bundle at {BUNDLE} — run PyInstaller first")

    marker = os.path.join(BUNDLE, "portable.txt")
    with open(marker, "w", encoding="utf-8") as f:
        f.write(MARKER_TEXT)

    if os.path.exists(out):
        os.remove(out)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for dirpath, _dirs, files in os.walk(BUNDLE):
            for name in files:
                full = os.path.join(dirpath, name)
                rel = os.path.relpath(full, BUNDLE)
                z.write(full, os.path.join("Scribe", rel))

    # Keep the loose bundle clean for subsequent installer builds — the ZIP
    # already carries the marker.
    os.remove(marker)
    print(f"wrote {out} ({round(os.path.getsize(out) / 1e6, 1)} MB)")


if __name__ == "__main__":
    main()
