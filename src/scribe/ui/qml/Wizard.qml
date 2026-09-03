import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material
import QtQuick.Layouts
import "."

// First-run onboarding, shown as a full-cover overlay while app.needsOnboarding.
// Welcome → Microphone → Push-to-talk key → Speech model → Done. Reuses the
// engine's own model download (observed via app.status) and the shared Theme so
// it matches the rest of the app. Finishing calls app.finishOnboarding(), which
// writes the config and clears needsOnboarding (hiding this overlay).
Item {
    id: wiz
    anchors.fill: parent
    visible: app.needsOnboarding
    z: 100

    property int step: 0
    readonly property int lastStep: 4
    property string hotkey: app.hotkeyName
    property string device: ""

    // model step is "settled" once the engine has the model (or errored)
    readonly property bool modelReady: app.status === "ready"
    readonly property bool modelError: app.status === "error"
    readonly property bool modelSettled: modelReady || modelError

    function prettyKey(k) {
        return ({ralt:"Right Alt", altgr:"Right Alt", lalt:"Left Alt", rctrl:"Right Ctrl",
                 lctrl:"Left Ctrl", rshift:"Right Shift", scroll_lock:"Scroll Lock",
                 pause:"Pause", f13:"F13"})[k] || k
    }

    // side effects on entering/leaving steps
    onStepChanged: {
        app.stopKeyCapture()
        if (step === 1) app.startMeter(); else app.stopMeter()
    }
    onVisibleChanged: if (!visible) { app.stopMeter(); app.stopKeyCapture() }

    Connections {
        target: app
        function onKeyCaptured(name) { wiz.hotkey = name; capture.capturing = false }
    }

    // opaque backdrop hides the app behind the wizard and swallows stray clicks
    Rectangle {
        anchors.fill: parent; color: Theme.bg
        MouseArea { anchors.fill: parent; hoverEnabled: true }
    }

    Card {
        anchors.centerIn: parent
        width: Math.min(560, parent.width - 48)
        implicitHeight: body.implicitHeight + 48

        ColumnLayout {
            id: body
            anchors.fill: parent
            anchors.margins: 24
            spacing: 18

            // ---- header: brand + step dots ----
            RowLayout {
                Layout.fillWidth: true
                spacing: 12
                Brand { size: 30 }
                Label { text: "Set up Scribe"; color: Theme.text
                    font.pixelSize: 16; font.weight: Font.DemiBold }
                Item { Layout.fillWidth: true }
                Row {
                    spacing: 6
                    Repeater {
                        model: wiz.lastStep + 1
                        Rectangle {
                            width: index === wiz.step ? 18 : 7; height: 7; radius: 4
                            color: index === wiz.step ? Theme.accent
                                 : (index < wiz.step ? Theme.accent2 : Theme.s2)
                            Behavior on width { NumberAnimation { duration: 160 } }
                            Behavior on color { ColorAnimation { duration: 160 } }
                        }
                    }
                }
            }

            Rectangle { Layout.fillWidth: true; height: 1; color: Theme.stroke }

            // ---- step body ----
            StackLayout {
                Layout.fillWidth: true
                currentIndex: wiz.step

                // 0 — welcome / privacy
                ColumnLayout {
                    spacing: 14
                    Label { text: "Talk instead of type"; color: Theme.text
                        font.pixelSize: 22; font.weight: Font.DemiBold }
                    Label {
                        Layout.fillWidth: true
                        text: "Hold a key, speak, release — Scribe types your words wherever your "
                            + "cursor is, in any app."
                        color: Theme.muted; font.pixelSize: 14; wrapMode: Text.WordWrap
                    }
                    Label {
                        Layout.fillWidth: true
                        text: "Everything stays on this device. Your voice is transcribed locally and "
                            + "never leaves your computer — no account, no cloud, no telemetry."
                        color: Theme.muted; font.pixelSize: 14; wrapMode: Text.WordWrap
                    }
                    Flow {
                        Layout.fillWidth: true; Layout.topMargin: 4; spacing: 8
                        Chip { icon: "lock"; label: "On-device"; accentIcon: Theme.good }
                        Chip { icon: "check"; label: "No account" }
                        Chip { icon: "check"; label: "Works offline" }
                    }
                }

                // 1 — microphone
                ColumnLayout {
                    spacing: 14
                    Label { text: "Choose your microphone"; color: Theme.text
                        font.pixelSize: 22; font.weight: Font.DemiBold }
                    Label {
                        Layout.fillWidth: true
                        text: "Pick your mic and say something — the bars should move."
                        color: Theme.muted; font.pixelSize: 14; wrapMode: Text.WordWrap
                    }
                    ComboBox {
                        id: micBox
                        Layout.fillWidth: true
                        Component.onCompleted: {
                            var d = app.inputDevices()
                            model = (d && d.length) ? d : ["System default microphone"]
                            currentIndex = 0
                            wiz.device = currentText
                        }
                        onActivated: wiz.device = currentText
                    }
                    // live level meter
                    Rectangle {
                        Layout.fillWidth: true
                        implicitHeight: 56; radius: Theme.radiusSm
                        color: Theme.s0; border.color: Theme.stroke; border.width: 1
                        Row {
                            anchors.centerIn: parent
                            spacing: 4
                            Repeater {
                                model: 20
                                Rectangle {
                                    width: 5; radius: 2
                                    anchors.verticalCenter: parent.verticalCenter
                                    property real k: 0.35 + 0.65*Math.abs(Math.sin(index*0.6))
                                    height: Math.max(4, 34 * k * (0.15 + app.level))
                                    color: app.level > 0.02 ? Theme.accent : Theme.s2
                                    Behavior on height { NumberAnimation { duration: 70 } }
                                    Behavior on color { ColorAnimation { duration: 200 } }
                                }
                            }
                        }
                    }
                }

                // 2 — push-to-talk key
                ColumnLayout {
                    spacing: 14
                    Label { text: "Pick your push-to-talk key"; color: Theme.text
                        font.pixelSize: 22; font.weight: Font.DemiBold }
                    Label {
                        Layout.fillWidth: true
                        text: "You hold this key while speaking. Right Alt works well — it's rarely "
                            + "used for anything else."
                        color: Theme.muted; font.pixelSize: 14; wrapMode: Text.WordWrap
                    }
                    RowLayout {
                        Layout.fillWidth: true; spacing: 14
                        Rectangle {
                            implicitWidth: 150; implicitHeight: 52; radius: Theme.radiusSm
                            color: Theme.s0; border.width: 1
                            border.color: capture.capturing ? Theme.accent : Theme.stroke2
                            Behavior on border.color { ColorAnimation { duration: 160 } }
                            Label {
                                anchors.centerIn: parent
                                text: capture.capturing ? "Press a key…" : wiz.prettyKey(wiz.hotkey)
                                color: capture.capturing ? Theme.accent : Theme.text
                                font.pixelSize: 16; font.weight: Font.DemiBold
                            }
                        }
                        Rectangle {
                            id: capture
                            property bool capturing: false
                            implicitWidth: rebindRow.implicitWidth + 28; implicitHeight: 40
                            radius: 999
                            color: ch.hovered ? Theme.s2 : Theme.s1
                            border.color: Theme.stroke2; border.width: 1
                            Behavior on color { ColorAnimation { duration: 120 } }
                            RowLayout {
                                id: rebindRow; anchors.centerIn: parent; spacing: 7
                                Glyph { name: "mic"; width: 15; height: 15; color: Theme.muted }
                                Label { text: capture.capturing ? "Listening…" : "Change key"
                                    color: Theme.text; font.pixelSize: 13 }
                            }
                            HoverHandler { id: ch }
                            TapHandler { onTapped: { capture.capturing = true; app.startKeyCapture() } }
                        }
                        Item { Layout.fillWidth: true }
                    }
                    Label {
                        Layout.fillWidth: true
                        text: "Tip: hold " + wiz.prettyKey(app.boostKeyName) + " together with "
                            + wiz.prettyKey(wiz.hotkey) + " for high-accuracy mode."
                        color: Theme.faint; font.pixelSize: 13; wrapMode: Text.WordWrap
                    }
                }

                // 3 — speech model download
                ColumnLayout {
                    spacing: 14
                    Label { text: "Get the speech model"; color: Theme.text
                        font.pixelSize: 22; font.weight: Font.DemiBold }
                    Label {
                        Layout.fillWidth: true
                        text: "Scribe downloads its speech model once (~500 MB), then works fully "
                            + "offline. This is the only time Scribe uses the network."
                        color: Theme.muted; font.pixelSize: 14; wrapMode: Text.WordWrap
                    }
                    RowLayout {
                        Layout.fillWidth: true; Layout.topMargin: 4; spacing: 12
                        // status glyph
                        Item {
                            implicitWidth: 26; implicitHeight: 26
                            Glyph { anchors.centerIn: parent; visible: wiz.modelReady
                                name: "check"; width: 24; height: 24; color: Theme.good; thickness: 2.2 }
                            Rectangle {
                                anchors.centerIn: parent; visible: wiz.modelError
                                width: 12; height: 12; radius: 6; color: Theme.warn
                            }
                            // spinner while loading/downloading
                            Rectangle {
                                anchors.centerIn: parent; visible: !wiz.modelSettled
                                width: 20; height: 20; radius: 10; color: "transparent"
                                border.width: 2; border.color: Theme.accent
                                Rectangle { width: 6; height: 6; radius: 3; color: Theme.bg
                                    x: parent.width - 6; y: parent.height/2 - 3 }
                                RotationAnimation on rotation {
                                    running: !wiz.modelSettled; loops: Animation.Infinite
                                    from: 0; to: 360; duration: 900
                                }
                            }
                        }
                        Label {
                            Layout.fillWidth: true
                            text: wiz.modelReady ? "Ready — " + app.modelName + " is set up and works on this machine."
                                : wiz.modelError ? "Couldn't finish setup. You can continue anyway — Scribe retries at startup (see the log)."
                                : "Preparing " + app.modelName + "… " + app.statusDetail
                            color: wiz.modelReady ? Theme.text : Theme.muted
                            font.pixelSize: 14; wrapMode: Text.WordWrap
                        }
                    }
                    ProgressBar {
                        Layout.fillWidth: true
                        indeterminate: !wiz.modelSettled
                        from: 0; to: 1; value: wiz.modelReady ? 1 : 0
                        Material.accent: Theme.accent
                        visible: !wiz.modelReady
                    }
                    Label {
                        Layout.fillWidth: true
                        text: "The high-accuracy model downloads automatically the first time you "
                            + "hold " + wiz.prettyKey(app.boostKeyName) + " — no need to wait now."
                        color: Theme.faint; font.pixelSize: 13; wrapMode: Text.WordWrap
                    }
                }

                // 4 — done
                ColumnLayout {
                    spacing: 14
                    Label { text: "You're all set"; color: Theme.text
                        font.pixelSize: 22; font.weight: Font.DemiBold }
                    Label {
                        Layout.fillWidth: true
                        text: "Hold " + wiz.prettyKey(wiz.hotkey) + " anywhere, speak, then release — "
                            + "your words appear at the cursor."
                        color: Theme.muted; font.pixelSize: 14; wrapMode: Text.WordWrap
                    }
                    Label {
                        Layout.fillWidth: true
                        text: "Hold " + wiz.prettyKey(app.boostKeyName) + " + " + wiz.prettyKey(wiz.hotkey)
                            + " for high-accuracy mode. Scribe lives in your system tray — open "
                            + "settings, pause, or quit from there."
                        color: Theme.muted; font.pixelSize: 14; wrapMode: Text.WordWrap
                    }
                    Label {
                        Layout.fillWidth: true
                        text: "Try it right after you finish: click into any text box and dictate."
                        color: Theme.faint; font.pixelSize: 13; wrapMode: Text.WordWrap
                    }
                }
            }

            Rectangle { Layout.fillWidth: true; height: 1; color: Theme.stroke }

            // ---- footer nav ----
            RowLayout {
                Layout.fillWidth: true
                spacing: 12

                // Back (ghost)
                Rectangle {
                    visible: wiz.step > 0 && wiz.step < wiz.lastStep
                    implicitWidth: 96; implicitHeight: 42; radius: Theme.radiusSm
                    color: bh.hovered ? Theme.s2 : "transparent"
                    border.color: Theme.stroke2; border.width: 1
                    Behavior on color { ColorAnimation { duration: 120 } }
                    Label { anchors.centerIn: parent; text: "Back"; color: Theme.muted; font.pixelSize: 14 }
                    HoverHandler { id: bh }
                    TapHandler { onTapped: if (wiz.step > 0) wiz.step-- }
                }

                Item { Layout.fillWidth: true }

                // Primary (Next / Finish)
                Rectangle {
                    id: primary
                    // disabled only while the model is still downloading on its step
                    property bool blocked: wiz.step === 3 && !wiz.modelSettled
                    implicitWidth: primaryRow.implicitWidth + 40; implicitHeight: 42
                    radius: Theme.radiusSm
                    opacity: blocked ? 0.5 : 1.0
                    gradient: Gradient {
                        GradientStop { position: 0; color: Theme.accent }
                        GradientStop { position: 1; color: Theme.accent2 }
                    }
                    Behavior on opacity { NumberAnimation { duration: 140 } }
                    RowLayout {
                        id: primaryRow; anchors.centerIn: parent; spacing: 8
                        Label {
                            text: wiz.step === wiz.lastStep ? "Start dictating"
                                : (primary.blocked ? "Preparing…" : "Next")
                            color: "#04201D"; font.pixelSize: 14; font.weight: Font.DemiBold
                        }
                        Glyph { visible: wiz.step < wiz.lastStep && !primary.blocked
                            name: "chevronR"; width: 15; height: 15; color: "#04201D"; thickness: 2.2 }
                    }
                    HoverHandler { id: ph; enabled: !primary.blocked }
                    TapHandler {
                        enabled: !primary.blocked
                        onTapped: {
                            if (wiz.step < wiz.lastStep) wiz.step++
                            else app.finishOnboarding(wiz.hotkey, wiz.device)
                        }
                    }
                    scale: ph.hovered ? 1.02 : 1.0
                    Behavior on scale { NumberAnimation { duration: 120 } }
                }
            }
        }
    }
}
