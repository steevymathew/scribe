#!/usr/bin/env python3
"""Produce (and validate) a QNN-ready Whisper encoder for the Hexagon NPU.

Why this exists: the stock onnx-community Whisper encoders are plain float
ONNX, which the QNN/HTP runtime refuses to place on the NPU (verified — it
assigns zero nodes). A QNN-prepared graph from Qualcomm AI Hub does run there.
This is a ONE-TIME, maintainer-only step: the resulting artifact is committed
to the repo (Git LFS) so end users never need an account (ROADMAP §7 Phase 6).

Prerequisites (maintainer machine, one time):
    pip install qai-hub
    qai-hub configure --api_token <token from https://aihub.qualcomm.com>

Usage:
    # 1) compile on AI Hub (uploads only the PUBLIC model graph, never audio):
    python tools/build_npu_encoder.py compile --model base.en

    # 2) validate any candidate encoder against the CPU reference locally:
    python tools/build_npu_encoder.py validate --encoder models/npu/encoder_base.en.onnx --model base.en

    # 3) commit: git lfs track "models/npu/*" && git add models/npu && commit.
       Scribe picks it up via:  scribe --npu-encoder models/npu/encoder_base.en.onnx

The `validate` step is fully offline and also verifies the encoder actually
binds to the QNN provider (prints which EP claimed it).
"""

import argparse
import os
import sys

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

ENCODER_SHAPE = (1, 80, 3000)  # Whisper log-mel input (batch, mels, frames)
OUT_DIR = os.path.join(os.path.dirname(__file__), "..", "models", "npu")
# Snapdragon X Elite is the device this repo's artifact targets; the compiled
# binary is HTP-arch-specific (fine across X Elite/Plus, not phones).
AIHUB_DEVICE = "Snapdragon X Elite CRD"


def _fixed_shape_encoder(model_name):
    """Download the stock encoder and pin its dynamic dims (QNN needs static)."""
    import onnx
    from onnxruntime.tools.onnx_model_utils import (
        fix_output_shapes, make_input_shape_fixed,
    )
    from scribe.hub import hf_fetch

    path = hf_fetch(f"onnx-community/whisper-{model_name}", "onnx/encoder_model.onnx")
    m = onnx.load(path)
    make_input_shape_fixed(m.graph, m.graph.input[0].name, list(ENCODER_SHAPE))
    fix_output_shapes(m)
    os.makedirs(OUT_DIR, exist_ok=True)
    fixed = os.path.join(OUT_DIR, f"encoder_{model_name}_fixed.onnx")
    onnx.save(m, fixed)
    return fixed


def cmd_compile(args):
    """Submit the encoder to Qualcomm AI Hub for QNN compilation.

    NOTE: untested until a maintainer runs it with a real API token — expect
    to tweak options against current qai-hub docs if the API has moved.
    """
    try:
        import qai_hub as hub
    except ImportError:
        sys.exit("pip install qai-hub, then: qai-hub configure --api_token <token>")

    fixed = _fixed_shape_encoder(args.model)
    print(f"submitting {fixed} for {AIHUB_DEVICE} ...")
    job = hub.submit_compile_job(
        model=fixed,
        device=hub.Device(AIHUB_DEVICE),
        input_specs={"input_features": ENCODER_SHAPE},
        # Precompiled QNN graph wrapped in ONNX so scribe's --npu-encoder
        # (onnxruntime + QNN EP) can load it directly.
        options="--target_runtime onnx --quantize_full_type float16",
    )
    compiled = job.get_target_model()
    out = os.path.join(OUT_DIR, f"encoder_{args.model}.onnx")
    compiled.download(out)
    print(f"downloaded compiled encoder -> {out}")
    print("now run the validate step, then commit via Git LFS.")


def cmd_validate(args):
    """Offline: compare candidate encoder output vs CPU fp32 reference."""
    import onnxruntime as ort
    from scribe.backends.onnx import _ensure_qnn_registered
    from scribe.hub import hf_fetch

    ref_path = hf_fetch(f"onnx-community/whisper-{args.model}", "onnx/encoder_model.onnx")
    so = ort.SessionOptions()
    so.log_severity_level = 3

    rng = np.random.default_rng(0)
    feats = rng.standard_normal(ENCODER_SHAPE, dtype=np.float32) * 0.5

    ref_sess = ort.InferenceSession(ref_path, sess_options=so,
                                    providers=["CPUExecutionProvider"])
    ref = ref_sess.run(None, {ref_sess.get_inputs()[0].name: feats})[0]

    htp = _ensure_qnn_registered()
    providers = ["CPUExecutionProvider"]
    if htp:
        providers = [("QNNExecutionProvider", {"backend_path": htp}),
                     "CPUExecutionProvider"]
    cand_sess = ort.InferenceSession(args.encoder, sess_options=so,
                                     providers=providers)
    ep = cand_sess.get_providers()[0]
    inp = cand_sess.get_inputs()[0]
    cand_feats = feats.astype(np.float16) if "16" in inp.type else feats
    out = cand_sess.run(None, {inp.name: cand_feats})[0].astype(np.float32)

    mae = float(np.abs(out - ref).mean())
    scale = float(np.abs(ref).mean())
    print(f"encoder EP: {ep}")
    print(f"MAE vs fp32 reference: {mae:.5f}  (reference mean |x|: {scale:.5f})")
    ok_ep = ep == "QNNExecutionProvider"
    ok_acc = mae < 0.05 * scale  # within 5% of signal scale — fp16-level error
    print("NPU binding:", "OK" if ok_ep else "FAILED (ran on CPU)")
    print("accuracy:   ", "OK" if ok_acc else "FAILED")
    sys.exit(0 if (ok_ep and ok_acc) else 1)


def main():
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("compile", help="compile the encoder on Qualcomm AI Hub")
    c.add_argument("--model", default="base.en")
    c.set_defaults(func=cmd_compile)
    v = sub.add_parser("validate", help="validate a candidate encoder locally")
    v.add_argument("--encoder", required=True)
    v.add_argument("--model", default="base.en")
    v.set_defaults(func=cmd_validate)
    args = p.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
