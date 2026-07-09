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

    property var s: ({})
    readonly property var keyNames: ["ralt","altgr","lalt","rctrl","lctrl","rshift","scroll_lock","pause","f13"]
    readonly property var keyLabels: ["Right Alt","Right Alt (AltGr)","Left Alt","Right Ctrl","Left Ctrl","Right Shift","Scroll Lock","Pause","F13"]

    Component.onCompleted: { s = app.snapshotSettings(); app.startMeter() }
    Component.onDestruction: app.stopMeter()

    function keyIndex(name) { var i = keyNames.indexOf(name); return i < 0 ? 0 : i }

    ColumnLayout {
        id: col
        x: 22; y: 22; width: page.width - 44
        spacing: 16

        SettingsGroup {
            title: "DICTATION"
            SettingRow {
                label: "Push-to-talk key"; sub: "Hold this while you speak"
                ComboBox {
                    Layout.preferredWidth: 190
                    model: page.keyLabels
                    currentIndex: page.keyIndex(page.s.hotkey || "ralt")
                    onActivated: app.setHotkey("hotkey", page.keyNames[currentIndex])
                }
            }
            SettingRow {
                label: "High-accuracy key"; sub: "Hold with push-to-talk for the large model"
                ComboBox {
                    Layout.preferredWidth: 190
                    model: page.keyLabels
                    currentIndex: page.keyIndex(page.s.boost_key || "rshift")
                    onActivated: app.setHotkey("boost_key", page.keyNames[currentIndex])
                }
            }
            SettingRow {
                label: "Model"; sub: "Everyday transcription · applies on restart"
                ComboBox {
                    Layout.preferredWidth: 190
                    model: ["tiny.en","base.en","small.en","medium.en"]
                    Component.onCompleted: { var i = model.indexOf(page.s.model || "small.en"); if (i>=0) currentIndex = i }
                    onActivated: app.setSetting("model", model[currentIndex])
                }
            }
            SettingRow {
                label: "Compute"; sub: "How audio is processed · applies on restart"
                ComboBox {
                    Layout.preferredWidth: 190
                    model: ["auto","cpu","npu","cuda"]
                    Component.onCompleted: { var i = model.indexOf(page.s.device || "auto"); if (i>=0) currentIndex = i }
                    onActivated: app.setSetting("device", model[currentIndex])
                }
            }
        }

        SettingsGroup {
            title: "AUDIO & TEXT"
            SettingRow {
                label: "Input level"; sub: "Speak — the bar should move"
                Rectangle {
                    Layout.preferredWidth: 190; Layout.preferredHeight: 8
                    radius: 5; color: Theme.s2; border.color: Theme.stroke; border.width: 1
                    Rectangle {
                        height: parent.height; radius: 5
                        width: Math.max(4, parent.width * Math.min(1, app.level))
                        gradient: Gradient { orientation: Gradient.Horizontal
                            GradientStop { position: 0; color: Theme.accent2 }
                            GradientStop { position: 1; color: Theme.accent } }
                        Behavior on width { NumberAnimation { duration: 60 } }
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
                Button { text: "Open"; flat: true
                    Material.foreground: Theme.accent
                    onClicked: app.openLogFolder() }
            }
        }
    }
}
