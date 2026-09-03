"""First-run wizard (ROADMAP §7 Phase 5).

Shown by app.run_ui() when no config file exists yet. Pages:

  1. Welcome — what Scribe is, the privacy promise.
  2. Microphone — device picker + live level meter (reuses AudioTab).
  3. Push-to-talk key — press-the-actual-key capture (reuses KeyCaptureButton).
  4. Model download — loads the model once on a worker thread, which both
     downloads it (the only network step, clearly labeled) and proves it runs.
  5. Done — how to dictate.

Finishing saves the config file, so the wizard never shows again; cancelling
saves nothing and the wizard reappears next launch.
"""

import logging

from PySide6.QtCore import QThread, Signal
from PySide6.QtWidgets import (
    QCheckBox,
    QFormLayout,
    QLabel,
    QProgressBar,
    QVBoxLayout,
    QWizard,
    QWizardPage,
)

from .. import config
from .settings import AudioTab, KeyCaptureButton

log = logging.getLogger(__name__)


class _ModelLoader(QThread):
    """Downloads + loads models off the Qt thread; reports one-line statuses."""

    status = Signal(str)
    finished_ok = Signal(bool, str)  # (success, message)

    def __init__(self, device, model, include_heavy=False):
        super().__init__()
        self.device = device
        self.model = model
        self.include_heavy = include_heavy

    def run(self):
        try:
            from ..backends import autodetect_device, make_transcriber
            from ..engine import HEAVY_MODEL

            device = self.device
            if device == "auto":
                device = autodetect_device()

            self.status.emit(f"Preparing '{self.model}' — downloading if needed…")
            make_transcriber(device).load(self.model)

            if self.include_heavy:
                self.status.emit(
                    f"Preparing high-accuracy model '{HEAVY_MODEL}' (~1 GB, one-time)…"
                )
                make_transcriber(device, precision="_int8").load(HEAVY_MODEL)

            self.finished_ok.emit(True, f"Ready — '{self.model}' works on this machine.")
        except Exception:
            log.exception("wizard model preparation failed")
            self.finished_ok.emit(
                False,
                "Model setup failed — check your internet connection and see "
                "the log for details. You can finish anyway; Scribe will retry "
                "at startup.",
            )


class _DownloadPage(QWizardPage):
    """Commit page (no going back) that runs the model preparation."""

    def __init__(self, settings):
        super().__init__()
        self.setTitle("Speech model")
        self.setSubTitle(
            "Scribe downloads its speech model once, then works fully offline. "
            "This is the only time Scribe touches the network."
        )
        self.setCommitPage(True)
        self._settings = settings
        self._loader = None
        self._done = False

        self.heavy_check = QCheckBox(
            "Also download the high-accuracy model now (~1 GB). "
            "Otherwise it downloads on first use of boost mode."
        )
        self.progress = QProgressBar()
        self.progress.setRange(0, 0)  # indeterminate while working
        self.progress.hide()
        self.status = QLabel("Click Next to download and verify the model.")
        self.status.setWordWrap(True)

        layout = QVBoxLayout(self)
        layout.addWidget(self.heavy_check)
        layout.addWidget(self.progress)
        layout.addWidget(self.status)

    def isComplete(self):
        return self._done

    def initializePage(self):
        self._start()

    def _start(self):
        if self._loader is not None:
            return
        self.heavy_check.setEnabled(False)
        self.progress.show()
        self._loader = _ModelLoader(
            self._settings.get("device", "auto"),
            self._settings.get("model", config.DEFAULTS["model"]),
            include_heavy=self.heavy_check.isChecked(),
        )
        self._loader.status.connect(self.status.setText)
        self._loader.finished_ok.connect(self._finished)
        self._loader.start()

    def _finished(self, ok, message):
        self.progress.setRange(0, 1)
        self.progress.setValue(1 if ok else 0)
        self.status.setText(message)
        self._done = True  # even on failure the user may finish; engine retries
        self.completeChanged.emit()


class FirstRunWizard(QWizard):
    """Returns the chosen settings via `result_settings` after exec()."""

    def __init__(self, settings, parent=None):
        super().__init__(parent)
        self.setWindowTitle("Welcome to Scribe")
        self.setWizardStyle(QWizard.ModernStyle)
        self.setOption(QWizard.NoBackButtonOnStartPage, True)
        self._settings = dict(settings)
        self.result_settings = None

        # 1 — welcome / privacy
        welcome = QWizardPage()
        welcome.setTitle("Welcome to Scribe")
        welcome.setSubTitle("Offline dictation: hold a key, speak, release.")
        w_label = QLabel(
            "Scribe types what you say, wherever your cursor is — in any app.<br><br>"
            "<b>Everything stays on this device.</b> Your voice is processed "
            "locally and never sent anywhere. No account, no cloud, no telemetry."
        )
        w_label.setWordWrap(True)
        QVBoxLayout(welcome).addWidget(w_label)
        self.addPage(welcome)

        # 2 — microphone
        mic = QWizardPage()
        mic.setTitle("Microphone")
        mic.setSubTitle("Pick your microphone and check that the level bar moves.")
        self._audio_tab = AudioTab(current_device=self._settings.get("input_device"))
        QVBoxLayout(mic).addWidget(self._audio_tab)
        self.addPage(mic)

        # 3 — push-to-talk key
        keys = QWizardPage()
        keys.setTitle("Push-to-talk key")
        keys.setSubTitle(
            "You hold this key while speaking. Right Alt works well — it's "
            "rarely used for anything else."
        )
        self._hotkey_button = KeyCaptureButton(self._settings.get("hotkey", "ralt"))
        form = QFormLayout(keys)
        form.addRow("Push-to-talk key:", self._hotkey_button)
        form.addRow("", QLabel("Click the button, then press the key you want."))
        self.addPage(keys)

        # 4 — model download (commit page)
        self.addPage(_DownloadPage(self._settings))

        # 5 — done
        done = QWizardPage()
        done.setTitle("You're set")
        self._done_label = QLabel()
        self._done_label.setWordWrap(True)
        done.setFinalPage(True)
        QVBoxLayout(done).addWidget(self._done_label)
        self.addPage(done)
        self.currentIdChanged.connect(self._refresh_done_text)

    def _refresh_done_text(self, _page_id):
        key = self._hotkey_button.key_name
        self._done_label.setText(
            f"<b>Hold [{key}]</b>, speak, release — your words appear at the "
            "cursor.<br><br>Hold <b>[rshift] + "
            f"[{key}]</b> for high-accuracy mode.<br><br>"
            "Scribe lives in your system tray: pause it, open Settings, or "
            "quit from there. Try dictating into any text field right after "
            "you click Finish."
        )

    def accept(self):
        settings = dict(self._settings)
        settings.update(
            hotkey=self._hotkey_button.key_name,
            input_device=self._audio_tab.selected_device(),
        )
        try:
            config.save(settings)
        except Exception:
            log.exception("saving config from wizard failed")
        self.result_settings = settings
        super().accept()
