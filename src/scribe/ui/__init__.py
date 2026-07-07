"""Scribe UI (PySide6) — tray icon, recording overlay, settings, log viewer.

ALL Qt code lives in this package and only here (ROADMAP §4/§8). The engine
never imports it; `python -m scribe --ui` lazily imports `scribe.ui.app`.
Keep this module itself import-light: importing `scribe.ui` must not pull
in Qt (submodules do that).
"""
