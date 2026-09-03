"""Transcript post-processing pipeline (ROADMAP Phase 4).

`process(text, settings)` applies, in order:

  1. strip_annotations   — always: drop pure non-speech annotations
  2. remove_fillers      — only when settings["remove_fillers"] (default off)
  3. apply_dictionary    — settings["dictionary"] {spoken: replacement}
  4. normalize_spacing   — always: collapse doubled spaces, fix " ,"-style gaps

All steps are pure text functions (no models); with default settings the
pipeline is a no-op for clean transcripts.
"""

import re

DEFAULT_SETTINGS = {"remove_fillers": False, "dictionary": {}}

# Conservative filler list: whole words only, so "her"/"summer"/"error" are
# never touched. "you know" is deliberately excluded until tested (ROADMAP §7.4).
_FILLER_RE = re.compile(r"\b(?:um|uh|uhm|erm|er)\b,?\s*", re.IGNORECASE)


def strip_annotations(text):
    """Drop Whisper's non-speech annotations like [BLANK_AUDIO] or (music).

    Returns "" when the entire transcript is one annotation; otherwise returns
    the text unchanged.
    """
    text = text.strip()
    if text.startswith(("[", "(", "♪")) and text.endswith(("]", ")", "♪")):
        return ""
    return text


def remove_fillers(text):
    """Remove filler words (um, uh, uhm, erm, er) plus their trailing comma.

    Word-boundary matches only. Leftover comma-before-punctuation artifacts
    (e.g. "I will, ." after a trailing filler) are cleaned up here; doubled
    spaces are left for normalize_spacing.
    """
    text = _FILLER_RE.sub("", text)
    text = re.sub(r",\s*([.,!?;:])", r"\1", text)  # "word, ." -> "word."
    return text.strip()


def apply_dictionary(text, dictionary):
    """Replace spoken forms with the user's preferred spellings.

    Keys match case-insensitively on whole words; the replacement is used
    verbatim (so "jira" -> "Jira" fixes casing anywhere in the sentence).
    """
    for spoken, replacement in dictionary.items():
        if not spoken:
            continue
        pattern = r"\b" + re.escape(str(spoken)) + r"\b"
        # lambda replacement so backslashes in the user's text stay verbatim
        text = re.sub(pattern, lambda _m, r=str(replacement): r, text,
                      flags=re.IGNORECASE)
    return text


def normalize_spacing(text):
    """Collapse doubled spaces and remove spaces before punctuation."""
    text = re.sub(r"[ \t]{2,}", " ", text)
    text = re.sub(r"\s+([.,!?;:])", r"\1", text)
    return text.strip()


def process(text, settings=None):
    """Run the full pipeline. `settings=None` means defaults (near-no-op)."""
    if settings is None:
        settings = DEFAULT_SETTINGS
    text = strip_annotations(text)
    if settings.get("remove_fillers"):
        text = remove_fillers(text)
    dictionary = settings.get("dictionary") or {}
    if dictionary:
        text = apply_dictionary(text, dictionary)
    return normalize_spacing(text)
