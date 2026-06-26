#!/usr/bin/env python3
"""
Scribe — fully offline push-to-talk dictation.

Hold a key, speak, release. Text appears at your cursor.
Nothing leaves your machine. No cloud. No telemetry. No network calls.

Runs on Linux (X11/Wayland) and Windows.
"""

import argparse
import os
import platform
import signal
import subprocess
import sys
import threading
import time
from abc import ABC, abstractmethod
from collections import deque

import numpy as np
import sounddevice as sd
from pynput import keyboard


SAMPLE_RATE = 16000
MIN_AUDIO_SEC = 0.3
MAX_AUDIO_SEC = 120
HEAVY_MODEL = "large-v3-turbo"
PLATFORM = platform.system()


# ---------------------------------------------------------------------------
# Transcription backends
# ---------------------------------------------------------------------------

class Transcriber(ABC):
    @abstractmethod
    def load(self, model_name: str) -> None: ...

    @abstractmethod
    def transcribe(self, audio: np.ndarray) -> str: ...

    @property
    @abstractmethod
    def backend_label(self) -> str: ...


class CPUTranscriber(Transcriber):
    """CTranslate2 int8 — runs anywhere, no GPU required."""

    def __init__(self):
        self._model = None
        self._name = ""

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
            beam_size=5,
            vad_filter=True,
            vad_parameters=dict(min_silence_duration_ms=500, speech_pad_ms=200),
        )
        return " ".join(s.text.strip() for s in segments if s.text.strip())

    @property
    def backend_label(self):
        return f"{self._name}/cpu"


class GPUTranscriber(Transcriber):
    """PyTorch CUDA fp16 — fast inference on NVIDIA GPUs."""

    def __init__(self):
        self._model = None
        self._name = ""

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
            beam_size=5,
            fp16=True,
            no_speech_threshold=0.6,
            condition_on_previous_text=False,
        )
        return result["text"].strip()

    @property
    def backend_label(self):
        return f"{self._name}/cuda"


def make_transcriber(device: str) -> Transcriber:
    if device == "cuda":
        return GPUTranscriber()
    return CPUTranscriber()


# ---------------------------------------------------------------------------
# Text injection (platform-specific)
# ---------------------------------------------------------------------------

def _type_linux_x11(text):
    subprocess.run(
        ["xdotool", "type", "--clearmodifiers", "--delay", "12", "--", text],
        check=False,
    )


def _type_linux_wayland(text):
    subprocess.run(["wtype", "--", text], check=False)


def _type_windows(text):
    from pynput.keyboard import Controller
    _type_windows._kb.type(text)

_type_windows._kb = None


def _get_typer():
    if PLATFORM == "Windows":
        from pynput.keyboard import Controller
        _type_windows._kb = Controller()
        return _type_windows

    session = os.environ.get("XDG_SESSION_TYPE", "x11")
    if session == "wayland":
        return _type_linux_wayland
    return _type_linux_x11


# ---------------------------------------------------------------------------
# Key matching — cross-platform variance handling
# ---------------------------------------------------------------------------

# Windows virtual key codes for right-side modifiers.
# pynput on Windows sometimes delivers a bare KeyCode(vk=N) instead of the
# named Key enum, so equality checks fail even though it's the right key.
_WIN_VK: dict[keyboard.Key, int] = {
    keyboard.Key.ctrl_r:  163,  # VK_RCONTROL
    keyboard.Key.ctrl_l:  162,  # VK_LCONTROL
    keyboard.Key.alt_r:   165,  # VK_RMENU
    keyboard.Key.alt_l:   164,  # VK_LMENU
    keyboard.Key.shift_r: 161,  # VK_RSHIFT
    keyboard.Key.shift_l: 160,  # VK_LSHIFT
}

# Functional aliases: keys that should be treated as equivalent.
# AltGr (Right Alt on international keyboards) reports as Key.alt_gr on
# Windows/Linux but is physically and functionally the same key as alt_r.
_KEY_ALIASES: dict[keyboard.Key, set] = {
    keyboard.Key.alt_r: {keyboard.Key.alt_gr},
}


def _vk_of(key) -> int | None:
    """Return the virtual key code for a key object, or None if unavailable."""
    if hasattr(key, 'vk'):                                      # bare KeyCode
        return key.vk
    if hasattr(key, 'value') and hasattr(key.value, 'vk'):     # Key enum
        return key.value.vk
    return None


def match_key(key, target: keyboard.Key) -> bool:
    """
    Return True if `key` (from a pynput event) matches the `target` key,
    accounting for:
      - Direct equality (all platforms, normal case)
      - Functional aliases (e.g. alt_gr == alt_r on international keyboards)
      - VK-code matching (Windows drivers that return KeyCode instead of Key enum)
    """
    if key == target:
        return True

    aliases = _KEY_ALIASES.get(target, set())
    if key in aliases:
        return True

    key_vk = _vk_of(key)
    if key_vk is not None:
        target_vk = _vk_of(target) or _WIN_VK.get(target)
        if target_vk is not None and key_vk == target_vk:
            return True
        for alias in aliases:
            alias_vk = _vk_of(alias) or _WIN_VK.get(alias)
            if alias_vk is not None and key_vk == alias_vk:
                return True

    return False


# ---------------------------------------------------------------------------
# Daemon
# ---------------------------------------------------------------------------

class Scribe:
    def __init__(self, model_size, hotkey, boost_key, device, debug=False):
        self.model_size = model_size
        self.hotkey = hotkey
        self.boost_key = boost_key
        self.device = device
        self.debug = debug
        self._type_text = _get_typer()

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
        self.transcriber = make_transcriber(self.device)
        self.transcriber.load(self.model_size)

    def _ensure_heavy_transcriber(self):
        if self.heavy_transcriber is None:
            print("  First use of heavy model — loading (one-time)...")
            self.heavy_transcriber = make_transcriber(self.device)
            self.heavy_transcriber.load(HEAVY_MODEL)
        return self.heavy_transcriber

    def _audio_callback(self, indata, frames, time_info, status):
        if status:
            print(f"  Audio: {status}", file=sys.stderr)
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
                self.stream = sd.InputStream(
                    samplerate=SAMPLE_RATE,
                    channels=1,
                    dtype="float32",
                    blocksize=1024,
                    callback=self._audio_callback,
                )
                self.stream.start()
                print("  [REC]", end="", flush=True)
            except Exception as e:
                print(f"\n  Mic error: {e}", file=sys.stderr)
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
                    pass
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
                self._transcribe_and_type(audio, use_heavy)

    def _transcribe_and_type(self, audio, use_heavy=False):
        if use_heavy:
            tr = self._ensure_heavy_transcriber()
        else:
            tr = self.transcriber

        t0 = time.monotonic()
        try:
            text = tr.transcribe(audio)
        except Exception as e:
            print(f"\n  Transcription error: {e}", file=sys.stderr)
            return

        elapsed = time.monotonic() - t0

        if not text:
            print(f" -> (silence, {elapsed:.1f}s) [{tr.backend_label}]")
            return

        print(f" -> \"{text}\" ({elapsed:.1f}s) [{tr.backend_label}]")
        if text:
            self._type_text(text)

    def on_press(self, key):
        if self.debug:
            print(f"  [DBG] press: {key!r}  vk={_vk_of(key)}  boost_held={self.boost_held}")
        if match_key(key, self.boost_key):
            self.boost_held = True
            if self.recording:
                self.use_heavy = True
                print(" +BOOST", end="", flush=True)
        if match_key(key, self.hotkey):
            self.start_recording()

    def on_release(self, key):
        if self.debug:
            print(f"  [DBG] release: {key!r}  vk={_vk_of(key)}  boost_held={self.boost_held}")
        if match_key(key, self.boost_key):
            self.boost_held = False
        if match_key(key, self.hotkey):
            self.stop_recording()

    def run(self):
        print(f"  Platform: {PLATFORM}")
        if PLATFORM == "Linux":
            session = os.environ.get("XDG_SESSION_TYPE", "x11")
            print(f"  Display: {session}")
        print("  Backend:", "GPU (PyTorch CUDA fp16)" if self.device == "cuda" else "CPU (CTranslate2 int8)")
        self.load_model()
        self.worker.start()

        hotkey_name = self.hotkey.name if hasattr(self.hotkey, "name") else str(self.hotkey)
        boost_name = self.boost_key.name if hasattr(self.boost_key, "name") else str(self.boost_key)
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


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

HOTKEY_MAP = {
    "rctrl": keyboard.Key.ctrl_r,
    "lctrl": keyboard.Key.ctrl_l,
    "ralt": keyboard.Key.alt_r,
    "alt_gr": keyboard.Key.alt_gr,   # explicit AltGr for international keyboards
    "lalt": keyboard.Key.alt_l,
    "rshift": keyboard.Key.shift_r,
    "scroll_lock": keyboard.Key.scroll_lock,
    "pause": keyboard.Key.pause,
    "f13": keyboard.KeyCode.from_vk(191),
}


def detect_cuda():
    try:
        import torch
        return torch.cuda.is_available()
    except ImportError:
        return False


def main():
    parser = argparse.ArgumentParser(
        prog="scribe",
        description="Scribe — fully offline push-to-talk dictation",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="Models (smallest to largest): tiny.en, base.en, small.en, medium.en, large-v3-turbo, large-v3",
    )
    parser.add_argument(
        "--model", "-m",
        default="small.en",
        help="Whisper model size (default: small.en)",
    )
    parser.add_argument(
        "--hotkey", "-k",
        default="ralt",
        choices=list(HOTKEY_MAP.keys()),
        help="Push-to-talk key (default: ralt)",
    )
    parser.add_argument(
        "--boost-key", "-b",
        default="rshift",
        choices=list(HOTKEY_MAP.keys()),
        help="Hold with hotkey for accurate mode (default: rshift)",
    )
    parser.add_argument(
        "--device", "-d",
        choices=["cpu", "cuda", "auto"],
        default="auto",
        help="Compute device (default: auto-detect)",
    )
    parser.add_argument(
        "--debug",
        action="store_true",
        help="Print all key events for troubleshooting",
    )
    args = parser.parse_args()

    print("\n  === Scribe ===\n")

    if args.device == "auto":
        device = "cuda" if detect_cuda() else "cpu"
    else:
        device = args.device

    hotkey = HOTKEY_MAP.get(args.hotkey, keyboard.Key.alt_r)
    boost_key = HOTKEY_MAP.get(args.boost_key, keyboard.Key.shift_r)

    scribe = Scribe(
        model_size=args.model,
        hotkey=hotkey,
        boost_key=boost_key,
        device=device,
        debug=args.debug,
    )

    if PLATFORM != "Windows":
        signal.signal(signal.SIGTERM, lambda *_: scribe.shutdown.set())

    scribe.run()


if __name__ == "__main__":
    main()
