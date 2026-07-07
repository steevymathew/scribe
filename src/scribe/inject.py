"""Text injection at the cursor (platform-specific).

Linux uses xdotool (X11) or wtype (Wayland); Windows uses pynput's keyboard
controller (Win32 SendInput). All approaches type character-by-character so
the clipboard stays untouched.
"""

import os
import platform
import subprocess

PLATFORM = platform.system()


def _type_linux_x11(text):
    subprocess.run(
        ["xdotool", "type", "--clearmodifiers", "--delay", "12", "--", text],
        check=False,
    )


def _type_linux_wayland(text):
    subprocess.run(["wtype", "--", text], check=False)


def _type_windows(text):
    _type_windows._kb.type(text)


_type_windows._kb = None


def get_typer():
    """Return the text-injection function for this platform/session."""
    if PLATFORM == "Windows":
        from pynput.keyboard import Controller
        _type_windows._kb = Controller()
        return _type_windows

    session = os.environ.get("XDG_SESSION_TYPE", "x11")
    if session == "wayland":
        return _type_linux_wayland
    return _type_linux_x11
