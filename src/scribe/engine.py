"""The Scribe daemon: hotkey → record → transcribe → inject state machine."""

import logging
import os
import platform
import threading
import time
from collections import deque

import numpy as np
from pynput import keyboard

from .audio import MAX_AUDIO_SEC, MIN_AUDIO_SEC, SAMPLE_RATE, open_input_stream
from .backends import Transcriber, make_transcriber
from .hotkeys import key_name, match_key
from .inject import get_typer
from .logsetup import friendly_error
from . import postproc, vad

log = logging.getLogger(__name__)

HEAVY_MODEL = "large-v3-turbo"
PLATFORM = platform.system()


class Scribe:
    def __init__(self, model_size, hotkey, boost_key, device, beam_size=1,
                 npu_encoder=None, debug=False, postproc_settings=None):
        self.model_size = model_size
        self.hotkey = hotkey
        self.boost_key = boost_key
        self.device = device
        self.beam_size = beam_size
        self.npu_encoder = npu_encoder
        self.debug = debug
        # None = defaults = near-no-op pipeline; also carries the language
        # and custom-dictionary settings the backends consume (ROADMAP §7.4).
        self.postproc_settings = postproc_settings
        _pp = postproc_settings or {}
        self.language = _pp.get("language", "en")
        self.dictionary = _pp.get("dictionary") or {}
        self._type_text = get_typer()

        self.transcriber: Transcriber | None = None
        self.heavy_transcriber: Transcriber | None = None
        self.recording = False
        self.use_heavy = False
        self.boost_held = False
        self.audio_chunks: list[np.ndarray] = []
        self.stream = None
        self.lock = threading.Lock()
        self.worker = threading.Thread(target=self._worker_loop, daemon=True)
        self.work_queue: deque = deque()
        self.work_event = threading.Event()
        self.shutdown = threading.Event()

    def load_model(self):
        self.transcriber = make_transcriber(
            self.device, self.beam_size, self.npu_encoder,
            language=self.language, dictionary=self.dictionary,
        )
        self.transcriber.load(self.model_size)

    def _ensure_heavy_transcriber(self):
        if self.heavy_transcriber is None:
            print("  First use of heavy model — loading (one-time)...")
            # The heavy model has its own encoder; a custom --npu-encoder only
            # applies to the primary model, so don't pass it here. On the ONNX
            # backend use the int8 build — large-v3-turbo in fp32 is a ~3 GB
            # download and slow on CPU; int8 is far lighter and still accurate.
            # Assign only after a successful load, so a failed load doesn't
            # leave a broken half-initialized transcriber behind.
            tr = make_transcriber(self.device, self.beam_size, precision="_int8",
                                  language=self.language, dictionary=self.dictionary)
            tr.load(HEAVY_MODEL)
            self.heavy_transcriber = tr
        return self.heavy_transcriber

    def _audio_callback(self, indata, frames, time_info, status):
        if status:
            log.warning("audio stream status: %s", status)
        if self.recording:
            self.audio_chunks.append(indata.copy())

    def start_recording(self):
        with self.lock:
            if self.recording:
                return
            self.recording = True
            self.use_heavy = False
            self.audio_chunks = []
            try:
                self.stream = open_input_stream(self._audio_callback)
                print("  [REC]", end="", flush=True)
            except Exception:
                log.exception("microphone stream failed to start")
                friendly_error(
                    "Microphone unavailable — check the input device in your "
                    "system sound settings"
                )
                self.recording = False

    def stop_recording(self):
        with self.lock:
            if not self.recording:
                return
            self.recording = False
            use_heavy = self.use_heavy or self.boost_held
            if self.stream:
                try:
                    self.stream.stop()
                    self.stream.close()
                except Exception:
                    log.debug("audio stream close failed", exc_info=True)
                self.stream = None

            chunks = self.audio_chunks
            self.audio_chunks = []

        if not chunks:
            print(" skip (no audio)")
            return

        audio = np.concatenate(chunks, axis=0).flatten()
        duration = len(audio) / SAMPLE_RATE

        if duration < MIN_AUDIO_SEC:
            print(f" skip ({duration:.1f}s too short)")
            return
        if duration > MAX_AUDIO_SEC:
            audio = audio[: int(MAX_AUDIO_SEC * SAMPLE_RATE)]
            duration = MAX_AUDIO_SEC

        print(f" {duration:.1f}s", end="", flush=True)
        self.work_queue.append((audio, use_heavy))
        self.work_event.set()

    def _worker_loop(self):
        while not self.shutdown.is_set():
            self.work_event.wait()
            self.work_event.clear()
            while self.work_queue:
                audio, use_heavy = self.work_queue.popleft()
                try:
                    self._transcribe_and_type(audio, use_heavy)
                except Exception:
                    # Never let one bad clip (or a failed model load) take the
                    # worker down with a raw traceback — log it, tell the user,
                    # stay alive for the next dictation.
                    log.exception("transcription worker error")
                    friendly_error("Transcription failed — trying the next one fresh")

    def _transcribe_and_type(self, audio, use_heavy=False):
        if use_heavy:
            tr = self._ensure_heavy_transcriber()
        else:
            tr = self.transcriber

        t0 = time.monotonic()

        # Pre-trim leading/trailing silence on the ONNX path only: Whisper
        # hallucinates on padding-heavy clips there. The ct2 path keeps
        # faster-whisper's own built-in VAD; leave it alone (ROADMAP §7.4).
        if self.device == "npu":
            audio = vad.trim_silence(audio)
            if audio.size == 0:
                elapsed = time.monotonic() - t0
                print(f" -> (silence, {elapsed:.1f}s) [{tr.backend_label}]")
                return

        try:
            text = tr.transcribe(audio)
        except Exception:
            log.exception("transcription failed (%s)", tr.backend_label)
            friendly_error("Transcription failed")
            return

        text = postproc.process(text, self.postproc_settings)
        elapsed = time.monotonic() - t0

        if not text:
            print(f" -> (silence, {elapsed:.1f}s) [{tr.backend_label}]")
            return

        print(f" -> \"{text}\" ({elapsed:.1f}s) [{tr.backend_label}]")
        if text:
            self._type_text(text)

    def on_press(self, key):
        if self.debug:
            print(f"  [DBG] press: {key!r} boost_held={self.boost_held}")
        if match_key(key, self.boost_key):
            self.boost_held = True
            if self.recording:
                self.use_heavy = True
                print(" +BOOST", end="", flush=True)
        if match_key(key, self.hotkey):
            self.start_recording()

    def on_release(self, key):
        if self.debug:
            print(f"  [DBG] release: {key!r} boost_held={self.boost_held}")
        if match_key(key, self.boost_key):
            self.boost_held = False
        if match_key(key, self.hotkey):
            self.stop_recording()

    def run(self):
        print(f"  Platform: {PLATFORM}")
        if PLATFORM == "Linux":
            session = os.environ.get("XDG_SESSION_TYPE", "x11")
            print(f"  Display: {session}")
        backends = {
            "cuda": "GPU (PyTorch CUDA fp16)",
            "npu": "NPU (ONNX Runtime / QNN — Hexagon, CPU decoder)",
            "cpu": "CPU (CTranslate2 int8)",
        }
        print("  Backend:", backends.get(self.device, backends["cpu"]))
        self.load_model()
        self.worker.start()

        hotkey_name = key_name(self.hotkey)
        boost_name = key_name(self.boost_key)
        print(f"\n  Hold [{hotkey_name}] to dictate (fast, {self.model_size})")
        print(f"  Hold [{boost_name}] + [{hotkey_name}] to dictate (accurate, {HEAVY_MODEL})")
        print(f"  Ctrl+C to quit.\n")

        listener = keyboard.Listener(
            on_press=self.on_press,
            on_release=self.on_release,
        )
        listener.start()

        try:
            self.shutdown.wait()
        except KeyboardInterrupt:
            pass
        finally:
            print("\n  Shutting down...")
            self.shutdown.set()
            self.work_event.set()
            listener.stop()
