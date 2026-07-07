"""GPU backend — openai-whisper (PyTorch CUDA fp16) for NVIDIA GPUs."""

import logging
import time

from .base import Transcriber

log = logging.getLogger(__name__)


class GPUTranscriber(Transcriber):
    """PyTorch CUDA fp16 — fast inference on NVIDIA GPUs."""

    def __init__(self, beam_size=1):
        self._model = None
        self._name = ""
        self._beam_size = beam_size

    def load(self, model_name):
        import whisper

        self._name = model_name
        t0 = time.monotonic()
        self._model = whisper.load_model(model_name, device="cuda")
        print(f"  Loaded '{model_name}' on CUDA (fp16) in {time.monotonic() - t0:.1f}s")

    def transcribe(self, audio):
        result = self._model.transcribe(
            audio,
            language="en",
            beam_size=self._beam_size,
            fp16=True,
            no_speech_threshold=0.6,
            condition_on_previous_text=False,
        )
        return result["text"].strip()

    @property
    def backend_label(self):
        return f"{self._name}/cuda"
