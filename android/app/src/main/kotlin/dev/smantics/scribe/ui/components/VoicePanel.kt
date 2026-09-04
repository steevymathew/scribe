package dev.smantics.scribe.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.smantics.scribe.core.clean.Mode
import dev.smantics.scribe.core.model.ModelRegistry
import dev.smantics.scribe.dictation.DictationStage
import dev.smantics.scribe.dictation.EngineState
import dev.smantics.scribe.ui.theme.DictationStatus
import dev.smantics.scribe.ui.theme.ScribeMotion
import dev.smantics.scribe.ui.theme.ScribeTokens
import dev.smantics.scribe.ui.theme.statusColor

/**
 * The compact dictation panel: a toast-sized strip with the waveform, the Raw/Clean
 * switch, and a menu.
 *
 * Used by the floating bubble when it expands, and by the voice-input activity that other
 * apps launch. Both need the same three things visible while speaking — *is it hearing
 * me*, *what will it type*, and *which model* — and neither has room for the full
 * keyboard panel.
 *
 * It is deliberately one row plus an optional drawer. Anything permanently visible here
 * sits on top of whatever the user is actually writing in.
 */
@Composable
fun VoicePanel(
    state: EngineState,
    modelLabel: String,
    onToggleMode: () -> Unit,
    /** Start dictating, or finish and insert. One control, both ways round. */
    onToggleDictation: () -> Unit,
    onCancel: () -> Unit,
    onOpenApp: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Why the transcript could not be typed into the field, or null.
     *
     * Present, it takes over the panel: the words are still here, and there is a way to
     * try again. The version this replaced closed the panel on failure exactly as it did
     * on success, so a dictation that went nowhere was indistinguishable from one that
     * worked — right up until the user looked at the empty field.
     */
    failure: String? = null,
    onRetryInsert: () -> Unit = {},
    onDismissFailure: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        modifier
            .testTag("voice-panel")
            .widthIn(min = 260.dp, max = 420.dp)
            .neuRaised(RoundedCornerShape(ScribeTokens.radius), ScribeTokens.s0)
            .border(1.dp, ScribeTokens.stroke2, RoundedCornerShape(ScribeTokens.radius))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(ScribeTokens.gapSmall),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ScribeTokens.gapSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Cancel first, and only while there is something to cancel. It is the one
            // control here that throws work away, so it is the one control here that is
            // red — an unlabelled grey circle beside four other unlabelled grey circles
            // is not a cancel button, it is a guess.
            if (state.stage != DictationStage.IDLE) CancelButton(onCancel)
            MicButton(state, onToggleDictation)
            Waveform(
                level = state.level,
                stage = state.stage,
                boostActive = state.boostActive,
                modifier = Modifier.weight(1f),
                height = 28.dp,
                barCount = 18,
            )
            ModeToggle(state.mode, enabled = true, onToggle = onToggleMode)
            HamburgerButton(open = menuOpen) { menuOpen = !menuOpen }
        }

        // The transcript, and the cleanup happening to it. This is the point of the
        // panel: the bubble exists so there is something to press, and this is what makes
        // pressing it worth watching.
        if (failure != null) {
            InsertionFailed(
                reason = failure,
                text = state.lastText,
                onRetry = onRetryInsert,
                onDismiss = onDismissFailure,
            )
        } else if (state.stage != DictationStage.IDLE) {
            TranscriptReveal(
                stage = state.stage,
                partialText = state.partialText,
                diff = state.diff,
                finalText = state.finalText,
                recordedSeconds = state.recordedSeconds,
                decodingSeconds = state.decodingSeconds,
            )
        } else {
            // The answer to "is this working", never hidden behind the menu.
            Text(
                text = state.statusDetail,
                color = if (state.error != null) ScribeTokens.rec else ScribeTokens.muted,
                fontSize = 12.sp,
                modifier = Modifier.testTag("voice-panel-status"),
            )
        }

        AnimatedVisibility(
            visible = menuOpen,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MenuRow("Model", modelLabel)
                MenuRow(
                    "Mode",
                    if (state.mode == Mode.RAW) {
                        "Raw — exactly as spoken"
                    } else {
                        "Clean — punctuated and tidied"
                    },
                )
                if (state.boostActive) MenuRow("Accuracy", "High")
                Row(
                    Modifier.padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(ScribeTokens.gapSmall),
                ) {
                    // Discard used to live here as well. It does not any more: a
                    // destructive action buried in a drawer is either missed or found by
                    // accident, and it is now a red button in the row above.
                    SecondaryButton("Open Scribe", "voice-open-app", onClick = onOpenApp)
                }
            }
        }
    }
}

/**
 * What to show when the words could not be typed into the field.
 *
 * Three things, in this order: that it did not work, **the transcript itself** so nothing
 * spoken is lost, and one button to try again — which is usually all it takes, because the
 * common cause is the field having lost focus while the reveal was running. Tapping back
 * into the field and pressing this puts the sentence where it was meant to go.
 */
@Composable
private fun InsertionFailed(
    reason: String,
    text: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        Modifier
            .testTag("voice-insert-failed")
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "Couldn't put that in the field — $reason.",
            color = ScribeTokens.rec,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (text.isNotEmpty()) {
            Text(
                text,
                color = ScribeTokens.text,
                fontSize = 13.sp,
                modifier = Modifier
                    .testTag("voice-insert-failed-text")
                    // neuInset clips to the shape itself; a second clip here would be a
                    // redundant layer around every one of these.
                    .neuInset(RoundedCornerShape(ScribeTokens.radiusSm), depth = 0.6f)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ScribeTokens.gapSmall)) {
            SecondaryButton(
                "Tap the field, then try again",
                "voice-retry-insert",
                tint = ScribeTokens.accent,
                onClick = onRetry,
            )
            SecondaryButton("Discard", "voice-dismiss-failure", onClick = onDismiss)
        }
    }
}

@Composable
private fun MenuRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ScribeTokens.gapSmall),
    ) {
        Text(label, color = ScribeTokens.faint, fontSize = 12.sp, modifier = Modifier.widthIn(min = 64.dp))
        Text(value, color = ScribeTokens.text, fontSize = 12.sp)
    }
}

/**
 * Throw the utterance away.
 *
 * Red, filled, and first in the row. Every other control here is a grey circle, so the one
 * that destroys work has to stop looking like them — "make the cancel button an obvious
 * cancel button" was the note, and it was right: it had been a line in the hamburger menu.
 */
@Composable
private fun CancelButton(onCancel: () -> Unit) {
    Box(
        Modifier
            .testTag("voice-cancel")
            .size(38.dp)
            .neuActive(CircleShape, ScribeTokens.rec)
            .semantics { contentDescription = "Discard what I said" }
            .clickable(onClick = onCancel),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(GlyphName.CLOSE, ScribeTokens.rec, size = 17.dp, thickness = 2.dp)
    }
}

/**
 * Start dictating, then finish and insert. One control, two jobs, in one place.
 *
 * The same shape the keyboard uses — a microphone when idle, an insert arrow while
 * listening — because they are the same action and having them differ between the two
 * surfaces is how a user learns one and is surprised by the other. It became a starter as
 * well as a finisher when the circle took over expanding and collapsing: something in the
 * panel has to begin the recording.
 */
@Composable
private fun MicButton(state: EngineState, onToggle: () -> Unit) {
    val listening = state.stage == DictationStage.LISTENING
    val finishing = state.stage != DictationStage.IDLE && !listening
    // Composed only while it breathes; see the note on the waveform's motion.
    val alpha = if (finishing) dotPulse() else 1f

    Box(
        Modifier
            .testTag("voice-insert")
            .size(42.dp)
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
            when {
                listening -> ScribeTokens.accent
                finishing -> statusColor(state.status).copy(alpha = alpha)
                else -> statusColor(state.status)
            },
            size = 19.dp,
            thickness = 1.9.dp,
        )
    }
}

/** The breath the button takes while Scribe is decoding, and only then. */
@Composable
private fun dotPulse(): Float {
    val transition = rememberInfiniteTransition(label = "voice-dot")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(ScribeMotion.DOT_PULSE),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "voice-dot-alpha",
    )
    return alpha
}

@Composable
private fun HamburgerButton(open: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .testTag("voice-menu")
            .size(32.dp)
            .then(
                if (open) {
                    Modifier.neuActive(RoundedCornerShape(ScribeTokens.radiusChip), ScribeTokens.accent)
                } else {
                    Modifier.neuInset(RoundedCornerShape(ScribeTokens.radiusChip), depth = 0.7f)
                },
            )
            .semantics { contentDescription = if (open) "Hide details" else "Show details" }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            repeat(3) {
                Box(
                    Modifier
                        .size(width = 14.dp, height = 1.5.dp)
                        .background(if (open) ScribeTokens.accent else ScribeTokens.muted),
                )
            }
        }
    }
}

/**
 * The bubble as one object: the panel, when it is open, sitting on top of the circle.
 *
 * The layout is the point. The circle is aligned to the same edge as the panel and sits
 * below it, and the window it lives in is anchored to the bottom of the screen — so
 * opening the panel grows the window *upward* and the circle does not move. Press it, the
 * panel appears above your finger; press it again, the panel goes and your finger is still
 * on the circle. Nothing to chase.
 *
 * [modifier] is where the service puts the drag handler, so the whole assembly moves
 * together rather than the circle escaping its own panel.
 */
@Composable
fun BubbleAssembly(
    state: EngineState,
    expanded: Boolean,
    modelLabel: String,
    onToggleExpanded: () -> Unit,
    onClose: () -> Unit,
    onToggleDictation: () -> Unit,
    onToggleMode: () -> Unit,
    onCancel: () -> Unit,
    onOpenApp: () -> Unit,
    modifier: Modifier = Modifier,
    failure: String? = null,
    onRetryInsert: () -> Unit = {},
    onDismissFailure: () -> Unit = {},
) {
    Column(
        modifier.testTag("bubble-assembly"),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (expanded) {
            VoicePanel(
                state = state,
                modelLabel = modelLabel,
                onToggleMode = onToggleMode,
                onToggleDictation = onToggleDictation,
                onCancel = onCancel,
                onOpenApp = onOpenApp,
                failure = failure,
                onRetryInsert = onRetryInsert,
                onDismissFailure = onDismissFailure,
            )
        }
        VoiceBubble(
            state = state,
            expanded = expanded,
            onClick = onToggleExpanded,
            onClose = onClose,
        )
    }
}

/**
 * The circle. It is on screen the whole time now, panel open or closed.
 *
 * It used to be one half of a swap — circle *or* panel, never both — which meant the thing
 * you had pressed to open the panel was gone the moment it opened, and getting back was a
 * different control in a different place. Now the circle is the constant: it opens the
 * panel, it closes it again, it is the handle the whole thing is dragged by, and it is
 * where the recording state is shown. The panel hangs off it.
 *
 * It carries its own close badge. An overlay that appears over other apps and cannot be
 * dismissed without going to system settings is the kind of thing people uninstall, and
 * "there is no X" was the first thing said about the earliest version.
 */
@Composable
fun VoiceBubble(
    state: EngineState,
    expanded: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val recording = state.stage == DictationStage.LISTENING

    Box(modifier.padding(6.dp)) {
        // A circle pressed into the surface, like the Scribe mark itself. Raised while it
        // listens, so the one moment it is doing something is the one moment it stands
        // proud of the screen.
        Box(
            Modifier
                .testTag("voice-bubble")
                .size(58.dp)
                .then(
                    when {
                        recording -> Modifier.neuActive(CircleShape, ScribeTokens.rec)
                        // Open, but not listening: lit faintly, so the circle says which
                        // way the next tap goes rather than looking identical either way.
                        expanded -> Modifier.neuActive(CircleShape, ScribeTokens.accent)
                        else -> Modifier.neuInset(CircleShape)
                    },
                )
                .semantics {
                    contentDescription =
                        if (expanded) "Close the Scribe panel" else "Open Scribe"
                }
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (recording) {
                Waveform(
                    level = state.level,
                    stage = state.stage,
                    boostActive = state.boostActive,
                    modifier = Modifier.padding(horizontal = 10.dp),
                    height = 22.dp,
                    barCount = 5,
                )
            } else {
                ScribeMark(size = 34.dp)
            }
        }

        Box(
            Modifier
                .align(Alignment.TopStart)
                .testTag("bubble-close")
                .size(22.dp)
                .neuRaised(CircleShape, ScribeTokens.s1)
                .semantics { contentDescription = "Hide the Scribe button" }
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Glyph(GlyphName.CLOSE, ScribeTokens.muted, size = 11.dp, thickness = 1.6.dp)
        }
    }
}

/**
 * The one-line summary the panel's menu shows for the loaded model.
 *
 * Resolved to the same display name the models screen uses — "Base (English)", not
 * "base.en". The panel and Settings naming the same model two different ways is a small
 * thing that costs a moment every time somebody checks whether their choice took effect.
 */
fun modelLabelFor(state: EngineState): String {
    if (state.modelName.isEmpty()) return "loading…"
    val name = ModelRegistry.byId(state.modelName)?.displayName ?: state.modelName
    return if (state.boostActive) "$name · high accuracy" else name
}

/**
 * The drop zone that closes the bubble.
 *
 * Appears at the bottom of the screen while the bubble is being dragged and grows when the
 * bubble is over it, which is the gesture Android uses for every other floating control.
 * Learning it costs nothing because nobody has to.
 */
@Composable
fun DismissTarget(active: Boolean, modifier: Modifier = Modifier) {
    val size by animateDpAsState(
        targetValue = if (active) 74.dp else 58.dp,
        label = "dismiss-size",
    )
    Box(
        modifier
            .testTag("bubble-dismiss-target")
            .size(size)
            .then(
                if (active) {
                    Modifier.neuActive(CircleShape, ScribeTokens.rec)
                } else {
                    Modifier.neuInset(CircleShape)
                },
            )
            .semantics { contentDescription = "Drop here to close Scribe" },
        contentAlignment = Alignment.Center,
    ) {
        Glyph(
            GlyphName.CLOSE,
            if (active) ScribeTokens.rec else ScribeTokens.muted,
            size = if (active) 26.dp else 20.dp,
            thickness = 2.dp,
        )
    }
}
