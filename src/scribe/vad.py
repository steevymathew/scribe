"""Voice activity detection — Silero VAD (ONNX) with an RMS-gate fallback.

Silero VAD is a ~2 MB ONNX model (repo ``onnx-community/silero-vad``,
downloaded once, then fully offline) that runs via onnxruntime on every
backend, including native ARM64. Audio is scored in 512-sample (32 ms)
windows at 16 kHz — the frame size Silero v5 expects — carrying the model's
recurrent state tensor across windows.

If onnxruntime or the model is unavailable the old cheap RMS energy gate
takes over silently; VAD must never crash a dictation.
"""

import logging
import threading

import numpy as np

log = logging.getLogger(__name__)

SILENCE_RMS = 0.005   # fallback gate: clips quieter than this are silence
SPEECH_THRESHOLD = 0.5
WINDOW = 512          # samples per Silero window at 16 kHz (32 ms)
PAD_SAMPLES = 3200    # 200 ms of padding kept around trimmed speech
SILERO_REPO = "onnx-community/silero-vad"
SILERO_FILE = "onnx/model.onnx"

_lock = threading.Lock()
_session = None
_session_failed = False  # don't retry a failed load on every clip


def _rms_is_speech(audio):
    """The original cheap energy gate (fallback when Silero is unavailable)."""
    if len(audio) == 0:
        return False
    return float(np.sqrt(np.mean(np.square(audio)))) >= SILENCE_RMS


def has_energy(audio):
    """True if the clip carries audible energy (RMS gate), independent of the
    Silero model. Used as a safety net so an over-aggressive VAD never drops a
    clip that clearly contains sound."""
    return _rms_is_speech(audio)


def _get_session():
    """The Silero ONNX session, loaded once; None if unavailable."""
    global _session, _session_failed
    with _lock:
        if _session is not None or _session_failed:
            return _session
        try:
            import onnxruntime as ort
            from .hub import hf_fetch
            path = hf_fetch(SILERO_REPO, SILERO_FILE)
            so = ort.SessionOptions()
            so.log_severity_level = 3
            _session = ort.InferenceSession(
                path, sess_options=so, providers=["CPUExecutionProvider"]
            )
            log.info("Silero VAD loaded from %s", path)
        except Exception:
            _session_failed = True
            log.debug("Silero VAD unavailable; falling back to RMS gate",
                      exc_info=True)
        return _session


def _speech_probs(audio):
    """Per-window speech probabilities from Silero; None if VAD unavailable.

    Input: 16 kHz float32 mono in [-1, 1]. The model takes (batch, 512)
    frames plus a recurrent state (2, batch, 128) that we thread through
    the windows; the trailing partial window is zero-padded.
    """
    session = _get_session()
    if session is None:
        return None
    try:
        audio = np.ascontiguousarray(audio, dtype=np.float32).reshape(-1)
        if audio.size == 0:
            return []
        state = np.zeros((2, 1, 128), dtype=np.float32)
        sr = np.array(16000, dtype=np.int64)
        probs = []
        for start in range(0, len(audio), WINDOW):
            chunk = audio[start:start + WINDOW]
            if len(chunk) < WINDOW:
                chunk = np.pad(chunk, (0, WINDOW - len(chunk)))
            prob, state = session.run(
                None, {"input": chunk.reshape(1, -1), "state": state, "sr": sr}
            )
            probs.append(float(prob[0, 0]))
        return probs
    except Exception:
        log.debug("Silero VAD inference failed; falling back to RMS gate",
                  exc_info=True)
        return None


def is_speech(audio):
    """True if the clip plausibly contains speech."""
    probs = _speech_probs(audio)
    if probs is None:
        return _rms_is_speech(audio)
    return any(p > SPEECH_THRESHOLD for p in probs)


def trim_silence(audio):
    """Crop the clip to first..last speech window, with 200 ms of padding.

    Returns an empty array when no window contains speech. When Silero is
    unavailable this degrades to the RMS gate: the clip comes back unchanged
    if it passes the gate, empty otherwise.
    """
    probs = _speech_probs(audio)
    if probs is None:
        return audio if _rms_is_speech(audio) else np.empty(0, dtype=np.float32)
    speech = [i for i, p in enumerate(probs) if p > SPEECH_THRESHOLD]
    if not speech:
        return np.empty(0, dtype=np.float32)
    start = max(0, speech[0] * WINDOW - PAD_SAMPLES)
    end = min(len(audio), (speech[-1] + 1) * WINDOW + PAD_SAMPLES)
    return audio[start:end]
