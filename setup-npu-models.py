"""Pre-download the ONNX Whisper models used by the NPU backend.

Run once during setup so dictation is fully offline afterwards. Grabs the
normal-mode model (small.en, fp32) and the boost model (large-v3-turbo, int8),
including any external-data weight sidecars.
"""
from huggingface_hub import hf_hub_download
from transformers import AutoProcessor


def grab(repo, fname):
    hf_hub_download(repo, fname)
    try:
        hf_hub_download(repo, fname + "_data")  # weights sidecar, if present
    except Exception:
        pass


JOBS = [
    ("onnx-community/whisper-small.en", ""),              # normal mode (fp32)
    ("onnx-community/whisper-large-v3-turbo", "_int8"),   # boost mode (int8)
]

for repo, prec in JOBS:
    print(f"    {repo} ({prec.lstrip('_') or 'fp32'}) ...")
    for name in ("encoder_model", "decoder_model", "decoder_with_past_model"):
        grab(repo, f"onnx/{name}{prec}.onnx")
    AutoProcessor.from_pretrained(repo)

print("    Done.")
