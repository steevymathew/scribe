"""Opt-in on-device transcript history (privacy-first, off by default).

When the user turns History on (Settings → History), each injected transcript is
appended to a plain JSON file in the config dir — newest-first and capped — so it
never grows without bound. Nothing here talks to the network; the file lives only
on this device.

Pure-Python by design: no Qt, no engine imports. That keeps it unit-testable and
usable headless, and keeps Qt confined to scribe.ui (enforced by tests). Every
disk operation is best-effort — a missing, corrupt, or locked file logs and
returns an empty list / no-ops rather than raising into the caller (the UI must
never crash on a bad history file).
"""

import json
import logging
import os

from . import config

log = logging.getLogger(__name__)

CAP = 500  # keep at most this many entries (newest-first); older ones drop off


def history_path():
    return os.path.join(config.config_dir(), "history.json")


def load():
    """Return the saved history (newest-first), or [] if none/unreadable."""
    path = history_path()
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
    except FileNotFoundError:
        return []
    except Exception:
        log.exception("history file %s is unreadable; ignoring it", path)
        return []
    if not isinstance(data, list):
        log.warning("history file %s is not a list; ignoring it", path)
        return []
    return data[:CAP]


def prepend(entries, entry):
    """Return a new list with `entry` at the front, capped to CAP (pure)."""
    return ([entry] + list(entries))[:CAP]


def save(entries):
    """Write entries (newest-first, capped) to disk. Best-effort: never raises."""
    path = history_path()
    try:
        os.makedirs(config.config_dir(), exist_ok=True)
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            json.dump(list(entries)[:CAP], f, ensure_ascii=False, indent=0)
    except Exception:
        log.exception("saving history to %s failed", path)


def clear():
    """Delete the history file. Best-effort: never raises."""
    path = history_path()
    try:
        os.remove(path)
    except FileNotFoundError:
        pass
    except Exception:
        log.exception("clearing history file %s failed", path)
