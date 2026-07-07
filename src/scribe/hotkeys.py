"""Hotkey names, key matching, and layout quirks."""

from pynput import keyboard

HOTKEY_MAP = {
    "rctrl": keyboard.Key.ctrl_r,
    "lctrl": keyboard.Key.ctrl_l,
    "ralt": keyboard.Key.alt_r,
    "altgr": keyboard.Key.alt_gr,
    "lalt": keyboard.Key.alt_l,
    "rshift": keyboard.Key.shift_r,
    "scroll_lock": keyboard.Key.scroll_lock,
    "pause": keyboard.Key.pause,
    "f13": keyboard.KeyCode.from_vk(191),
}

# Right Alt is reported as alt_r on most layouts but as alt_gr on some
# (notably several Windows keyboard layouts). Treat both as "Right Alt".
RIGHT_ALT_KEYS = frozenset({keyboard.Key.alt_r, keyboard.Key.alt_gr})


def match_key(key, target):
    """True if a received key event matches the configured target key."""
    if key == target:
        return True
    if target in RIGHT_ALT_KEYS and key in RIGHT_ALT_KEYS:
        return True
    if hasattr(key, "vk") and hasattr(target, "value") and hasattr(target.value, "vk"):
        return key.vk == target.value.vk
    return False


def key_name(key):
    """Human-readable name for a pynput key."""
    return key.name if hasattr(key, "name") else str(key)
