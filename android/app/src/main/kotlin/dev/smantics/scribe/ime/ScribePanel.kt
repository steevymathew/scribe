package dev.smantics.scribe.ime

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.smantics.scribe.dictation.DictationStage
import dev.smantics.scribe.dictation.EngineState
import dev.smantics.scribe.ui.components.Glyph
import dev.smantics.scribe.ui.components.GlyphName
import dev.smantics.scribe.ui.components.ModeToggle
import dev.smantics.scribe.ui.components.ScribeMark
import dev.smantics.scribe.ui.components.TranscriptReveal
import dev.smantics.scribe.ui.components.Waveform
import dev.smantics.scribe.ui.components.neuActive
import dev.smantics.scribe.ui.components.neuInset
import dev.smantics.scribe.ui.components.neuRaised
import dev.smantics.scribe.ui.theme.DictationStatus
import dev.smantics.scribe.ui.theme.Handedness
import dev.smantics.scribe.ui.theme.LocalHandedness
import dev.smantics.scribe.ui.theme.ScribeMotion
import dev.smantics.scribe.ui.theme.ScribeTokens
import dev.smantics.scribe.ui.theme.statusColor

/** What the panel can ask the service to do. */
interface PanelActions {
    /** Start or stop dictating. One tap each way. */
    fun toggleDictation()

    /** Throw away the utterance in progress. */
    fun cancelDictation()

    fun toggleMode()
    fun setBoost(held: Boolean)
    fun type(text: String)
    fun backspace()
    fun deleteWord()
    fun space()
    fun enter()

    /**
     * Back to the previous keyboard.
     *
     * **No key calls this any more.** Android's own picker does that job, from the system's
     * own navigation bar, and a second one on the primary keyboard is an invitation to tap
     * away from the keyboard the user just chose. It stays on the interface for the
     * fallback view — the plain-View panel that appears when Compose cannot start, which
     * has to offer a way out that does not depend on anything Scribe drew.
     */
    fun switchKeyboard()

    fun openApp()
}

/**
 * The keyboard.
 *
 * It is a keyboard first and a dictation panel second, which is the correction the first
 * version needed: shipping voice-only meant that fixing a single misheard word sent the
 * user off to another keyboard, and a dictation keyboard you have to leave in order to
 * type is not a keyboard, it is a dialog.
 *
 * Two parts, and only two. The **voice strip** is always there — the way into the app, the
 * waveform, the mode switch and the one big button — and below it the **keys**, a card
 * that pages sideways and can be sent away downward. There is no utility bar between them
 * any more; [KeysCard] carries the note on what was there and why it went.
 */
@Composable
fun ScribePanel(
    state: EngineState,
    actions: PanelActions,
    keysShown: Boolean,
    onToggleKeys: () -> Unit,
    modifier: Modifier = Modifier,
    /** Whether to draw the keys in two halves. Decided in Settings, not by a key. */
    split: Boolean = false,
    /** How much of the width the keyboard occupies. Also a setting. */
    widthFraction: Float = 1f,
) {
    // The page is deliberately *not* remembered. Whether the keys are up is worth keeping
    // across appearances — that is `keysShown`, and it persists — but coming back to a
    // keyboard that is showing emoji because that is where you left it is a keyboard that
    // has lost its place. It always opens on the letters.
    var page by remember { mutableStateOf(KeyboardPage.LETTERS) }
    var shift by remember { mutableStateOf(ShiftState.OFF) }
    LaunchedEffect(keysShown) { if (!keysShown) page = KeyboardPage.LETTERS }

    val dictating = state.stage != DictationStage.IDLE
    val alignment = if (LocalHandedness.current == Handedness.LEFT) {
        Alignment.CenterStart
    } else {
        Alignment.CenterEnd
    }

    Box(modifier.fillMaxWidth().background(ScribeTokens.bg), contentAlignment = alignment) {
        Column(
            Modifier
                .fillMaxWidth(widthFraction)
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.status == DictationStatus.NEEDS_PERMISSION) {
                PermissionRow(onOpenApp = actions::openApp)
            } else {
                VoiceStrip(state, actions)

                AnimatedVisibility(
                    visible = dictating,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    TranscriptReveal(
                        stage = state.stage,
                        partialText = state.partialText,
                        diff = state.diff,
                        finalText = state.finalText,
                        modifier = Modifier.padding(horizontal = 2.dp),
                        recordedSeconds = state.recordedSeconds,
                        decodingSeconds = state.decodingSeconds,
                    )
                }
            }

            // The letters stay exactly as the user left them across appearances. An early
            // version hid them the moment dictation started, which meant the panel jumped
            // every time the microphone was pressed and the choice never stuck — if they
            // were up they stay up, and the transcript appears above them.
            LayingCard(open = keysShown, onShow = onToggleKeys) {
                KeysCard(
                    page = page,
                    shift = shift,
                    split = split,
                    actions = actions,
                    onPage = { next ->
                        page = next
                        shift = ShiftState.OFF
                    },
                    onShift = {
                        shift = when (shift) {
                            ShiftState.OFF -> ShiftState.ONCE
                            ShiftState.ONCE -> ShiftState.LOCKED
                            ShiftState.LOCKED -> ShiftState.OFF
                        }
                    },
                    onHide = onToggleKeys,
                    onTypedCharacter = {
                        // One-shot shift releases after a single letter, as every other
                        // keyboard behaves. Caps lock does not.
                        if (shift == ShiftState.ONCE) shift = ShiftState.OFF
                    },
                )
            }
        }
    }
}

/**
 * The keys, laid face down and propped back up.
 *
 * Swiping the keyboard away tips the card forward until you are looking at its edge, and
 * swiping up stands it back on its face. The rotation is around the *bottom* edge, which is
 * what makes it read as a hinge rather than a spin, and the container's height is
 * interpolated in step so the app behind moves with the card instead of jumping when it
 * finishes.
 *
 * The edge is a real control: it takes the swipe up and a tap, and it is drawn as the same
 * slab seen side-on, so what is left behind is recognisably the thing that went away rather
 * than a stray bar.
 */
@Composable
private fun LayingCard(
    open: Boolean,
    onShow: () -> Unit,
    content: @Composable () -> Unit,
) {
    val openness by animateFloatAsState(
        targetValue = if (open) 1f else 0f,
        animationSpec = tween(CARD_FLIP_MS),
        label = "keys-openness",
    )
    val full = KeyMetrics.keysHeight + KeyMetrics.cardPadding * 2
    val height = KeyMetrics.edgeHeight + (full - KeyMetrics.edgeHeight) * openness

    Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.BottomCenter) {
        // The edge is underneath the whole time and simply stops being covered, so there is
        // no moment where neither is on screen.
        KeysGrabber(onShow = onShow)
        if (openness > 0.01f) {
            Box(
                Modifier.graphicsLayer {
                    // Tipped forward from the bottom edge. Past about eighty degrees the
                    // face is edge-on and there is nothing left to see, so that is where
                    // the closed state sits.
                    rotationX = -CARD_FLIP_DEGREES * (1f - openness)
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    // Without a camera distance the projection is extreme enough to look
                    // like the card is being crushed rather than tilted.
                    cameraDistance = 14f * density
                    alpha = openness
                },
            ) {
                content()
            }
        }
    }
}

/** Long enough to see the card move, short enough not to delay the next keystroke. */
private const val CARD_FLIP_MS = 260
private const val CARD_FLIP_DEGREES = 82f

/**
 * The dictation controls: the way into Scribe, the waveform, the mode, and the one big
 * button.
 *
 * While listening, that button turns into **insert** — an arrow dropping onto a line —
 * because it is the control the user's finger is already on. Making them hunt for a
 * separate "done" elsewhere in the panel is the thing that made the first version feel
 * like a puzzle; the button that started the recording is the button that finishes it.
 */
@Composable
private fun VoiceStrip(state: EngineState, actions: PanelActions) {
    val haptics = rememberKeyHaptics()
    val toggleFirst = LocalHandedness.current == Handedness.LEFT
    val busy = state.stage != DictationStage.IDLE

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedVisibility(visible = busy) {
                RoundControl(
                    glyph = GlyphName.CLOSE,
                    description = "Discard what I said",
                    testTag = "voice-cancel",
                    tint = ScribeTokens.rec,
                ) { haptics(); actions.cancelDictation() }
            }

            // The way into the app, and the only permanent control here that is not part
            // of dictating. It is the mark rather than a cog because it goes to Scribe
            // itself — settings, models, vocabulary, history — not to one screen of it.
            // Holding the comma reaches the same place, which is where Android users look.
            Box(
                Modifier
                    .testTag("open-scribe")
                    .size(34.dp)
                    .neuInset(CircleShape, depth = 0.7f)
                    .semantics { contentDescription = "Open Scribe" }
                    .clickable { haptics(); actions.openApp() },
                contentAlignment = Alignment.Center,
            ) {
                ScribeMark(size = 22.dp)
            }

            if (toggleFirst) {
                ModeToggle(state.mode, enabled = true, onToggle = actions::toggleMode)
                Waveform(
                    state.level, state.stage, state.boostActive,
                    Modifier.weight(1f), height = 32.dp, barCount = 20,
                )
            } else {
                Waveform(
                    state.level, state.stage, state.boostActive,
                    Modifier.weight(1f), height = 32.dp, barCount = 20,
                )
                ModeToggle(state.mode, enabled = true, onToggle = actions::toggleMode)
            }

            MicToggle(state) { haptics(); actions.toggleDictation() }
        }

        // Status sits under the strip, not beside it: the strip is already several controls
        // wide on a cover screen, and an error must never be what gets truncated.
        if (!busy) {
            Text(
                text = state.statusDetail,
                color = if (state.error != null) ScribeTokens.rec else ScribeTokens.faint,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 8.dp).testTag("status-detail"),
            )
        }
    }
}

/**
 * Start dictating, then insert what was said.
 *
 * One control, two jobs, in the same place — a microphone when idle, an insert arrow while
 * listening. A toggle rather than press-and-hold: holding occupies the hand that is holding
 * the phone, and it makes the live transcript pointless, since there is no reading what is
 * being heard with a thumb pinned to a button.
 *
 * While the transcript is being finished the control is busy rather than interactive, and
 * says so — pressing it again mid-decode would be ambiguous at best.
 */
@Composable
private fun MicToggle(state: EngineState, onToggle: () -> Unit) {
    val listening = state.stage == DictationStage.LISTENING
    val finishing = state.stage != DictationStage.IDLE && !listening

    // Composed only while it is actually breathing. An infinite transition holds the frame
    // clock open for as long as it exists, so one that animates 1f to 1f is not free — it
    // is a keyboard that never stops drawing. See the note in Waveform.
    val pulse = if (listening) micPulse() else 1f

    val tint = when {
        listening -> ScribeTokens.accent
        finishing -> ScribeTokens.warn
        else -> statusColor(state.status)
    }

    Box(
        Modifier
            .testTag("mic-button")
            .size(52.dp)
            .scale(if (listening) pulse else 1f)
            .then(
                when {
                    listening -> Modifier.neuActive(CircleShape, ScribeTokens.accent)
                    finishing -> Modifier.neuInset(CircleShape, depth = 0.8f)
                    else -> Modifier.neuRaised(CircleShape)
                },
            )
            .semantics {
                contentDescription = when {
                    listening -> "Insert what I said"
                    finishing -> "Working on it"
                    else -> "Start dictating"
                }
            }
            .clickable(enabled = !finishing, onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(
            if (listening) GlyphName.INSERT else GlyphName.MIC,
            tint,
            size = 24.dp,
            thickness = 2.dp,
        )
    }
}

/** The slow breath the microphone takes while it is listening, and only then. */
@Composable
private fun micPulse(): Float {
    val transition = rememberInfiniteTransition(label = "mic")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(ScribeMotion.PULSE_RING),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "mic-pulse",
    )
    return pulse
}

@Composable
private fun RoundControl(
    glyph: GlyphName,
    description: String,
    testTag: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .testTag(testTag)
            .size(38.dp)
            .neuActive(CircleShape, tint)
            .semantics { contentDescription = description }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(glyph, tint, size = 17.dp, thickness = 1.7.dp)
    }
}

/**
 * The dead end that must not be one.
 *
 * An input method cannot request a runtime permission — it has no Activity to host the
 * dialog — so a keyboard without microphone access can only explain and offer a way out.
 */
@Composable
private fun PermissionRow(onOpenApp: () -> Unit) {
    Column(
        Modifier
            .testTag("permission-row")
            .fillMaxWidth()
            .neuInset(RoundedCornerShape(ScribeTokens.radius), depth = 0.8f)
            .border(
                1.dp,
                ScribeTokens.warn.copy(alpha = 0.4f),
                RoundedCornerShape(ScribeTokens.radius),
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
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
                .testTag("open-scribe-permission")
                .neuActive(RoundedCornerShape(ScribeTokens.radiusSm), ScribeTokens.accent)
                .clickable(onClick = onOpenApp)
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
            Text(
                "Open Scribe",
                color = ScribeTokens.accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
