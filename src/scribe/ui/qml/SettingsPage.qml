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

    // ---- custom dictionary helpers ----
    // Rebinds whenever app.dictionary changes (its notify signal fires on edit).
    readonly property var dictKeys: Object.keys(app.dictionary)

    function addPair(spoken, replacement) {
        var s = ("" + spoken).trim()
        if (s === "") return
        var m = {}, ks = Object.keys(app.dictionary)
        for (var i = 0; i < ks.length; i++) m[ks[i]] = app.dictionary[ks[i]]
        m[s] = ("" + replacement)
        app.setDictionary(m)
    }
    function removePair(key) {
        var m = {}, ks = Object.keys(app.dictionary)
        for (var i = 0; i < ks.length; i++) if (ks[i] !== key) m[ks[i]] = app.dictionary[ks[i]]
        app.setDictionary(m)
    }
    function copyToClipboard(t) { clip.text = t; clip.selectAll(); clip.copy() }

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
            title: "DICTIONARY"
            Item {
                Layout.fillWidth: true
                implicitHeight: dictCol.implicitHeight + 32
                ColumnLayout {
                    id: dictCol
                    anchors.left: parent.left; anchors.right: parent.right; anchors.top: parent.top
                    anchors.leftMargin: 20; anchors.rightMargin: 20; anchors.topMargin: 16
                    spacing: 12

                    Label {
                        text: "Fix how specific words are spelled/capitalised, e.g. “jira” → “Jira”."
                        color: Theme.faint; font.pixelSize: 12
                        Layout.fillWidth: true; wrapMode: Text.WordWrap
                    }

                    Label {
                        visible: page.dictKeys.length === 0
                        text: "No entries yet — add one below."
                        color: Theme.faint; font.pixelSize: 13
                    }

                    // existing {spoken → replacement} pairs
                    Repeater {
                        model: page.dictKeys
                        delegate: RowLayout {
                            required property var modelData
                            Layout.fillWidth: true
                            spacing: 10
                            Label {
                                text: modelData; color: Theme.text; font.pixelSize: 13
                                elide: Text.ElideRight
                                Layout.preferredWidth: 150
                            }
                            Glyph { name: "chevronR"; width: 14; height: 14; color: Theme.faint }
                            Label {
                                text: app.dictionary[modelData]; color: Theme.muted; font.pixelSize: 13
                                elide: Text.ElideRight; Layout.fillWidth: true
                            }
                            Button {
                                text: "Remove"; flat: true; font.pixelSize: 12
                                Material.foreground: Theme.faint
                                onClicked: page.removePair(modelData)
                            }
                        }
                    }

                    Rectangle { Layout.fillWidth: true; height: 1; color: Theme.stroke
                        visible: page.dictKeys.length > 0 }

                    // add a new pair
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 8
                        TextField {
                            id: spokenField
                            placeholderText: "spoken"; font.pixelSize: 13
                            Layout.preferredWidth: 150
                            onAccepted: addBtn.clicked()
                        }
                        Glyph { name: "chevronR"; width: 14; height: 14; color: Theme.faint }
                        TextField {
                            id: replField
                            placeholderText: "replacement"; font.pixelSize: 13
                            Layout.fillWidth: true
                            onAccepted: addBtn.clicked()
                        }
                        Button {
                            id: addBtn
                            text: "Add"; flat: true; Material.foreground: Theme.accent
                            enabled: spokenField.text.trim() !== ""
                            onClicked: {
                                page.addPair(spokenField.text, replField.text)
                                spokenField.text = ""; replField.text = ""
                                spokenField.forceActiveFocus()
                            }
                        }
                    }
                }
            }
        }

        SettingsGroup {
            title: "HISTORY"
            SettingRow {
                label: "Save my dictations"
                sub: "Off by default. When on, your dictations are saved on this device only."
                Switch {
                    checked: app.historyEnabled
                    onToggled: app.setHistoryEnabled(checked)
                }
            }
            Item {
                Layout.fillWidth: true
                visible: app.historyEnabled
                implicitHeight: visible ? histCol.implicitHeight + 28 : 0
                ColumnLayout {
                    id: histCol
                    anchors.left: parent.left; anchors.right: parent.right; anchors.top: parent.top
                    anchors.leftMargin: 20; anchors.rightMargin: 20; anchors.topMargin: 14
                    spacing: 10

                    RowLayout {
                        Layout.fillWidth: true
                        Label {
                            text: app.history.length + (app.history.length === 1 ? " saved dictation" : " saved dictations")
                            color: Theme.muted; font.pixelSize: 12; Layout.fillWidth: true
                        }
                        Button {
                            text: "Clear history"; flat: true; font.pixelSize: 12
                            Material.foreground: Theme.rec
                            enabled: app.history.length > 0
                            onClicked: app.clearHistory()
                        }
                    }

                    Label {
                        visible: app.history.length === 0
                        text: "Nothing saved yet — your dictations will appear here."
                        color: Theme.faint; font.pixelSize: 13
                    }

                    // Its own scroll area so a long history stays contained and
                    // virtualised rather than stretching the whole page.
                    ListView {
                        visible: app.history.length > 0
                        Layout.fillWidth: true
                        Layout.preferredHeight: Math.min(contentHeight, 280)
                        clip: true
                        boundsBehavior: Flickable.StopAtBounds
                        spacing: 0
                        ScrollBar.vertical: ScrollBar {}
                        model: app.history
                        delegate: Item {
                            required property var modelData
                            width: ListView.view.width
                            implicitHeight: Math.max(46, hRow.implicitHeight + 16)
                            Rectangle { width: parent.width; height: 1; color: Theme.stroke
                                visible: index > 0 }
                            RowLayout {
                                id: hRow
                                anchors.fill: parent
                                anchors.leftMargin: 2; anchors.rightMargin: 2
                                anchors.topMargin: 8; anchors.bottomMargin: 8
                                spacing: 10
                                Label {
                                    text: modelData.text; color: Theme.text; font.pixelSize: 13
                                    wrapMode: Text.WordWrap; Layout.fillWidth: true
                                }
                                Label {
                                    text: modelData.seconds + "s"; color: Theme.faint; font.pixelSize: 12
                                }
                                IconButton {
                                    glyph: "copy"; tip: "Copy"
                                    onClicked: page.copyToClipboard(modelData.text)
                                }
                            }
                        }
                    }
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

    // hidden helper for clipboard copy (same pattern as DictatePage)
    TextEdit { id: clip; visible: false }
}
