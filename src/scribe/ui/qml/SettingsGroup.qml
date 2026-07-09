import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

ColumnLayout {
    property string title: ""
    default property alias content: rows.data
    Layout.fillWidth: true
    spacing: 10

    Label {
        text: title; color: Theme.muted; font.pixelSize: 11; font.letterSpacing: 1.4
        Layout.leftMargin: 2
    }
    Card {
        Layout.fillWidth: true
        implicitHeight: rows.implicitHeight
        ColumnLayout { id: rows; anchors.fill: parent; spacing: 0 }
    }
}
