import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "."

// One entry in the nav rail. Extracted so Dictate can sit at the top and
// Settings can sit pinned to the bottom (a spacer between them in Main).
Rectangle {
    id: item
    property string glyph: "mic"
    property string label: "Dictate"
    property bool compact: false
    property bool selected: false
    signal activated()

    Layout.fillWidth: true
    implicitHeight: 44
    radius: 11
    color: selected ? Qt.rgba(0.20,0.89,0.81,0.12) : (nh.hovered ? Theme.s1 : "transparent")
    border.color: selected ? Qt.rgba(0.20,0.89,0.81,0.22) : "transparent"
    border.width: 1
    Behavior on color { ColorAnimation { duration: 120 } }

    RowLayout {
        anchors.fill: parent
        anchors.leftMargin: item.compact ? 0 : 12
        spacing: 12
        Item { visible: item.compact; Layout.fillWidth: true }
        Glyph {
            name: item.glyph; width: 19; height: 19
            color: item.selected ? Theme.accent : (nh.hovered ? Theme.text : Theme.muted)
        }
        Label {
            visible: !item.compact
            text: item.label; Layout.fillWidth: true
            color: item.selected ? Theme.text : Theme.muted
            font.pixelSize: 14; font.weight: Font.Medium
        }
        Item { visible: item.compact; Layout.fillWidth: true }
    }
    HoverHandler { id: nh }
    TapHandler { onTapped: item.activated() }
    ToolTip.visible: item.compact && nh.hovered
    ToolTip.text: item.label
    ToolTip.delay: 400
}
