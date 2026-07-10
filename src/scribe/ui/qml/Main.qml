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

    // Closing hides to the tray instead of quitting; tell Python so it can pop a
    // one-time "still running in the tray" hint the first time it happens.
    onClosing: (c) => { c.accepted = false; root.hide(); app.notifyClosedToTray() }

    // floating status pill (its own top-level window; shows even when hidden)
    Overlay { id: statusPill }

    // first-run onboarding (covers everything while app.needsOnboarding)
    Wizard { anchors.fill: parent }

    RowLayout {
        anchors.fill: parent
        spacing: 0

        // ---------- nav rail ----------
        Rectangle {
            id: navRail
            Layout.fillHeight: true
            Layout.preferredWidth: root.compact ? 66 : 224
            color: Theme.s0
            z: 2   // so the edge handle can straddle the divider above the content
            Behavior on Layout.preferredWidth { NumberAnimation { duration: 170; easing.type: Easing.OutCubic } }
            Rectangle { anchors.right: parent.right; width: 1; height: parent.height; color: Theme.stroke }
            HoverHandler { id: railHover }

            ColumnLayout {
                anchors.fill: parent
                anchors.margins: 14
                spacing: 6

                // brand + wordmark (wordmark only when expanded; mark centered
                // when compact). No collapse control here — it lives on the rail
                // edge and only appears on hover (see railHandle below).
                RowLayout {
                    Layout.fillWidth: true
                    Layout.bottomMargin: root.compact ? 8 : 14
                    spacing: 11
                    Item { visible: root.compact; Layout.fillWidth: true }
                    Brand { size: root.compact ? 30 : 34 }
                    ColumnLayout {
                        visible: !root.compact; spacing: 0; Layout.fillWidth: true
                        Label { text: "Scribe"; color: Theme.text; font.pixelSize: 17; font.weight: Font.DemiBold }
                        Label { text: "Offline dictation"; color: Theme.faint; font.pixelSize: 11 }
                    }
                    Item { visible: root.compact; Layout.fillWidth: true }
                }

                // primary nav at the top
                NavItem {
                    glyph: "mic"; label: "Dictate"; compact: root.compact
                    selected: root.page === 0; onActivated: root.page = 0
                }

                Item { Layout.fillHeight: true }   // pushes Settings to the bottom

                // Settings pinned to the bottom of the rail
                NavItem {
                    glyph: "gear"; label: "Settings"; compact: root.compact
                    selected: root.page === 1; onActivated: root.page = 1
                }
            }

            // Collapse/expand handle: sits on the rail's right edge and only
            // fades in when the rail (or the handle) is hovered — an unobtrusive
            // hint rather than a permanent button. Hidden when the window is too
            // narrow to bother expanding.
            Rectangle {
                id: railHandle
                visible: root.width >= 480
                anchors.verticalCenter: parent.verticalCenter
                x: parent.width - width / 2
                width: 20; height: 46; radius: 10
                color: hh.hovered ? Theme.s2 : Theme.s1
                border.color: Theme.stroke2; border.width: 1
                opacity: (railHover.hovered || hh.hovered) ? 1 : 0
                Behavior on opacity { NumberAnimation { duration: 160 } }
                Behavior on color { ColorAnimation { duration: 120 } }
                Glyph {
                    anchors.centerIn: parent
                    name: root.compact ? "chevronR" : "chevronL"
                    width: 14; height: 14
                    color: hh.hovered ? Theme.text : Theme.muted
                }
                HoverHandler { id: hh }
                TapHandler { onTapped: root.manualCollapse = !root.manualCollapse }
                ToolTip.visible: hh.hovered
                ToolTip.text: root.compact ? "Expand" : "Collapse"
                ToolTip.delay: 400
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
