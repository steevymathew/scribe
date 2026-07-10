"""The Scribe daemon: hotkey → record → transcribe → inject state machine."""

import logging
import os
import platform
import sys
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
from . import postproc

log = logging.getLogger(__name__)

HEAVY_MODEL = "large-v3-turbo"
PLATFORM = platform.system()


def _out(msg, end="\n"):
    """Write a console status line, safely.

    The windowed build (scribe-tray.exe) has no stdout — sys.stdout is None —
    so a bare print() would raise. Status also goes to the log, which is the
    only diagnostic trail a windowed app has.
    """
    try:
        stream = sys.stdout
        if stream is not None:
            stream.write(msg + end)
            stream.flush()
    except Exception:
        pass


class Scribe:
    def __init__(self, model_size, hotkey, boost_key, device, beam_size=1,
                 npu_encoder=None, debug=False, postproc_settings=None,
                 event_sink=None, heavy_model=HEAVY_MODEL):
        self.model_size = model_size
        self.heavy_model = heavy_model
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
        # Optional UI contract (ROADMAP §7 Phase 3): a thread-safe callable
        # `event_sink(name, payload_dict)` that observes engine state. May be
        # called from the listener thread, the audio thread, or the worker
        # thread. When None (headless), the engine behaves exactly as before.
        self.event_sink = event_sink
        # A UI (tray menu) can pause dictation without stopping the listener;
        # headless mode never touches this.
        self.paused = False
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

    def _emit(self, name, **payload):
        """Send an event to the UI sink, if any. Never lets a broken sink
        (or a UI that went away) take the engine down."""
        sink = self.event_sink
        if sink is None:
            return
        try:
            sink(name, payload)
        except Exception:
            log.exception("event sink failed for %r", name)

    def load_model(self):
        self._emit("model_loading", model=self.model_size)
        self.transcriber = make_transcriber(
            self.device, self.beam_size, self.npu_encoder,
            language=self.language, dictionary=self.dictionary,
        )
        self.transcriber.load(self.model_size)
        self._emit("model_loaded", model=self.model_size,
                   backend=self.transcriber.backend_label)

    def _ensure_heavy_transcriber(self):
        if self.heavy_transcriber is None:
            print("  First use of heavy model — loading (one-time)...")
            self._emit("model_loading", model=self.heavy_model)
            # The heavy model has its own encoder; a custom --npu-encoder only
            # applies to the primary model, so don't pass it here. On the ONNX
            # backend use the int8 build — large-v3-turbo in fp32 is a ~3 GB
            # download and slow on CPU; int8 is far lighter and still accurate.
            # Assign only after a successful load, so a failed load doesn't
            # leave a broken half-initialized transcriber behind.
            tr = make_transcriber(self.device, self.beam_size, precision="_int8",
                                  language=self.language, dictionary=self.dictionary)
            tr.load(self.heavy_model)
            self.heavy_transcriber = tr
            self._emit("model_loaded", model=self.heavy_model, backend=tr.backend_label)
        return self.heavy_transcriber

    def _audio_callback(self, indata, frames, time_info, status):
        if status:
            log.warning("audio stream status: %s", status)
        if self.recording:
            self.audio_chunks.append(indata.copy())
            # Feed the UI a live input level (~16/s) so the orb/overlay react to
            # the real voice. Only ever emitted while actually recording, so the
            # mic is never held open outside push-to-talk.
            if self.event_sink is not None:
                rms = float(np.sqrt(np.mean(np.square(indata))))
                self._emit("level", rms=rms)

    def start_recording(self):
        with self.lock:
            if self.recording:
                return
            self.recording = True
            self.use_heavy = False
            self.audio_chunks = []
            try:
                self.stream = open_input_stream(self._audio_callback)
                _out("  [REC]", end="")
                log.info("recording started")
                self._emit("recording_started")
            except Exception:
                log.exception("microphone stream failed to start")
                friendly_error(
                    "Microphone unavailable — check the input device in your "
                    "system sound settings"
                )
                self.recording = False
                self._emit("error", message="Microphone unavailable — check "
                           "the input device in your system sound settings")

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
            _out(" skip (no audio)")
            log.info("recording stopped: no audio captured")
            self._emit("recording_stopped", duration=0.0)
            return

        audio = np.concatenate(chunks, axis=0).flatten()
        duration = len(audio) / SAMPLE_RATE

        if duration < MIN_AUDIO_SEC:
            _out(f" skip ({duration:.1f}s too short)")
            log.info("recording stopped: %.2fs too short", duration)
            self._emit("recording_stopped", duration=duration)
            return
        if duration > MAX_AUDIO_SEC:
            audio = audio[: int(MAX_AUDIO_SEC * SAMPLE_RATE)]
            duration = MAX_AUDIO_SEC

        _out(f" {duration:.1f}s", end="")
        log.info("recording stopped: %.1fs queued for transcription", duration)
        self._emit("recording_stopped", duration=duration)
        self.work_queue.append((audio, use_heavy))
        self.work_event.set()
        self._emit("transcribing")

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
                    self._emit("error", message="Transcription failed — "
                               "trying the next one fresh")

    def _transcribe_and_type(self, audio, use_heavy=False):
        if use_heavy:
            tr = self._ensure_heavy_transcriber()
        else:
            tr = self.transcriber

        t0 = time.monotonic()
        log.info("transcribe start: %.1fs audio heavy=%s [%s]",
                 len(audio) / SAMPLE_RATE, use_heavy, tr.backend_label)

        # No VAD trimming on the ONNX path: Whisper pads every clip to 30 s
        # internally, so trimming saved almost nothing while risking clipped
        # words on quiet starts/ends — and gating on VAD dropped real speech
        # outright. Transcribe the whole clip; true silence comes back as a
        # [BLANK_AUDIO] annotation that postproc strips to "". (The ct2 path
        # keeps faster-whisper's own built-in VAD.)
        try:
            text = tr.transcribe(audio)
        except Exception:
            log.exception("transcription failed (%s)", tr.backend_label)
            friendly_error("Transcription failed")
            self._emit("error", message="Transcription failed")
            return

        text = postproc.process(text, self.postproc_settings)
        elapsed = time.monotonic() - t0
        log.info("transcribe done in %.1fs: %r", elapsed, text)

        if not text:
            _out(f" -> (silence, {elapsed:.1f}s) [{tr.backend_label}]")
            self._emit("injected", text="", elapsed=elapsed,
                       backend=tr.backend_label, heavy=use_heavy)
            return

        _out(f" -> \"{text}\" ({elapsed:.1f}s) [{tr.backend_label}]")
        try:
            self._type_text(text)
            log.info("typed %d chars", len(text))
        except Exception:
            # A typing failure must not leave the UI stuck — log, tell the
            # user, but still emit the terminal event below.
            log.exception("text injection failed")
            friendly_error("Couldn't type the text — see the log")
        self._emit("injected", text=text, elapsed=elapsed,
                   backend=tr.backend_label, heavy=use_heavy)

    def _set_boost(self, held):
        """Track the boost (high-accuracy) key and notify the UI.

        Emitting only on an actual state change keeps Windows key auto-repeat
        from flooding the sink with duplicate events. When boost engages mid
        recording we upgrade the in-flight clip to the heavy model, matching the
        pre-existing behaviour; boost held before recording is honoured at
        stop_recording() via `self.boost_held`.
        """
        if held == self.boost_held:
            return
        self.boost_held = held
        if held and self.recording:
            self.use_heavy = True
            _out(" +BOOST", end="")
        self._emit("boost", active=held)

    def on_press(self, key):
        if self.paused:
            return
        if self.debug:
            _out(f"  [DBG] press: {key!r} boost_held={self.boost_held}")
        if match_key(key, self.boost_key):
            self._set_boost(True)
        if match_key(key, self.hotkey):
            self.start_recording()

    def on_release(self, key):
        if self.paused:
            # Still track key-ups so a hotkey held across "pause" can't wedge
            # a recording; stop_recording() is a no-op when idle.
            if match_key(key, self.boost_key):
                self._set_boost(False)
            if match_key(key, self.hotkey):
                self.stop_recording()
            return
        if self.debug:
            _out(f"  [DBG] release: {key!r} boost_held={self.boost_held}")
        if match_key(key, self.boost_key):
            self._set_boost(False)
        if match_key(key, self.hotkey):
            self.stop_recording()

    def run(self):
        _out(f"  Platform: {PLATFORM}")
        if PLATFORM == "Linux":
            session = os.environ.get("XDG_SESSION_TYPE", "x11")
            _out(f"  Display: {session}")
        backends = {
            "cuda": "GPU (PyTorch CUDA fp16)",
            "npu": "NPU (ONNX Runtime / QNN — Hexagon, CPU decoder)",
            "cpu": "CPU (CTranslate2 int8)",
        }
        _out("  Backend: " + backends.get(self.device, backends["cpu"]))
        self.load_model()
        self.worker.start()

        hotkey_name = key_name(self.hotkey)
        boost_name = key_name(self.boost_key)
        _out(f"\n  Hold [{hotkey_name}] to dictate (fast, {self.model_size})")
        _out(f"  Hold [{boost_name}] + [{hotkey_name}] to dictate (accurate, {self.heavy_model})")
        _out("  Ctrl+C to quit.\n")

        listener = keyboard.Listener(
            on_press=self.on_press,
            on_release=self.on_release,
        )
        listener.start()
        log.info("ready: model=%s device=%s hotkey=%s boost=%s",
                 self.model_size, self.device, hotkey_name, boost_name)

        try:
            self.shutdown.wait()
        except KeyboardInterrupt:
            pass
        finally:
            _out("\n  Shutting down...")
            self.shutdown.set()
            self.work_event.set()
            listener.stop()
