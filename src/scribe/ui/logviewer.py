"""Advanced mode: a live tail of the Scribe log file."""

import os

from PySide6.QtCore import Qt, QTimer
from PySide6.QtGui import QFont, QGuiApplication
from PySide6.QtWidgets import (
    QHBoxLayout,
    QLabel,
    QPlainTextEdit,
    QPushButton,
    QVBoxLayout,
    QWidget,
)

from .. import logsetup

TAIL_BYTES = 128 * 1024  # show at most the last 128 KB
REFRESH_MS = 1000


def current_log_path():
    """The active log file (set by setup_logging), or the default location."""
    return logsetup.LOG_PATH or os.path.join(logsetup.log_dir(), "scribe.log")


class LogViewer(QWidget):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowTitle("Scribe — Log")
        self.resize(760, 420)
        self._path = current_log_path()
        self._last_size = -1

        top = QHBoxLayout()
        self._path_label = QLabel(self._path)
        self._path_label.setTextInteractionFlags(Qt.TextSelectableByMouse)
        copy_btn = QPushButton("Copy path")
        copy_btn.clicked.connect(self._copy_path)
        top.addWidget(self._path_label, 1)
        top.addWidget(copy_btn)

        self._text = QPlainTextEdit()
        self._text.setReadOnly(True)
        self._text.setMaximumBlockCount(10000)
        font = QFont("Consolas")
        font.setStyleHint(QFont.Monospace)
        self._text.setFont(font)

        layout = QVBoxLayout(self)
        layout.addLayout(top)
        layout.addWidget(self._text)

        self._timer = QTimer(self)
        self._timer.setInterval(REFRESH_MS)
        self._timer.timeout.connect(self.refresh)
        self.refresh()

    def showEvent(self, event):
        self._timer.start()
        super().showEvent(event)

    def hideEvent(self, event):
        self._timer.stop()
        super().hideEvent(event)

    def _copy_path(self):
        QGuiApplication.clipboard().setText(self._path)

    def refresh(self):
        # The engine may have called setup_logging after we were constructed.
        path = current_log_path()
        if path != self._path:
            self._path = path
            self._path_label.setText(path)
            self._last_size = -1
        try:
            size = os.path.getsize(path)
        except OSError:
            self._text.setPlainText(f"(no log file yet at {path})")
            self._last_size = -1
            return
        if size == self._last_size:
            return  # unchanged — don't churn the widget every second
        self._last_size = size
        try:
            with open(path, "rb") as f:
                if size > TAIL_BYTES:
                    f.seek(size - TAIL_BYTES)
                data = f.read()
        except OSError:
            return
        text = data.decode("utf-8", errors="replace")
        if size > TAIL_BYTES:
            # Drop the first (probably partial) line of the tail window.
            text = text.split("\n", 1)[-1]
        self._text.setPlainText(text)
        self._text.verticalScrollBar().setValue(
            self._text.verticalScrollBar().maximum()
        )
