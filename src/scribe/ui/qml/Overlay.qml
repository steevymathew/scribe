import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// Frameless, click-through, always-on-top pill near the bottom of the screen.
// Shows Listening / Transcribing with a live level, and a brief "done" flash —
// visible even when the main window is hidden to the tray.
Window {
    id: ov
    width: 268; height: 60
    flags: Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint | Qt.Tool
           | Qt.WindowDoesNotAcceptFocus | Qt.WindowTransparentForInput
    color: "transparent"
    visible: false

    x: Screen.virtualX + Math.round((Screen.width - width) / 2)
    y: Screen.virtualY + Screen.height - height - 78

    property string phase: "idle"     // idle | recording | transcribing | done
    property string doneText: ""

    Timer { id: hideTimer; interval: 1600; onTriggered: { ov.phase = "idle"; ov.visible = false } }

    Connections {
        target: app
        function onStatusChanged() {
            var s = app.status
            if (s === "recording") { ov.phase = "recording"; hideTimer.stop(); ov.show() }
            else if (s === "transcribing") { ov.phase = "transcribing"; hideTimer.stop(); ov.show() }
            else if (ov.visible && (s === "ready" || s === "error")) {
                ov.phase = "done"; hideTimer.restart()
            }
        }
        function onTranscriptAdded(text, secs, backend) { ov.doneText = text }
    }

    Rectangle {
        anchors.fill: parent; anchors.margins: 4
        radius: height / 2
        color: Qt.rgba(0.043, 0.06, 0.082, 0.96)   // near-black, slightly translucent
        border.color: Theme.stroke2; border.width: 1

        RowLayout {
            anchors.fill: parent
            anchors.leftMargin: 20; anchors.rightMargin: 20
            spacing: 13

            // status dot / check
            Item {
                implicitWidth: 20; implicitHeight: 20
                Rectangle {
                    anchors.centerIn: parent; width: 11; height: 11; radius: 6
                    visible: ov.phase !== "done"
                    color: ov.phase === "recording" ? Theme.rec : Theme.warn
                    SequentialAnimation on opacity {
                        running: ov.phase === "recording" || ov.phase === "transcribing"
                        loops: Animation.Infinite
                        NumberAnimation { to: 0.35; duration: 550 }
                        NumberAnimation { to: 1.0;  duration: 550 }
                    }
                }
                Glyph { anchors.centerIn: parent; visible: ov.phase === "done"
                    name: "check"; width: 18; height: 18; color: Theme.good; thickness: 2.2 }
            }

            Label {
                text: ov.phase === "recording" ? "Listening…"
                    : ov.phase === "transcribing" ? "Transcribing…"
                    : "Inserted"
                color: Theme.text; font.pixelSize: 14; font.weight: Font.Medium
            }

            // boost tag — high-accuracy model armed (visible even when the main
            // window is hidden to the tray, which is the whole point of the pill)
            Rectangle {
                Layout.alignment: Qt.AlignVCenter
                visible: app.boostActive && ov.phase !== "done"
                implicitHeight: 20; implicitWidth: bRow.implicitWidth + 14
                radius: 10
                color: Qt.rgba(1.0, 0.76, 0.30, 0.18)
                border.color: Qt.rgba(1.0, 0.76, 0.30, 0.5); border.width: 1
                RowLayout {
                    id: bRow; anchors.centerIn: parent; spacing: 4
                    Glyph { name: "bolt"; width: 11; height: 11; color: Theme.warn }
                    Label { text: "HD"; color: Theme.warn; font.pixelSize: 11; font.weight: Font.DemiBold }
                }
            }

            Item { Layout.fillWidth: true }

            // live level bars (real amplitude while recording)
            Row {
                spacing: 3
                visible: ov.phase === "recording" || ov.phase === "transcribing"
                Repeater {
                    model: 5
                    Rectangle {
                        width: 3; radius: 2
                        anchors.verticalCenter: parent.verticalCenter
                        property real base: [0.5, 0.85, 1.0, 0.7, 0.45][index]
                        height: ov.phase === "recording"
                                ? Math.max(4, 22 * base * (0.25 + app.level))
                                : (6 + (index % 2) * 6)
                        color: app.boostActive ? Theme.warn : Theme.accent
                        Behavior on height { NumberAnimation { duration: 70 } }
                        Behavior on color { ColorAnimation { duration: 160 } }
                        SequentialAnimation on opacity {
                            running: ov.phase === "transcribing"; loops: Animation.Infinite
                            NumberAnimation { to: 0.3; duration: 300 + index*60 }
                            NumberAnimation { to: 1.0; duration: 300 + index*60 }
                        }
                    }
                }
            }
        }
    }
}
