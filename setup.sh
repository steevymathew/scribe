#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENV_DIR="$SCRIPT_DIR/.venv"

echo ""
echo "  Scribe — setup"
echo ""

# ── System packages ────────────────────────────────────────────────────────

install_system_deps() {
    if command -v apt-get &>/dev/null; then
        sudo apt-get update -qq
        sudo apt-get install -y xdotool portaudio19-dev libportaudio2
    elif command -v dnf &>/dev/null; then
        sudo dnf install -y xdotool portaudio-devel
    elif command -v pacman &>/dev/null; then
        sudo pacman -S --noconfirm xdotool portaudio
    elif command -v zypper &>/dev/null; then
        sudo zypper install -y xdotool portaudio-devel
    else
        echo "  Could not detect package manager."
        echo "  Install xdotool and portaudio manually, then re-run this script."
        return 1
    fi
}

NEED_DEPS=false
command -v xdotool &>/dev/null || NEED_DEPS=true
ldconfig -p 2>/dev/null | grep -q libportaudio || NEED_DEPS=true

if $NEED_DEPS; then
    echo "  Missing system packages (xdotool and/or portaudio)."
    echo ""
    read -rp "  Install now? (requires sudo) [Y/n] " ans
    if [[ "${ans:-y}" =~ ^[Yy]$ ]]; then
        install_system_deps
    else
        echo "  Skipping. Install these manually before running Scribe."
    fi
fi

# ── Python environment ─────────────────────────────────────────────────────

echo "  [1/3] Setting up Python environment..."
python3 -m venv "$VENV_DIR"
source "$VENV_DIR/bin/activate"
pip install --upgrade pip -q

# ── Choose install mode ────────────────────────────────────────────────────

MODE="cpu"
if command -v nvidia-smi &>/dev/null; then
    echo ""
    echo "  NVIDIA GPU detected."
    read -rp "  Install GPU support? Adds ~1.5 GB but runs 8-20x faster. [Y/n] " ans
    if [[ "${ans:-y}" =~ ^[Yy]$ ]]; then
        MODE="gpu"
    fi
fi

echo "  [2/3] Installing dependencies ($MODE)..."
if [ "$MODE" = "gpu" ]; then
    pip install -r "$SCRIPT_DIR/requirements-gpu.txt" -q
else
    pip install -r "$SCRIPT_DIR/requirements.txt" -q
fi

# ── Download models ────────────────────────────────────────────────────────

echo "  [3/3] Downloading speech models (one-time)..."

python3 -c "
from faster_whisper import WhisperModel
print('    small.en ...')
WhisperModel('small.en', device='cpu', compute_type='int8')
print('    large-v3-turbo ...')
WhisperModel('large-v3-turbo', device='cpu', compute_type='int8')
print('    Done.')
"

if [ "$MODE" = "gpu" ]; then
    python3 -c "
import whisper
print('    small.en (GPU format) ...')
whisper.load_model('small.en', device='cpu')
print('    large-v3-turbo (GPU format) ...')
whisper.load_model('large-v3-turbo', device='cpu')
print('    Done.')
"
fi

# ── Finished ───────────────────────────────────────────────────────────────

echo ""
echo "  Setup complete."
echo ""
if [ "$MODE" = "gpu" ]; then
    echo "  ./scribe              CPU mode"
    echo "  ./scribe-gpu          GPU mode (recommended for your hardware)"
else
    echo "  ./scribe              Start Scribe"
fi
echo ""
echo "  Hold Right Alt and speak. Release to transcribe."
echo "  Hold Right Shift + Right Alt for high-accuracy mode."
echo ""
