package dev.smantics.scribe.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.smantics.scribe.ui.theme.DictationStatus
import dev.smantics.scribe.ui.theme.ScribeMotion
import dev.smantics.scribe.ui.theme.ScribeTokens
import kotlin.math.abs
import kotlin.math.sin

/**
 * The live level meter, carried over from the desktop's recording pill and Dictate page.
 *
 * Two details are load-bearing and both come from bugs the desktop already fixed:
 *
 *  - **The container height is fixed.** Bars grow and shrink *inside* it. An earlier
 *    desktop version sized the container to its content and the whole layout bounced on
 *    every syllable.
 *  - **Idle is not flat.** When nothing is being recorded the bars rest in a low, still
 *    sine shape rather than collapsing to nothing, so "not listening" and "listening to
 *    silence" do not look identical. A meter that reads zero when the microphone is dead
 *    and zero when the room is quiet has told the user nothing.
 *
 * While transcribing, the bars stop tracking amplitude — there is none — and shimmer
 * instead, so the state is legible without reading the label.
 */
@Composable
fun Waveform(
    level: Float,
    status: DictationStatus,
    boostActive: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
    barCount: Int = 28,
) {
    val recording = status == DictationStatus.RECORDING
    val transcribing = status == DictationStatus.TRANSCRIBING

    val transition = rememberInfiniteTransition(label = "waveform")
    val shimmer by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "shimmer",
    )

    // The bar heights are smoothed here rather than animated per-bar: 28 separate
    // Animatables would be 28 recompositions per audio callback, sixteen times a second.
    val smoothed by animateFloatSmoothed(level, recording)

    val tint = if (boostActive) ScribeTokens.warn else ScribeTokens.accent
    val idleTint = ScribeTokens.faint

    Box(modifier.fillMaxWidth().height(height)) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val barWidth = 3.dp.toPx()
            val gap = (size.width - barCount * barWidth) / (barCount - 1).coerceAtLeast(1)
            val centreY = size.height / 2f

            for (i in 0 until barCount) {
                // A fixed envelope, tallest in the middle, so the meter reads as one shape
                // rather than as a row of unrelated bars.
                val position = i / (barCount - 1f).coerceAtLeast(1f)
                val envelope = 0.35f + 0.65f * sin(position * Math.PI).toFloat()

                val fraction = when {
                    recording -> {
                        val jitter = 0.75f + 0.25f * abs(sin((i * 1.7f + smoothed * 9f).toDouble())).toFloat()
                        (0.18f + smoothed * 0.82f) * envelope * jitter
                    }
                    transcribing -> {
                        val phase = ((i % 5) / 5f + shimmer) % 1f
                        (0.20f + 0.30f * sin(phase * Math.PI).toFloat()) * envelope
                    }
                    else -> 0.16f * envelope
                }

                val barHeight = (fraction * size.height).coerceAtLeast(barWidth)
                val color: Color = if (recording || transcribing) tint else idleTint
                drawRoundRect(
                    color = color,
                    topLeft = Offset(i * (barWidth + gap), centreY - barHeight / 2f),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barWidth / 2f),
                )
            }
        }
    }
}

/**
 * Smooths the raw RMS so the meter reads as a voice rather than as noise.
 *
 * The duration is the desktop's 70 ms bar animation: fast enough to track a syllable,
 * slow enough not to flicker.
 */
@Composable
private fun animateFloatSmoothed(target: Float, active: Boolean) =
    androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (active) target else 0f,
        animationSpec = tween(durationMillis = ScribeMotion.LEVEL_BAR),
        label = "level",
    )
