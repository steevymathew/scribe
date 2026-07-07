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

# Right Alt is reported as alt_r on most layouts but as alt_gr on some
# (notably several Windows keyboard layouts). Treat both as "Right Alt".
RIGHT_ALT_KEYS = frozenset({keyboard.Key.alt_r, keyboard.Key.alt_gr})


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


_qnn_htp_path = None
_qnn_checked = False


def _ensure_qnn_registered():
    """Register the QNN execution-provider plugin once.

    Recent ONNX Runtime ships QNN as a separate plugin (`onnxruntime_qnn`) that
    must be registered before the provider becomes available. Returns the path
    to QnnHtp.dll when the Qualcomm NPU stack is present, otherwise None.
    """
    global _qnn_htp_path, _qnn_checked
    if _qnn_checked:
        return _qnn_htp_path
    _qnn_checked = True
    try:
        import onnxruntime as ort
        import onnxruntime_qnn as oq
    except ImportError:
        return None
    try:
        os.add_dll_directory(oq.LIB_DIR_FULL_PATH)
    except (OSError, AttributeError):
        pass
    try:
        if "QNNExecutionProvider" not in ort.get_available_providers():
            ort.register_execution_provider_library(oq.get_ep_name(), oq.get_library_path())
    except Exception:
        pass
    if "QNNExecutionProvider" in ort.get_available_providers():
        _qnn_htp_path = oq.get_qnn_htp_path()
    return _qnn_htp_path


def _download_onnx(repo, fname):
    """Download an ONNX file plus its external-data sidecar, if it has one.

    Big models (e.g. large-v3-turbo) keep weights in a separate
    ``<name>.onnx_data`` file because they exceed ONNX's 2 GB single-file limit;
    the .onnx alone is just the graph and won't load without it.
    """
    from huggingface_hub import hf_hub_download
    path = hf_hub_download(repo, fname)
    try:
        hf_hub_download(repo, fname + "_data")  # weights sidecar, if present
    except Exception:
        pass
    return path


class ONNXTranscriber(Transcriber):
    """ONNX Runtime backend — native ARM64, NPU-ready (Qualcomm Snapdragon).

    Runs Whisper as ONNX models with no PyTorch and no CTranslate2, so it
    executes *natively* on ARM64 (Snapdragon X) instead of the emulated x64 that
    faster-whisper requires there. Models are standard ONNX Whisper weights from
    the onnx-community repos, downloaded once and then used entirely offline.

    The encoder (the heavy part) is offered to the Hexagon NPU via the QNN
    execution provider; the small autoregressive decoder runs on the CPU with a
    key/value cache — ``decoder_model`` produces the initial cache, then
    ``decoder_with_past_model`` advances one token per step, so each step is a
    single-token forward pass instead of re-reading the whole sequence.

    NPU offload is opportunistic: the stock onnx-community encoder graphs are
    plain float ONNX, which this QNN/HTP runtime declines to place on the NPU
    (it wants a QNN-prepared, statically quantized graph). So by default the
    encoder runs on the native-ARM64 CPU. Point ``--npu-encoder`` at a
    QNN-ready encoder .onnx and, when QNN binds it, the encoder runs on the
    Hexagon NPU automatically.
    """

    ONNX_REPO_PREFIX = "onnx-community/whisper-"
    SILENCE_RMS = 0.005   # clips quieter than this are treated as silence

    def __init__(self, beam_size=1, npu_encoder=None, precision=""):
        self._name = ""
        self._beam_size = beam_size  # decoder is greedy; kept for interface parity
        self._npu_encoder = npu_encoder
        # "" = fp32 (most accurate), "_int8" = quantized (much smaller/faster to
        # load and run, used for the heavy boost model where it's still accurate).
        self._precision = precision
        self._encoder = None
        self._decoder = None        # first step (no past)
        self._decoder_past = None   # subsequent steps (with KV cache)
        self._processor = None
        self._encoder_ep = "cpu"
        self._enc_in = None
        self._enc_out = None
        self._enc_dtype = np.float32
        self._n_layers = 0
        self._start_ids = []
        self._eot_id = None

    def load(self, model_name):
        import onnxruntime as ort
        from transformers import AutoProcessor

        self._name = model_name
        repo = self.ONNX_REPO_PREFIX + model_name
        p = self._precision
        t0 = time.monotonic()

        enc_path = self._npu_encoder or _download_onnx(repo, f"onnx/encoder_model{p}.onnx")
        dec_path = _download_onnx(repo, f"onnx/decoder_model{p}.onnx")
        dec_past_path = _download_onnx(repo, f"onnx/decoder_with_past_model{p}.onnx")
        self._processor = AutoProcessor.from_pretrained(repo)

        so = ort.SessionOptions()
        so.log_severity_level = 3  # quiet ORT's per-op chatter

        # Offer the encoder to the NPU (QNN/HTP) when available; ORT silently
        # keeps on CPU whatever QNN won't take, so this never fails — we just
        # read back which provider actually claimed the graph.
        providers = ["CPUExecutionProvider"]
        htp_path = _ensure_qnn_registered()
        if htp_path:
            providers = [
                ("QNNExecutionProvider",
                 {"backend_path": htp_path, "htp_performance_mode": "burst"}),
                "CPUExecutionProvider",
            ]
        self._encoder = ort.InferenceSession(enc_path, sess_options=so, providers=providers)
        self._encoder_ep = (
            "npu" if "QNNExecutionProvider" in self._encoder.get_providers() else "cpu"
        )

        self._decoder = ort.InferenceSession(
            dec_path, sess_options=so, providers=["CPUExecutionProvider"]
        )
        self._decoder_past = ort.InferenceSession(
            dec_past_path, sess_options=so, providers=["CPUExecutionProvider"]
        )

        enc_input = self._encoder.get_inputs()[0]
        self._enc_in = enc_input.name
        self._enc_out = self._encoder.get_outputs()[0].name
        self._enc_dtype = np.float16 if "16" in enc_input.type else np.float32
        self._n_layers = sum(
            1 for o in self._decoder.get_outputs() if o.name.endswith(".decoder.key")
        )

        tok = self._processor.tokenizer
        # English-only models (e.g. small.en) take just <sot><notimestamps>.
        # Multilingual models (e.g. large-v3-turbo, used for boost mode) need
        # the language + task tokens, or they misbehave — force English.
        self._start_ids = [tok.convert_tokens_to_ids("<|startoftranscript|>")]
        if not model_name.endswith(".en"):
            self._start_ids += [
                tok.convert_tokens_to_ids("<|en|>"),
                tok.convert_tokens_to_ids("<|transcribe|>"),
            ]
        self._start_ids.append(tok.convert_tokens_to_ids("<|notimestamps|>"))
        self._eot_id = tok.convert_tokens_to_ids("<|endoftext|>")

        where = "Hexagon NPU (QNN)" if self._encoder_ep == "npu" else "CPU (native ARM64)"
        print(f"  Loaded '{model_name}' ONNX — encoder on {where}, decoder on CPU "
              f"(KV cache) in {time.monotonic() - t0:.1f}s")

    def transcribe(self, audio):
        # No VAD here, so gate near-silence to avoid the decoder hallucinating.
        if float(np.sqrt(np.mean(np.square(audio)))) < self.SILENCE_RMS:
            return ""

        feats = self._processor.feature_extractor(
            audio, sampling_rate=SAMPLE_RATE, return_tensors="np"
        ).input_features.astype(self._enc_dtype)

        enc = self._encoder.run([self._enc_out], {self._enc_in: feats})[0]
        enc = enc.astype(np.float32)  # decoder runs fp32 on CPU

        # First step: full prompt through the no-past decoder, which emits the
        # initial cache — decoder self-attn KV and (constant) encoder cross-attn KV.
        out = self._decoder.run(
            None,
            {"input_ids": np.array([self._start_ids], dtype=np.int64),
             "encoder_hidden_states": enc},
        )
        named = {o.name: v for o, v in zip(self._decoder.get_outputs(), out)}
        next_id = int(named["logits"][0, -1].argmax())

        enc_past, dec_past = {}, {}
        for i in range(self._n_layers):
            enc_past[f"past_key_values.{i}.encoder.key"] = named[f"present.{i}.encoder.key"]
            enc_past[f"past_key_values.{i}.encoder.value"] = named[f"present.{i}.encoder.value"]
            dec_past[f"past_key_values.{i}.decoder.key"] = named[f"present.{i}.decoder.key"]
            dec_past[f"past_key_values.{i}.decoder.value"] = named[f"present.{i}.decoder.value"]

        generated = []
        for _ in range(444):  # Whisper's max decode length
            if next_id == self._eot_id:
                break
            generated.append(next_id)
            feed = {"input_ids": np.array([[next_id]], dtype=np.int64)}
            feed.update(dec_past)
            feed.update(enc_past)  # cross-attn KV is constant, reused every step
            out = self._decoder_past.run(None, feed)
            named = {o.name: v for o, v in zip(self._decoder_past.get_outputs(), out)}
            next_id = int(named["logits"][0, -1].argmax())
            for i in range(self._n_layers):
                dec_past[f"past_key_values.{i}.decoder.key"] = named[f"present.{i}.decoder.key"]
                dec_past[f"past_key_values.{i}.decoder.value"] = named[f"present.{i}.decoder.value"]

        text = self._processor.tokenizer.decode(generated, skip_special_tokens=True).strip()
        # Drop Whisper's non-speech annotations like [BLANK_AUDIO] or (music).
        if text.startswith(("[", "(", "♪")) and text.endswith(("]", ")", "♪")):
            return ""
        return text

    @property
    def backend_label(self):
        return f"{self._name}/{'npu' if self._encoder_ep == 'npu' else 'onnx-cpu'}"


def make_transcriber(device: str, beam_size: int = 1, npu_encoder=None,
                     precision: str = "") -> Transcriber:
    if device == "cuda":
        return GPUTranscriber(beam_size)
    if device == "npu":
        return ONNXTranscriber(beam_size, npu_encoder, precision)
    return CPUTranscriber(beam_size)


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
# Daemon
# ---------------------------------------------------------------------------

class Scribe:
    def __init__(self, model_size, hotkey, boost_key, device, beam_size=1,
                 npu_encoder=None, debug=False):
        self.model_size = model_size
        self.hotkey = hotkey
        self.boost_key = boost_key
        self.device = device
        self.beam_size = beam_size
        self.npu_encoder = npu_encoder
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
        self.transcriber = make_transcriber(self.device, self.beam_size, self.npu_encoder)
        self.transcriber.load(self.model_size)

    def _ensure_heavy_transcriber(self):
        if self.heavy_transcriber is None:
            print("  First use of heavy model — loading (one-time)...")
            # The heavy model has its own encoder; a custom --npu-encoder only
            # applies to the primary model, so don't pass it here. On the ONNX
            # backend use the int8 build — large-v3-turbo in fp32 is a ~3 GB
            # download and slow on CPU; int8 is far lighter and still accurate.
            self.heavy_transcriber = make_transcriber(
                self.device, self.beam_size, precision="_int8"
            )
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

    def _match_key(self, key, target):
        if key == target:
            return True
        # On some Windows keyboard layouts, the Right Alt key is reported as
        # AltGr (vk 165) instead of alt_r. Treat the two as equivalent so the
        # default `ralt` hotkey works everywhere.
        if target in RIGHT_ALT_KEYS and key in RIGHT_ALT_KEYS:
            return True
        if hasattr(key, 'vk') and hasattr(target, 'value') and hasattr(target.value, 'vk'):
            return key.vk == target.value.vk
        return False

    def on_press(self, key):
        if self.debug:
            print(f"  [DBG] press: {key!r} boost_held={self.boost_held}")
        if self._match_key(key, self.boost_key):
            self.boost_held = True
            if self.recording:
                self.use_heavy = True
                print(" +BOOST", end="", flush=True)
        if self._match_key(key, self.hotkey):
            self.start_recording()

    def on_release(self, key):
        if self.debug:
            print(f"  [DBG] release: {key!r} boost_held={self.boost_held}")
        if self._match_key(key, self.boost_key):
            self.boost_held = False
        if self._match_key(key, self.hotkey):
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
    "altgr": keyboard.Key.alt_gr,
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


def detect_qnn():
    """True if the Qualcomm Hexagon NPU (QNN execution provider) is available."""
    return _ensure_qnn_registered() is not None


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
        choices=["cpu", "cuda", "npu", "auto"],
        default="auto",
        help="Compute device: cpu, cuda (NVIDIA), npu (Qualcomm Hexagon), auto",
    )
    parser.add_argument(
        "--beam-size",
        type=int,
        default=1,
        help="Beam search width (default: 1 = greedy, fastest)",
    )
    parser.add_argument(
        "--npu-encoder",
        default=None,
        metavar="PATH",
        help="Path to a QNN-ready ONNX encoder to offload onto the Hexagon NPU "
             "(npu device only). Without it the encoder runs on the native-ARM64 CPU.",
    )
    parser.add_argument(
        "--debug",
        action="store_true",
        help="Print all key events for troubleshooting",
    )
    args = parser.parse_args()

    print("\n  === Scribe ===\n")

    if args.device == "auto":
        if detect_cuda():
            device = "cuda"
        elif detect_qnn():
            device = "npu"
        else:
            device = "cpu"
    else:
        device = args.device

    hotkey = HOTKEY_MAP.get(args.hotkey, keyboard.Key.alt_r)
    boost_key = HOTKEY_MAP.get(args.boost_key, keyboard.Key.shift_r)

    scribe = Scribe(
        model_size=args.model,
        hotkey=hotkey,
        boost_key=boost_key,
        device=device,
        beam_size=args.beam_size,
        npu_encoder=args.npu_encoder,
        debug=args.debug,
    )

    if PLATFORM != "Windows":
        signal.signal(signal.SIGTERM, lambda *_: scribe.shutdown.set())

    scribe.run()


if __name__ == "__main__":
    main()
