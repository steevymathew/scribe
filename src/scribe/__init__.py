"""Scribe — fully offline push-to-talk dictation.

Hold a key, speak, release. Text appears at your cursor.
Nothing leaves your machine. No cloud. No telemetry. No network calls.

Runs on Linux (X11/Wayland) and Windows.
"""

# Importing logsetup first applies the console noise policy (env vars that
# quiet third-party libraries) before any of them can be imported.
from . import logsetup  # noqa: F401

__version__ = "0.9.0"
