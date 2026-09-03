"""Offscreen smoke tests for the Qt UI.

Skipped cleanly when PySide6 is not installed (e.g. the x64 CPU venv), so the
headless test run stays green. Availability is probed with find_spec — NOT an
import — so this module never loads Qt into a process where
test_engine_no_qt.py has yet to run.
"""

import importlib.util
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

# Must be set before QApplication exists: no display needed for these tests.
os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

HAVE_QT = importlib.util.find_spec("PySide6") is not None


@unittest.skipIf(not HAVE_QT, "PySide6 not installed — UI is optional")
class UISmoke(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        from PySide6.QtWidgets import QApplication

        cls.app = QApplication.instance() or QApplication([])

    def test_settings_dialog_builds(self):
        from scribe.ui.settings import SettingsDialog

        dlg = SettingsDialog()
        self.assertEqual(dlg.windowTitle(), "Scribe Settings")
        # Widgets exist and carry the effective settings.
        self.assertTrue(dlg.hotkey_button.key_name)
        self.assertGreaterEqual(dlg.model_combo.count(), 4)
        self.assertGreaterEqual(dlg.beam_spin.value(), 1)
        dlg.deleteLater()

    def test_overlay_states(self):
        from scribe.ui.overlay import Overlay

        overlay = Overlay()
        overlay.show_recording()
        overlay.show_transcribing()
        overlay.finish()
        overlay.hide_now()
        self.assertFalse(overlay.isVisible())
        overlay.deleteLater()

    def test_log_viewer_builds_and_refreshes(self):
        from scribe.ui.logviewer import LogViewer

        viewer = LogViewer()
        viewer.refresh()  # must not raise, with or without a log file
        viewer.deleteLater()

    def test_tray_state_icons(self):
        from scribe.ui.tray import ScribeTray

        tray = ScribeTray()
        for state in ("idle", "recording", "transcribing", "warning"):
            tray.set_state(state)
            self.assertEqual(tray.state, state)
            self.assertFalse(tray.icon().isNull())
        tray.deleteLater()

    def test_first_run_wizard_builds(self):
        from scribe import config
        from scribe.ui.wizard import FirstRunWizard

        wizard = FirstRunWizard(config.effective({k: None for k in config.DEFAULTS}))
        # 5 pages: welcome, mic, hotkey, download, done.
        self.assertEqual(len(wizard.pageIds()), 5)
        self.assertIsNone(wizard.result_settings)  # nothing saved until Finish
        wizard.deleteLater()


if __name__ == "__main__":
    unittest.main()
