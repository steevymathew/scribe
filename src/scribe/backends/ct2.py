"""CPU backend — faster-whisper (CTranslate2 int8). Runs anywhere, no GPU."""

import logging
import time

from .base import Transcriber

log = logging.getLogger(__name__)


class CPUTranscriber(Transcriber):
    """CTranslate2 int8 — runs anywhere, no GPU required."""

    def __init__(self, beam_size=1):
        self._model = None
        self._name = ""
        self._beam_size = beam_size

    def load(self, model_name):
        from faster_whisper import WhisperModel

        self._name = model_name
        t0 = time.monotonic()
        self._model = WhisperModel(model_name, device="cpu", compute_type="int8")
        print(f"  Loaded '{model_name}' on CPU (int8) in {time.monotonic() - t0:.1f}s")

    def transcribe(self, audio):
        segments, _ = self._model.transcribe(
            audio,
            language="en",
            beam_size=self._beam_size,
            vad_filter=True,
            vad_parameters=dict(min_silence_duration_ms=500, speech_pad_ms=200),
        )
        return " ".join(s.text.strip() for s in segments if s.text.strip())

    @property
    def backend_label(self):
        return f"{self._name}/cpu"
