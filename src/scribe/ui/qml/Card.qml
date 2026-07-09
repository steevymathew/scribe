import QtQuick
import QtQuick.Effects

// Elevated surface with a soft drop shadow and optional hover lift.
Item {
    id: card
    property alias color: bg.color
    property bool hoverable: false
    property int radius: Theme.radius
    default property alias content: inner.data

    implicitWidth: inner.implicitWidth
    implicitHeight: inner.implicitHeight

    Rectangle {
        id: bg
        anchors.fill: parent
        radius: card.radius
        color: hoverable && hover.hovered ? Theme.s1h : Theme.s1
        border.color: hoverable && hover.hovered ? Theme.stroke2 : Theme.stroke
        border.width: 1
        y: hoverable && hover.hovered ? -2 : 0
        Behavior on y { NumberAnimation { duration: 140; easing.type: Easing.OutCubic } }
        Behavior on color { ColorAnimation { duration: 140 } }
        layer.enabled: true
        layer.effect: MultiEffect {
            shadowEnabled: true
            shadowColor: "#B0000000"
            shadowVerticalOffset: hoverable && hover.hovered ? 16 : 10
            shadowBlur: 0.7
        }
    }
    HoverHandler { id: hover; enabled: card.hoverable }
    Item { id: inner; anchors.fill: parent }
}
