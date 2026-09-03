import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Rectangle {
    property string label: ""
    property string icon: ""
    property color accentIcon: Theme.accent
    implicitHeight: 30
    implicitWidth: row.implicitWidth + 22
    radius: 9
    color: Theme.s2
    border.color: Theme.stroke; border.width: 1

    RowLayout {
        id: row
        anchors.centerIn: parent
        spacing: 7
        Glyph { visible: icon !== ""; name: icon; width: 14; height: 14; color: accentIcon }
        Label { text: label; color: Theme.muted; font.pixelSize: 12 }
    }
}
