import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material
import QtQuick.Layouts
import "."

ApplicationWindow {
    id: root
    visible: true
    width: 900; height: 620
    minimumWidth: 300; minimumHeight: 360
    title: "Scribe"
    color: Theme.bg

    Material.theme: Material.Dark
    Material.accent: Theme.accent
    Material.background: Theme.s1
    Material.foreground: Theme.text
    Material.primary: Theme.accent

    property bool manualCollapse: false
    // rail collapses either when the user toggles it or when very narrow
    readonly property bool compact: manualCollapse || width < 480
    property int page: 0
    readonly property var navs: [
        { key: "mic",  label: "Dictate" },
        { key: "gear", label: "Settings" }
    ]

    onClosing: (c) => { c.accepted = false; root.hide() }   // minimize to tray

    // floating status pill (its own top-level window; shows even when hidden)
    Overlay { id: statusPill }

    RowLayout {
        anchors.fill: parent
        spacing: 0

        // ---------- nav rail ----------
        Rectangle {
            Layout.fillHeight: true
            Layout.preferredWidth: root.compact ? 66 : 224
            color: Theme.s0
            Behavior on Layout.preferredWidth { NumberAnimation { duration: 170; easing.type: Easing.OutCubic } }
            Rectangle { anchors.right: parent.right; width: 1; height: parent.height; color: Theme.stroke }

            ColumnLayout {
                anchors.fill: parent
                anchors.margins: 14
                spacing: 6

                // brand + collapse toggle
                RowLayout {
                    Layout.fillWidth: true; Layout.bottomMargin: 12; spacing: 11
                    Brand { size: 34 }
                    ColumnLayout {
                        visible: !root.compact; spacing: 0; Layout.fillWidth: true
                        Label { text: "Scribe"; color: Theme.text; font.pixelSize: 17; font.weight: Font.DemiBold }
                        Label { text: "Offline dictation"; color: Theme.faint; font.pixelSize: 11 }
                    }
                    IconButton {
                        glyph: root.compact ? "chevronR" : "chevronL"
                        tip: root.compact ? "Expand" : "Collapse"
                        visible: !root.compact || root.width >= 480
                        onClicked: root.manualCollapse = !root.manualCollapse
                    }
                }

                // nav items
                Repeater {
                    model: root.navs
                    delegate: Rectangle {
                        required property int index
                        required property var modelData
                        Layout.fillWidth: true
                        implicitHeight: 44
                        radius: 11
                        color: root.page === index ? Qt.rgba(0.20,0.89,0.81,0.12)
                                                   : (nh.hovered ? Theme.s1 : "transparent")
                        border.color: root.page === index ? Qt.rgba(0.20,0.89,0.81,0.22) : "transparent"
                        border.width: 1
                        Behavior on color { ColorAnimation { duration: 120 } }

                        RowLayout {
                            anchors.fill: parent
                            anchors.leftMargin: root.compact ? 0 : 12
                            spacing: 12
                            Item { visible: root.compact; Layout.fillWidth: true }
                            Glyph {
                                name: modelData.key; width: 19; height: 19
                                color: root.page === index ? Theme.accent : (nh.hovered ? Theme.text : Theme.muted)
                            }
                            Label {
                                visible: !root.compact
                                text: modelData.label; Layout.fillWidth: true
                                color: root.page === index ? Theme.text : Theme.muted
                                font.pixelSize: 14; font.weight: Font.Medium
                            }
                            Item { visible: root.compact; Layout.fillWidth: true }
                        }
                        HoverHandler { id: nh }
                        TapHandler { onTapped: root.page = index }
                        ToolTip.visible: root.compact && nh.hovered
                        ToolTip.text: modelData.label
                    }
                }

                Item { Layout.fillHeight: true }
            }
        }

        // ---------- content ----------
        ColumnLayout {
            Layout.fillWidth: true; Layout.fillHeight: true
            spacing: 0

            // title bar — hidden in compact form for a cleaner flyout look
            Rectangle {
                Layout.fillWidth: true
                implicitHeight: 54
                visible: !root.compact
                color: "transparent"
                Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.stroke }
                RowLayout {
                    anchors.fill: parent
                    anchors.leftMargin: 20; anchors.rightMargin: 12
                    spacing: 12
                    Label {
                        text: root.navs[root.page].label
                        color: Theme.text; font.pixelSize: 16; font.weight: Font.DemiBold
                    }
                    Item { Layout.fillWidth: true }
                    Rectangle {
                        implicitHeight: 30; implicitWidth: chipRow.implicitWidth + 22
                        radius: 999; color: Theme.s1; border.color: Theme.stroke; border.width: 1
                        RowLayout {
                            id: chipRow; anchors.centerIn: parent; spacing: 8
                            Rectangle {
                                width: 8; height: 8; radius: 4
                                color: Theme.statusColor(app.status)
                                SequentialAnimation on opacity {
                                    running: app.status === "recording" || app.status === "transcribing"
                                    loops: Animation.Infinite
                                    NumberAnimation { to: 0.3; duration: 600 }
                                    NumberAnimation { to: 1.0; duration: 600 }
                                }
                            }
                            Label { text: app.statusDetail; color: Theme.muted; font.pixelSize: 12 }
                        }
                    }
                    ToolButton {
                        text: "–"; font.pixelSize: 18
                        Material.foreground: Theme.muted
                        onClicked: root.hide()
                        ToolTip.text: "Minimize to tray"; ToolTip.visible: hovered; ToolTip.delay: 500
                    }
                }
            }

            StackLayout {
                Layout.fillWidth: true; Layout.fillHeight: true
                currentIndex: root.page
                DictatePage {}
                SettingsPage { active: root.page === 1 }
            }
        }
    }
}
