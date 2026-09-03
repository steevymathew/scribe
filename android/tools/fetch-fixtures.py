#!/usr/bin/env python3
"""Build the ASR test corpus under android/fixtures/audio/.

Two sources, both public and both with a known-correct transcript, because a word error
rate measured against a transcript we produced ourselves would measure nothing:

  * LibriSpeech dev-clean, via the small public mirror used for library testing. Read
    speech, clean recording, standard benchmark.
  * whisper.cpp's own `samples/jfk.wav`, which is real archive audio rather than a studio
    read and is therefore a harder and more honest case.

Everything is converted to 16 kHz mono PCM16, which is what Whisper consumes and what
Scribe's own capture produces, so the bench measures the same path the phone runs.

The audio is gitignored; this script is the source of truth. Re-run it after a checkout:

    python3 tools/fetch-fixtures.py
"""

from __future__ import annotations

import json
import pathlib
import re
import shutil
import subprocess
import sys
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "fixtures" / "audio"
DATASET = "hf-internal-testing/librispeech_asr_dummy"
ROWS_URL = (
    "https://datasets-server.huggingface.co/rows"
    f"?dataset={DATASET.replace('/', '%2F')}&config=clean&split=validation"
    "&offset=0&length={n}"
)
SAMPLE_COUNT = 12


def run(*args: str) -> None:
    result = subprocess.run(args, capture_output=True, text=True)
    if result.returncode != 0:
        sys.exit(f"command failed: {' '.join(args)}\n{result.stderr[-2000:]}")


def to_wav(source: pathlib.Path, target: pathlib.Path) -> None:
    """16 kHz mono PCM16 — the format Whisper wants and Scribe's capture produces."""
    run(
        "ffmpeg", "-y", "-loglevel", "error",
        "-i", str(source),
        "-ac", "1", "-ar", "16000", "-sample_fmt", "s16",
        str(target),
    )


def normalise_reference(text: str) -> str:
    """LibriSpeech transcripts are upper-case and unpunctuated; the bench lower-cases and
    strips punctuation on both sides anyway, so this only tidies whitespace."""
    return re.sub(r"\s+", " ", text).strip()


def fetch_librispeech() -> int:
    with urllib.request.urlopen(ROWS_URL.format(n=SAMPLE_COUNT), timeout=60) as response:
        payload = json.load(response)

    written = 0
    for entry in payload.get("rows", []):
        row = entry["row"]
        audio = row.get("audio")
        src = None
        if isinstance(audio, list) and audio:
            src = audio[0].get("src")
        elif isinstance(audio, dict):
            src = audio.get("src")
        if not src:
            continue

        name = str(row.get("id") or f"librispeech-{written:02d}")
        flac = OUT / f"{name}.flac"
        with urllib.request.urlopen(src, timeout=120) as response, flac.open("wb") as f:
            shutil.copyfileobj(response, f)

        to_wav(flac, OUT / f"{name}.wav")
        flac.unlink()
        (OUT / f"{name}.txt").write_text(normalise_reference(row.get("text", "")) + "\n")
        written += 1
    return written


JFK_REFERENCE = (
    "And so my fellow Americans ask not what your country can do for you "
    "ask what you can do for your country"
)


def fetch_jfk() -> int:
    source = ROOT / "third_party" / "whisper.cpp" / "samples" / "jfk.wav"
    if not source.is_file():
        print("skipping jfk.wav: whisper.cpp submodule not checked out", file=sys.stderr)
        return 0
    to_wav(source, OUT / "jfk.wav")
    (OUT / "jfk.txt").write_text(JFK_REFERENCE + "\n")
    return 1


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    count = fetch_librispeech() + fetch_jfk()
    total_seconds = 0.0
    for wav in sorted(OUT.glob("*.wav")):
        total_seconds += wav.stat().st_size / (16000 * 2)
    print(f"{count} clips, {total_seconds:.1f}s of audio, in {OUT}")


if __name__ == "__main__":
    main()
