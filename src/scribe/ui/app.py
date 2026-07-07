"""QApplication bootstrap: run the engine on a worker thread, reflect its
events in the tray icon and overlay.

Threading contract: the engine calls `event_sink(name, payload)` from its own
threads; the sink only puts the event on a queue. A QTimer on the Qt main
thread drains the queue and updates the widgets — no Qt calls ever happen off
the main thread.
"""

import logging
import os
import queue
import sys
import threading

from PySide6.QtCore import QDir, QLockFile, QTimer
from PySide6.QtWidgets import QApplication, QMessageBox, QSystemTrayIcon

from .. import __version__, config
from ..hotkeys import HOTKEY_MAP
from .logviewer import LogViewer
from .overlay import Overlay
from .settings import SettingsDialog
from .tray import ScribeTray

log = logging.getLogger(__name__)

POLL_MS = 50          # engine → UI latency budget (ROADMAP: visible < 100 ms)
WARNING_RESET_MS = 5000


def acquire_instance_lock():
    """One tray per machine: returns a held QLockFile, or None if another
    Scribe UI already owns it. QLockFile detects stale locks from crashes."""
    lock = QLockFile(os.path.join(QDir.tempPath(), "scribe-ui.lock"))
    if not lock.tryLock(0):
        return None
    return lock


class ScribeUI:
    """Owns the Qt-side objects and consumes engine events from the queue."""

    def __init__(self, scribe, settings, app=None):
        self.app = app or QApplication.instance() or QApplication(sys.argv)
        self.app.setApplicationName("Scribe")
        self.app.setQuitOnLastWindowClosed(False)  # tray app: no windows ≠ quit

        self.scribe = scribe
        self.settings = settings
        self._events = queue.Queue()
        # The sink runs on engine threads and must stay Qt-free: enqueue only.
        scribe.event_sink = self._event_sink

        self.tray = ScribeTray()
        self.overlay = Overlay()
        self._settings_dialog = None
        self._log_viewer = None
        self._engine_thread = None

        self.tray.pause_action.toggled.connect(self._toggle_pause)
        self.tray.settings_action.triggered.connect(self.open_settings)
        self.tray.advanced_action.triggered.connect(self.open_log_viewer)
        self.tray.about_action.triggered.connect(self._about)
        self.tray.quit_action.triggered.connect(self.quit)
        self.tray.show()

        self._poll_timer = QTimer()
        self._poll_timer.setInterval(POLL_MS)
        self._poll_timer.timeout.connect(self._drain_events)
        self._poll_timer.start()

        self._warning_reset = QTimer()
        self._warning_reset.setSingleShot(True)
        self._warning_reset.setInterval(WARNING_RESET_MS)
        self._warning_reset.timeout.connect(self._clear_warning)

    # -- engine side (any thread) --

    def _event_sink(self, name, payload):
        self._events.put((name, payload))

    def start_engine(self):
        self._engine_thread = threading.Thread(
            target=self.scribe.run, name="scribe-engine", daemon=True
        )
        self._engine_thread.start()

    # -- Qt side (main thread) --

    def _drain_events(self):
        while True:
            try:
                name, payload = self._events.get_nowait()
            except queue.Empty:
                return
            try:
                self._dispatch(name, payload)
            except Exception:
                log.exception("UI dispatch failed for event %r", name)

    def _dispatch(self, name, payload):
        if name == "model_loading":
            self.tray.set_state("transcribing")
            self.tray.setToolTip(f"Scribe — loading {payload.get('model')}…")
        elif name == "model_loaded":
            self.tray.set_state("idle")
            self.tray.setToolTip(
                f"Scribe — {payload.get('model')} ready [{payload.get('backend')}]"
            )
        elif name == "recording_started":
            self.tray.set_state("recording")
            self.overlay.show_recording()
        elif name == "recording_stopped":
            # If real audio was captured, a "transcribing" event follows in the
            # same batch; otherwise (too short / no audio) we go back to idle.
            self.tray.set_state("idle")
            self.overlay.hide_now()
        elif name == "transcribing":
            self.tray.set_state("transcribing")
            self.overlay.show_transcribing()
        elif name == "injected":
            self.tray.set_state("idle")
            self.overlay.finish()
        elif name == "error":
            self.tray.set_state("warning")
            self.tray.setToolTip(f"Scribe — {payload.get('message', 'error')}")
            self.overlay.hide_now()
            self._warning_reset.start()
        else:
            log.debug("unknown engine event: %s %s", name, payload)

    def _clear_warning(self):
        if self.tray.state == "warning":
            self.tray.set_state("idle")

    def _toggle_pause(self, paused):
        self.scribe.paused = paused
        if paused:
            self.tray.setToolTip("Scribe — paused")
        else:
            self.tray.setToolTip("Scribe — offline dictation")

    def open_settings(self):
        # Recreate each time so the dialog reflects the config file on disk.
        if self._settings_dialog is not None:
            self._settings_dialog.raise_()
            self._settings_dialog.activateWindow()
            return
        self._settings_dialog = SettingsDialog(scribe=self.scribe)
        self._settings_dialog.finished.connect(self._settings_closed)
        self._settings_dialog.show()

    def _settings_closed(self, _result):
        if self._settings_dialog is not None:
            self._settings_dialog.deleteLater()
            self._settings_dialog = None

    def open_log_viewer(self):
        if self._log_viewer is None:
            self._log_viewer = LogViewer()
        self._log_viewer.show()
        self._log_viewer.raise_()
        self._log_viewer.activateWindow()

    def _about(self):
        QMessageBox.about(
            None,
            "About Scribe",
            f"<b>Scribe {__version__}</b><br>"
            "Fully offline push-to-talk dictation.<br>"
            "Hold a key, speak, release — text appears at your cursor.<br><br>"
            "Nothing leaves your machine. No cloud, no telemetry.",
        )

    def quit(self):
        self.scribe.shutdown.set()
        self.scribe.work_event.set()
        self._poll_timer.stop()
        self.tray.hide()
        self.overlay.hide_now()
        self.app.quit()


def run_ui(scribe, settings):
    """Entry point used by `python -m scribe --ui`. Returns an exit code."""
    app = QApplication.instance() or QApplication(sys.argv)

    lock = acquire_instance_lock()
    if lock is None:
        print("  Scribe is already running — check the system tray.")
        return 1

    if not QSystemTrayIcon.isSystemTrayAvailable():
        # Wayland compositors without StatusNotifier, odd sessions, etc.
        # Keep running: the overlay still shows recording state.
        print("  No system tray available — overlay only (ROADMAP §7).")

    # First run (no config file yet): onboard before the engine starts.
    # Cancelling skips saving, so the wizard simply reappears next launch.
    if not os.path.exists(config.config_path()):
        from .wizard import FirstRunWizard

        wizard = FirstRunWizard(settings)
        if wizard.exec() and wizard.result_settings:
            settings = wizard.result_settings
            hotkey = HOTKEY_MAP.get(settings.get("hotkey"))
            if hotkey is not None:
                scribe.hotkey = hotkey

    ui = ScribeUI(scribe, settings, app=app)
    ui.start_engine()
    try:
        return app.exec()
    finally:
        scribe.shutdown.set()
        scribe.work_event.set()
        lock.unlock()
