package dev.smantics.scribe.ui.components

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import dev.smantics.scribe.ui.theme.ScribeTokens

/**
 * Soft, moulded surfaces — the "neumorphic" look: a shape that appears pressed into the
 * background rather than drawn on top of it.
 *
 * The effect is two gradients and nothing more. Light is treated as coming from the top
 * left, so an inset shape is darkest along its top-left edge and carries a faint highlight
 * along the bottom right; a raised shape is the reverse. Both are drawn *inside* the
 * shape's own clip, because Compose has no real outer shadow that can take a colour, and
 * faking one with elevation produces a grey halo rather than a moulded edge.
 *
 * Kept deliberately subtle. On a near-black ground there is very little room between "you
 * can see the shape" and "this is a smudge", and the desktop build's rule that depth is a
 * scarce signal still applies — these are for the two controls that are meant to feel
 * physical, the microphone and the bubble, not for every surface in the app.
 */

/**
 * A shape pressed *into* the surface.
 *
 * [depth] scales both shadows together; 1f matches the reference and anything much above
 * it stops reading as a surface and starts reading as a hole.
 */
fun Modifier.neuInset(
    shape: Shape,
    surface: Color = ScribeTokens.s2,
    depth: Float = 1f,
): Modifier = this
    .clip(shape)
    // The well is slightly darker than the surround; that difference alone carries most
    // of the effect, and the gradients only shape it.
    .background(ScribeTokens.bg.copy(alpha = 0.55f).compositeOver(surface))
    .drawWithContent {
        val far = maxOf(size.width, size.height)
        // Top-left: the shadow cast by the rim nearest the light.
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.Black.copy(alpha = 0.55f * depth),
                    Color.Transparent,
                ),
                start = Offset.Zero,
                end = Offset(far * 0.62f, far * 0.62f),
            ),
        )
        // Bottom-right: the lit far wall.
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.Transparent,
                    Color.White.copy(alpha = 0.07f * depth),
                ),
                start = Offset(size.width * 0.42f, size.height * 0.42f),
                end = Offset(size.width, size.height),
            ),
        )
        drawContent()
    }

/** A shape standing *proud of* the surface — the same light, the other way round. */
fun Modifier.neuRaised(
    shape: Shape,
    surface: Color = ScribeTokens.s2,
    depth: Float = 1f,
): Modifier = this
    .clip(shape)
    .background(surface)
    .drawWithContent {
        val far = maxOf(size.width, size.height)
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.09f * depth),
                    Color.Transparent,
                ),
                start = Offset.Zero,
                end = Offset(far * 0.55f, far * 0.55f),
            ),
        )
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.Transparent,
                    Color.Black.copy(alpha = 0.42f * depth),
                ),
                start = Offset(size.width * 0.45f, size.height * 0.45f),
                end = Offset(size.width, size.height),
            ),
        )
        drawContent()
    }

/**
 * A raised shape tinted by the accent, for the one control that is currently active.
 *
 * Colour still carries the state — this only changes how the surface is lit, so the
 * "recording" red and the "ready" teal stay the thing the eye reads first.
 */
fun Modifier.neuActive(
    shape: Shape,
    tint: Color,
    depth: Float = 1f,
): Modifier = this
    .clip(shape)
    .background(tint.copy(alpha = 0.16f).compositeOver(ScribeTokens.s2))
    .drawWithContent {
        val far = maxOf(size.width, size.height)
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(tint.copy(alpha = 0.22f * depth), Color.Transparent),
                start = Offset.Zero,
                end = Offset(far * 0.6f, far * 0.6f),
            ),
        )
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f * depth)),
                start = Offset(size.width * 0.5f, size.height * 0.5f),
                end = Offset(size.width, size.height),
            ),
        )
        drawContent()
    }
