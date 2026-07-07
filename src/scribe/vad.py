"""Voice activity detection.

Currently a simple RMS energy gate. ROADMAP Phase 4 replaces the internals
with Silero VAD (ONNX) behind the same interface; callers won't change.
"""

import numpy as np

SILENCE_RMS = 0.005  # clips quieter than this are treated as silence


def is_speech(audio):
    """True if the clip plausibly contains speech (cheap energy gate)."""
    return float(np.sqrt(np.mean(np.square(audio)))) >= SILENCE_RMS
