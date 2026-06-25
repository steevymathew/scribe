
# Scribe

Local speech-to-text that runs entirely on your machine. Hold a key, speak, release — your words appear at the cursor. No accounts, no API keys, no data leaving your computer. Ever.

Scribe sits in the background using almost no resources until you need it. When you hold the push-to-talk key, it records from your microphone. When you release, it transcribes your speech using OpenAI's Whisper model running locally, then types the result wherever your cursor is — a text editor, a browser input field, a terminal, anything.

Works on Linux and Windows.

## Why local?

Every cloud dictation service sends your audio to someone else's server. That's a problem if you're dictating notes in a meeting, writing something personal, or working with anything confidential. Scribe processes everything on your own hardware. Your audio never touches a network socket. There is no telemetry, no analytics, no phone-home behavior. The models are downloaded once during setup and cached on disk.

## Quick start

### Linux

```bash
git clone https://github.com/steevymathew/scribe.git
cd Scribe
./setup.sh
./scribe          # CPU mode
./scribe-gpu      # GPU mode (NVIDIA)
```

The setup script creates a Python virtual environment, installs dependencies, and downloads models. It will ask for `sudo` once to install two system packages (`xdotool` for typing at the cursor, `portaudio` for mic access). If you'd rather install those yourself first:

```bash
# Ubuntu / Debian
sudo apt-get install -y xdotool portaudio19-dev libportaudio2

# Fedora
sudo dnf install -y xdotool portaudio-devel

# Arch
sudo pacman -S xdotool portaudio
```

### Windows

```
git clone https://github.com/steevymathew/Scribe.git
cd Scribe
setup.bat
scribe.bat          # CPU mode
scribe-gpu.bat      # GPU mode (NVIDIA)
```

Requires Python 3.10+ installed and on your PATH. Get it from [python.org](https://www.python.org/downloads/) — check "Add Python to PATH" during the install. No other system dependencies needed; Windows has built-in audio and keyboard APIs that Scribe uses directly.

## Requirements

- **Python 3.10+**
- **A microphone**
- **Linux**: X11 or Wayland desktop
- **Windows**: 10 or 11
- **NVIDIA GPU** (optional, for GPU mode)

## How to use it

**Normal mode** — hold `Right Alt`, speak, release. Uses a smaller model that's fast and accurate for everyday dictation.

**High-accuracy mode** — hold `Right Shift` + `Right Alt`, speak, release. Uses `large-v3-turbo`, the most accurate Whisper model available. Good for technical terms, proper nouns, or anything where you need it to get the words exactly right.

The boost key (Right Shift) can be pressed at any point while recording — before, during, or after you start holding Right Alt. Scribe upgrades to the heavy model on the fly.

The first time you use high-accuracy mode, the heavy model takes a few seconds to load into memory. After that it stays loaded and subsequent uses are instant.

## CPU vs GPU

Scribe ships with two backends. The CPU backend uses [faster-whisper](https://github.com/SYSTRAN/faster-whisper) (CTranslate2 with int8 quantization). The GPU backend uses [openai-whisper](https://github.com/openai/whisper) (PyTorch with CUDA fp16). Both produce the same transcription — they're running the same underlying Whisper models — but the GPU backend is significantly faster.

Benchmarked on a 5-second audio clip, NVIDIA GB10:

| Backend | Model | Transcription time | Real-time factor | vs. CPU |
|---------|-------|--------------------|------------------|---------|
| CPU | small.en | 1.41s | 0.28x | — |
| CPU | large-v3-turbo | 5.05s | 1.01x | — |
| **GPU** | **small.en** | **0.16s** | **0.03x** | **8.7x faster** |
| **GPU** | **large-v3-turbo** | **0.23s** | **0.05x** | **21.6x faster** |

Real-time factor means how long transcription takes relative to the audio length. Below 1.0 is faster than real-time. The CPU backend can barely keep up with real-time on the heavy model; the GPU backend finishes before you've lifted your finger off the key.

The tradeoff: GPU mode takes longer to start up (~5s to load the model vs ~1s for CPU) and needs about 1.5 GB more disk space for PyTorch. Once running, it's just better if you have the hardware for it.

If you don't have an NVIDIA GPU, CPU mode works fine. The CPU backend with `small.en` transcribes a 5-second clip in about 1.4 seconds, which is plenty fast for normal use.

## Configuration

All options are passed as command-line flags. No config files to manage.

```bash
# Use a different model
./scribe --model base.en          # smallest and fastest
./scribe --model medium.en        # middle ground
./scribe-gpu --model large-v3     # largest (not turbo)

# Change the push-to-talk key
./scribe --hotkey rctrl           # Right Ctrl instead of Right Alt
./scribe --hotkey pause           # Pause/Break key

# Change the boost key
./scribe --boost-key lalt         # Left Alt for high-accuracy mode

# Debug key detection (prints every keypress)
./scribe --debug
```

On Windows, use `scribe.bat` and `scribe-gpu.bat` instead of `./scribe` and `./scribe-gpu`.

Available hotkeys: `ralt`, `lalt`, `rctrl`, `lctrl`, `rshift`, `scroll_lock`, `pause`, `f13`

Available models (smallest to largest): `tiny.en`, `base.en`, `small.en`, `medium.en`, `large-v3-turbo`, `large-v3`

## Running as a background service (Linux)

If you want Scribe to start automatically when you log in:

```bash
mkdir -p ~/.config/systemd/user
cp scribe.service ~/.config/systemd/user/

# Edit the service file if you want GPU mode:
# Change ExecStart to point to scribe-gpu instead of scribe

systemctl --user daemon-reload
systemctl --user enable --now scribe
```

Check status with `systemctl --user status scribe`. Stop with `systemctl --user stop scribe`.

## Architecture

Scribe is a single Python file with no framework dependencies. Here's what happens when you press the key:

1. A `pynput` keyboard listener detects the push-to-talk key. This is event-driven, not polling, so it uses close to zero CPU while idle.
2. A `sounddevice` input stream starts capturing from the default microphone at 16 kHz mono.
3. When you release the key, the audio buffer is handed to a background worker thread.
4. The worker runs it through Whisper with voice activity detection to skip silence.
5. The transcribed text is typed at the cursor position.

The transcription worker runs on a separate thread so the keyboard listener stays responsive. You can start a new recording immediately after releasing, even if the previous clip is still being transcribed.

### Why two transcription backends?

The `ctranslate2` library (which `faster-whisper` uses) doesn't publish CUDA-enabled packages for all platforms. Rather than asking users to compile from source, Scribe uses OpenAI's reference Whisper implementation for GPU inference — it goes through PyTorch and works anywhere CUDA is available. The CPU backend sticks with `faster-whisper` because its int8 quantization is genuinely faster on CPU than PyTorch.

### How text injection works

On Linux, Scribe calls `xdotool type` (X11) or `wtype` (Wayland) to simulate keystrokes at the cursor. On Windows, it uses `pynput`'s keyboard controller, which calls the Win32 `SendInput` API directly. Both approaches type character-by-character so your clipboard stays untouched — Scribe never overwrites what you've copied.

### Platform detection

Scribe checks `platform.system()` at startup and selects the right text injection method. On Linux, it also reads `XDG_SESSION_TYPE` to pick between X11 and Wayland tooling. All platform-specific code is isolated in the text injection layer; the rest of Scribe (audio capture, transcription, hotkey detection) is cross-platform through `sounddevice` and `pynput`.

## Troubleshooting

**"PortAudio library not found"** (Linux only) — install the system library:
```bash
sudo apt-get install -y portaudio19-dev libportaudio2    # Debian/Ubuntu
sudo dnf install -y portaudio-devel                       # Fedora
sudo pacman -S portaudio                                  # Arch
```

**Mic records silence** — check that your microphone is selected as the default input in system sound settings. On Linux/GNOME: Settings > Sound > Input. On Windows: Settings > System > Sound > Input. You should see the level meter move when you speak.

**Hotkey not detected** — run with `--debug` to see what key events Scribe receives. Some desktop environments or apps intercept certain key combinations before they reach applications.

**GPU mode says "CUDA not available"** — make sure NVIDIA drivers are installed and `nvidia-smi` runs without errors. Then reinstall with the setup script and say yes to GPU support.

**Windows Defender blocks execution** — right-click the `.bat` file and select "Run as administrator" for the first run, or add the Scribe folder to your Defender exclusions.

## License

MIT

