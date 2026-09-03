"""Generate the Windows app icon (scribe.ico) from the logo (scribe.png).

Run whenever the logo changes:

    python tools/make_icon.py

Produces a multi-size .ico (16–256 px) next to the PNG in
src/scribe/ui/assets/. PyInstaller embeds it into scribe.exe / scribe-tray.exe
(see packaging/scribe.spec) and Inno Setup uses it for the installer chrome
(see packaging/installer.iss). Needs Pillow (build-time only; not an app
runtime dependency): pip install Pillow.
"""

import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "src", "scribe", "ui", "assets")
SRC = os.path.join(ASSETS, "scribe.png")
DST = os.path.join(ASSETS, "scribe.ico")

# Windows uses these; 16/32/48 render in the taskbar and Explorer lists, 256
# for the large tile. All embedded in one .ico.
SIZES = [16, 24, 32, 48, 64, 128, 256]


def main():
    try:
        from PIL import Image
    except ImportError:
        sys.exit("Pillow is required: pip install Pillow")

    if not os.path.isfile(SRC):
        sys.exit(f"Logo not found: {SRC}")

    img = Image.open(SRC).convert("RGBA")

    # Trim the transparent margin so the mark fills the icon — otherwise the
    # padded logo looks tiny at 16/32 px.
    bbox = img.getbbox()
    if bbox:
        img = img.crop(bbox)

    # Center on a transparent square so non-square marks aren't distorted.
    w, h = img.size
    side = max(w, h)
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.paste(img, ((side - w) // 2, (side - h) // 2), img)

    canvas.save(DST, format="ICO", sizes=[(s, s) for s in SIZES])
    print(f"wrote {DST} ({os.path.getsize(DST)} bytes; sizes {SIZES})")


if __name__ == "__main__":
    main()
