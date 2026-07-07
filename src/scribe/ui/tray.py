"""System tray icon with engine-state colors and the main menu.

Icons are simple colored dots drawn in code with QPainter — no binary assets
in the repo. States: idle (gray), recording (red), transcribing (blue),
warning (yellow).
"""

from PySide6.QtCore import Qt
from PySide6.QtGui import QColor, QIcon, QPainter, QPixmap
from PySide6.QtWidgets import QMenu, QSystemTrayIcon

STATE_COLORS = {
    "idle": "#8a9199",
    "recording": "#e5484d",
    "transcribing": "#3a86ff",
    "warning": "#f5c518",
}


def make_dot_icon(color):
    """A round colored-dot QIcon rendered with QPainter (no image files)."""
    pixmap = QPixmap(64, 64)
    pixmap.fill(Qt.transparent)
    painter = QPainter(pixmap)
    painter.setRenderHint(QPainter.Antialiasing)
    painter.setPen(Qt.NoPen)
    # Subtle dark ring so the dot reads on both light and dark taskbars.
    painter.setBrush(QColor(0, 0, 0, 60))
    painter.drawEllipse(6, 6, 52, 52)
    painter.setBrush(QColor(color))
    painter.drawEllipse(10, 10, 44, 44)
    painter.end()
    return QIcon(pixmap)


class ScribeTray(QSystemTrayIcon):
    """Tray icon + menu. Pure view: app.py connects the menu actions."""

    def __init__(self, parent=None):
        super().__init__(parent)
        self._icons = {state: make_dot_icon(c) for state, c in STATE_COLORS.items()}
        self.state = "idle"
        self.setIcon(self._icons["idle"])
        self.setToolTip("Scribe — offline dictation")

        # Keep a reference: QSystemTrayIcon does not take ownership of the menu.
        self._menu = QMenu()
        self.pause_action = self._menu.addAction("Pause dictation")
        self.pause_action.setCheckable(True)
        self.settings_action = self._menu.addAction("Settings…")
        self.advanced_action = self._menu.addAction("Advanced mode (log viewer)")
        self._menu.addSeparator()
        self.about_action = self._menu.addAction("About Scribe")
        self.quit_action = self._menu.addAction("Quit")
        self.setContextMenu(self._menu)

    def set_state(self, state):
        icon = self._icons.get(state)
        if icon is None:
            state, icon = "idle", self._icons["idle"]
        self.state = state
        self.setIcon(icon)
