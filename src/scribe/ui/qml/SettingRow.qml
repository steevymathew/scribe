import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Item {
    id: r
    property string label: ""
    property string sub: ""
    default property alias control: ctl.data
    Layout.fillWidth: true
    implicitHeight: Math.max(58, row.implicitHeight + 24)

    Rectangle {                      // row divider
        width: parent.width - 24; x: 12; y: 0; height: 1; color: Theme.stroke
        visible: r.y > 0
    }
    RowLayout {
        id: row
        anchors.fill: parent
        anchors.leftMargin: 20; anchors.rightMargin: 20
        spacing: 16
        ColumnLayout {
            Layout.fillWidth: true; spacing: 3
            Label { text: r.label; color: Theme.text; font.pixelSize: 14 }
            Label {
                text: r.sub; visible: r.sub !== ""
                color: Theme.faint; font.pixelSize: 12
                Layout.fillWidth: true; wrapMode: Text.WordWrap
            }
        }
        Item {
            id: ctl
            Layout.alignment: Qt.AlignVCenter
            implicitWidth: childrenRect.width
            implicitHeight: childrenRect.height
        }
    }
}
