import os
import platform
import subprocess
import sys
import tempfile
import unittest
import wave

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from scribe import vad  # noqa: E402

TEST_PHRASE = "The quick brown fox jumps over the lazy dog."


def synth_speech_16k():
    """A spoken test phrase as 16 kHz float32 mono, via Windows TTS."""
    with tempfile.TemporaryDirectory() as tmp:
        wav_path = os.path.join(tmp, "speech.wav")
        ps = (
            "Add-Type -AssemblyName System.Speech; "
            "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; "
            f"$s.SetOutputToWaveFile('{wav_path}'); "
            f"$s.Speak('{TEST_PHRASE}'); $s.Dispose()"
        )
        subprocess.run(
            ["powershell", "-NoProfile", "-Command", ps],
            check=True, capture_output=True,
        )
        with wave.open(wav_path, "rb") as w:
            rate = w.getframerate()
            channels = w.getnchannels()
            data = np.frombuffer(
                w.readframes(w.getnframes()), dtype=np.int16
            ).astype(np.float32) / 32768.0
    if channels > 1:
        data = data.reshape(-1, channels).mean(axis=1)
    n16 = int(len(data) * 16000 / rate)
    return np.interp(
        np.linspace(0, len(data) - 1, n16), np.arange(len(data)), data
    ).astype(np.float32)


class NearSilence(unittest.TestCase):
    def test_near_silence_is_not_speech(self):
        rng = np.random.default_rng(42)
        audio = (rng.standard_normal(32000) * 0.001).astype(np.float32)  # 2 s
        self.assertFalse(vad.is_speech(audio))

    def test_empty_audio_is_not_speech(self):
        self.assertFalse(vad.is_speech(np.zeros(0, dtype=np.float32)))


@unittest.skipIf(platform.system() != "Windows", "uses Windows TTS")
class SpeechWav(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.audio = synth_speech_16k()

    def test_speech_detected(self):
        self.assertTrue(vad.is_speech(self.audio))

    def test_trim_silence_returns_nonempty_shorter_or_equal(self):
        trimmed = vad.trim_silence(self.audio)
        self.assertGreater(trimmed.size, 0)
        self.assertLessEqual(trimmed.size, self.audio.size)
        # what survives the trim must still read as speech
        self.assertTrue(vad.is_speech(trimmed))

    def test_trim_silence_on_silence_is_empty(self):
        rng = np.random.default_rng(7)
        silence = (rng.standard_normal(32000) * 0.001).astype(np.float32)
        self.assertEqual(vad.trim_silence(silence).size, 0)


if __name__ == "__main__":
    unittest.main()
