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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.draw.clip
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

    /** Replace the half-typed word with the completion the user picked. */
    fun acceptSuggestion(word: String)
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
    /** Whether a pressed key floats a bubble above itself. Off unless asked for. */
    keyPreview: Boolean = false,
    /** Completions for the half-typed word, or empty. Shown where the status sits. */
    suggestions: List<String> = emptyList(),
) {
    // The page is deliberately *not* remembered. Whether the keys are up is worth keeping
    // across appearances — that is `keysShown`, and it persists — but coming back to a
    // keyboard that is showing emoji because that is where you left it is a keyboard that
    // has lost its place. It always opens on the letters.
    var page by remember { mutableStateOf(KeyboardPage.LETTERS) }
    var shift by remember { mutableStateOf(ShiftState.OFF) }

    // Rises the last little way into place rather than simply being there. The system
    // already slides the input window up; this rides on top of it so the panel arrives as
    // one object with the shoulders it just grew, instead of appearing fully formed at the
    // end of somebody else's animation.
    val entrance by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(ENTRANCE_MS),
        label = "panel-entrance",
    )
    // Shared with the card: a key commits on touch-down, and if the gesture turns out to be
    // a drag of the card the key takes its character back.
    val arbiter = remember { SwipeArbiter() }
    LaunchedEffect(keysShown) { if (!keysShown) page = KeyboardPage.LETTERS }

    val dictating = state.stage != DictationStage.IDLE
    val alignment = if (LocalHandedness.current == Handedness.LEFT) {
        Alignment.CenterStart
    } else {
        Alignment.CenterEnd
    }

    // **The panel's own top corners are rounded, and the rest of it is transparent.** That
    // is what makes the black behind the keys read as part of the device rather than as a
    // rectangle pasted over the app: the wallpaper shows through at the shoulders, and the
    // ground curves up into the screen with the same radius as the card sitting on it.
    // Anything that arrives from off-screen has to have an edge, and this is that edge.
    Box(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                translationY = (1f - entrance) * ENTRANCE_TRAVEL_PX
                alpha = entrance
            }
            // Painted to the shape rather than clipped to it. `Modifier.clip` would do the
            // same to the eye and introduces a layer that rejects pointer input outside its
            // bounds — which cost an afternoon and eight failing tests. Nothing here
            // overflows the corners anyway: the card is inset from every edge.
            .background(
                color = ScribeTokens.bg,
                shape = RoundedCornerShape(
                    topStart = ScribeTokens.radius,
                    topEnd = ScribeTokens.radius,
                ),
            ),
        contentAlignment = alignment,
    ) {
        Column(
            Modifier
                .fillMaxWidth(widthFraction)
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.status == DictationStatus.NEEDS_PERMISSION) {
                PermissionRow(onOpenApp = actions::openApp)
            } else {
                VoiceStrip(state, actions, suggestions)

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
            KeysCardStack(
                page = page,
                open = keysShown,
                onPage = { next ->
                    page = next
                    shift = ShiftState.OFF
                },
                onOpenChange = { wanted -> if (wanted != keysShown) onToggleKeys() },
                onDragClaimed = { arbiter.swipes++ },
            ) { shown ->
                KeyboardPageContent(
                    page = shown,
                    shift = shift,
                    split = split,
                    actions = actions,
                    arbiter = arbiter,
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
                    onTypedCharacter = {
                        // One-shot shift releases after a single letter, as every other
                        // keyboard behaves. Caps lock does not.
                        if (shift == ShiftState.ONCE) shift = ShiftState.OFF
                    },
                    keyPreview = keyPreview,
                )
            }
        }
    }
}

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
private fun VoiceStrip(
    state: EngineState,
    actions: PanelActions,
    suggestions: List<String>,
) {
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

        // One line under the strip, doing two jobs that never happen at once: completions
        // while typing, and what Scribe is doing when it is not. Nobody types and talks
        // simultaneously, so the row does not need to be shared — it needs to be swapped.
        // The alternative was a permanent suggestion row, which costs height on every
        // screen for something that is empty most of the time.
        if (!busy) {
            if (suggestions.isNotEmpty()) {
                SuggestionStrip(suggestions, actions::acceptSuggestion)
            } else if (state.statusDetail.isNotEmpty()) {
                // Empty most of the time, and gone rather than reading "Ready" for ever.
                // The engine clears it a moment after it stops being news; a line that
                // always says the same thing is a line nobody reads.
                Text(
                    text = state.statusDetail,
                    color = if (state.error != null) ScribeTokens.rec else ScribeTokens.faint,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 8.dp).testTag("status-detail"),
                )
            }
        }
    }
}

/**
 * Completions for the word being typed, in the line where the status usually sits.
 *
 * **The pick is in the middle and it is the bright one.** Every keyboard puts its best guess
 * somewhere and then relies on the user learning where; putting it in the centre and giving
 * it the only near-white text on the row means it does not have to be learned. The
 * alternatives sit either side, dimmer, fading toward the background — present if wanted,
 * and not competing for the glance.
 *
 * Three at most, and the order is deliberately not "best first, left to right": the eye
 * lands in the middle of a row, so that is where the answer goes.
 */
@Composable
private fun SuggestionStrip(words: List<String>, onAccept: (String) -> Unit) {
    val haptics = rememberKeyHaptics()
    // Best in the centre, second to its left, third to its right.
    val ordered = when (words.size) {
        0 -> emptyList()
        1 -> words
        2 -> listOf(words[1], words[0])
        else -> listOf(words[1], words[0], words[2])
    }
    val pick = words.firstOrNull()

    Row(
        Modifier.fillMaxWidth().height(24.dp).testTag("suggestions"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ordered.forEach { word ->
            val chosen = word == pick
            Box(
                Modifier
                    .testTag("suggestion-$word")
                    .weight(1f)
                    .fillMaxHeight()
                    .semantics { contentDescription = "Use $word" }
                    .clickable { haptics(); onAccept(word) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    word,
                    color = if (chosen) ScribeTokens.text else ScribeTokens.faint,
                    fontSize = if (chosen) 14.sp else 13.sp,
                    fontWeight = if (chosen) FontWeight.Medium else FontWeight.Normal,
                )
            }
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

/** How far the panel rises on its way in. Small: this rides on the system's own slide. */
private const val ENTRANCE_TRAVEL_PX = 42f
private const val ENTRANCE_MS = 220
