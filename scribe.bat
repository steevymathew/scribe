@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
set "VENV_DIR=%SCRIPT_DIR%.venv"

if not exist "%VENV_DIR%\Scripts\activate.bat" (
    echo Run setup.bat first.
    exit /b 1
)

call "%VENV_DIR%\Scripts\activate.bat"
python "%SCRIPT_DIR%scribe.py" --device cpu %*
