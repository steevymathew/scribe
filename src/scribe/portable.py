"""Portable mode — keep all data next to the executable.

When a marker file named ``portable.txt`` sits beside the (frozen) Scribe
executable, config, logs and the downloaded model cache go into a ``ScribeData``
folder next to the exe instead of the user profile. That makes the shipped
portable ZIP fully self-contained: it runs with no admin rights, writes nothing
to ``%APPDATA%``/``%LOCALAPPDATA%``, and can live on a USB stick.

Detection is a plain marker file so the *installed* build (no marker) keeps the
normal per-user locations. This only sets environment variables the rest of the
app already honours (``SCRIBE_CONFIG_DIR``, ``SCRIBE_LOG_DIR``, ``HF_HOME``), so
nothing else needs to know portable mode exists. Call ``apply()`` once, as early
as possible, before logging is set up or any model is loaded.
"""

import os
import sys

MARKER = "portable.txt"


def base_dir():
    """Folder the app lives in: the exe's dir when frozen, else the repo root."""
    if getattr(sys, "frozen", False):
        return os.path.dirname(sys.executable)
    return os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def is_portable():
    return os.path.isfile(os.path.join(base_dir(), MARKER))


def apply():
    """Enable portable mode if the marker is present. Returns the data dir, or
    None when running normally (installed / from source without the marker)."""
    if not is_portable():
        return None
    data = os.path.join(base_dir(), "ScribeData")
    dirs = {
        "SCRIBE_CONFIG_DIR": os.path.join(data, "config"),
        "SCRIBE_LOG_DIR": os.path.join(data, "logs"),
        "HF_HOME": os.path.join(data, "models"),  # HF model cache → local
    }
    for var, path in dirs.items():
        try:
            os.makedirs(path, exist_ok=True)
        except OSError:
            # A read-only medium is possible; fall back to the default location
            # for whatever we couldn't create rather than failing to launch.
            continue
        os.environ.setdefault(var, path)
    return data
