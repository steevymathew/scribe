"""Backend selection and hardware autodetection.

Backend modules are imported lazily inside functions so a machine that lacks
one stack (e.g. no onnxruntime on x64, no faster-whisper on ARM64) can still
run the others.
"""

from .base import Transcriber


def make_transcriber(device: str, beam_size: int = 1, npu_encoder=None,
                     precision: str = "") -> Transcriber:
    if device == "cuda":
        from .torch_cuda import GPUTranscriber
        return GPUTranscriber(beam_size)
    if device == "npu":
        from .onnx import ONNXTranscriber
        return ONNXTranscriber(beam_size, npu_encoder, precision)
    from .ct2 import CPUTranscriber
    return CPUTranscriber(beam_size)


def detect_cuda():
    try:
        import torch
        return torch.cuda.is_available()
    except ImportError:
        return False


def detect_qnn():
    """True if the Qualcomm Hexagon NPU (QNN execution provider) is available."""
    from .onnx import _ensure_qnn_registered
    return _ensure_qnn_registered() is not None


def autodetect_device():
    """Pick the best available device: cuda -> npu -> cpu."""
    if detect_cuda():
        return "cuda"
    if detect_qnn():
        return "npu"
    return "cpu"
