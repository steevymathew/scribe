"""Engine ↔ QML bridge.

A single QObject exposed to QML as `app`. It:
  - receives engine events on the engine's threads (via `event_sink`) and
    re-emits them on the Qt main thread through a queued signal, so QML only
    ever sees main-thread updates;
  - exposes current state as QML Properties (status, model, backend, level…);
  - exposes actions as Slots (pause, quit, open log folder, save settings).

Nothing here imports the engine's heavy modules at construction time; the
Scribe instance is passed in already built.
"""

import logging
import os
import queue
import sys

from PySide6.QtCore import (
    Property, QObject, QTimer, Signal, Slot,
)

from .. import config, logsetup
from ..hotkeys import HOTKEY_MAP

log = logging.getLogger(__name__)


class AppBridge(QObject):
    # notify signals for QML property bindings
    statusChanged = Signal()
    levelChanged = Signal()
    lastTranscriptChanged = Signal()
    recentChanged = Signal()
    pausedChanged = Signal()

    # one-shot event for transient UI (e.g. a toast)
    transcriptAdded = Signal(str, float, str)  # text, seconds, backend

    def __init__(self, scribe, settings, parent=None):
        super().__init__(parent)
        self._scribe = scribe
        self._settings = dict(settings)
        self._status = "loading"       # loading | ready | recording | transcribing | error
        self._status_detail = "Starting…"
        self._level = 0.0
        self._last = ""
        self._recent = []              # newest-first, session only (history off by default)
        self._paused = False

        # Engine threads only enqueue; a timer drains on the Qt thread.
        self._events = queue.Queue()
        # event_sink is called as sink(name, payload); enqueue as one tuple
        # (Queue.put's own 2nd arg is `block`, so it can't be used directly).
        scribe.event_sink = lambda name, payload: self._events.put((name, payload))
        self._pump = QTimer(self)
        self._pump.setInterval(40)
        self._pump.timeout.connect(self._drain)
        self._pump.start()

        self._meter_stream = None
        self._meter_level = 0.0
        self._meter_timer = None

    # ---- engine event pump (Qt thread) ----

    def _drain(self):
        changed_status = False
        while True:
            try:
                name, payload = self._events.get_nowait()
            except queue.Empty:
                break
            changed_status |= self._apply(name, payload)
        if changed_status:
            self.statusChanged.emit()

    def _apply(self, name, payload):
        if name == "model_loading":
            self._status, self._status_detail = "loading", f"Loading {payload.get('model','model')}…"
        elif name == "model_loaded":
            self._status, self._status_detail = "ready", "Ready"
        elif name == "recording_started":
            self._status, self._status_detail = "recording", "Listening…"
        elif name == "recording_stopped":
            if self._status == "recording":
                self._status, self._status_detail = "ready", "Ready"
        elif name == "transcribing":
            self._status, self._status_detail = "transcribing", "Transcribing…"
        elif name == "injected":
            self._status, self._status_detail = "ready", "Ready"
            text = (payload.get("text") or "").strip()
            if text:
                self._last = text
                self._recent.insert(0, {
                    "text": text,
                    "seconds": round(float(payload.get("elapsed", 0)), 1),
                    "backend": payload.get("backend", ""),
                })
                del self._recent[24:]
                self.lastTranscriptChanged.emit()
                self.recentChanged.emit()
                self.transcriptAdded.emit(text, float(payload.get("elapsed", 0)),
                                          payload.get("backend", ""))
        elif name == "error":
            self._status, self._status_detail = "error", payload.get("message", "Something went wrong")
        else:
            return False
        return True

    # ---- properties for QML ----

    @Property(str, notify=statusChanged)
    def status(self):
        return self._status

    @Property(str, notify=statusChanged)
    def statusDetail(self):
        return self._status_detail

    @Property(str, constant=True)
    def modelName(self):
        return self._settings.get("model", "small.en")

    @Property(str, constant=True)
    def deviceName(self):
        d = self._scribe.device
        return {"npu": "NPU-ready · native ARM64", "cuda": "GPU (CUDA)",
                "cpu": "CPU"}.get(d, d)

    @Property(str, notify=lastTranscriptChanged)
    def lastTranscript(self):
        return self._last

    @Property("QVariantList", notify=recentChanged)
    def recent(self):
        return self._recent

    @Property(bool, notify=pausedChanged)
    def paused(self):
        return self._paused

    @Property(str, constant=True)
    def hotkeyName(self):
        return self._settings.get("hotkey", "ralt")

    @Property(str, constant=True)
    def version(self):
        from .. import __version__
        return __version__

    # ---- actions ----

    @Slot(bool)
    def setPaused(self, value):
        self._paused = bool(value)
        self._scribe.paused = self._paused
        self.pausedChanged.emit()

    @Slot()
    def openLogFolder(self):
        from PySide6.QtGui import QDesktopServices
        from PySide6.QtCore import QUrl
        QDesktopServices.openUrl(QUrl.fromLocalFile(logsetup.log_dir()))

    @Slot(str, str)
    def setHotkey(self, which, name):
        """which = 'hotkey' | 'boost_key'; name must be a HOTKEY_MAP key."""
        if name not in HOTKEY_MAP:
            return
        self._settings[which] = name
        if which == "hotkey":
            self._scribe.hotkey = HOTKEY_MAP[name]
        else:
            self._scribe.boost_key = HOTKEY_MAP[name]
        self._save()

    @Slot(str, "QVariant")
    def setSetting(self, key, value):
        if key in config.DEFAULTS:
            self._settings[key] = value
            self._save()

    @Slot(result="QVariant")
    def snapshotSettings(self):
        return dict(self._settings)

    def _save(self):
        try:
            config.save(self._settings)
        except Exception:
            log.exception("saving settings from UI failed")

    @Slot()
    def quit(self):
        from PySide6.QtWidgets import QApplication
        self._scribe.shutdown.set()
        self._scribe.work_event.set()
        app = QApplication.instance()
        if app:
            app.quit()

    def set_level(self, value):
        self._level = float(value)
        self.levelChanged.emit()

    @Property(float, notify=levelChanged)
    def level(self):
        return self._level

    # ---- live mic meter (Settings → Audio) ----

    @Slot()
    def startMeter(self):
        if self._meter_stream is not None:
            return
        try:
            import numpy as np
            import sounddevice as sd

            def cb(indata, frames, t, status):
                self._meter_level = float(np.sqrt(np.mean(np.square(indata))))

            self._meter_stream = sd.InputStream(
                samplerate=16000, channels=1, dtype="float32",
                blocksize=1024, callback=cb)
            self._meter_stream.start()
            self._meter_timer = QTimer(self)
            self._meter_timer.setInterval(50)
            self._meter_timer.timeout.connect(
                lambda: self.set_level(min(1.0, self._meter_level * 4)))
            self._meter_timer.start()
        except Exception:
            log.exception("mic meter failed to start")
            self._meter_stream = None

    @Slot()
    def stopMeter(self):
        if self._meter_timer is not None:
            self._meter_timer.stop()
            self._meter_timer = None
        if self._meter_stream is not None:
            try:
                self._meter_stream.stop()
                self._meter_stream.close()
            except Exception:
                log.debug("meter close failed", exc_info=True)
            self._meter_stream = None
        self.set_level(0.0)

    # ---- launch at login ----

    @Slot(bool, result=bool)
    def setAutostart(self, enabled):
        try:
            if sys.platform == "win32":
                import winreg
                run = r"Software\Microsoft\Windows\CurrentVersion\Run"
                key = winreg.OpenKey(winreg.HKEY_CURRENT_USER, run, 0,
                                     winreg.KEY_SET_VALUE)
                if enabled:
                    winreg.SetValueEx(key, "Scribe", 0, winreg.REG_SZ,
                                      f'"{sys.executable}"')
                else:
                    try:
                        winreg.DeleteValue(key, "Scribe")
                    except FileNotFoundError:
                        pass
                winreg.CloseKey(key)
                return True
        except Exception:
            log.exception("autostart toggle failed")
        return False

    @Slot(result=bool)
    def autostartEnabled(self):
        try:
            if sys.platform == "win32":
                import winreg
                run = r"Software\Microsoft\Windows\CurrentVersion\Run"
                key = winreg.OpenKey(winreg.HKEY_CURRENT_USER, run)
                try:
                    winreg.QueryValueEx(key, "Scribe")
                    return True
                except FileNotFoundError:
                    return False
                finally:
                    winreg.CloseKey(key)
        except Exception:
            log.debug("autostart query failed", exc_info=True)
        return False
