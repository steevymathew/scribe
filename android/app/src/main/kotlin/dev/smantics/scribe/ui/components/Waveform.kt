package dev.smantics.scribe.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.smantics.scribe.dictation.DictationStage
import dev.smantics.scribe.ui.theme.ScribeMotion
import dev.smantics.scribe.ui.theme.ScribeTokens
import kotlin.math.abs
import kotlin.math.sin

/**
 * The level meter, and the app's main signal of what it is doing.
 *
 * Each stage gets a **visibly different motion**, because the words in the caption are the
 * thing nobody reads while they are talking:
 *
 *  - **listening** — bars track the voice, hard. The amplitude is deliberately
 *    over-driven: a meter that twitches politely at a normal speaking volume reads as
 *    "barely hearing you", which is the opposite of reassuring.
 *  - **transcribing** — a sweep travelling left to right through settled bars. Nothing is
 *    being heard any more, and the shape says so without a word: the input stopped and a
 *    machine started.
 *  - **cleaning and punctuating** — a slow, low breath. Work is happening, but it is text
 *    work, and it should not look like the microphone is open.
 *  - **idle** — a still, low profile. Not flat: "not listening" and "listening to silence"
 *    must not look identical.
 */
@Composable
fun Waveform(
    level: Float,
    stage: DictationStage,
    boostActive: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
    barCount: Int = 28,
) {
    val listening = stage == DictationStage.LISTENING
    val transcribing = stage == DictationStage.TRANSCRIBING
    val settling = stage == DictationStage.CLEANING ||
        stage == DictationStage.PUNCTUATING ||
        stage == DictationStage.FINAL

    // **Only while something is actually happening.** An infinite transition subscribes to
    // the frame clock and never lets go, so leaving these running when the meter is at
    // rest kept the whole surface producing frames forever. In the keyboard — where this
    // strip is always on screen — that meant the panel was recomposing and redrawing at
    // display rate while the window was still sliding into place, and the show and hide
    // animations dropped frames for it. Composing the transitions only when the stage
    // needs them lets the panel go idle, which is what makes it settle smoothly.
    val motion = if (listening || transcribing || settling) waveMotion() else WaveMotion.STILL

    // Smoothed so the meter reads as a voice rather than as noise, but only just: too much
    // smoothing and it stops tracking syllables. This one is finite — it settles and stops.
    val smoothed by animateFloatAsState(
        targetValue = if (listening) level else 0f,
        animationSpec = tween(durationMillis = ScribeMotion.LEVEL_BAR),
        label = "level",
    )
    val sweep = motion.sweep
    val breath = motion.breath
    val churn = motion.churn

    val tint = if (boostActive) ScribeTokens.warn else ScribeTokens.accent
    val activeColour = when {
        listening -> tint
        transcribing -> ScribeTokens.warn
        settling -> ScribeTokens.good
        else -> ScribeTokens.faint
    }

    // A fixed envelope, tallest in the middle, so the meter reads as one shape.
    val envelope = remember(barCount) {
        FloatArray(barCount) { i ->
            val position = i / (barCount - 1f).coerceAtLeast(1f)
            0.35f + 0.65f * sin(position * Math.PI).toFloat()
        }
    }

    Box(modifier.fillMaxWidth().height(height)) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val barWidth = 3.dp.toPx()
            val gap = (size.width - barCount * barWidth) / (barCount - 1).coerceAtLeast(1)
            val centreY = size.height / 2f

            for (i in 0 until barCount) {
                val position = i / (barCount - 1f).coerceAtLeast(1f)
                val env = envelope[i]

                val fraction = when {
                    listening -> {
                        // Over-driven on purpose, and jittered per bar so the shape lives.
                        val jitter = 0.55f + 0.45f *
                            abs(sin((i * 0.9f + churn * 0.35f).toDouble())).toFloat()
                        val amplitude = (smoothed * 1.35f).coerceAtMost(1f)
                        (0.12f + amplitude * 0.88f) * env * jitter
                    }
                    transcribing -> {
                        // A pulse travelling through the bars: distance from the sweep
                        // head decides the height, so one crest moves left to right.
                        val distance = abs(position - sweep)
                        val crest = (1f - (distance * 3.2f)).coerceAtLeast(0f)
                        (0.14f + crest * 0.86f) * env
                    }
                    settling -> (0.16f + 0.14f * breath) * env
                    else -> 0.14f * env
                }

                val barHeight = (fraction * size.height).coerceAtLeast(barWidth)

                // While the sweep passes, the bar it is on takes the full colour and the
                // rest sit back — the motion is carried by brightness as well as height.
                val colour: Color = if (transcribing) {
                    val distance = abs(position - sweep)
                    lerp(ScribeTokens.faint, activeColour, (1f - distance * 3.2f).coerceIn(0f, 1f))
                } else {
                    activeColour
                }

                drawRoundRect(
                    color = colour,
                    topLeft = Offset(i * (barWidth + gap), centreY - barHeight / 2f),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barWidth / 2f),
                )
            }
        }
    }
}


/**
 * The three continuous motions the meter can be driven by, sampled for one frame.
 *
 * Held in a value class rather than read straight from the transition so that [Waveform]
 * can swap in [STILL] and drop the animations out of the composition entirely when there
 * is nothing to animate.
 */
private data class WaveMotion(val sweep: Float, val breath: Float, val churn: Float) {
    companion object {
        /** What the meter looks like when nothing is running: a still, low profile. */
        val STILL = WaveMotion(sweep = 0f, breath = 0f, churn = 0f)
    }
}

/**
 * Start the continuous motions, for exactly as long as this is composed.
 *
 * One sweep of the scanner, one slow breath, and a slow churn that jitters the bars
 * individually while listening so the shape moves as a voice does rather than every bar
 * rising and falling together.
 */
@Composable
private fun waveMotion(): WaveMotion {
    val transition = rememberInfiniteTransition(label = "waveform")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(900), repeatMode = RepeatMode.Restart),
        label = "sweep",
    )
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(1400), repeatMode = RepeatMode.Reverse),
        label = "breath",
    )
    val churn by transition.animateFloat(
        initialValue = 0f,
        targetValue = 100f,
        animationSpec = infiniteRepeatable(animation = tween(3000), repeatMode = RepeatMode.Restart),
        label = "churn",
    )
    return WaveMotion(sweep = sweep, breath = breath, churn = churn)
}
