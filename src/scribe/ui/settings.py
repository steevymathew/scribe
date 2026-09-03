"""Settings dialog: General / Audio / Advanced tabs.

Saving writes the config file via scribe.config.save(). Hotkey and boost-key
changes apply to a running engine immediately; model, device and beam-size
changes need a restart (hot-swapping models is out of scope for Phase 3) —
the dialog says so.
"""

import logging

import numpy as np
from PySide6.QtCore import QTimer, QUrl, Signal
from PySide6.QtGui import QDesktopServices
from PySide6.QtWidgets import (
    QCheckBox,
    QComboBox,
    QDialog,
    QDialogButtonBox,
    QFormLayout,
    QLabel,
    QProgressBar,
    QPushButton,
    QSpinBox,
    QTabWidget,
    QVBoxLayout,
    QWidget,
)

from .. import config, logsetup
from ..audio import SAMPLE_RATE, list_input_devices
from ..hotkeys import HOTKEY_MAP, match_key

log = logging.getLogger(__name__)

MODEL_CHOICES = ["tiny.en", "base.en", "small.en", "medium.en"]
DEVICE_CHOICES = ["auto", "cpu", "npu", "cuda"]
SYSTEM_DEFAULT_DEVICE = "System default"


class KeyCaptureButton(QPushButton):
    """Press-the-actual-key hotkey picker.

    Click → "press a key…" → a one-shot pynput listener grabs the next key
    press and maps it to a HOTKEY_MAP name. AltGr counts as Right Alt
    (RIGHT_ALT_KEYS in scribe.hotkeys), so match_key() does the mapping and
    AltGr keyboards land on "ralt" like the engine expects.
    """

    # pynput calls us from its own thread; a signal hops back to the Qt thread.
    _raw_key = Signal(object)

    def __init__(self, key_name="ralt", parent=None):
        super().__init__(key_name, parent)
        self.key_name = key_name
        self._listener = None
        self._raw_key.connect(self._on_key)
        self.clicked.connect(self._begin_capture)

    def _begin_capture(self):
        if self._listener is not None:
            return
        self.setText("press a key…")
        from pynput import keyboard  # engine dependency, already installed

        def on_press(key):
            self._raw_key.emit(key)
            return False  # one key is all we want; stop the listener

        self._listener = keyboard.Listener(on_press=on_press)
        self._listener.start()

    def _on_key(self, key):
        self._listener = None
        for name, target in HOTKEY_MAP.items():
            if match_key(key, target):
                self.key_name = name
                self.setText(name)
                return
        self.setText(f"{self.key_name}  (unsupported key — click to retry)")

    def set_key(self, name):
        self.key_name = name
        self.setText(name)


class AudioTab(QWidget):
    """Input device picker + live level meter.

    The meter stream is only open while the tab is visible; it never touches
    the engine's recording stream.
    """

    def __init__(self, current_device=None, parent=None):
        super().__init__(parent)
        self._stream = None
        self._level = 0.0

        self.device_combo = QComboBox()
        self.device_combo.addItem(SYSTEM_DEFAULT_DEVICE)
        try:
            for _index, name in list_input_devices():
                self.device_combo.addItem(name)
        except Exception:
            log.exception("could not enumerate input devices")
        if current_device:
            i = self.device_combo.findText(current_device)
            if i < 0:
                self.device_combo.addItem(current_device)
                i = self.device_combo.count() - 1
            self.device_combo.setCurrentIndex(i)

        self.meter = QProgressBar()
        self.meter.setRange(0, 100)
        self.meter.setValue(0)
        self.meter.setTextVisible(False)
        self.status_label = QLabel("")

        form = QFormLayout(self)
        form.addRow("Input device:", self.device_combo)
        form.addRow("Level:", self.meter)
        form.addRow("", self.status_label)
        form.addRow("", QLabel("Device changes take effect after restart."))

        self._timer = QTimer(self)
        self._timer.setInterval(50)
        self._timer.timeout.connect(self._update_meter)

    def selected_device(self):
        name = self.device_combo.currentText()
        return None if name == SYSTEM_DEFAULT_DEVICE else name

    def showEvent(self, event):
        self._start_meter()
        super().showEvent(event)

    def hideEvent(self, event):
        self._stop_meter()
        super().hideEvent(event)

    def _start_meter(self):
        if self._stream is not None:
            return
        try:
            import sounddevice as sd

            def callback(indata, frames, time_info, status):
                self._level = float(np.sqrt(np.mean(np.square(indata))))

            self._stream = sd.InputStream(
                samplerate=SAMPLE_RATE,
                channels=1,
                dtype="float32",
                blocksize=1024,
                callback=callback,
            )
            self._stream.start()
            self.status_label.setText("Speak — the bar should move.")
        except Exception:
            log.exception("level-meter stream failed")
            self._stream = None
            self.status_label.setText(
                "Microphone unavailable — check your system sound settings."
            )
        self._timer.start()

    def _stop_meter(self):
        self._timer.stop()
        if self._stream is not None:
            try:
                self._stream.stop()
                self._stream.close()
            except Exception:
                log.debug("meter stream close failed", exc_info=True)
            self._stream = None
        self._level = 0.0
        self.meter.setValue(0)

    def _update_meter(self):
        # Speech RMS is roughly 0.02–0.3; scale so normal speech fills the bar.
        self.meter.setValue(min(100, int(self._level * 400)))


class SettingsDialog(QDialog):
    def __init__(self, settings=None, scribe=None, parent=None):
        super().__init__(parent)
        self.setWindowTitle("Scribe Settings")
        self._scribe = scribe
        self._settings = dict(settings) if settings else config.effective({})

        tabs = QTabWidget()
        tabs.addTab(self._build_general(), "General")
        self._audio_tab = AudioTab(current_device=self._settings.get("input_device"))
        tabs.addTab(self._audio_tab, "Audio")
        tabs.addTab(self._build_advanced(), "Advanced")

        note = QLabel(
            "Model, device and beam-size changes take effect the next time "
            "Scribe starts. Hotkey changes apply immediately."
        )
        note.setWordWrap(True)

        buttons = QDialogButtonBox(QDialogButtonBox.Save | QDialogButtonBox.Cancel)
        buttons.accepted.connect(self._save)
        buttons.rejected.connect(self.reject)

        layout = QVBoxLayout(self)
        layout.addWidget(tabs)
        layout.addWidget(note)
        layout.addWidget(buttons)

    # -- tabs --

    def _build_general(self):
        page = QWidget()
        form = QFormLayout(page)

        self.hotkey_button = KeyCaptureButton(self._settings.get("hotkey", "ralt"))
        form.addRow("Push-to-talk key:", self.hotkey_button)

        self.boost_combo = QComboBox()
        self.boost_combo.addItems(list(HOTKEY_MAP.keys()))
        self.boost_combo.setCurrentText(self._settings.get("boost_key", "rshift"))
        form.addRow("Boost key:", self.boost_combo)

        self.model_combo = QComboBox()
        self.model_combo.addItems(MODEL_CHOICES)
        current_model = self._settings.get("model", "small.en")
        if self.model_combo.findText(current_model) < 0:
            self.model_combo.addItem(current_model)
        self.model_combo.setCurrentText(current_model)
        form.addRow("Model:", self.model_combo)

        self.device_combo = QComboBox()
        self.device_combo.addItems(DEVICE_CHOICES)
        current_device = self._settings.get("device", "auto")
        if self.device_combo.findText(current_device) < 0:
            self.device_combo.addItem(current_device)
        self.device_combo.setCurrentText(current_device)
        form.addRow("Compute device:", self.device_combo)

        return page

    def _build_advanced(self):
        page = QWidget()
        form = QFormLayout(page)

        self.beam_spin = QSpinBox()
        self.beam_spin.setRange(1, 10)
        self.beam_spin.setValue(int(self._settings.get("beam_size", 1) or 1))
        form.addRow("Beam size:", self.beam_spin)

        self.advanced_check = QCheckBox("Advanced mode (stream full log to console)")
        self.advanced_check.setChecked(bool(self._settings.get("advanced")))
        form.addRow("", self.advanced_check)

        open_logs = QPushButton("Open log folder")
        open_logs.clicked.connect(
            lambda: QDesktopServices.openUrl(QUrl.fromLocalFile(logsetup.log_dir()))
        )
        form.addRow("", open_logs)

        return page

    # -- save --

    def _save(self):
        settings = dict(self._settings)
        settings.update(
            hotkey=self.hotkey_button.key_name,
            boost_key=self.boost_combo.currentText(),
            model=self.model_combo.currentText(),
            device=self.device_combo.currentText(),
            beam_size=self.beam_spin.value(),
            advanced=self.advanced_check.isChecked(),
            input_device=self._audio_tab.selected_device(),
        )
        try:
            path = config.save(settings)
            log.info("settings saved to %s", path)
        except Exception:
            log.exception("saving settings failed")

        # Apply what we can without a restart: the engine reads hotkey/boost_key
        # on every key event, so swapping them live is safe.
        if self._scribe is not None:
            hotkey = HOTKEY_MAP.get(settings["hotkey"])
            boost = HOTKEY_MAP.get(settings["boost_key"])
            if hotkey is not None:
                self._scribe.hotkey = hotkey
            if boost is not None:
                self._scribe.boost_key = boost

        self.accept()
