import QtQuick
import QtQuick.Controls

Rectangle {
    id: b
    property string glyph: "copy"
    property string tip: ""
    signal clicked()
    implicitWidth: 34; implicitHeight: 34
    radius: 9
    color: hh.hovered ? Theme.s2 : "transparent"
    border.color: hh.hovered ? Theme.stroke : "transparent"; border.width: 1
    Behavior on color { ColorAnimation { duration: 120 } }

    Glyph { anchors.centerIn: parent; name: b.glyph; width: 17; height: 17
        color: hh.hovered ? Theme.text : Theme.muted }
    HoverHandler { id: hh }
    TapHandler { onTapped: b.clicked() }
    ToolTip.visible: tip !== "" && hh.hovered
    ToolTip.text: tip
    ToolTip.delay: 400
}
