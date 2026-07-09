import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material
import QtQuick.Layouts

Flickable {
    id: page
    contentWidth: width
    contentHeight: col.implicitHeight + 44
    clip: true
    boundsBehavior: Flickable.StopAtBounds
    ScrollBar.vertical: ScrollBar {}

    // Set true by Main only while Settings is the visible page. The mic meter
    // runs ONLY while this is true, so the microphone is never held open in the
    // background — it's live only when you're actually looking at this page.
    property bool active: false
    onActiveChanged: active ? app.startMeter() : app.stopMeter()
    Component.onDestruction: app.stopMeter()

    property var s: ({})
    Component.onCompleted: s = app.snapshotSettings()

    readonly property var keyNames: ["ralt","altgr","lalt","rctrl","lctrl","rshift","scroll_lock","pause","f13"]
    readonly property var keyLabels: ["Right Alt","Right Alt (AltGr)","Left Alt","Right Ctrl","Left Ctrl","Right Shift","Scroll Lock","Pause","F13"]
    readonly property var models: ["tiny.en","base.en","small.en","medium.en","large-v3-turbo"]
    function keyIndex(name) { var i = keyNames.indexOf(name); return i < 0 ? 0 : i }
    function keyLabel(name) { var i = keyIndex(name); return keyLabels[i] }
    function modelIndex(name) { var i = models.indexOf(name); return i < 0 ? 2 : i }

    ColumnLayout {
        id: col
        x: 22; y: 22; width: page.width - 44
        spacing: 16

        SettingsGroup {
            title: "DICTATION"
            SettingRow {
                label: "Push-to-talk key"; sub: "Hold this while you speak"
                ComboBox {
                    Layout.preferredWidth: 200
                    model: page.keyLabels
                    currentIndex: page.keyIndex(page.s.hotkey || "ralt")
                    onActivated: app.setHotkey("hotkey", page.keyNames[currentIndex])
                }
            }
            SettingRow {
                label: "Everyday model"; sub: "Fast, accurate for normal dictation · applies on restart"
                ComboBox {
                    Layout.preferredWidth: 200
                    model: page.models
                    Component.onCompleted: currentIndex = page.modelIndex(page.s.model || "small.en")
                    onActivated: app.setSetting("model", page.models[currentIndex])
                }
            }
            SettingRow {
                label: "High-accuracy key"; sub: "Hold together with push-to-talk to switch to the heavier model mid-sentence"
                ComboBox {
                    Layout.preferredWidth: 200
                    model: page.keyLabels
                    currentIndex: page.keyIndex(page.s.boost_key || "rshift")
                    onActivated: app.setHotkey("boost_key", page.keyNames[currentIndex])
                }
            }
            SettingRow {
                label: "High-accuracy model"
                sub: "Used while you hold " + page.keyLabel(page.s.boost_key || "rshift")
                      + " — larger and more accurate, a little slower. Downloaded on first use."
                ComboBox {
                    Layout.preferredWidth: 200
                    model: ["small.en","medium.en","large-v3-turbo","large-v3"]
                    Component.onCompleted: { var i = model.indexOf(page.s.heavy_model || "large-v3-turbo"); if (i>=0) currentIndex = i }
                    onActivated: app.setSetting("heavy_model", model[currentIndex])
                }
            }
            SettingRow {
                label: "Compute"; sub: "How audio is processed · applies on restart"
                ComboBox {
                    Layout.preferredWidth: 200
                    model: ["auto","cpu","npu","cuda"]
                    Component.onCompleted: { var i = model.indexOf(page.s.device || "auto"); if (i>=0) currentIndex = i }
                    onActivated: app.setSetting("device", model[currentIndex])
                }
            }
        }

        SettingsGroup {
            title: "AUDIO & TEXT"
            SettingRow {
                label: "Microphone input"; sub: "Speak — the bars should move"
                Row {
                    spacing: 3
                    Repeater {
                        model: 16
                        Rectangle {
                            width: 4; radius: 2
                            anchors.verticalCenter: parent.verticalCenter
                            property real k: [0.4,0.7,1.0,0.6,0.85,0.5,0.95,0.65,0.8,0.55,1.0,0.7,0.45,0.9,0.6,0.35][index]
                            height: Math.max(4, 26 * k * (0.15 + app.level))
                            color: app.level > 0.02 ? Theme.accent : Theme.s2
                            Behavior on height { NumberAnimation { duration: 70 } }
                            Behavior on color { ColorAnimation { duration: 200 } }
                        }
                    }
                }
            }
            SettingRow {
                label: "Remove filler words"; sub: "Drop “um”, “uh” automatically"
                Switch {
                    checked: page.s.remove_fillers === true
                    onToggled: app.setSetting("remove_fillers", checked)
                }
            }
        }

        SettingsGroup {
            title: "SYSTEM"
            SettingRow {
                label: "Start with Windows"; sub: "Launch to the tray when you sign in"
                Switch {
                    Component.onCompleted: checked = app.autostartEnabled()
                    onToggled: if (!app.setAutostart(checked)) checked = !checked
                }
            }
            SettingRow {
                label: "Advanced mode"; sub: "Stream the full technical log · applies on restart"
                Switch {
                    checked: page.s.advanced === true
                    onToggled: app.setSetting("advanced", checked)
                }
            }
            SettingRow {
                label: "Log folder"; sub: "For troubleshooting"
                Button { text: "Open"; flat: true; Material.foreground: Theme.accent
                    onClicked: app.openLogFolder() }
            }
        }

        SettingsGroup {
            title: "DEVELOPER"
            SettingRow {
                label: "Built by SMantics.dev"; sub: "Offline, on-device dictation"
                Button { text: "Visit SMantics.dev"; flat: true; Material.foreground: Theme.accent
                    onClicked: Qt.openUrlExternally("https://smantics.dev") }
            }
        }

        Label {
            text: "Scribe v" + app.version
            color: Theme.faint; font.pixelSize: 11; Layout.leftMargin: 2; Layout.topMargin: 2
        }
    }
}
