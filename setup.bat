@echo off
setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"
set "VENV_DIR=%SCRIPT_DIR%.venv"

echo.
echo   Scribe — setup (Windows)
echo.

:: ── Python check ──────────────────────────────────────────────────────────

where python >nul 2>&1
if errorlevel 1 (
    echo   Python not found. Install Python 3.10+ from https://python.org
    echo   Make sure to check "Add Python to PATH" during install.
    exit /b 1
)

for /f "tokens=2 delims= " %%v in ('python --version 2^>^&1') do set PYVER=%%v
echo   Found Python %PYVER%

:: ── Windows on ARM (Snapdragon) ───────────────────────────────────────────
:: faster-whisper / CTranslate2 has no ARM64 build, so on Snapdragon we set up
:: the native-ARM64, NPU-ready ONNX backend instead.
if /i "%PROCESSOR_ARCHITECTURE%"=="ARM64" goto npu_setup

:: ── Virtual environment ───────────────────────────────────────────────────

echo   [1/3] Setting up Python environment...
python -m venv "%VENV_DIR%"
call "%VENV_DIR%\Scripts\activate.bat"
python -m pip install --upgrade pip -q

:: ── Choose install mode ───────────────────────────────────────────────────

set "MODE=cpu"
where nvidia-smi >nul 2>&1
if not errorlevel 1 (
    echo.
    echo   NVIDIA GPU detected.
    set /p "ANS=  Install GPU support? Adds ~1.5 GB but runs 8-20x faster. [Y/n] "
    if /i "!ANS!"=="" set "ANS=Y"
    if /i "!ANS!"=="Y" set "MODE=gpu"
    if /i "!ANS!"=="y" set "MODE=gpu"
)

echo   [2/3] Installing dependencies (%MODE%)...
if "%MODE%"=="gpu" (
    pip install -r "%SCRIPT_DIR%requirements-gpu.txt" -q
) else (
    pip install -r "%SCRIPT_DIR%requirements.txt" -q
)

:: ── Download models ───────────────────────────────────────────────────────

echo   [3/3] Downloading speech models (one-time)...

python -c "from faster_whisper import WhisperModel; print('    small.en ...'); WhisperModel('small.en', device='cpu', compute_type='int8'); print('    large-v3-turbo ...'); WhisperModel('large-v3-turbo', device='cpu', compute_type='int8'); print('    Done.')"

if "%MODE%"=="gpu" (
    python -c "import whisper; print('    small.en (GPU) ...'); whisper.load_model('small.en', device='cpu'); print('    large-v3-turbo (GPU) ...'); whisper.load_model('large-v3-turbo', device='cpu'); print('    Done.')"
)

:: ── Finished ──────────────────────────────────────────────────────────────

echo.
echo   Setup complete.
echo.
if "%MODE%"=="gpu" (
    echo   scribe.bat          CPU mode
    echo   scribe-gpu.bat      GPU mode (recommended for your hardware)
) else (
    echo   scribe.bat          Start Scribe
)
echo.
echo   Hold Right Alt and speak. Release to transcribe.
echo   Hold Right Shift + Right Alt for high-accuracy mode.
echo.
pause
exit /b 0

:: ── NPU backend (Windows on ARM / Snapdragon) ─────────────────────────────
:npu_setup
set "VENV_NPU=%SCRIPT_DIR%.venv-npu"
echo.
echo   Windows on ARM detected (Snapdragon). Setting up the NPU backend.
echo   (native ARM64; the encoder is offloaded to the Hexagon NPU when a
echo    QNN-ready model is available, otherwise it runs on the ARM64 CPU.)
echo.
echo   [1/3] Setting up Python environment...
python -m venv "%VENV_NPU%"
call "%VENV_NPU%\Scripts\activate.bat"
python -m pip install --upgrade pip -q

echo   [2/3] Installing dependencies (onnxruntime-qnn, transformers)...
pip install -r "%SCRIPT_DIR%requirements-npu.txt" -q

echo   [3/3] Downloading speech models (one-time; the boost model is large)...
python "%SCRIPT_DIR%setup-npu-models.py"

echo.
echo   Setup complete.
echo.
echo   scribe-npu.bat       Start Scribe (native ARM64, NPU-ready)
echo.
echo   Hold Right Alt and speak. Release to transcribe.
echo   Hold Right Shift + Right Alt for high-accuracy mode.
echo.
pause
exit /b 0
