import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from scribe.postproc import strip_annotations  # noqa: E402


class StripAnnotations(unittest.TestCase):
    def test_normal_text_unchanged(self):
        self.assertEqual(
            strip_annotations("Hello, world."), "Hello, world."
        )

    def test_blank_audio_dropped(self):
        self.assertEqual(strip_annotations("[BLANK_AUDIO]"), "")

    def test_parenthetical_dropped(self):
        self.assertEqual(strip_annotations("(keyboard clacking)"), "")

    def test_music_notes_dropped(self):
        self.assertEqual(strip_annotations("♪ humming ♪"), "")

    def test_leading_annotation_with_text_kept(self):
        # Only pure annotations are dropped; mixed content survives.
        self.assertEqual(
            strip_annotations("[cough] take two"), "[cough] take two"
        )

    def test_whitespace_trimmed(self):
        self.assertEqual(strip_annotations("  hi there  "), "hi there")


if __name__ == "__main__":
    unittest.main()
