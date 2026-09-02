#!/usr/bin/env python3
"""Fetch model weights into android/models/.

`models/` is gitignored — a 57 MB binary has no business in git history — so this script
is what a fresh checkout runs to get the model that gets bundled into the APK.

Every file is checked against the SHA-256 recorded in
`core/.../model/ModelRegistry.kt`, which was read from Hugging Face's own metadata. A
truncated model does not crash; it transcribes badly, which is far harder to diagnose from
the user's side, so nothing unverified is left on disk.

    python3 tools/fetch-models.py            # just the bundled model
    python3 tools/fetch-models.py --all      # every speech model, for benchmarking
    python3 tools/fetch-models.py --polish   # also the optional polish model
"""

from __future__ import annotations

import argparse
import hashlib
import pathlib
import sys
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "models"

WHISPER = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

# name, url, size, sha256 — kept in step with ModelRegistry.kt.
BUNDLED = [
    ("ggml-base.en-q5_1.bin", WHISPER + "ggml-base.en-q5_1.bin", 59_721_011,
     "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f"),
]

EXTRA_SPEECH = [
    ("ggml-tiny.en-q5_1.bin", WHISPER + "ggml-tiny.en-q5_1.bin", 32_166_155,
     "c77c5766f1cef09b6b7d47f21b546cbddd4157886b3b5d6d4f709e91e66c7c2b"),
    ("ggml-small.en-q5_1.bin", WHISPER + "ggml-small.en-q5_1.bin", 190_098_681,
     "bfdff4894dcb76bbf647d56263ea2a96645423f1669176f4844a1bf8e478ad30"),
    ("ggml-large-v3-turbo-q5_0.bin", WHISPER + "ggml-large-v3-turbo-q5_0.bin", 574_041_195,
     "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2"),
]

POLISH = [
    ("gemma-3-270m-it-Q8_0.gguf",
     "https://huggingface.co/ggml-org/gemma-3-270m-it-GGUF/resolve/main/gemma-3-270m-it-Q8_0.gguf",
     291_545_600,
     "0ef57d2c838458a1952664260dcba38e5bdda37494f3af732f06e4add24068e3"),
]


def digest(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def fetch(name: str, url: str, size: int, sha256: str) -> None:
    target = OUT / name
    if target.is_file() and target.stat().st_size == size and digest(target) == sha256:
        print(f"  {name} already present and verified")
        return

    print(f"  {name} ({size // 1_048_576} MB) …", end="", flush=True)
    partial = target.with_suffix(target.suffix + ".part")
    with urllib.request.urlopen(url, timeout=120) as response, partial.open("wb") as f:
        done = 0
        while True:
            chunk = response.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)
            done += len(chunk)
            print(f"\r  {name} ({done // 1_048_576}/{size // 1_048_576} MB) …",
                  end="", flush=True)

    if partial.stat().st_size != size:
        partial.unlink()
        sys.exit(f"\n{name}: expected {size} bytes, got {partial.stat().st_size}")
    actual = digest(partial)
    if actual != sha256:
        partial.unlink()
        sys.exit(f"\n{name}: checksum mismatch\n  expected {sha256}\n  got      {actual}")
    partial.rename(target)
    print("\r" + " " * 70 + f"\r  {name} downloaded and verified")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--all", action="store_true", help="every speech model")
    parser.add_argument("--polish", action="store_true", help="also the polish model")
    args = parser.parse_args()

    OUT.mkdir(parents=True, exist_ok=True)
    wanted = list(BUNDLED)
    if args.all:
        wanted += EXTRA_SPEECH
    if args.polish:
        wanted += POLISH

    print(f"fetching {len(wanted)} model(s) into {OUT}")
    for entry in wanted:
        fetch(*entry)
    total = sum(f.stat().st_size for f in OUT.glob("*") if f.is_file())
    print(f"done — {total // 1_048_576} MB in {OUT}")


if __name__ == "__main__":
    main()
