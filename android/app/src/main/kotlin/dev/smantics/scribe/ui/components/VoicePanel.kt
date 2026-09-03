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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.smantics.scribe.core.clean.Mode
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
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onCollapse: () -> Unit,
    onOpenApp: () -> Unit,
    modifier: Modifier = Modifier,
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
            // Collapse sits first, exactly where the bubble was before it expanded, so
            // putting the panel away is the same spot that opened it rather than a hunt
            // across the screen.
            CollapseButton(onCollapse)
            InsertButton(state, onStop)
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
        if (state.stage != DictationStage.IDLE) {
            TranscriptReveal(
                stage = state.stage,
                partialText = state.partialText,
                diff = state.diff,
                finalText = state.finalText,
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
                    SecondaryButton("Open Scribe", "voice-open-app", onClick = onOpenApp)
                    SecondaryButton(
                        "Discard",
                        "voice-discard",
                        tint = ScribeTokens.rec,
                        onClick = onCancel,
                    )
                }
            }
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

/** Put the panel away again, back to the small bubble. */
@Composable
private fun CollapseButton(onCollapse: () -> Unit) {
    Box(
        Modifier
            .testTag("voice-collapse")
            .size(32.dp)
            .neuInset(CircleShape, depth = 0.7f)
            .semantics { contentDescription = "Shrink Scribe back to the button" }
            .clickable(onClick = onCollapse),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(GlyphName.RESIZE, ScribeTokens.muted, size = 15.dp, thickness = 1.6.dp)
    }
}

/**
 * Finish, and put the words in the field.
 *
 * An arrow dropping onto a line rather than a red dot: a red circle reads as "recording",
 * not as "press me to finish", and the first version left people looking for a separate
 * button that did not exist.
 */
@Composable
private fun InsertButton(state: EngineState, onStop: () -> Unit) {
    val pulsing = state.status == DictationStatus.RECORDING ||
        state.status == DictationStatus.TRANSCRIBING
    val transition = rememberInfiniteTransition(label = "voice-dot")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (pulsing) 0.35f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(ScribeMotion.DOT_PULSE),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "voice-dot-alpha",
    )
    val listening = state.stage == DictationStage.LISTENING
    Box(
        Modifier
            .testTag("voice-insert")
            .size(38.dp)
            .then(
                if (listening) {
                    Modifier.neuActive(CircleShape, ScribeTokens.accent)
                } else {
                    Modifier.neuInset(CircleShape, depth = 0.7f)
                },
            )
            .semantics {
                contentDescription = if (listening) "Insert what I said" else "Working on it"
            }
            .clickable(enabled = listening, onClick = onStop),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(
            if (listening) GlyphName.INSERT else GlyphName.MIC,
            if (listening) {
                ScribeTokens.accent
            } else {
                statusColor(state.status).copy(alpha = if (pulsing) alpha else 1f)
            },
            size = 18.dp,
            thickness = 1.8.dp,
        )
    }
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
 * The collapsed bubble: the smallest thing that says "Scribe is here".
 *
 * It carries its own close badge. An overlay that appears over other apps and cannot be
 * dismissed without going to system settings is the kind of thing people uninstall, and
 * "there is no X" was the first thing said about the previous version.
 */
@Composable
fun VoiceBubble(
    state: EngineState,
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
                    if (recording) {
                        Modifier.neuActive(CircleShape, ScribeTokens.rec)
                    } else {
                        Modifier.neuInset(CircleShape)
                    },
                )
                .semantics { contentDescription = "Dictate with Scribe" }
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

/** The one-line summary the panel's menu shows for the loaded model. */
fun modelLabelFor(state: EngineState): String = when {
    state.modelName.isNotEmpty() && state.boostActive -> "${state.modelName} · high accuracy"
    state.modelName.isNotEmpty() -> state.modelName
    else -> "loading…"
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
