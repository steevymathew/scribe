"""Audio capture constants and helpers (device handling grows here — ROADMAP §6)."""

import logging

import sounddevice as sd

log = logging.getLogger(__name__)

SAMPLE_RATE = 16000
MIN_AUDIO_SEC = 0.3
MAX_AUDIO_SEC = 120


def open_input_stream(callback):
    """Open and start a 16 kHz mono float32 input stream on the default mic."""
    stream = sd.InputStream(
        samplerate=SAMPLE_RATE,
        channels=1,
        dtype="float32",
        blocksize=1024,
        callback=callback,
    )
    stream.start()
    return stream


def list_input_devices():
    """[(index, name)] of devices that can capture audio."""
    devices = []
    for i, d in enumerate(sd.query_devices()):
        if d.get("max_input_channels", 0) > 0:
            devices.append((i, d["name"]))
    return devices
