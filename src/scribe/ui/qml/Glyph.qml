import QtQuick

// Small line icons drawn on a Canvas so we bundle no image assets and need no
// SVG plugin. `name` selects the shape; `color` and `size` are bindable.
Canvas {
    id: g
    property string name: "mic"
    property color color: "#93A1B0"
    property real thickness: 1.8
    width: 20; height: 20
    onColorChanged: requestPaint()
    onNameChanged: requestPaint()

    onPaint: {
        var ctx = getContext("2d")
        ctx.reset()
        ctx.strokeStyle = g.color
        ctx.fillStyle = g.color
        ctx.lineWidth = g.thickness
        ctx.lineCap = "round"
        ctx.lineJoin = "round"
        var w = width, h = height
        if (name === "mic") {
            ctx.beginPath(); ctx.roundedRect(w*0.37, h*0.12, w*0.26, w*0.44, w*0.13, w*0.13); ctx.stroke()
            ctx.beginPath(); ctx.arc(w*0.5, h*0.5, w*0.28, 0.15*Math.PI, 0.85*Math.PI); ctx.stroke()
            ctx.beginPath(); ctx.moveTo(w*0.5, h*0.78); ctx.lineTo(w*0.5, h*0.9); ctx.stroke()
        } else if (name === "gear") {
            var cx = w*0.5, cy = h*0.5, Rb = w*0.22
            ctx.beginPath(); ctx.arc(cx, cy, Rb, 0, 2*Math.PI); ctx.stroke()          // ring
            ctx.beginPath(); ctx.arc(cx, cy, w*0.075, 0, 2*Math.PI); ctx.stroke()     // hub
            var save = ctx.lineWidth; ctx.lineWidth = g.thickness*1.7
            for (var i = 0; i < 8; i++) {
                var a = i * Math.PI/4
                ctx.beginPath()
                ctx.moveTo(cx + Math.cos(a)*Rb,          cy + Math.sin(a)*Rb)
                ctx.lineTo(cx + Math.cos(a)*(Rb+w*0.10), cy + Math.sin(a)*(Rb+w*0.10))
                ctx.stroke()
            }
            ctx.lineWidth = save
        } else if (name === "chevronL" || name === "chevronR") {
            var d = name === "chevronL" ? -1 : 1
            ctx.beginPath()
            ctx.moveTo(w*0.5 - d*w*0.12, h*0.28)
            ctx.lineTo(w*0.5 + d*w*0.12, h*0.5)
            ctx.lineTo(w*0.5 - d*w*0.12, h*0.72)
            ctx.stroke()
        } else if (name === "check") {
            ctx.beginPath(); ctx.moveTo(w*0.24, h*0.52); ctx.lineTo(w*0.43, h*0.7); ctx.lineTo(w*0.76, h*0.3); ctx.stroke()
        } else if (name === "lock") {
            ctx.beginPath(); ctx.roundedRect(w*0.24, h*0.44, w*0.52, h*0.38, 3, 3); ctx.stroke()
            ctx.beginPath(); ctx.arc(w*0.5, h*0.44, w*0.17, Math.PI, 2*Math.PI); ctx.stroke()
        } else if (name === "copy") {
            ctx.beginPath(); ctx.roundedRect(w*0.36, h*0.36, w*0.44, h*0.44, 3, 3); ctx.stroke()
            ctx.beginPath(); ctx.moveTo(w*0.2, h*0.6); ctx.lineTo(w*0.2, h*0.2); ctx.lineTo(w*0.6, h*0.2); ctx.stroke()
        } else if (name === "insert") {
            ctx.beginPath(); ctx.moveTo(w*0.5, h*0.2); ctx.lineTo(w*0.5, h*0.8); ctx.stroke()
            ctx.beginPath(); ctx.moveTo(w*0.2, h*0.5); ctx.lineTo(w*0.8, h*0.5); ctx.stroke()
        } else if (name === "chip") {
            ctx.beginPath(); ctx.roundedRect(w*0.22, h*0.22, w*0.56, h*0.56, 3, 3); ctx.stroke()
            ctx.beginPath(); ctx.rect(w*0.4, h*0.4, w*0.2, h*0.2); ctx.stroke()
        } else if (name === "clock") {
            ctx.beginPath(); ctx.arc(w*0.5, h*0.5, w*0.32, 0, 2*Math.PI); ctx.stroke()
            ctx.beginPath(); ctx.moveTo(w*0.5, h*0.5); ctx.lineTo(w*0.5, h*0.3); ctx.moveTo(w*0.5, h*0.5); ctx.lineTo(w*0.64, h*0.56); ctx.stroke()
        }
    }
}
