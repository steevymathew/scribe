"""Hotkey names, key matching, and layout quirks."""

import platform

from pynput import keyboard

PLATFORM = platform.system()

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

# Windows virtual key codes for the side-specific modifiers.
#
# pynput on Windows sometimes delivers a bare KeyCode(vk=N) where the named Key
# enum was expected, so `key == target` fails on the right key. The enum's own
# `.value.vk` is not always populated there either, which is why this table
# exists rather than the code simply reading it off the target.
#
# **Consulted on Windows only.** These numbers are not codes anywhere else: on
# X11 the same small integers are Latin-1 keysyms, so 163 is £ rather than Right
# Ctrl, and matching on it unconditionally would fire the hotkey on a currency
# symbol.
_WIN_VK = {
    keyboard.Key.ctrl_r: 163,   # VK_RCONTROL
    keyboard.Key.ctrl_l: 162,   # VK_LCONTROL
    keyboard.Key.alt_r: 165,    # VK_RMENU
    keyboard.Key.alt_l: 164,    # VK_LMENU
    keyboard.Key.shift_r: 161,  # VK_RSHIFT
    keyboard.Key.shift_l: 160,  # VK_LSHIFT
}


def vk_of(key):
    """The virtual key code behind a pynput key object, or None."""
    if hasattr(key, "vk"):                                   # a bare KeyCode
        return key.vk
    if hasattr(key, "value") and hasattr(key.value, "vk"):   # a Key enum
        return key.value.vk
    return None


def match_key(key, target):
    """
    True if a received key event matches the configured target key.

    Three ways it can match, and Windows needs all three:

      - direct equality, which is the normal case everywhere
      - a functional alias — AltGr and Right Alt are one physical key, reported
        under either name depending on the layout
      - the virtual key code, for drivers that hand pynput a bare KeyCode
        instead of the named key
    """
    if key == target:
        return True
    if target in RIGHT_ALT_KEYS and key in RIGHT_ALT_KEYS:
        return True

    key_vk = vk_of(key)
    if key_vk is None:
        return False

    # Aliases are compared by code as well: on the layouts where Right Alt comes
    # through as a bare KeyCode, it comes through with AltGr's code.
    targets = RIGHT_ALT_KEYS if target in RIGHT_ALT_KEYS else {target}
    return any(key_vk in _codes_for(t) for t in targets)


def _codes_for(target):
    """
    Every code that should be accepted as this key.

    Normally one: whatever pynput reports for the key on this platform. On
    Windows the [_WIN_VK] entry is accepted too, for the drivers where the named
    key carries no code of its own.
    """
    codes = set()
    own = vk_of(target)
    if own is not None:
        codes.add(own)
    if PLATFORM == "Windows" and target in _WIN_VK:
        codes.add(_WIN_VK[target])
    return codes


def key_name(key):
    """Human-readable name for a pynput key."""
    return key.name if hasattr(key, "name") else str(key)
