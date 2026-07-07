"""ONNX Runtime backend — native ARM64, NPU-ready (Qualcomm Snapdragon)."""

import logging
import os
import time

import numpy as np

from ..audio import SAMPLE_RATE
from ..hub import hf_fetch
from ..logsetup import ADVANCED
from ..postproc import strip_annotations
from ..vad import is_speech
from .base import Transcriber

log = logging.getLogger(__name__)

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
        log.debug("onnxruntime/onnxruntime_qnn not installed; no NPU support")
        return None
    if not ADVANCED:
        ort.set_default_logger_severity(3)
    try:
        os.add_dll_directory(oq.LIB_DIR_FULL_PATH)
    except (OSError, AttributeError):
        log.debug("could not add QNN DLL directory", exc_info=True)
    try:
        if "QNNExecutionProvider" not in ort.get_available_providers():
            ort.register_execution_provider_library(oq.get_ep_name(), oq.get_library_path())
    except Exception:
        log.debug("QNN provider registration failed", exc_info=True)
    if "QNNExecutionProvider" in ort.get_available_providers():
        _qnn_htp_path = oq.get_qnn_htp_path()
    log.info("QNN provider %s", "available" if _qnn_htp_path else "not available")
    return _qnn_htp_path


def _download_onnx(repo, fname):
    """Download an ONNX file plus its external-data sidecar, if it has one.

    Big models (e.g. large-v3-turbo) keep weights in a separate
    ``<name>.onnx_data`` file because they exceed ONNX's 2 GB single-file limit;
    the .onnx alone is just the graph and won't load without it.
    """
    path = hf_fetch(repo, fname)
    try:
        hf_fetch(repo, fname + "_data")  # weights sidecar, if present
    except Exception:
        log.debug("no external-data sidecar for %s (normal for small models)", fname)
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

    def __init__(self, beam_size=1, npu_encoder=None, precision="", language="en"):
        self._name = ""
        self._beam_size = beam_size  # decoder is greedy; kept for interface parity
        self._npu_encoder = npu_encoder
        # Language support on this backend is English-only for now: the
        # multilingual start-token sequence below hardcodes <|en|>. Wiring an
        # arbitrary <|xx|> token is future work (needs per-language token
        # validation + a multilingual primary model); until then any other
        # requested language logs a warning and transcribes as English.
        self._language = language
        if language != "en":
            log.warning("ONNX backend supports language='en' only for now; "
                        "requested %r will transcribe as English", language)
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
        # No VAD in this backend yet, so gate near-silence to avoid the
        # decoder hallucinating (ROADMAP Phase 4 upgrades this to Silero VAD).
        if not is_speech(audio):
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

        text = self._processor.tokenizer.decode(generated, skip_special_tokens=True)
        return strip_annotations(text)

    @property
    def backend_label(self):
        return f"{self._name}/{'npu' if self._encoder_ep == 'npu' else 'onnx-cpu'}"
