package dev.smantics.scribe.ime

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.smantics.scribe.core.clean.Mode
import dev.smantics.scribe.dictation.EngineState
import dev.smantics.scribe.ui.components.Glyph
import dev.smantics.scribe.ui.components.GlyphName
import dev.smantics.scribe.ui.components.ModeToggle
import dev.smantics.scribe.ui.components.Waveform
import dev.smantics.scribe.ui.theme.DictationStatus
import dev.smantics.scribe.ui.theme.Handedness
import dev.smantics.scribe.ui.theme.LocalHandedness
import dev.smantics.scribe.ui.theme.ScribeMotion
import dev.smantics.scribe.ui.theme.ScribeTokens
import dev.smantics.scribe.ui.theme.statusColor

/** What the panel can ask the service to do. */
interface PanelActions {
    fun startRecording()
    fun stopRecording()
    fun toggleMode()
    fun setBoost(held: Boolean)
    fun backspace()
    fun space()
    fun enter()
    fun moveCursor(delta: Int)
    fun switchKeyboard()
    fun openApp()
}

/**
 * The keyboard.
 *
 * The layout answers the two questions this surface exists for — *is it hearing me?* and
 * *what will it type?* — in the top half, and puts the thing you press underneath, within
 * thumb reach. Everything else is subordinate.
 *
 * The mode toggle sits beside the waveform because that is where the eye already is while
 * speaking, and because the two are the same decision: what is going in, and what will
 * come out.
 */
@Composable
fun ScribePanel(
    state: EngineState,
    actions: PanelActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(ScribeTokens.s0)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(ScribeTokens.gap),
    ) {
        if (state.status == DictationStatus.NEEDS_PERMISSION) {
            PermissionRow(onOpenApp = actions::openApp)
        } else {
            StatusRow(state)
            WaveformRow(state, actions)
            MicRow(state, actions)
        }
        EditRow(actions)
    }
}

/**
 * Status, and only status.
 *
 * The label says what Scribe is doing in the words the desktop uses — "Listening…",
 * "Transcribing…", "Inserted" — and the dot carries the same information in colour for
 * anyone glancing rather than reading. An error replaces the label outright: it is never
 * collapsed, deferred, or shown only in a log.
 */
@Composable
private fun StatusRow(state: EngineState) {
    val colour = statusColor(state.status)
    val pulsing = state.status == DictationStatus.RECORDING ||
        state.status == DictationStatus.TRANSCRIBING

    val transition = rememberInfiniteTransition(label = "dot")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (pulsing) 0.35f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(ScribeMotion.DOT_PULSE),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot-alpha",
    )

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ScribeTokens.gapSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(colour.copy(alpha = if (pulsing) alpha else 1f)),
        )
        Text(
            text = state.statusDetail,
            color = if (state.error != null) ScribeTokens.rec else ScribeTokens.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.testTag("status-detail"),
        )
        Spacer(Modifier.weight(1f))

        AnimatedVisibility(visible = state.boostActive) { BoostBadge() }

        if (state.modelName.isNotEmpty() && !state.boostActive) {
            Text(
                text = state.modelName,
                color = ScribeTokens.faint,
                fontSize = 11.sp,
            )
        }
    }
}

/** The amber "HD" tag from the desktop pill: the high-accuracy model is armed. */
@Composable
private fun BoostBadge() {
    Row(
        Modifier
            .clip(RoundedCornerShape(ScribeTokens.radiusPill))
            .background(ScribeTokens.warn.copy(alpha = 0.18f))
            .border(1.dp, ScribeTokens.warn.copy(alpha = 0.5f), RoundedCornerShape(ScribeTokens.radiusPill))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(GlyphName.BOLT, ScribeTokens.warn, size = 11.dp, thickness = 1.4.dp)
        Text("HD", color = ScribeTokens.warn, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * The waveform and, next to it, the Raw/Clean toggle.
 *
 * Handedness decides which side the toggle lands on. On an 8-inch inner display a control
 * pinned to one edge is out of reach of the thumb holding the device, and which edge that
 * is depends on the hand.
 */
@Composable
private fun WaveformRow(state: EngineState, actions: PanelActions) {
    val toggleFirst = LocalHandedness.current == Handedness.LEFT
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ScribeTokens.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (toggleFirst) {
            ModeToggle(state.mode, enabled = true, onToggle = actions::toggleMode)
            Waveform(state.level, state.status, state.boostActive, Modifier.weight(1f))
        } else {
            Waveform(state.level, state.status, state.boostActive, Modifier.weight(1f))
            ModeToggle(state.mode, enabled = true, onToggle = actions::toggleMode)
        }
    }
}

/**
 * The button.
 *
 * Press and hold to dictate, exactly like holding the key on the desktop; the pulse ring
 * is the desktop's, at the same 1400 ms. A long-press on the badge to its side arms the
 * high-accuracy model, so boost is reachable without leaving the keyboard.
 */
@Composable
private fun MicRow(state: EngineState, actions: PanelActions) {
    val recording = state.status == DictationStatus.RECORDING
    val transition = rememberInfiniteTransition(label = "pulse")
    val ring by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (recording) 1.35f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(ScribeMotion.PULSE_RING),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ring",
    )
    val colour = statusColor(state.status)

    Box(Modifier.fillMaxWidth().heightIn(min = 96.dp), contentAlignment = Alignment.Center) {
        if (recording) {
            Box(
                Modifier
                    .size(84.dp)
                    .scale(ring)
                    .clip(CircleShape)
                    .border(2.dp, colour.copy(alpha = (1.35f - ring) / 0.35f), CircleShape),
            )
        }
        Box(
            Modifier
                .testTag("mic-button")
                .size(84.dp)
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(listOf(ScribeTokens.s2, ScribeTokens.s0)),
                )
                .border(1.dp, ScribeTokens.stroke2, CircleShape)
                .semantics {
                    contentDescription = if (recording) {
                        "Stop dictating"
                    } else {
                        "Hold to dictate"
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            actions.startRecording()
                            // Hold to dictate: recording ends when the finger lifts,
                            // whether that is a release or the gesture being cancelled.
                            tryAwaitRelease()
                            actions.stopRecording()
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Glyph(GlyphName.MIC, colour, size = 34.dp, thickness = 2.dp)
        }

        // Boost, parked beside the mic so it is reachable with the same hand.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            BoostButton(active = state.boostActive, onChange = actions::setBoost)
        }
    }
}

@Composable
private fun BoostButton(active: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        Modifier
            .testTag("boost-button")
            .size(48.dp)
            .clip(CircleShape)
            .background(if (active) ScribeTokens.warn.copy(alpha = 0.18f) else ScribeTokens.s1)
            .border(
                1.dp,
                if (active) ScribeTokens.warn.copy(alpha = 0.5f) else ScribeTokens.stroke,
                CircleShape,
            )
            .semantics {
                contentDescription =
                    if (active) "High accuracy on" else "Turn on high accuracy"
            }
            .clickable { onChange(!active) },
        contentAlignment = Alignment.Center,
    ) {
        Glyph(
            GlyphName.BOLT,
            if (active) ScribeTokens.warn else ScribeTokens.faint,
            size = 20.dp,
            thickness = 1.8.dp,
        )
    }
}

/**
 * Enough editing to fix a word without switching keyboards.
 *
 * A voice keyboard that cannot delete the one word it got wrong sends the user back to
 * Gboard for every correction, and that round trip is what makes people give up on
 * dictation. The last key returns to the previous keyboard in one tap, so choosing Scribe
 * is never a trap.
 */
@Composable
private fun EditRow(actions: PanelActions) {
    Row(
        Modifier.fillMaxWidth().height(52.dp),
        horizontalArrangement = Arrangement.spacedBy(ScribeTokens.gapSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Key(GlyphName.ARROW_LEFT, "Move cursor left", Modifier.weight(1f)) { actions.moveCursor(-1) }
        Key(GlyphName.ARROW_RIGHT, "Move cursor right", Modifier.weight(1f)) { actions.moveCursor(1) }
        Key(GlyphName.SPACE, "Space", Modifier.weight(2f)) { actions.space() }
        Key(GlyphName.BACKSPACE, "Backspace", Modifier.weight(1f)) { actions.backspace() }
        Key(GlyphName.RETURN, "Enter", Modifier.weight(1f)) { actions.enter() }
        Key(GlyphName.KEYBOARD, "Switch keyboard", Modifier.weight(1f)) { actions.switchKeyboard() }
    }
}

@Composable
private fun Key(
    glyph: GlyphName,
    description: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .testTag("key-$description")
            .height(48.dp)
            .clip(RoundedCornerShape(ScribeTokens.radiusSm))
            .background(ScribeTokens.s1)
            .border(1.dp, ScribeTokens.stroke, RoundedCornerShape(ScribeTokens.radiusSm))
            .semantics { contentDescription = description }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(glyph, ScribeTokens.muted, size = 20.dp, thickness = 1.8.dp)
    }
}

/**
 * The dead end that must not be one.
 *
 * An input method cannot request a runtime permission — it has no Activity to host the
 * dialog — so a keyboard without microphone access can only explain and offer a way out.
 * Wispr Flow's Android reviews are full of people stuck in setup; this screen exists so
 * that cannot happen here.
 */
@Composable
private fun PermissionRow(onOpenApp: () -> Unit) {
    Column(
        Modifier
            .testTag("permission-row")
            .fillMaxWidth()
            .clip(RoundedCornerShape(ScribeTokens.radius))
            .background(ScribeTokens.s1)
            .border(1.dp, ScribeTokens.warn.copy(alpha = 0.4f), RoundedCornerShape(ScribeTokens.radius))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(ScribeTokens.gapSmall),
    ) {
        Text(
            "Scribe needs the microphone",
            color = ScribeTokens.text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "A keyboard can't ask for permissions itself. Open Scribe once to allow it — " +
                "your voice stays on this phone either way.",
            color = ScribeTokens.muted,
            fontSize = 13.sp,
        )
        Box(
            Modifier
                .testTag("open-scribe")
                .clip(RoundedCornerShape(ScribeTokens.radiusSm))
                .background(ScribeTokens.accent)
                .clickable(onClick = onOpenApp)
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
            Text(
                "Open Scribe",
                color = ScribeTokens.onAccent,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
