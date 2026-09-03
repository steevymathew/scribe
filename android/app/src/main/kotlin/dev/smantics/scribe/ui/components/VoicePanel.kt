package dev.smantics.scribe.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
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
            StopDot(state, onStop)
            Waveform(
                level = state.level,
                status = state.status,
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

/** The status dot doubles as the stop control, so the panel needs no separate button. */
@Composable
private fun StopDot(state: EngineState, onStop: () -> Unit) {
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
    Box(
        Modifier
            .testTag("voice-stop-dot")
            .size(32.dp)
            .neuInset(CircleShape, depth = 0.7f)
            .semantics { contentDescription = "Stop dictating" }
            .clickable(onClick = onStop),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(11.dp)
                .clip(CircleShape)
                .background(statusColor(state.status).copy(alpha = if (pulsing) alpha else 1f)),
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

/** The collapsed bubble: the smallest thing that says "Scribe is here". */
@Composable
fun VoiceBubble(
    state: EngineState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val recording = state.status == DictationStatus.RECORDING
    // A circle pressed into the surface, the way the Scribe mark itself is drawn. Raised
    // while it is listening, so the one moment it is doing something is the one moment it
    // stands proud of the screen.
    Box(
        modifier
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
                status = state.status,
                boostActive = state.boostActive,
                modifier = Modifier.padding(horizontal = 10.dp),
                height = 22.dp,
                barCount = 5,
            )
        } else {
            ScribeMark(size = 34.dp)
        }
    }
}

/** The one-line summary the panel's menu shows for the loaded model. */
fun modelLabelFor(state: EngineState): String = when {
    state.modelName.isNotEmpty() && state.boostActive -> "${state.modelName} · high accuracy"
    state.modelName.isNotEmpty() -> state.modelName
    else -> "loading…"
}
