"""QML front end (Qt Quick Controls, Material Dark).

`run_qml_ui(scribe, settings)` builds the app: a QQmlApplicationEngine hosting
Main.qml, an AppBridge exposed to QML as `app`, a system-tray icon that
minimizes/restores the window, and a single-instance guard. The engine runs on
a worker thread; all UI updates arrive through the bridge on the Qt thread.
"""

import logging
import os
import sys
import threading

from PySide6.QtCore import QCoreApplication, QDir, QLockFile, QUrl
from PySide6.QtGui import QGuiApplication, QIcon, QPainter, QPixmap, QColor
from PySide6.QtQml import QQmlApplicationEngine
from PySide6.QtQuickControls2 import QQuickStyle
from PySide6.QtWidgets import QApplication, QMenu, QSystemTrayIcon

from .bridge import AppBridge

log = logging.getLogger(__name__)

_STATUS_COLORS = {
    "loading": "#FFC24D", "ready": "#48E39B", "recording": "#FF6584",
    "transcribing": "#FFC24D", "error": "#FF6584",
}


def _tray_icon(color="#34E4CE"):
    """A simple round dot icon so we ship no binary assets."""
    pm = QPixmap(32, 32)
    pm.fill(QColor(0, 0, 0, 0))
    p = QPainter(pm)
    p.setRenderHint(QPainter.Antialiasing)
    p.setBrush(QColor(color))
    p.setPen(QColor(0, 0, 0, 0))
    p.drawEllipse(6, 6, 20, 20)
    p.end()
    return QIcon(pm)


def _acquire_lock():
    lock = QLockFile(os.path.join(QDir.tempPath(), "scribe-ui.lock"))
    return lock if lock.tryLock(0) else None


def run_qml_ui(scribe, settings):
    QCoreApplication.setOrganizationName("Scribe")
    QCoreApplication.setApplicationName("Scribe")
    QQuickStyle.setStyle("Material")

    app = QApplication.instance() or QApplication(sys.argv)
    app.setQuitOnLastWindowClosed(False)  # closing the window hides to tray

    lock = _acquire_lock()
    if lock is None:
        print("  Scribe is already running — check the system tray.")
        return 1

    bridge = AppBridge(scribe, settings)

    engine = QQmlApplicationEngine()
    engine.rootContext().setContextProperty("app", bridge)
    qml_dir = os.path.join(os.path.dirname(__file__), "qml")
    engine.load(QUrl.fromLocalFile(os.path.join(qml_dir, "Main.qml")))
    if not engine.rootObjects():
        log.error("QML failed to load from %s", qml_dir)
        return 2
    window = engine.rootObjects()[0]

    # ---- tray ----
    tray = None
    if QSystemTrayIcon.isSystemTrayAvailable():
        tray = QSystemTrayIcon(_tray_icon(), app)
        tray.setToolTip("Scribe — offline dictation")
        menu = QMenu()
        act_show = menu.addAction("Open Scribe")
        act_pause = menu.addAction("Pause dictation")
        act_pause.setCheckable(True)
        menu.addSeparator()
        act_quit = menu.addAction("Quit")
        tray.setContextMenu(menu)

        def show_window():
            window.show()
            window.raise_()
            window.requestActivate()

        act_show.triggered.connect(show_window)
        act_pause.toggled.connect(bridge.setPaused)
        act_quit.triggered.connect(bridge.quit)
        tray.activated.connect(
            lambda reason: show_window()
            if reason == QSystemTrayIcon.Trigger else None)

        def on_status():
            tray.setIcon(_tray_icon(_STATUS_COLORS.get(bridge.status, "#34E4CE")))
            tray.setToolTip(f"Scribe — {bridge.statusDetail}")
        bridge.statusChanged.connect(on_status)
        tray.show()
    else:
        print("  No system tray available — the window stays open.")

    # ---- run the engine on a worker thread ----
    threading.Thread(target=scribe.run, name="scribe-engine", daemon=True).start()

    try:
        return app.exec()
    finally:
        scribe.shutdown.set()
        scribe.work_event.set()
        lock.unlock()
