import contextlib
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from pynput import keyboard  # noqa: E402

from scribe import hotkeys  # noqa: E402


class MatchKey(unittest.TestCase):
    """
    Windows is the reason this is not just `==`.

    pynput there can deliver a bare `KeyCode(vk=N)` where the named key was
    expected, and Right Alt arrives under two different names depending on the
    keyboard layout. Both cases end with the hotkey silently not working, which
    is indistinguishable from Scribe being broken.
    """

    def test_the_ordinary_case_is_equality(self):
        self.assertTrue(hotkeys.match_key(keyboard.Key.ctrl_r, keyboard.Key.ctrl_r))

    def test_a_different_key_does_not_match(self):
        self.assertFalse(hotkeys.match_key(keyboard.Key.ctrl_l, keyboard.Key.ctrl_r))

    def test_altgr_is_right_alt(self):
        self.assertTrue(hotkeys.match_key(keyboard.Key.alt_gr, keyboard.Key.alt_r))
        self.assertTrue(hotkeys.match_key(keyboard.Key.alt_r, keyboard.Key.alt_gr))

    def test_a_bare_key_code_matches_by_virtual_key_code(self):
        """What a Windows driver sends when it does not send the named key."""
        with self.as_windows():
            self.assertTrue(
                hotkeys.match_key(keyboard.KeyCode.from_vk(163), keyboard.Key.ctrl_r)
            )
            self.assertTrue(
                hotkeys.match_key(keyboard.KeyCode.from_vk(165), keyboard.Key.alt_r)
            )

    def test_a_bare_key_code_for_another_key_still_does_not_match(self):
        with self.as_windows():
            self.assertFalse(
                hotkeys.match_key(keyboard.KeyCode.from_vk(162), keyboard.Key.ctrl_r)
            )

    def test_the_windows_codes_are_not_consulted_anywhere_else(self):
        """On X11 those same small numbers are Latin-1 keysyms — 163 is a £."""
        self.assertFalse(
            hotkeys.match_key(keyboard.KeyCode.from_vk(163), keyboard.Key.ctrl_r)
        )

    @contextlib.contextmanager
    def as_windows(self):
        """This host is not Windows, and the fallback these test is Windows-only."""
        was = hotkeys.PLATFORM
        hotkeys.PLATFORM = "Windows"
        try:
            yield
        finally:
            hotkeys.PLATFORM = was

    def test_the_left_and_right_modifiers_are_not_confused(self):
        for left, right in (
            (keyboard.Key.ctrl_l, keyboard.Key.ctrl_r),
            (keyboard.Key.alt_l, keyboard.Key.alt_r),
            (keyboard.Key.shift_l, keyboard.Key.shift_r),
        ):
            self.assertFalse(hotkeys.match_key(left, right), f"{left} matched {right}")

    def test_every_named_hotkey_matches_itself(self):
        for name, key in hotkeys.HOTKEY_MAP.items():
            self.assertTrue(hotkeys.match_key(key, key), name)


class KeyName(unittest.TestCase):
    def test_named_keys_report_their_name(self):
        self.assertEqual("ctrl_r", hotkeys.key_name(keyboard.Key.ctrl_r))

    def test_a_bare_key_code_still_produces_something_printable(self):
        self.assertTrue(hotkeys.key_name(keyboard.KeyCode.from_vk(191)))


if __name__ == "__main__":
    unittest.main()
