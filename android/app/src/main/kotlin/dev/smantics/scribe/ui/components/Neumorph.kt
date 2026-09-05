package dev.smantics.scribe.ui.components

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
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
 *
 * **The gradients are built in [drawWithCache], not in the draw pass.** A `Brush` caches
 * the shader it compiles, keyed by the size it was asked to fill — so a brush constructed
 * inside `drawWithContent` throws that cache away and compiles a new shader on every
 * frame. With forty keys on screen that is eighty shaders a frame, which is what made the
 * keyboard's own show and hide animations stutter. `drawWithCache` rebuilds them only
 * when the element changes size.
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
    .drawWithCache {
        val far = maxOf(size.width, size.height)
        // Top-left: the shadow cast by the rim nearest the light.
        val near = Brush.linearGradient(
            colors = listOf(Color.Black.copy(alpha = 0.55f * depth), Color.Transparent),
            start = Offset.Zero,
            end = Offset(far * 0.62f, far * 0.62f),
        )
        // Bottom-right: the lit far wall.
        val lit = Brush.linearGradient(
            colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.07f * depth)),
            start = Offset(size.width * 0.42f, size.height * 0.42f),
            end = Offset(size.width, size.height),
        )
        onDrawWithContent {
            drawRect(brush = near)
            drawRect(brush = lit)
            drawContent()
        }
    }

/** A shape standing *proud of* the surface — the same light, the other way round. */
fun Modifier.neuRaised(
    shape: Shape,
    surface: Color = ScribeTokens.s2,
    depth: Float = 1f,
): Modifier = this
    .clip(shape)
    .background(surface)
    .drawWithCache {
        val far = maxOf(size.width, size.height)
        val lit = Brush.linearGradient(
            colors = listOf(Color.White.copy(alpha = 0.09f * depth), Color.Transparent),
            start = Offset.Zero,
            end = Offset(far * 0.55f, far * 0.55f),
        )
        val shade = Brush.linearGradient(
            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.42f * depth)),
            start = Offset(size.width * 0.45f, size.height * 0.45f),
            end = Offset(size.width, size.height),
        )
        onDrawWithContent {
            drawRect(brush = lit)
            drawRect(brush = shade)
            drawContent()
        }
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
    .drawWithCache {
        val far = maxOf(size.width, size.height)
        val glow = Brush.linearGradient(
            colors = listOf(tint.copy(alpha = 0.22f * depth), Color.Transparent),
            start = Offset.Zero,
            end = Offset(far * 0.6f, far * 0.6f),
        )
        val shade = Brush.linearGradient(
            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f * depth)),
            start = Offset(size.width * 0.5f, size.height * 0.5f),
            end = Offset(size.width, size.height),
        )
        onDrawWithContent {
            drawRect(brush = glow)
            drawRect(brush = shade)
            drawContent()
        }
    }

/**
 * A key, raised until it is touched and **pressed into the surface while it is**.
 *
 * This is the whole point of the style on a keyboard, and it was missing: every key was
 * drawn raised and stayed raised, so the only confirmation a tap had registered was the
 * haptic tick and the character appearing somewhere else on screen. A physical key
 * *moves*. Here the light flips — the highlight that sat on the top-left edge goes to the
 * bottom-right and the shadow crosses the other way — which is what the eye reads as the
 * surface going down, and it lands on the same frame as the vibration.
 *
 * [pressed] is passed rather than read from an interaction source because the gesture that
 * drives it also has to survive a drag: a finger that starts on a key and swipes the page
 * away must un-press the key without typing it, and only the gesture detector knows that.
 */
fun Modifier.neuKey(
    shape: Shape,
    pressed: Boolean,
    surface: Color = ScribeTokens.s2,
    tint: Color? = null,
): Modifier = when {
    tint != null && !pressed -> neuActive(shape, tint)
    tint != null -> neuActive(shape, tint, depth = PRESSED_DEPTH).clip(shape)
    pressed -> neuInset(shape, surface, depth = PRESSED_DEPTH)
    else -> neuRaised(shape, surface)
}

/**
 * How far a pressed key sinks.
 *
 * Deeper than the resting inset used for wells and toggles. A key is being touched at the
 * moment it is drawn, so the difference from its neighbours needs to be unmistakable at a
 * glance taken with a fingertip covering it.
 */
private const val PRESSED_DEPTH = 1.15f
