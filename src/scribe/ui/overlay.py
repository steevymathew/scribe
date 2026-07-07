"""Recording pill: a small frameless, click-through, always-on-top overlay.

Shows a red dot + "Listening…" while the hotkey is held, a spinner +
"Transcribing…" after release, then auto-hides once text is injected.
It must never steal focus from the window the user is dictating into.
"""

from PySide6.QtCore import QRect, Qt, QTimer
from PySide6.QtGui import QColor, QFont, QGuiApplication, QPainter, QPen
from PySide6.QtWidgets import QWidget

PILL_WIDTH = 210
PILL_HEIGHT = 44
BOTTOM_MARGIN = 56


class Overlay(QWidget):
    def __init__(self):
        super().__init__(
            None,
            Qt.FramelessWindowHint
            | Qt.Tool
            | Qt.WindowStaysOnTopHint
            | Qt.WindowDoesNotAcceptFocus,
        )
        # Never activate, never take clicks — the user is typing elsewhere.
        self.setAttribute(Qt.WA_ShowWithoutActivating)
        self.setAttribute(Qt.WA_TranslucentBackground)
        self.setAttribute(Qt.WA_TransparentForMouseEvents)
        self.setFixedSize(PILL_WIDTH, PILL_HEIGHT)

        self._state = "hidden"  # hidden | recording | transcribing
        self._angle = 0

        self._spin_timer = QTimer(self)
        self._spin_timer.setInterval(80)
        self._spin_timer.timeout.connect(self._spin_tick)

        self._hide_timer = QTimer(self)
        self._hide_timer.setSingleShot(True)
        self._hide_timer.timeout.connect(self.hide_now)

    # -- state changes (call from the Qt thread only; app.py guarantees that) --

    def show_recording(self):
        self._hide_timer.stop()
        self._spin_timer.stop()
        self._state = "recording"
        self._reposition()
        self.show()
        self.update()

    def show_transcribing(self):
        self._hide_timer.stop()
        self._state = "transcribing"
        self._reposition()
        self.show()
        self._spin_timer.start()
        self.update()

    def finish(self, delay_ms=350):
        """Auto-hide shortly after injection (long enough to read the pill)."""
        self._hide_timer.start(delay_ms)

    def hide_now(self):
        self._hide_timer.stop()
        self._spin_timer.stop()
        self._state = "hidden"
        self.hide()

    # -- internals --

    def _spin_tick(self):
        self._angle = (self._angle + 30) % 360
        self.update()

    def _reposition(self):
        screen = QGuiApplication.primaryScreen()
        if screen is None:
            return
        geo = screen.availableGeometry()
        x = geo.x() + (geo.width() - self.width()) // 2
        y = geo.y() + geo.height() - self.height() - BOTTOM_MARGIN
        self.move(x, y)

    def paintEvent(self, event):
        if self._state == "hidden":
            return
        painter = QPainter(self)
        painter.setRenderHint(QPainter.Antialiasing)

        painter.setPen(Qt.NoPen)
        painter.setBrush(QColor(24, 25, 28, 232))
        painter.drawRoundedRect(self.rect(), PILL_HEIGHT // 2, PILL_HEIGHT // 2)

        cy = self.height() // 2
        if self._state == "recording":
            painter.setBrush(QColor("#e5484d"))
            painter.drawEllipse(18, cy - 6, 12, 12)
            text = "Listening…"
        else:
            pen = QPen(QColor("#3a86ff"), 3)
            pen.setCapStyle(Qt.RoundCap)
            painter.setPen(pen)
            painter.setBrush(Qt.NoBrush)
            # 270°-open arc rotating each tick = spinner.
            painter.drawArc(QRect(16, cy - 8, 16, 16), -self._angle * 16, 270 * 16)
            text = "Transcribing…"

        painter.setPen(QColor(240, 240, 243))
        font = QFont(self.font())
        font.setPointSize(10)
        painter.setFont(font)
        painter.drawText(
            QRect(44, 0, self.width() - 52, self.height()),
            Qt.AlignVCenter | Qt.AlignLeft,
            text,
        )
        painter.end()
