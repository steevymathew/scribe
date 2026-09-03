"""PyInstaller entry point for scribe-tray.exe (windowed build).

Forces --ui so double-clicking the Start-menu entry gives the tray app;
power users who want the console CLI run scribe.exe instead.
"""

import sys

if "--ui" not in sys.argv:
    sys.argv.append("--ui")

from scribe.__main__ import main

if __name__ == "__main__":
    main()
