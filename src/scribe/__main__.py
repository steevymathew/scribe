"""CLI entry point: `python -m scribe` (or the root scribe.py shim)."""

import argparse
import logging
import signal
import sys

from . import config, portable
from .backends import autodetect_device
from .engine import PLATFORM, Scribe
from .hotkeys import HOTKEY_MAP
from .logsetup import ADVANCED, setup_logging

log = logging.getLogger("scribe")


def build_parser():
    parser = argparse.ArgumentParser(
        prog="scribe",
        description="Scribe — fully offline push-to-talk dictation",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="Models (smallest to largest): tiny.en, base.en, small.en, "
               "medium.en, large-v3-turbo, large-v3",
    )
    # Defaults are None so we can tell "flag given" from "not given"; real
    # defaults live in config.DEFAULTS and merge in config.effective().
    parser.add_argument(
        "--model", "-m",
        default=None,
        help=f"Whisper model size (default: {config.DEFAULTS['model']})",
    )
    parser.add_argument(
        "--hotkey", "-k",
        default=None,
        choices=list(HOTKEY_MAP.keys()),
        help=f"Push-to-talk key (default: {config.DEFAULTS['hotkey']})",
    )
    parser.add_argument(
        "--boost-key", "-b",
        default=None,
        choices=list(HOTKEY_MAP.keys()),
        help=f"Hold with hotkey for accurate mode (default: {config.DEFAULTS['boost_key']})",
    )
    parser.add_argument(
        "--device", "-d",
        choices=["cpu", "cuda", "npu", "auto"],
        default=None,
        help="Compute device: cpu, cuda (NVIDIA), npu (Qualcomm Hexagon), auto",
    )
    parser.add_argument(
        "--beam-size",
        type=int,
        default=None,
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
        "--remove-fillers",
        action="store_const", const=True, default=None,
        help="Remove filler words (um, uh, ...) from transcripts "
             "(default: off; also a config key)",
    )
    parser.add_argument(
        "--debug",
        action="store_const", const=True, default=None,
        help="Print all key events for troubleshooting",
    )
    parser.add_argument(
        "--advanced",
        action="store_const", const=True, default=None,
        help="Advanced mode: stream the full log (warnings, tracebacks, "
             "third-party output) to the console instead of only the log file",
    )
    parser.add_argument(
        "--save-config",
        action="store_true",
        help=f"Write the effective settings to {config.config_path()} and exit",
    )
    parser.add_argument(
        "--ui",
        action="store_true",
        help="Run with the desktop UI: system-tray icon, recording overlay "
             "and settings window (requires PySide6)",
    )
    return parser


def main():
    # Before anything reads a path or loads a model: if a portable marker sits
    # next to the exe, redirect config/logs/model-cache beside it.
    portable.apply()

    args = build_parser().parse_args()

    settings = config.effective({
        "model": args.model,
        "hotkey": args.hotkey,
        "boost_key": args.boost_key,
        "device": args.device,
        "beam_size": args.beam_size,
        "npu_encoder": args.npu_encoder,
        "debug": args.debug,
        "advanced": args.advanced,
        "remove_fillers": args.remove_fillers,
    })

    if args.save_config:
        path = config.save(settings)
        print(f"  Saved settings to {path}")
        return

    log_path = setup_logging(ADVANCED or settings["advanced"])
    log.info("scribe starting: platform=%s argv=%s settings=%s",
             PLATFORM, sys.argv[1:], settings)

    print("\n  === Scribe ===\n")
    print(f"  Log: {log_path}")

    device = settings["device"]
    if device == "auto":
        device = autodetect_device()

    hotkey = HOTKEY_MAP.get(settings["hotkey"], HOTKEY_MAP["ralt"])
    boost_key = HOTKEY_MAP.get(settings["boost_key"], HOTKEY_MAP["rshift"])

    scribe = Scribe(
        model_size=settings["model"],
        heavy_model=settings["heavy_model"],
        hotkey=hotkey,
        boost_key=boost_key,
        device=device,
        beam_size=settings["beam_size"],
        npu_encoder=settings["npu_encoder"],
        debug=settings["debug"],
        postproc_settings={
            "remove_fillers": settings["remove_fillers"],
            "dictionary": settings["dictionary"],
            "language": settings["language"],
        },
    )

    if PLATFORM != "Windows":
        signal.signal(signal.SIGTERM, lambda *_: scribe.shutdown.set())

    if args.ui:
        # Lazy import: headless machines (CLI, systemd) never touch Qt, and
        # nothing outside scribe.ui may import PySide6 (ROADMAP §8).
        from .ui.qml_app import run_qml_ui
        raise SystemExit(run_qml_ui(scribe, settings))

    scribe.run()


if __name__ == "__main__":
    main()
