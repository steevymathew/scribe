"""Persisted settings (TOML) with precedence: defaults < config file < CLI flags.

The config file is optional — Scribe runs fine without one, exactly as before.
`--save-config` writes the effective settings so they stick for future runs.
Location: %APPDATA%\\Scribe\\config.toml on Windows, ~/.config/scribe/config.toml
on Linux; override with SCRIBE_CONFIG_DIR (used by tests and portable installs).
"""

import logging
import os
import platform
import tomllib

log = logging.getLogger(__name__)

DEFAULTS = {
    "model": "small.en",
    "heavy_model": "large-v3-turbo",   # boost mode (hold the high-accuracy key)
    "hotkey": "ralt",
    "boost_key": "rshift",
    "device": "auto",
    "beam_size": 1,
    "npu_encoder": None,
    "debug": False,
    "advanced": False,
    "remove_fillers": False,
    "language": "en",
    "dictionary": {},  # {spoken: replacement}, a [dictionary] table in TOML
    # Preferred input device name (Settings → Audio). None = system default.
    # Persisted for the UI; the engine currently records from the default mic.
    "input_device": None,
}


def config_dir():
    override = os.environ.get("SCRIBE_CONFIG_DIR")
    if override:
        return override
    if platform.system() == "Windows":
        base = os.environ.get("APPDATA") or os.path.expanduser("~")
        return os.path.join(base, "Scribe")
    base = os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config")
    return os.path.join(base, "scribe")


def config_path():
    return os.path.join(config_dir(), "config.toml")


def load_file():
    """Settings from the config file; {} if there is none or it's unreadable."""
    path = config_path()
    try:
        with open(path, "rb") as f:
            data = tomllib.load(f)
    except FileNotFoundError:
        return {}
    except Exception:
        log.exception("config file %s is unreadable; ignoring it", path)
        return {}
    unknown = set(data) - set(DEFAULTS)
    if unknown:
        log.warning("config file has unknown keys (ignored): %s", sorted(unknown))
    return {k: v for k, v in data.items() if k in DEFAULTS}


def effective(cli_values):
    """Merge settings: DEFAULTS < config file < CLI flags that were passed.

    `cli_values` maps setting names to the parsed CLI value, or None when the
    flag wasn't given on the command line.
    """
    merged = dict(DEFAULTS)
    merged.update(load_file())
    merged.update({k: v for k, v in cli_values.items() if v is not None})
    # Never hand out DEFAULTS' own mutable dict — a caller mutating the
    # dictionary table must not silently change the defaults.
    if merged.get("dictionary") is DEFAULTS["dictionary"]:
        merged["dictionary"] = {}
    return merged


def _toml_value(v):
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, (int, float)):
        return str(v)
    escaped = str(v).replace("\\", "\\\\").replace('"', '\\"')
    return f'"{escaped}"'


def save(settings):
    """Write settings to the config file. Returns the path.

    Flat keys first, then dict-valued settings as TOML table sections
    (e.g. [dictionary]) — tables must come after the flat keys or TOML
    would attach the flat keys to the last table.
    """
    os.makedirs(config_dir(), exist_ok=True)
    path = config_path()
    lines = ["# Scribe settings — CLI flags override these.\n"]
    tables = []
    for key in DEFAULTS:
        value = settings.get(key)
        if value is None:
            continue
        if isinstance(value, dict):
            tables.append((key, value))
            continue
        lines.append(f"{key} = {_toml_value(value)}\n")
    for key, table in tables:
        if not table:
            continue
        lines.append(f"\n[{key}]\n")
        for k, v in table.items():
            lines.append(f"{_toml_value(str(k))} = {_toml_value(v)}\n")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.writelines(lines)
    log.info("saved config to %s", path)
    return path
