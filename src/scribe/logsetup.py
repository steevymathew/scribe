"""Console/noise policy and logging (ROADMAP §5).

By default the console shows only Scribe's own status lines; every warning,
traceback and download bar from third-party libraries goes to the log file.
--advanced (or SCRIBE_ADVANCED=1) streams everything to the console too.

This module must be imported before any noisy library (transformers,
huggingface_hub, tqdm, onnxruntime) so the environment variables take effect.
`scribe/__init__.py` guarantees that for any `scribe.*` import.
"""

import logging
import logging.handlers
import os
import platform
import sys
import threading

ADVANCED = "--advanced" in sys.argv or os.environ.get("SCRIBE_ADVANCED") == "1"

# Never phone home, regardless of mode (offline is a core constraint).
os.environ.setdefault("HF_HUB_DISABLE_TELEMETRY", "1")

if not ADVANCED:
    os.environ.setdefault("HF_HUB_DISABLE_SYMLINKS_WARNING", "1")
    os.environ.setdefault("HF_HUB_DISABLE_PROGRESS_BARS", "1")
    os.environ.setdefault("HF_HUB_VERBOSITY", "error")
    os.environ.setdefault("TRANSFORMERS_VERBOSITY", "error")
    os.environ.setdefault("TRANSFORMERS_NO_ADVISORY_WARNINGS", "1")
    os.environ.setdefault("TQDM_DISABLE", "1")

log = logging.getLogger("scribe")
LOG_PATH = None  # set by setup_logging()


def log_dir():
    if platform.system() == "Windows":
        base = os.environ.get("LOCALAPPDATA") or os.path.expanduser("~")
        return os.path.join(base, "Scribe", "logs")
    base = os.environ.get("XDG_STATE_HOME") or os.path.expanduser("~/.local/state")
    return os.path.join(base, "scribe", "logs")


def setup_logging(advanced=ADVANCED):
    """Route everything to a rotating log file; console stays clean.

    In advanced mode the full log also streams to the console. Returns the
    log file path. Configuring a root handler here also disables logging's
    last-resort stderr handler, which is what let third-party warnings splat
    onto the console before.
    """
    global LOG_PATH
    # Windowed builds (scribe-tray.exe, console=False) have no console: stdout
    # and stderr are None, so any print()/traceback would raise. Redirect them
    # to a sink — everything of value is in the log file anyway.
    _guard_std_streams()
    os.makedirs(log_dir(), exist_ok=True)
    LOG_PATH = os.path.join(log_dir(), "scribe.log")
    root = logging.getLogger()
    root.setLevel(logging.DEBUG)
    fh = logging.handlers.RotatingFileHandler(
        LOG_PATH, maxBytes=2_000_000, backupCount=5, encoding="utf-8"
    )
    fh.setFormatter(
        logging.Formatter("%(asctime)s %(levelname)-7s %(name)s: %(message)s")
    )
    root.addHandler(fh)
    if advanced:
        ch = logging.StreamHandler()
        ch.setFormatter(logging.Formatter("%(levelname)-7s %(name)s: %(message)s"))
        root.addHandler(ch)
    logging.captureWarnings(True)  # warnings.warn(...) -> log, not console
    # Cap per-request DEBUG chatter so the rotating file holds useful history.
    for noisy in ("httpcore", "httpx", "urllib3", "filelock"):
        logging.getLogger(noisy).setLevel(logging.INFO)

    def _hook(exc_type, exc, tb):
        if issubclass(exc_type, KeyboardInterrupt):
            return
        log.critical("unhandled exception", exc_info=(exc_type, exc, tb))
        friendly_error("Scribe hit an unexpected error and may need a restart")

    sys.excepthook = _hook
    threading.excepthook = lambda a: _hook(a.exc_type, a.exc_value, a.exc_traceback)
    return LOG_PATH


class _NullStream:
    """Minimal write-only stream for windowed builds with no console."""

    def write(self, *_a, **_k):
        return 0

    def flush(self):
        pass

    def isatty(self):
        return False


def _guard_std_streams():
    if sys.stdout is None:
        sys.stdout = _NullStream()
    if sys.stderr is None:
        sys.stderr = _NullStream()


def friendly_error(msg):
    """One calm line for the user; the traceback lives in the log file."""
    where = f" (details: {LOG_PATH})" if LOG_PATH else ""
    print(f"\n  {msg}{where}", file=sys.stderr)
