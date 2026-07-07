"""The engine must stay UI-free: nothing outside scribe.ui may import Qt.

This is a hard rule from ROADMAP §8 — headless mode (CLI, systemd service)
must work on machines with no GUI stack at all.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))


class EngineImportsNoQt(unittest.TestCase):
    def test_engine_modules_do_not_import_qt(self):
        import scribe.audio          # noqa: F401
        import scribe.backends       # noqa: F401
        import scribe.config         # noqa: F401
        import scribe.engine         # noqa: F401
        import scribe.hotkeys        # noqa: F401
        import scribe.inject         # noqa: F401
        import scribe.logsetup       # noqa: F401
        import scribe.postproc       # noqa: F401
        import scribe.vad            # noqa: F401

        qt_modules = [m for m in sys.modules if m.startswith(("PySide", "PyQt"))]
        self.assertEqual(qt_modules, [], f"engine imported Qt: {qt_modules}")


if __name__ == "__main__":
    unittest.main()
