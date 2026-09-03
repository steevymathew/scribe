import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from scribe.postproc import (  # noqa: E402
    apply_dictionary,
    normalize_spacing,
    process,
    remove_fillers,
    strip_annotations,
)


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


class RemoveFillers(unittest.TestCase):
    def test_clean_text_unchanged(self):
        self.assertEqual(
            remove_fillers("The quick brown fox jumps."),
            "The quick brown fox jumps.",
        )

    def test_leading_filler_with_comma(self):
        self.assertEqual(remove_fillers("Um, hello there."), "hello there.")

    def test_mid_sentence_filler(self):
        self.assertEqual(
            remove_fillers("I think, um, we should go."),
            "I think, we should go.",
        )

    def test_trailing_filler(self):
        self.assertEqual(remove_fillers("I will do it, uh."), "I will do it.")

    def test_all_filler_variants(self):
        self.assertEqual(
            remove_fillers("um uh uhm erm er done"), "done"
        )

    def test_case_insensitive(self):
        self.assertEqual(remove_fillers("UM, sure. Uh, fine."), "sure. fine.")

    def test_words_containing_fillers_untouched(self):
        # No substring matches: summer/-er/uhm-like words must survive.
        self.assertEqual(
            remove_fillers("Her summer error costume shudder."),
            "Her summer error costume shudder.",
        )


class ApplyDictionary(unittest.TestCase):
    def test_empty_dictionary_noop(self):
        self.assertEqual(apply_dictionary("hello jira", {}), "hello jira")

    def test_casing_fix(self):
        self.assertEqual(
            apply_dictionary("we track it in jira now", {"jira": "Jira"}),
            "we track it in Jira now",
        )

    def test_case_insensitive_match(self):
        self.assertEqual(
            apply_dictionary("JIRA and jIrA", {"jira": "Jira"}),
            "Jira and Jira",
        )

    def test_whole_word_only(self):
        # "jira" inside another word must not match.
        self.assertEqual(
            apply_dictionary("jirafication stays", {"jira": "Jira"}),
            "jirafication stays",
        )

    def test_multi_word_spoken_form(self):
        self.assertEqual(
            apply_dictionary(
                "ping me on postgre sequel", {"postgre sequel": "PostgreSQL"}
            ),
            "ping me on PostgreSQL",
        )

    def test_replacement_verbatim(self):
        # Replacement text is literal, even with regex-special characters.
        self.assertEqual(
            apply_dictionary("see my site", {"my site": r"C:\www\$ite"}),
            r"see C:\www\$ite",
        )


class NormalizeSpacing(unittest.TestCase):
    def test_clean_text_unchanged(self):
        self.assertEqual(
            normalize_spacing("Hello, world. Fine!"), "Hello, world. Fine!"
        )

    def test_double_spaces_collapsed(self):
        self.assertEqual(normalize_spacing("a  b   c"), "a b c")

    def test_space_before_punctuation_removed(self):
        self.assertEqual(normalize_spacing("wait , what ?"), "wait, what?")

    def test_strips_ends(self):
        self.assertEqual(normalize_spacing("  hi  "), "hi")


class Process(unittest.TestCase):
    def test_default_settings_noop_for_clean_text(self):
        text = "The quick brown fox, obviously, jumps over the lazy dog."
        self.assertEqual(process(text), text)
        self.assertEqual(process(text, None), text)
        self.assertEqual(
            process(text, {"remove_fillers": False, "dictionary": {}}), text
        )

    def test_annotation_stripped_by_default(self):
        self.assertEqual(process("[BLANK_AUDIO]"), "")

    def test_fillers_only_when_enabled(self):
        text = "Um, deploy it."
        self.assertEqual(process(text, {"remove_fillers": False}), text)
        self.assertEqual(
            process(text, {"remove_fillers": True}), "deploy it."
        )

    def test_full_pipeline_order(self):
        out = process(
            "um, file the jira ticket , please",
            {"remove_fillers": True, "dictionary": {"jira": "Jira"}},
        )
        self.assertEqual(out, "file the Jira ticket, please")

    def test_empty_input(self):
        self.assertEqual(process(""), "")


if __name__ == "__main__":
    unittest.main()
