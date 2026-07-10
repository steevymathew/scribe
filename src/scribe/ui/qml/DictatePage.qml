import QtQuick
import QtQuick.Controls.Material
import QtQuick.Layouts

Flickable {
    id: page
    contentWidth: width
    contentHeight: col.implicitHeight + 44
    clip: true
    boundsBehavior: Flickable.StopAtBounds
    ScrollBar.vertical: ScrollBar {}

    readonly property bool active: app.status === "recording"

    ColumnLayout {
        id: col
        x: 22; y: 22; width: page.width - 44
        spacing: 16

        // ---- hero ----
        Card {
            Layout.fillWidth: true
            hoverable: true
            implicitHeight: hero.implicitHeight + 44

            GridLayout {
                id: hero
                anchors.fill: parent
                anchors.margins: 22
                columns: page.width < 520 ? 1 : 2
                columnSpacing: 24; rowSpacing: 18

                // orb
                Item {
                    Layout.alignment: Qt.AlignHCenter
                    implicitWidth: 148; implicitHeight: 148
                    Rectangle {                       // pulse ring
                        anchors.centerIn: parent
                        width: 138; height: 138; radius: width/2
                        color: "transparent"
                        border.width: 2
                        border.color: Theme.statusColor(app.status)
                        opacity: page.active ? 0.0 : 0.5
                        scale: page.active ? 1.35 : 1.0
                        Behavior on opacity { NumberAnimation { duration: 200 } }
                        SequentialAnimation on scale {
                            running: page.active; loops: Animation.Infinite
                            NumberAnimation { from: 1.0; to: 1.35; duration: 1400; easing.type: Easing.OutCubic }
                            PauseAnimation { duration: 1 }
                        }
                    }
                    Rectangle {                       // orb body
                        anchors.centerIn: parent
                        width: 122; height: 122; radius: width/2
                        gradient: Gradient {
                            GradientStop { position: 0.0; color: Theme.s2 }
                            GradientStop { position: 1.0; color: Theme.s0 }
                        }
                        border.color: Theme.stroke2; border.width: 1
                        Glyph {
                            anchors.centerIn: parent
                            name: "mic"; width: 46; height: 46; thickness: 2
                            color: Theme.statusColor(app.status)
                        }
                    }
                    TapHandler { onTapped: {} }  // reserved: click-to-record later
                }

                // status + chips
                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 6

                    // headline + boost badge
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 10
                        Label {
                            text: {
                                if (app.status === "loading") return "Warming up…"
                                if (app.status === "recording") return "Listening…"
                                if (app.status === "transcribing") return "Transcribing…"
                                if (app.status === "error") return "Needs attention"
                                return "Ready when you are"
                            }
                            color: Theme.text; font.pixelSize: 23; font.weight: Font.DemiBold
                        }
                        // BOOST badge — shows whenever the high-accuracy key is
                        // held, so it's obvious the heavier model will be used.
                        Rectangle {
                            Layout.alignment: Qt.AlignVCenter
                            visible: opacity > 0.01
                            opacity: app.boostActive ? 1 : 0
                            Behavior on opacity { NumberAnimation { duration: 160 } }
                            implicitHeight: 24
                            implicitWidth: boostRow.implicitWidth + 18
                            radius: 12
                            color: Qt.rgba(1.0, 0.76, 0.30, 0.16)
                            border.color: Qt.rgba(1.0, 0.76, 0.30, 0.5); border.width: 1
                            RowLayout {
                                id: boostRow; anchors.centerIn: parent; spacing: 5
                                Glyph { name: "bolt"; width: 13; height: 13; color: Theme.warn }
                                Label { text: "High accuracy"; color: Theme.warn
                                    font.pixelSize: 12; font.weight: Font.DemiBold }
                            }
                            SequentialAnimation on scale {
                                running: app.boostActive; loops: Animation.Infinite
                                NumberAnimation { from: 1.0; to: 1.05; duration: 700; easing.type: Easing.InOutSine }
                                NumberAnimation { from: 1.05; to: 1.0; duration: 700; easing.type: Easing.InOutSine }
                            }
                        }
                        Item { Layout.fillWidth: true }
                    }

                    Label {
                        Layout.fillWidth: true
                        text: app.status === "ready"
                              ? (app.boostActive
                                 ? "High accuracy armed — hold " + prettyKey(app.hotkeyName) + " and speak; " + app.heavyModelName + " transcribes this one."
                                 : "Hold " + prettyKey(app.hotkeyName) + " anywhere, speak, release — the text lands at your cursor.")
                              : app.statusDetail
                        color: Theme.muted; font.pixelSize: 14; wrapMode: Text.WordWrap
                    }

                    // Live input strip. FIXED height so per-frame amplitude
                    // changes animate *inside* it rather than resizing the card
                    // (that vertical jitter was the "cards bounce" problem). It's
                    // always present — the bars just rest low when idle — so
                    // starting/stopping a recording doesn't change card height
                    // either. Bars turn warm while boost is armed.
                    Item {
                        Layout.topMargin: 6
                        Layout.fillWidth: true
                        implicitHeight: 40
                        clip: true
                        Row {
                            anchors.left: parent.left
                            anchors.verticalCenter: parent.verticalCenter
                            spacing: 3
                            Repeater {
                                model: 28
                                Rectangle {
                                    width: 4; radius: 2
                                    anchors.verticalCenter: parent.verticalCenter
                                    property real k: 0.35 + 0.65*Math.abs(Math.sin(index*0.7))
                                    property bool live: app.status === "recording"
                                    height: live ? Math.max(4, 36 * k * (0.18 + app.level))
                                                 : 3 + 3*k
                                    color: live ? (app.boostActive ? Theme.warn : Theme.accent)
                                                : Theme.stroke2
                                    opacity: live ? 1.0 : 0.6
                                    Behavior on height { NumberAnimation { duration: 70 } }
                                    Behavior on color { ColorAnimation { duration: 160 } }
                                    Behavior on opacity { NumberAnimation { duration: 160 } }
                                }
                            }
                        }
                    }

                    Flow {
                        Layout.fillWidth: true
                        Layout.topMargin: 8
                        spacing: 8
                        Chip { icon: "chip"; label: app.modelName }
                        Chip { icon: "chip"; label: app.deviceName }
                        Chip { icon: "lock"; label: "On-device"; accentIcon: Theme.good }
                    }
                }
            }
        }

        // ---- recent ----
        Label {
            text: "THIS SESSION"
            color: Theme.muted; font.pixelSize: 11; font.letterSpacing: 1.4
            Layout.topMargin: 8; Layout.leftMargin: 2
        }

        Label {
            visible: app.recent.length === 0
            text: "Nothing yet — your dictation will show up here."
            color: Theme.faint; font.pixelSize: 13; Layout.leftMargin: 2
        }

        Repeater {
            model: app.recent
            delegate: Card {
                required property var modelData
                Layout.fillWidth: true
                hoverable: true
                implicitHeight: tRow.implicitHeight + 32
                RowLayout {
                    id: tRow
                    anchors.fill: parent; anchors.margins: 16
                    spacing: 12
                    ColumnLayout {
                        Layout.fillWidth: true; spacing: 6
                        Label {
                            Layout.fillWidth: true
                            text: modelData.text
                            color: Theme.text; font.pixelSize: 14; wrapMode: Text.WordWrap
                        }
                        RowLayout {
                            spacing: 8
                            Rectangle {
                                radius: 6; color: Theme.accentGlow
                                border.color: Qt.rgba(0.20,0.89,0.81,0.25); border.width: 1
                                implicitHeight: 18; implicitWidth: bl.implicitWidth + 14
                                Label { id: bl; anchors.centerIn: parent; text: modelData.backend
                                    color: Theme.accent; font.pixelSize: 11 }
                            }
                            // high-accuracy marker for clips transcribed by the heavy model
                            Rectangle {
                                visible: modelData.heavy === true
                                radius: 6; color: Qt.rgba(1.0, 0.76, 0.30, 0.16)
                                border.color: Qt.rgba(1.0, 0.76, 0.30, 0.4); border.width: 1
                                implicitHeight: 18; implicitWidth: hb.implicitWidth + 12
                                RowLayout {
                                    id: hb; anchors.centerIn: parent; spacing: 3
                                    Glyph { name: "bolt"; width: 10; height: 10; color: Theme.warn }
                                    Label { text: "Boost"; color: Theme.warn; font.pixelSize: 11 }
                                }
                            }
                            Label { text: modelData.seconds + "s"; color: Theme.faint; font.pixelSize: 12 }
                        }
                    }
                    IconButton { glyph: "copy"; tip: "Copy"
                        onClicked: { textEdit.text = modelData.text; textEdit.selectAll(); textEdit.copy() } }
                }
            }
        }
    }

    // hidden helper for clipboard copy
    TextEdit { id: textEdit; visible: false }

    function prettyKey(k) {
        return ({ralt:"Right Alt", altgr:"Right Alt", lalt:"Left Alt", rctrl:"Right Ctrl",
                  lctrl:"Left Ctrl", rshift:"Right Shift", scroll_lock:"Scroll Lock",
                  pause:"Pause", f13:"F13"})[k] || k
    }
}
