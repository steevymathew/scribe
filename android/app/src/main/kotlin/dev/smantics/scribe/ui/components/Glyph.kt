package dev.smantics.scribe.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The icon set, drawn rather than imported.
 *
 * This mirrors the desktop's `Glyph.qml`, which draws its icons for the same two reasons:
 * they inherit the palette exactly, and the app carries no icon font or icon library. The
 * Material extended icon set would add megabytes to a sideloaded APK for six shapes.
 *
 * All glyphs are stroked on a 24×24 grid with round caps, so they sit together as one set.
 */
enum class GlyphName { MIC, BACKSPACE, RETURN, ARROW_LEFT, ARROW_RIGHT, KEYBOARD, CHECK, BOLT, SPACE }

@Composable
fun Glyph(
    name: GlyphName,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    thickness: Dp = 2.dp,
) {
    Canvas(modifier.size(size)) {
        val unit = this.size.minDimension / 24f
        val stroke = Stroke(width = thickness.toPx(), cap = StrokeCap.Round)
        fun p(x: Float, y: Float) = Offset(x * unit, y * unit)

        when (name) {
            GlyphName.MIC -> {
                // Capsule body
                drawRoundRectOutline(p(9f, 2f), Size(6 * unit, 11 * unit), 3 * unit, color, stroke)
                // Cradle arc
                drawArc(
                    color = color,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = p(5f, 9f),
                    size = Size(14 * unit, 10 * unit),
                    style = stroke,
                )
                drawLine(color, p(12f, 19f), p(12f, 22f), stroke.width, StrokeCap.Round)
            }

            GlyphName.BACKSPACE -> {
                val path = Path().apply {
                    moveTo(9 * unit, 5 * unit)
                    lineTo(22 * unit, 5 * unit)
                    lineTo(22 * unit, 19 * unit)
                    lineTo(9 * unit, 19 * unit)
                    lineTo(2 * unit, 12 * unit)
                    close()
                }
                drawPath(path, color, style = stroke)
                drawLine(color, p(12f, 9f), p(18f, 15f), stroke.width, StrokeCap.Round)
                drawLine(color, p(18f, 9f), p(12f, 15f), stroke.width, StrokeCap.Round)
            }

            GlyphName.RETURN -> {
                drawLine(color, p(21f, 5f), p(21f, 13f), stroke.width, StrokeCap.Round)
                drawLine(color, p(21f, 13f), p(4f, 13f), stroke.width, StrokeCap.Round)
                drawLine(color, p(10f, 7f), p(4f, 13f), stroke.width, StrokeCap.Round)
                drawLine(color, p(10f, 19f), p(4f, 13f), stroke.width, StrokeCap.Round)
            }

            GlyphName.ARROW_LEFT -> {
                drawLine(color, p(19f, 12f), p(6f, 12f), stroke.width, StrokeCap.Round)
                drawLine(color, p(12f, 6f), p(6f, 12f), stroke.width, StrokeCap.Round)
                drawLine(color, p(12f, 18f), p(6f, 12f), stroke.width, StrokeCap.Round)
            }

            GlyphName.ARROW_RIGHT -> {
                drawLine(color, p(5f, 12f), p(18f, 12f), stroke.width, StrokeCap.Round)
                drawLine(color, p(12f, 6f), p(18f, 12f), stroke.width, StrokeCap.Round)
                drawLine(color, p(12f, 18f), p(18f, 12f), stroke.width, StrokeCap.Round)
            }

            GlyphName.KEYBOARD -> {
                drawRoundRectOutline(p(2f, 6f), Size(20 * unit, 12 * unit), 2.5f * unit, color, stroke)
                listOf(6f, 10f, 14f).forEach { x ->
                    drawLine(color, p(x, 10f), p(x + 0.2f, 10f), stroke.width, StrokeCap.Round)
                }
                drawLine(color, p(8f, 14f), p(16f, 14f), stroke.width, StrokeCap.Round)
            }

            GlyphName.CHECK -> {
                drawLine(color, p(4f, 13f), p(9f, 18f), stroke.width, StrokeCap.Round)
                drawLine(color, p(9f, 18f), p(20f, 6f), stroke.width, StrokeCap.Round)
            }

            GlyphName.BOLT -> {
                val path = Path().apply {
                    moveTo(13 * unit, 2 * unit)
                    lineTo(5 * unit, 13 * unit)
                    lineTo(11 * unit, 13 * unit)
                    lineTo(10 * unit, 22 * unit)
                    lineTo(19 * unit, 10 * unit)
                    lineTo(13 * unit, 10 * unit)
                    close()
                }
                drawPath(path, color, style = stroke)
            }

            GlyphName.SPACE -> {
                drawLine(color, p(4f, 10f), p(4f, 15f), stroke.width, StrokeCap.Round)
                drawLine(color, p(4f, 15f), p(20f, 15f), stroke.width, StrokeCap.Round)
                drawLine(color, p(20f, 15f), p(20f, 10f), stroke.width, StrokeCap.Round)
            }
        }
    }
}

private fun DrawScope.drawRoundRectOutline(
    topLeft: Offset,
    size: Size,
    radius: Float,
    color: Color,
    stroke: Stroke,
) {
    val path = Path().apply {
        addRoundRect(
            androidx.compose.ui.geometry.RoundRect(
                rect = Rect(topLeft, size),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
            ),
        )
    }
    drawPath(path, color, style = stroke)
}
