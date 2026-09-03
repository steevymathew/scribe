pragma Singleton
import QtQuick

// Single source of truth for the dark palette. Deep, near-black ground with one
// luminous signal-teal accent; semantic colours kept separate from the accent.
QtObject {
    readonly property color bg:      "#05070A"
    readonly property color s0:      "#0B0F14"   // window base / rail
    readonly property color s1:      "#10151C"   // card
    readonly property color s1h:     "#141C25"   // card hover
    readonly property color s2:      "#18212C"   // controls / elevated
    readonly property color stroke:  Qt.rgba(1, 1, 1, 0.08)
    readonly property color stroke2: Qt.rgba(1, 1, 1, 0.14)

    readonly property color text:    "#E7EEF4"
    readonly property color muted:   "#93A1B0"
    readonly property color faint:   "#5C6875"

    readonly property color accent:  "#34E4CE"
    readonly property color accent2: "#26B7C7"
    readonly property color accentGlow: Qt.rgba(0.20, 0.89, 0.81, 0.16)

    readonly property color rec:     "#FF6584"
    readonly property color warn:    "#FFC24D"
    readonly property color good:    "#48E39B"

    readonly property int radius:    16
    readonly property int radiusSm:  11

    function statusColor(s) {
        if (s === "recording") return rec
        if (s === "transcribing" || s === "loading") return warn
        if (s === "error") return rec
        return good
    }
}
