import QtQuick

// The Scribe mark. Uses assets/scribe.png when present, otherwise falls back to
// a teal gradient tile with a drawn microphone so the app always has a brand.
Item {
    id: brand
    property int size: 34
    implicitWidth: size; implicitHeight: size

    Image {
        id: logo
        anchors.fill: parent
        source: Qt.resolvedUrl("../assets/scribe.png")
        fillMode: Image.PreserveAspectFit
        smooth: true; mipmap: true
        visible: status === Image.Ready
    }
    Rectangle {
        anchors.fill: parent
        visible: logo.status !== Image.Ready
        radius: brand.size * 0.29
        gradient: Gradient {
            GradientStop { position: 0; color: Theme.accent }
            GradientStop { position: 1; color: Theme.accent2 }
        }
        Glyph {
            anchors.centerIn: parent
            name: "mic"; width: brand.size*0.53; height: brand.size*0.53
            color: "#04201D"; thickness: 2
        }
    }
}
