"""Transcript post-processing (grows into the full pipeline — ROADMAP Phase 4)."""


def strip_annotations(text):
    """Drop Whisper's non-speech annotations like [BLANK_AUDIO] or (music).

    Returns "" when the entire transcript is one annotation; otherwise returns
    the text unchanged.
    """
    text = text.strip()
    if text.startswith(("[", "(", "♪")) and text.endswith(("]", ")", "♪")):
        return ""
    return text
